// Night cosmetics service (logic; the Worker entry is index.js): which Night cape
// each Minecraft account has equipped, so Night clients can show each other's capes.
//
// GET /v1/capes?uuids=<uuid>,<uuid>,...   (up to 100, dashed or not)
//     -> 200 {"capes": {"<uuid>": "<cape id>", ...}}   only accounts with a cape
// PUT /v1/capes/<uuid>  {"cape", "ts", "nonce", "key": {"publicKey", "expiresAt", "signature"}, "signature"}
//     -> 204 when stored.
//
// Ownership is proven with the account's Mojang player certificate (the key pair
// Minecraft 1.19+ uses to sign chat), so this service never contacts Mojang and never
// sees a login token or private key:
//   1. key.signature is Mojang's signature (SHA1withRSA, one of Mojang's
//      playerCertificateKeys) over  uuid (16 bytes) || expiresAt (8 bytes, ms) || publicKey (DER),
//      which binds the player's public key to this UUID until expiresAt;
//   2. signature is the player's signature (SHA256withRSA, that public key) over
//      "night-cape-v1|<uuid>|<cape>|<ts>|<nonce>", which binds the cape and time.
// Requests older than two minutes are refused, so a captured request cannot be reused later.
//
// Code redemption (rewards.js has the codes and rewards):
// POST /v1/redeem  {"uuid", "code", "ts", "nonce", "key": {...as above}, "signature"}
//     signature is the player's over "night-redeem-v1|<uuid>|<code as sent>|<ts>|<nonce>", so only the
//     owner of an account can redeem for it.
//     -> 200 {"status": "redeemed" | "already_redeemed", "code", "entitlements": <signed>}
//     -> 404 {"error", "reason": "invalid_code"}, 410 {"reason": "expired" | "limit_reached"}
//     Redeeming is idempotent: once an account has used up its redemptions of a code,
//     retries return "already_redeemed" and the same entitlements; nothing is granted twice.
// GET /v1/entitlements/<uuid>  -> 200 <signed>
//     <signed> = {"payload": base64(JSON), "signature": base64(RSASSA-PKCS1-v1_5 SHA-256 over
//     the payload bytes)}, signed with the ENTITLEMENT_SIGNING_KEY secret (base64 PKCS#8 RSA).
//     Payload: {"v": 1, "protocol": "night-entitlements-v1", "uuid", "rewards": [ids],
//     "catalog": {id: {type, name, description}}, "redemptions": [{"code", "description", "at",
//     "rewards"}], "issued"}. Clients check the signature with the matching public key, so
//     editing a cached copy, or using another account's, unlocks nothing.
// Exclusive capes (official mojang: capes, and catalog capes in CAPE_REQUIREMENTS) are
// only stored and shared for accounts entitled to the reward they need.
//
// Storage: Workers KV namespace bound as CAPES: key = uuid (32 hex, no dashes) for the cape,
// "ent:<uuid>" for an account's redemptions, "count:<CODE>" for codes with a global limit.

import { PLAYER_CERTIFICATE_KEYS } from "./mojang-keys.js";
import { BUILTIN_GRANTS, CAPE_REQUIREMENTS, CODES, REWARDS, checkTables, expandRewards, normalizeCode } from "./rewards.js";

export const PROTOCOL = "night-cape-v1";
export const REDEEM_PROTOCOL = "night-redeem-v1";
export const ENTITLEMENTS_PROTOCOL = "night-entitlements-v1";
export const OFFICIAL_CAPES = "official_capes";
const MAX_LOOKUP = 100;
const MAX_BODY = 4096;
const MAX_SKEW_SECONDS = 120;
const UUID_RE = /^[0-9a-f]{32}$/;
const NONCE_RE = /^[0-9a-f]{16,64}$/;
const B64_RE = /^[A-Za-z0-9+/]+={0,2}$/;
// Built-in catalog ids and Mojang catalog ids; "none" removes the entry.
// Uploaded (custom:) capes are local images and are never shared.
const CAPE_RE = /^(none|[a-z0-9_]{1,48}|mojang:[a-z0-9_]{1,48})$/;

/** The reward a cape id needs, or null when anyone may share it. */
export function requiredReward(cape, requirements = CAPE_REQUIREMENTS) {
  if (typeof cape !== "string") return null;
  if (cape.startsWith("mojang:")) return OFFICIAL_CAPES;
  return Object.prototype.hasOwnProperty.call(requirements, cape) ? requirements[cape] : null;
}

export function normalizeUuid(raw) {
  if (typeof raw !== "string") return null;
  const u = raw.replace(/-/g, "").toLowerCase();
  return UUID_RE.test(u) ? u : null;
}

/** The text the player's key signs. Must match CapeSyncProtocol.signedText in the client. */
export function signedText(uuid, cape, ts, nonce) {
  return `${PROTOCOL}|${uuid}|${cape}|${ts}|${nonce}`;
}

/** The text the player's key signs to redeem a code. Must match the launcher's redeem request. */
export function redeemText(uuid, code, ts, nonce) {
  return `${REDEEM_PROTOCOL}|${uuid}|${code}|${ts}|${nonce}`;
}

/** What Mojang signs for a player certificate (Minecraft's ProfilePublicKey "V2" payload). */
export function certificatePayload(uuid, expiresAtMs, publicKeyDer) {
  const out = new Uint8Array(24 + publicKeyDer.length);
  const view = new DataView(out.buffer);
  for (let i = 0; i < 16; i++) out[i] = parseInt(uuid.substr(i * 2, 2), 16);
  view.setBigInt64(16, BigInt(expiresAtMs));
  out.set(publicKeyDer, 24);
  return out;
}

export function b64decode(s) {
  if (typeof s !== "string" || s.length === 0 || s.length > 4096 || !B64_RE.test(s)) return null;
  try {
    const bin = atob(s);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

async function verify(spkiDer, hash, signature, data) {
  try {
    const key = await crypto.subtle.importKey("spki", spkiDer, { name: "RSASSA-PKCS1-v1_5", hash }, false, ["verify"]);
    return await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, signature, data);
  } catch {
    return false;
  }
}

function json(status, body, extra = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...extra },
  });
}

function error(status, message) {
  return json(status, { error: message }, { "cache-control": "no-store" });
}

async function lookup(url, env) {
  const raw = url.searchParams.get("uuids") || "";
  const parts = raw.split(",").filter((s) => s.length > 0);
  if (parts.length === 0) return error(400, "uuids is required");
  if (parts.length > MAX_LOOKUP) return error(400, `at most ${MAX_LOOKUP} uuids`);
  const uuids = [];
  for (const p of parts) {
    const u = normalizeUuid(p);
    if (!u) return error(400, "invalid uuid");
    if (!uuids.includes(u)) uuids.push(u);
  }
  const capes = {};
  const values = await Promise.all(uuids.map((u) => env.CAPES.get(u, "json")));
  uuids.forEach((u, i) => {
    const v = values[i];
    if (v && typeof v.cape === "string" && CAPE_RE.test(v.cape) && v.cape !== "none") capes[u] = v.cape;
  });
  // Exclusive capes stored before they needed a reward (or whose reward was since lost) are
  // only shown for entitled accounts (rare, so the extra reads stay small).
  const gated = Object.keys(capes).filter((u) => requiredReward(capes[u]) !== null);
  const allowed = await Promise.all(gated.map(async (u) => (await entitledRewards(env, u)).includes(requiredReward(capes[u]))));
  gated.forEach((u, i) => {
    if (!allowed[i]) delete capes[u];
  });
  return json(200, { capes }, { "cache-control": "public, max-age=60" });
}

async function readBody(request) {
  const len = Number(request.headers.get("content-length") || "0");
  if (len > MAX_BODY) return { err: error(413, "body too large") };
  const text = await request.text();
  if (text.length > MAX_BODY) return { err: error(413, "body too large") };
  try {
    const body = JSON.parse(text);
    if (!body || typeof body !== "object") return { err: error(400, "body must be a JSON object") };
    return { body };
  } catch {
    return { err: error(400, "body must be JSON") };
  }
}

/**
 * Check that the owner of `uuid` signed `signedFor(ts, nonce)` just now: the request's player
 * certificate must be Mojang's for this UUID, and the signature the certificate key's.
 * Returns an error Response, or null when the request is genuine.
 */
async function proveOwner(uuid, body, signedFor, now, mojangKeys) {
  const { ts, nonce, key, signature } = body;
  if (!Number.isInteger(ts) || Math.abs(now - ts) > MAX_SKEW_SECONDS) return error(400, "stale or invalid ts");
  if (typeof nonce !== "string" || !NONCE_RE.test(nonce)) return error(400, "invalid nonce");
  if (!key || typeof key !== "object") return error(400, "missing key");
  const publicKey = b64decode(key.publicKey);
  const keySignature = b64decode(key.signature);
  const sig = b64decode(signature);
  if (!publicKey || !keySignature || !sig) return error(400, "invalid key or signature encoding");
  if (!Number.isSafeInteger(key.expiresAt)) return error(400, "invalid key expiry");
  if (key.expiresAt <= now * 1000) return error(403, "player certificate expired");

  // 1. Mojang vouches that this public key belongs to this UUID.
  const payload = certificatePayload(uuid, key.expiresAt, publicKey);
  let vouched = false;
  for (const k of mojangKeys) {
    if (await verify(k, "SHA-1", keySignature, payload)) {
      vouched = true;
      break;
    }
  }
  if (!vouched) return error(403, "player certificate not signed by Mojang for this account");
  // 2. The owner of that key asked for this, now.
  const data = new TextEncoder().encode(signedFor(ts, nonce));
  if (!(await verify(publicKey, "SHA-256", sig, data))) return error(403, "signature does not match");
  return null;
}

async function publish(request, uuidPart, env, now, mojangKeys) {
  const uuid = normalizeUuid(uuidPart);
  if (!uuid) return error(400, "invalid uuid");
  const { body, err } = await readBody(request);
  if (err) return err;
  const { cape } = body;
  if (typeof cape !== "string" || !CAPE_RE.test(cape)) return error(400, "invalid cape");
  const denied = await proveOwner(uuid, body, (ts, nonce) => signedText(uuid, cape, ts, nonce), now, mojangKeys);
  if (denied) return denied;
  const needs = requiredReward(cape);
  if (needs !== null && !(await entitledRewards(env, uuid)).includes(needs)) {
    return error(403, "this cape is not available for this account");
  }

  // Write only on change: KV writes are the scarce resource on the free plan.
  const current = await env.CAPES.get(uuid, "json");
  if (cape === "none") {
    if (current) await env.CAPES.delete(uuid);
  } else if (!current || current.cape !== cape) {
    await env.CAPES.put(uuid, JSON.stringify({ cape, updated: now }));
  }
  return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
}

// ---- redemption and entitlements -------------------------------------------------------

async function record(env, uuid) {
  const r = await env.CAPES.get(`ent:${uuid}`, "json");
  return r && Array.isArray(r.redemptions) ? r : { redemptions: [] };
}

function rewardsOf(rec, uuid, rewards = REWARDS) {
  const ids = [...(BUILTIN_GRANTS[uuid] || [])];
  for (const r of rec.redemptions) if (Array.isArray(r.rewards)) ids.push(...r.rewards);
  return expandRewards(ids, rewards);
}

/** Reward ids an account has (redeemed and built in), bundles expanded. */
export async function entitledRewards(env, uuid) {
  return rewardsOf(await record(env, uuid), uuid);
}

let signingKey;
let signingKeySource;
async function entitlementKey(env) {
  const src = env.ENTITLEMENT_SIGNING_KEY;
  if (typeof src !== "string" || src.length === 0) return null;
  if (signingKey && signingKeySource === src) return signingKey;
  const der = b64decode(src.replace(/\s+/g, ""));
  if (!der) return null;
  try {
    signingKey = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
    signingKeySource = src;
    return signingKey;
  } catch {
    return null;
  }
}

function b64encode(bytes) {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

/** The signed entitlement document for an account, or null when no signing key is set. */
async function signedEntitlements(env, uuid, rec, now) {
  const key = await entitlementKey(env);
  if (!key) return null;
  const rewards = rewardsOf(rec, uuid);
  const catalog = {};
  for (const id of rewards) {
    const r = REWARDS[id];
    catalog[id] = { type: r.type, name: r.name, description: r.description || "" };
  }
  const payload = {
    v: 1,
    protocol: ENTITLEMENTS_PROTOCOL,
    uuid,
    rewards,
    catalog,
    redemptions: rec.redemptions.map((r) => ({
      code: r.code,
      description: (CODES[r.code] && CODES[r.code].description) || "",
      at: r.at,
      rewards: r.rewards,
    })),
    issued: now,
  };
  const bytes = new TextEncoder().encode(JSON.stringify(payload));
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, bytes));
  return { payload: b64encode(bytes), signature: b64encode(sig) };
}

async function entitlements(uuidPart, env, now) {
  const uuid = normalizeUuid(uuidPart);
  if (!uuid) return error(400, "invalid uuid");
  const doc = await signedEntitlements(env, uuid, await record(env, uuid), now);
  if (!doc) return error(503, "entitlements are not configured");
  return json(200, doc, { "cache-control": "no-store" });
}

async function redeem(request, env, now, mojangKeys, codes) {
  const { body, err } = await readBody(request);
  if (err) return err;
  const uuid = normalizeUuid(body.uuid);
  if (!uuid) return error(400, "invalid uuid");
  if (typeof body.code !== "string") return error(400, "invalid code");
  if (body.code.length > 64) return error(400, "invalid code");
  // The signature covers the code exactly as sent; it is normalized only afterwards.
  const denied = await proveOwner(uuid, body, (ts, nonce) => redeemText(uuid, body.code, ts, nonce), now, mojangKeys);
  if (denied) return denied;
  if (!(await entitlementKey(env))) return error(503, "redemption is not configured");
  const code = normalizeCode(body.code);

  const reject = (status, reason, message) =>
    json(status, { error: message, reason }, { "cache-control": "no-store" });
  const c = code && Object.prototype.hasOwnProperty.call(codes, code) ? codes[code] : null;
  if (!c || c.enabled === false) return reject(404, "invalid_code", "that code doesn't exist");

  const rec = await record(env, uuid);
  const used = rec.redemptions.filter((r) => r.code === code).length;
  const perAccount = c.perAccountLimit || 1;
  if (used >= perAccount) {
    // Already done (also the answer to a retry whose reply was lost): same result, nothing new.
    const doc = await signedEntitlements(env, uuid, rec, now);
    return json(200, { status: "already_redeemed", code, entitlements: doc }, { "cache-control": "no-store" });
  }
  if (c.expires != null && now * 1000 > Date.parse(c.expires)) return reject(410, "expired", "that code has expired");
  if (c.globalLimit != null) {
    const count = Number(await env.CAPES.get(`count:${code}`)) || 0;
    if (count >= c.globalLimit) return reject(410, "limit_reached", "that code has been used up");
    await env.CAPES.put(`count:${code}`, String(count + 1));
  }
  rec.redemptions.push({ code, at: now, rewards: [...c.rewards] });
  await env.CAPES.put(`ent:${uuid}`, JSON.stringify(rec));
  const doc = await signedEntitlements(env, uuid, rec, now);
  return json(200, { status: "redeemed", code, entitlements: doc }, { "cache-control": "no-store" });
}

let defaultKeys;
function mojangKeysDefault() {
  if (!defaultKeys) defaultKeys = PLAYER_CERTIFICATE_KEYS.map((k) => b64decode(k));
  return defaultKeys;
}

let tablesChecked = false;

export async function handle(request, env, { now = Math.floor(Date.now() / 1000), mojangKeys, codes = CODES } = {}) {
  if (!tablesChecked) {
    checkTables();
    tablesChecked = true;
  }
  const url = new URL(request.url);
  const keys = () => mojangKeys || mojangKeysDefault();
  if (url.pathname === "/v1/capes" && request.method === "GET") return lookup(url, env);
  const m = url.pathname.match(/^\/v1\/capes\/([^/]+)$/);
  if (m && request.method === "PUT") return publish(request, m[1], env, now, keys());
  if (url.pathname === "/v1/redeem" && request.method === "POST") return redeem(request, env, now, keys(), codes);
  const e = url.pathname.match(/^\/v1\/entitlements\/([^/]+)$/);
  if (e && request.method === "GET") return entitlements(e[1], env, now);
  if (url.pathname === "/" && request.method === "GET") return json(200, { service: "night-cosmetics", protocol: PROTOCOL });
  return error(404, "not found");
}

export function internalError() {
  return error(500, "internal error");
}
