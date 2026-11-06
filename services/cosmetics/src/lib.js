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
// Storage: Workers KV namespace bound as CAPES, key = uuid (32 hex, no dashes).

import { PLAYER_CERTIFICATE_KEYS } from "./mojang-keys.js";

export const PROTOCOL = "night-cape-v1";
const MAX_LOOKUP = 100;
const MAX_BODY = 4096;
const MAX_SKEW_SECONDS = 120;
const UUID_RE = /^[0-9a-f]{32}$/;
const NONCE_RE = /^[0-9a-f]{16,64}$/;
const B64_RE = /^[A-Za-z0-9+/]+={0,2}$/;
// Built-in catalog ids and Mojang catalog ids; "none" removes the entry.
// Uploaded (custom:) capes are local images and are never shared.
const CAPE_RE = /^(none|[a-z0-9_]{1,48}|mojang:[a-z0-9_]{1,48})$/;

export function normalizeUuid(raw) {
  if (typeof raw !== "string") return null;
  const u = raw.replace(/-/g, "").toLowerCase();
  return UUID_RE.test(u) ? u : null;
}

/** The text the player's key signs. Must match CapeSyncProtocol.signedText in the client. */
export function signedText(uuid, cape, ts, nonce) {
  return `${PROTOCOL}|${uuid}|${cape}|${ts}|${nonce}`;
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
  return json(200, { capes }, { "cache-control": "public, max-age=60" });
}

async function publish(request, uuidPart, env, now, mojangKeys) {
  const uuid = normalizeUuid(uuidPart);
  if (!uuid) return error(400, "invalid uuid");
  const len = Number(request.headers.get("content-length") || "0");
  if (len > MAX_BODY) return error(413, "body too large");
  const text = await request.text();
  if (text.length > MAX_BODY) return error(413, "body too large");
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    return error(400, "body must be JSON");
  }
  const { cape, ts, nonce, key, signature } = body || {};
  if (typeof cape !== "string" || !CAPE_RE.test(cape)) return error(400, "invalid cape");
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
  // 2. The owner of that key asked for this cape, now.
  const data = new TextEncoder().encode(signedText(uuid, cape, ts, nonce));
  if (!(await verify(publicKey, "SHA-256", sig, data))) return error(403, "signature does not match");

  // Write only on change: KV writes are the scarce resource on the free plan.
  const current = await env.CAPES.get(uuid, "json");
  if (cape === "none") {
    if (current) await env.CAPES.delete(uuid);
  } else if (!current || current.cape !== cape) {
    await env.CAPES.put(uuid, JSON.stringify({ cape, updated: now }));
  }
  return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
}

let defaultKeys;
function mojangKeysDefault() {
  if (!defaultKeys) defaultKeys = PLAYER_CERTIFICATE_KEYS.map((k) => b64decode(k));
  return defaultKeys;
}

export async function handle(request, env, { now = Math.floor(Date.now() / 1000), mojangKeys } = {}) {
  const url = new URL(request.url);
  if (url.pathname === "/v1/capes" && request.method === "GET") return lookup(url, env);
  const m = url.pathname.match(/^\/v1\/capes\/([^/]+)$/);
  if (m && request.method === "PUT") return publish(request, m[1], env, now, mojangKeys || mojangKeysDefault());
  if (url.pathname === "/" && request.method === "GET") return json(200, { service: "night-cosmetics", protocol: PROTOCOL });
  return error(404, "not found");
}

export function internalError() {
  return error(500, "internal error");
}
