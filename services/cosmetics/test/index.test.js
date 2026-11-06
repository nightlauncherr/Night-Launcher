import { test } from "node:test";
import assert from "node:assert/strict";
import { handle, normalizeUuid, signedText, certificatePayload, b64decode } from "../src/lib.js";
import { PLAYER_CERTIFICATE_KEYS } from "../src/mojang-keys.js";

const UUID = "0123456789abcdef0123456789abcdef";
const OTHER = "fedcba9876543210fedcba9876543210";
const NOW = 1_800_000_000;
const EXPIRES = (NOW + 3600) * 1000;
const subtle = globalThis.crypto.subtle;

function kv() {
  const m = new Map();
  let writes = 0;
  return {
    m,
    writes: () => writes,
    async get(k, type) {
      const v = m.get(k);
      if (v === undefined) return null;
      return type === "json" ? JSON.parse(v) : v;
    },
    async put(k, v) {
      writes++;
      m.set(k, v);
    },
    async delete(k) {
      writes++;
      m.delete(k);
    },
  };
}

const b64 = (bytes) => Buffer.from(bytes).toString("base64");

async function rsa(hash) {
  return subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash },
    true,
    ["sign", "verify"],
  );
}

// A stand-in for Mojang (signs player certificates with SHA-1, like Mojang) and two players.
const mojang = await rsa("SHA-1");
const mojangSpki = new Uint8Array(await subtle.exportKey("spki", mojang.publicKey));
const forger = await rsa("SHA-1");
const player = await rsa("SHA-256");
const playerSpki = new Uint8Array(await subtle.exportKey("spki", player.publicKey));
const intruder = await rsa("SHA-256");

async function certificate(uuid, expiresAt = EXPIRES, signer = mojang.privateKey, spki = playerSpki) {
  const sig = await subtle.sign("RSASSA-PKCS1-v1_5", signer, certificatePayload(uuid, expiresAt, spki));
  return { publicKey: b64(spki), expiresAt, signature: b64(new Uint8Array(sig)) };
}

async function body(uuid, cape, { ts = NOW, nonce = "a".repeat(32), key, signer = player.privateKey } = {}) {
  const k = key || (await certificate(uuid));
  const sig = await subtle.sign("RSASSA-PKCS1-v1_5", signer, new TextEncoder().encode(signedText(uuid, cape, ts, nonce)));
  return { cape, ts, nonce, key: k, signature: b64(new Uint8Array(sig)) };
}

async function put(env, uuid, b, now = NOW) {
  const req = new Request(`https://x/v1/capes/${uuid}`, { method: "PUT", body: JSON.stringify(b) });
  return handle(req, env, { now, mojangKeys: [mojangSpki] });
}

async function get(env, q) {
  return handle(new Request(`https://x/v1/capes?uuids=${q}`), env, { now: NOW });
}

test("signed text and certificate payload are pinned (must match the Java client's CapeSyncProtocolTest)", () => {
  assert.equal(signedText(UUID, "ember", 1800000000, "a".repeat(32)), `night-cape-v1|${UUID}|ember|1800000000|${"a".repeat(32)}`);
  const p = certificatePayload(UUID, 1800000000123, new Uint8Array([0xde, 0xad, 0xbe, 0xef]));
  assert.equal(Buffer.from(p).toString("hex"), "0123456789abcdef0123456789abcdef000001a3185c507bdeadbeef");
});

test("the bundled Mojang keys decode and import as RSA keys", async () => {
  assert.ok(PLAYER_CERTIFICATE_KEYS.length >= 1);
  for (const k of PLAYER_CERTIFICATE_KEYS) {
    const der = b64decode(k);
    assert.ok(der, "base64");
    await subtle.importKey("spki", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-1" }, false, ["verify"]);
  }
});

test("normalizes uuids", () => {
  assert.equal(normalizeUuid("01234567-89AB-cdef-0123-456789abcdef"), UUID);
  assert.equal(normalizeUuid("xyz"), null);
  assert.equal(normalizeUuid(null), null);
});

test("verified publish is stored and returned by lookup", async () => {
  const env = { CAPES: kv() };
  const res = await put(env, UUID, await body(UUID, "ember"));
  assert.equal(res.status, 204);
  const r = await get(env, `${UUID},${OTHER}`);
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { capes: { [UUID]: "ember" } });
  assert.match(r.headers.get("cache-control"), /max-age=60/);
});

test("a signature over a different cape does not authorize this cape", async () => {
  const env = { CAPES: kv() };
  const b = await body(UUID, "ember");
  const res = await put(env, UUID, { ...b, cape: "glacier" });
  assert.equal(res.status, 403);
  assert.equal(env.CAPES.m.size, 0);
});

test("another account's certificate cannot set this uuid", async () => {
  const env = { CAPES: kv() };
  // Valid Mojang certificate, but issued for OTHER.
  const res = await put(env, UUID, await body(UUID, "ember", { key: await certificate(OTHER) }));
  assert.equal(res.status, 403);
  assert.equal(env.CAPES.m.size, 0);
});

test("a certificate not signed by Mojang is refused", async () => {
  const env = { CAPES: kv() };
  const res = await put(env, UUID, await body(UUID, "ember", { key: await certificate(UUID, EXPIRES, forger.privateKey) }));
  assert.equal(res.status, 403);
});

test("a cape request signed by someone else's key is refused", async () => {
  const env = { CAPES: kv() };
  const res = await put(env, UUID, await body(UUID, "ember", { signer: intruder.privateKey }));
  assert.equal(res.status, 403);
});

test("expired certificates are refused", async () => {
  const env = { CAPES: kv() };
  const res = await put(env, UUID, await body(UUID, "ember", { key: await certificate(UUID, NOW * 1000 - 1) }));
  assert.equal(res.status, 403);
});

test("a changed expiry breaks Mojang's signature", async () => {
  const env = { CAPES: kv() };
  const b = await body(UUID, "ember");
  const res = await put(env, UUID, { ...b, key: { ...b.key, expiresAt: b.key.expiresAt + 86_400_000 } });
  assert.equal(res.status, 403);
});

test("stale timestamps and bad input are rejected", async () => {
  const env = { CAPES: kv() };
  const ok = await body(UUID, "ember");
  assert.equal((await put(env, UUID, await body(UUID, "ember", { ts: NOW - 600 }))).status, 400);
  assert.equal((await put(env, UUID, { ...ok, cape: "custom:my.png" })).status, 400);
  assert.equal((await put(env, UUID, { ...ok, cape: "../x" })).status, 400);
  assert.equal((await put(env, UUID, { ...ok, nonce: "short" })).status, 400);
  assert.equal((await put(env, UUID, { ...ok, signature: "not base64!" })).status, 400);
  assert.equal((await put(env, UUID, { ...ok, key: undefined })).status, 400);
  assert.equal((await put(env, UUID, { ...ok, key: { ...ok.key, expiresAt: "soon" } })).status, 400);
  assert.equal((await put(env, "nope", ok)).status, 400);
  const big = new Request(`https://x/v1/capes/${UUID}`, { method: "PUT", body: "x".repeat(5000) });
  assert.equal((await handle(big, env, { now: NOW, mojangKeys: [mojangSpki] })).status, 413);
  const notJson = new Request(`https://x/v1/capes/${UUID}`, { method: "PUT", body: "{" });
  assert.equal((await handle(notJson, env, { now: NOW, mojangKeys: [mojangSpki] })).status, 400);
  assert.equal(env.CAPES.m.size, 0);
});

test("unchanged cape does not write; none removes the entry", async () => {
  const env = { CAPES: kv() };
  assert.equal((await put(env, UUID, await body(UUID, "mojang:minecon_2012", { nonce: "e".repeat(32) }))).status, 204);
  assert.equal((await put(env, UUID, await body(UUID, "mojang:minecon_2012", { nonce: "f".repeat(32) }))).status, 204);
  assert.equal(env.CAPES.writes(), 1);
  assert.deepEqual(await (await get(env, UUID)).json(), { capes: { [UUID]: "mojang:minecon_2012" } });
  assert.equal((await put(env, UUID, await body(UUID, "none", { nonce: "1".repeat(32) }))).status, 204);
  assert.deepEqual(await (await get(env, UUID)).json(), { capes: {} });
});

test("lookup validates its input", async () => {
  const env = { CAPES: kv() };
  assert.equal((await get(env, "")).status, 400);
  assert.equal((await get(env, "not-a-uuid")).status, 400);
  const many = Array.from({ length: 101 }, (_, i) => i.toString(16).padStart(32, "0")).join(",");
  assert.equal((await get(env, many)).status, 400);
  assert.equal((await handle(new Request("https://x/elsewhere"), env)).status, 404);
});
