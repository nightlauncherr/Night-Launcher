import { test } from "node:test";
import assert from "node:assert/strict";
import { handle, serverIdFor, normalizeUuid } from "../src/lib.js";

const UUID = "0123456789abcdef0123456789abcdef";
const OTHER = "fedcba9876543210fedcba9876543210";
const NOW = 1_800_000_000;

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

// Mojang stand-in: knows which (username, serverId) pairs joined, and their uuid.
function mojang(joins) {
  const calls = [];
  const fetchImpl = async (url) => {
    calls.push(url);
    const u = new URL(url);
    const key = `${u.searchParams.get("username")}|${u.searchParams.get("serverId")}`;
    const id = joins.get(key);
    return id ? new Response(JSON.stringify({ id, name: u.searchParams.get("username") }), { status: 200 })
              : new Response(null, { status: 204 });
  };
  return { fetchImpl, calls };
}

async function put(env, fetchImpl, uuid, body, now = NOW) {
  const req = new Request(`https://x/v1/capes/${uuid}`, { method: "PUT", body: JSON.stringify(body) });
  return handle(req, env, { now, fetchImpl });
}

async function get(env, q) {
  return handle(new Request(`https://x/v1/capes?uuids=${q}`), env, { now: NOW });
}

async function joined(name, uuid, cape, ts, nonce) {
  return [`${name}|${await serverIdFor(uuid, cape, ts, nonce)}`, uuid];
}

test("server id is pinned (must match the Java client's CapeSyncProtocolTest)", async () => {
  assert.equal(await serverIdFor(UUID, "ember", 1800000000, "a".repeat(32)), "8535a2580d22a544ba878513c8d0bf8f316e1a22");
});

test("normalizes uuids", () => {
  assert.equal(normalizeUuid("01234567-89AB-cdef-0123-456789abcdef"), UUID);
  assert.equal(normalizeUuid("xyz"), null);
  assert.equal(normalizeUuid(null), null);
});

test("verified publish is stored and returned by lookup", async () => {
  const env = { CAPES: kv() };
  const nonce = "a".repeat(32);
  const { fetchImpl, calls } = mojang(new Map([await joined("Steve", UUID, "ember", NOW, nonce)]));
  const res = await put(env, fetchImpl, UUID, { name: "Steve", cape: "ember", ts: NOW, nonce });
  assert.equal(res.status, 204);
  assert.equal(calls.length, 1);
  assert.match(calls[0], /^https:\/\/sessionserver\.mojang\.com\/session\/minecraft\/hasJoined\?username=Steve&serverId=[0-9a-f]{40}$/);
  const r = await get(env, `${UUID},${OTHER}`);
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { capes: { [UUID]: "ember" } });
  assert.match(r.headers.get("cache-control"), /max-age=60/);
});

test("a join for a different cape does not authorize this cape", async () => {
  const env = { CAPES: kv() };
  const nonce = "b".repeat(32);
  const { fetchImpl } = mojang(new Map([await joined("Steve", UUID, "ember", NOW, nonce)]));
  const res = await put(env, fetchImpl, UUID, { name: "Steve", cape: "glacier", ts: NOW, nonce });
  assert.equal(res.status, 403);
  assert.equal(env.CAPES.m.size, 0);
});

test("a session for another account cannot set this uuid", async () => {
  const env = { CAPES: kv() };
  const nonce = "c".repeat(32);
  // Mojang confirms the join, but for OTHER's account.
  const { fetchImpl } = mojang(new Map([[(await joined("Alex", UUID, "ember", NOW, nonce))[0], OTHER]]));
  const res = await put(env, fetchImpl, UUID, { name: "Alex", cape: "ember", ts: NOW, nonce });
  assert.equal(res.status, 403);
  assert.equal(env.CAPES.m.size, 0);
});

test("stale timestamps and bad input are rejected before asking Mojang", async () => {
  const env = { CAPES: kv() };
  const { fetchImpl, calls } = mojang(new Map());
  const ok = { name: "Steve", cape: "ember", ts: NOW, nonce: "d".repeat(32) };
  assert.equal((await put(env, fetchImpl, UUID, { ...ok, ts: NOW - 600 })).status, 400);
  assert.equal((await put(env, fetchImpl, UUID, { ...ok, cape: "custom:my.png" })).status, 400);
  assert.equal((await put(env, fetchImpl, UUID, { ...ok, cape: "../x" })).status, 400);
  assert.equal((await put(env, fetchImpl, UUID, { ...ok, name: "bad name!" })).status, 400);
  assert.equal((await put(env, fetchImpl, UUID, { ...ok, nonce: "short" })).status, 400);
  assert.equal((await put(env, fetchImpl, "nope", ok)).status, 400);
  const big = new Request(`https://x/v1/capes/${UUID}`, { method: "PUT", body: "x".repeat(2000) });
  assert.equal((await handle(big, env, { now: NOW, fetchImpl })).status, 413);
  assert.equal(calls.length, 0);
});

test("unchanged cape does not write; none removes the entry", async () => {
  const env = { CAPES: kv() };
  const n1 = "e".repeat(32), n2 = "f".repeat(32), n3 = "1".repeat(32);
  const { fetchImpl } = mojang(new Map([
    await joined("Steve", UUID, "mojang:minecon_2012", NOW, n1),
    await joined("Steve", UUID, "mojang:minecon_2012", NOW, n2),
    await joined("Steve", UUID, "none", NOW, n3),
  ]));
  assert.equal((await put(env, fetchImpl, UUID, { name: "Steve", cape: "mojang:minecon_2012", ts: NOW, nonce: n1 })).status, 204);
  assert.equal((await put(env, fetchImpl, UUID, { name: "Steve", cape: "mojang:minecon_2012", ts: NOW, nonce: n2 })).status, 204);
  assert.equal(env.CAPES.writes(), 1);
  assert.deepEqual(await (await get(env, UUID)).json(), { capes: { [UUID]: "mojang:minecon_2012" } });
  assert.equal((await put(env, fetchImpl, UUID, { name: "Steve", cape: "none", ts: NOW, nonce: n3 })).status, 204);
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

test("mojang unreachable is a 502, not a crash", async () => {
  const env = { CAPES: kv() };
  const fetchImpl = async () => { throw new Error("down"); };
  const res = await put(env, fetchImpl, UUID, { name: "Steve", cape: "ember", ts: NOW, nonce: "9".repeat(32) });
  assert.equal(res.status, 502);
});
