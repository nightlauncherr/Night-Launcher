import { test } from "node:test";
import assert from "node:assert/strict";
import { handle, normalizeUuid, signedText, redeemText, certificatePayload, b64decode, requiredReward } from "../src/lib.js";
import { CODES, REWARDS, BUILTIN_GRANTS, CAPE_REQUIREMENTS, checkTables, expandRewards, normalizeCode } from "../src/rewards.js";
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
  assert.equal((await put(env, UUID, await body(UUID, "glacier", { nonce: "e".repeat(32) }))).status, 204);
  assert.equal((await put(env, UUID, await body(UUID, "glacier", { nonce: "f".repeat(32) }))).status, 204);
  assert.equal(env.CAPES.writes(), 1);
  assert.deepEqual(await (await get(env, UUID)).json(), { capes: { [UUID]: "glacier" } });
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

// ---- code redemption --------------------------------------------------------------------

const BRINGING = "ba14933ea7ad48168696a788cc1b3c1f";
const signer = await subtle.generateKey(
  { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
  true,
  ["sign", "verify"],
);
const SIGNING_KEY = b64(new Uint8Array(await subtle.exportKey("pkcs8", signer.privateKey)));
const keyedEnv = () => ({ CAPES: kv(), ENTITLEMENT_SIGNING_KEY: SIGNING_KEY });

async function redeemBody(uuid, code, { ts = NOW, nonce = "b".repeat(32), key, signFor = uuid, signCode = code } = {}) {
  const k = key || (await certificate(uuid));
  const text = redeemText(signFor, signCode, ts, nonce);
  const sig = await subtle.sign("RSASSA-PKCS1-v1_5", player.privateKey, new TextEncoder().encode(text));
  return { uuid, code, ts, nonce, key: k, signature: b64(new Uint8Array(sig)) };
}

async function redeem(env, b, opts = {}) {
  const req = new Request("https://x/v1/redeem", { method: "POST", body: JSON.stringify(b) });
  return handle(req, env, { now: NOW, mojangKeys: [mojangSpki], ...opts });
}

async function entitlementsOf(env, uuid) {
  return handle(new Request(`https://x/v1/entitlements/${uuid}`), env, { now: NOW });
}

/** Check the document's signature like the clients do, and return its payload. */
async function opened(doc) {
  assert.ok(doc && doc.payload && doc.signature, "signed document");
  const bytes = Buffer.from(doc.payload, "base64");
  const ok = await subtle.verify("RSASSA-PKCS1-v1_5", signer.publicKey, Buffer.from(doc.signature, "base64"), bytes);
  assert.ok(ok, "signature verifies");
  const p = JSON.parse(bytes.toString("utf8"));
  assert.equal(p.protocol, "night-entitlements-v1");
  assert.equal(p.v, 1);
  return p;
}

const uuidN = (n) => n.toString(16).padStart(8, "0") + "0".repeat(12) + "4" + "0".repeat(3) + "8" + "0".repeat(7);

test("redeem text is pinned (must match the launcher's redeem request)", () => {
  assert.equal(redeemText(UUID, "LOVEMOJANG", 1800000000, "a".repeat(32)), `night-redeem-v1|${UUID}|LOVEMOJANG|1800000000|${"a".repeat(32)}`);
});

test("the code and reward tables are valid; LOVEMOJANG unlocks the official capes", () => {
  checkTables();
  assert.deepEqual(CODES.LOVEMOJANG.rewards, ["official_capes"]);
  assert.equal(CODES.LOVEMOJANG.enabled, true);
  assert.equal(CODES.LOVEMOJANG.globalLimit, null);
  assert.equal(REWARDS.official_capes.type, "cape_collection");
  assert.deepEqual(BUILTIN_GRANTS[BRINGING], ["official_capes"]);
  assert.equal(normalizeCode("  loveMojang "), "LOVEMOJANG");
  assert.equal(normalizeCode("love mojang"), null);
  assert.equal(normalizeCode(""), null);
  assert.throws(() => checkTables({ lower: { rewards: ["official_capes"] } }));
  assert.throws(() => checkTables({ X: { rewards: ["nope"] } }));
  assert.throws(() => checkTables({ X: { rewards: ["official_capes"], globalLimit: 0 } }));
  const bundles = { a: { type: "cape", name: "A" }, b: { type: "bundle", name: "B", includes: ["a"] } };
  assert.deepEqual(expandRewards(["b"], bundles), ["a", "b"]);
});

test("LOVEMOJANG redeems once per account, signed, and retries change nothing", async () => {
  const env = keyedEnv();
  const r = await redeem(env, await redeemBody(UUID, "lovemojang"));
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.status, "redeemed");
  assert.equal(j.code, "LOVEMOJANG");
  const p = await opened(j.entitlements);
  assert.equal(p.uuid, UUID);
  assert.deepEqual(p.rewards, ["official_capes"]);
  assert.equal(p.catalog.official_capes.type, "cape_collection");
  assert.equal(p.redemptions.length, 1);
  assert.equal(p.redemptions[0].code, "LOVEMOJANG");
  assert.equal(p.redemptions[0].at, NOW);
  const writes = env.CAPES.writes();
  assert.equal(writes, 1);

  // A retry (new nonce), and an exact replay of the first request: same result, no new grant.
  for (const b of [await redeemBody(UUID, "LOVEMOJANG", { nonce: "c".repeat(32) }), await redeemBody(UUID, "lovemojang")]) {
    const again = await redeem(env, b);
    assert.equal(again.status, 200);
    const a = await again.json();
    assert.equal(a.status, "already_redeemed");
    const q = await opened(a.entitlements);
    assert.equal(q.redemptions.length, 1);
    assert.deepEqual(q.rewards, ["official_capes"]);
  }
  assert.equal(env.CAPES.writes(), writes);
  const e = await opened(await (await entitlementsOf(env, UUID)).json());
  assert.deepEqual(e.rewards, ["official_capes"]);
});

test("many different accounts redeem the same code independently, without sharing it", async () => {
  const env = keyedEnv();
  const ids = [1, 2, 3, 4, 5, 6].map(uuidN);
  for (const u of ids.slice(0, 5)) {
    const j = await (await redeem(env, await redeemBody(u, "LOVEMOJANG"))).json();
    assert.equal(j.status, "redeemed", u);
    assert.equal((await opened(j.entitlements)).uuid, u);
  }
  for (const u of ids.slice(0, 5)) assert.deepEqual((await opened(await (await entitlementsOf(env, u)).json())).rewards, ["official_capes"]);
  // The sixth never redeemed: nothing, even though five others did.
  const none = await opened(await (await entitlementsOf(env, ids[5])).json());
  assert.deepEqual(none.rewards, []);
  assert.deepEqual(none.redemptions, []);
});

test("bringing has the official capes without redeeming, and can still redeem", async () => {
  const env = keyedEnv();
  assert.deepEqual((await opened(await (await entitlementsOf(env, BRINGING)).json())).rewards, ["official_capes"]);
  const j = await (await redeem(env, await redeemBody(BRINGING, "LOVEMOJANG"))).json();
  assert.equal(j.status, "redeemed");
  const p = await opened(j.entitlements);
  assert.deepEqual(p.rewards, ["official_capes"]);
  assert.equal(p.redemptions.length, 1);
});

test("invalid, disabled, expired and used-up codes grant nothing", async () => {
  const env = keyedEnv();
  for (const code of ["NOPE", "love mojang", "", "LOVEMOJANG2"]) {
    const r = await redeem(env, await redeemBody(UUID, code));
    assert.equal(r.status, 404, code);
    assert.equal((await r.json()).reason, "invalid_code");
  }
  assert.equal(env.CAPES.writes(), 0);
  const codes = {
    OFF: { rewards: ["official_capes"], enabled: false },
    OLD: { rewards: ["official_capes"], enabled: true, expires: "2020-01-01T00:00:00Z" },
    TWO: { rewards: ["official_capes"], enabled: true, globalLimit: 2 },
    TWICE: { rewards: ["official_capes"], enabled: true, perAccountLimit: 2 },
  };
  assert.equal((await redeem(env, await redeemBody(UUID, "OFF"), { codes })).status, 404);
  const old = await redeem(env, await redeemBody(UUID, "OLD"), { codes });
  assert.equal(old.status, 410);
  assert.equal((await old.json()).reason, "expired");
  assert.equal(env.CAPES.writes(), 0);
  assert.deepEqual((await opened(await (await entitlementsOf(env, UUID)).json())).rewards, []);

  for (const n of [1, 2]) assert.equal((await (await redeem(env, await redeemBody(uuidN(n), "TWO"), { codes })).json()).status, "redeemed");
  const third = await redeem(env, await redeemBody(uuidN(3), "TWO"), { codes });
  assert.equal(third.status, 410);
  assert.equal((await third.json()).reason, "limit_reached");
  // An account that already redeemed a used-up code still gets "already redeemed", not an error.
  assert.equal((await (await redeem(env, await redeemBody(uuidN(1), "TWO", { nonce: "d".repeat(32) }), { codes })).json()).status, "already_redeemed");

  const statuses = [];
  for (const nonce of ["1", "2", "3"].map((c) => c.repeat(32))) {
    statuses.push((await (await redeem(env, await redeemBody(uuidN(9), "TWICE", { nonce }), { codes })).json()).status);
  }
  assert.deepEqual(statuses, ["redeemed", "redeemed", "already_redeemed"]);
});

test("nobody can redeem for an account they can't prove they own", async () => {
  const env = keyedEnv();
  // My certificate, someone else's uuid.
  const mine = await certificate(UUID);
  assert.equal((await redeem(env, await redeemBody(OTHER, "LOVEMOJANG", { key: mine }))).status, 403);
  // A signature over a different code.
  assert.equal((await redeem(env, await redeemBody(UUID, "LOVEMOJANG", { signCode: "OTHERCODE" }))).status, 403);
  // A certificate Mojang didn't sign, a stale request, a missing signature.
  const forged = await certificate(UUID, EXPIRES, forger.privateKey);
  assert.equal((await redeem(env, await redeemBody(UUID, "LOVEMOJANG", { key: forged }))).status, 403);
  assert.equal((await redeem(env, await redeemBody(UUID, "LOVEMOJANG", { ts: NOW - 600 }))).status, 400);
  assert.equal((await redeem(env, { ...(await redeemBody(UUID, "LOVEMOJANG")), signature: undefined })).status, 400);
  assert.equal((await redeem(env, { ...(await redeemBody(UUID, "LOVEMOJANG")), uuid: "nope" })).status, 400);
  assert.equal(env.CAPES.writes(), 0);
});

test("without the signing key nothing is redeemed or issued", async () => {
  const env = { CAPES: kv() };
  assert.equal((await redeem(env, await redeemBody(UUID, "LOVEMOJANG"))).status, 503);
  assert.equal((await entitlementsOf(env, UUID)).status, 503);
  assert.equal(env.CAPES.writes(), 0);
  assert.equal((await entitlementsOf(keyedEnv(), "nope")).status, 400);
});

test("exclusive capes name their reward; the refusal never mentions a code", async () => {
  assert.equal(requiredReward("mojang:migrator"), "official_capes");
  assert.equal(requiredReward("ember"), null);
  assert.equal(requiredReward("none"), null);
  assert.equal(requiredReward("event_cape", { event_cape: "official_capes" }), "official_capes");
  assert.equal(requiredReward("constructor"), null);
  checkTables(CODES, REWARDS, BUILTIN_GRANTS, CAPE_REQUIREMENTS);
  assert.throws(() => checkTables(CODES, REWARDS, BUILTIN_GRANTS, { ember: "nope" }));
  const env = keyedEnv();
  const res = await put(env, UUID, await body(UUID, "mojang:valentine", { nonce: "9".repeat(32), key: undefined }));
  assert.equal(res.status, 403);
  const text = JSON.stringify(await res.json());
  for (const code of Object.keys(CODES)) assert.ok(!text.toUpperCase().includes(code), text);
});

test("official capes are only shared for entitled accounts; Night capes for everyone", async () => {
  const env = keyedEnv();
  const official = (u, nonce) => body(u, "mojang:minecon_2011", { nonce, key: undefined });
  assert.equal((await put(env, UUID, await official(UUID, "e".repeat(32)))).status, 403);
  assert.equal((await put(env, OTHER, await body(OTHER, "ember"))).status, 204);
  await redeem(env, await redeemBody(UUID, "LOVEMOJANG"));
  assert.equal((await put(env, UUID, await official(UUID, "f".repeat(32)))).status, 204);
  assert.equal((await put(env, BRINGING, await official(BRINGING, "1".repeat(32)))).status, 204);
  // A cape stored before official capes needed the collection is hidden for unentitled accounts.
  const legacy = uuidN(7);
  await env.CAPES.put(legacy, JSON.stringify({ cape: "mojang:cobalt", updated: NOW }));
  const j = await (await get(env, [UUID, OTHER, BRINGING, legacy].join(","))).json();
  assert.deepEqual(j.capes, { [UUID]: "mojang:minecon_2011", [OTHER]: "ember", [BRINGING]: "mojang:minecon_2011" });
});
