// Night cosmetics service (logic; the Worker entry is index.js): which Night cape each Minecraft account has equipped,
// so Night clients can show each other's capes.
//
// GET /v1/capes?uuids=<uuid>,<uuid>,...   (up to 100, dashed or not)
//     -> 200 {"capes": {"<uuid>": "<cape id>", ...}}   only accounts with a cape
// PUT /v1/capes/<uuid>  {"name", "cape", "ts", "nonce"}
//     -> 204 when stored. Ownership is proven the way Minecraft servers do it:
//        the client asks Mojang's session server to record a "join" for
//        serverId = sha1("night-cape-v1|uuid|cape|ts|nonce"), and this service
//        checks it with hasJoined. The serverId binds the cape and time, so a
//        captured request cannot set a different cape or be reused later.
//        The client's Minecraft token never reaches this service.
//
// Storage: Workers KV namespace bound as CAPES, key = uuid (32 hex, no dashes).

export const PROTOCOL = "night-cape-v1";
const MAX_LOOKUP = 100;
const MAX_BODY = 1024;
const MAX_SKEW_SECONDS = 120;
const UUID_RE = /^[0-9a-f]{32}$/;
const NAME_RE = /^[A-Za-z0-9_]{1,16}$/;
const NONCE_RE = /^[0-9a-f]{16,64}$/;
// Built-in catalog ids and Mojang catalog ids; "none" removes the entry.
// Uploaded (custom:) capes are local images and are never shared.
const CAPE_RE = /^(none|[a-z0-9_]{1,48}|mojang:[a-z0-9_]{1,48})$/;
const HAS_JOINED = "https://sessionserver.mojang.com/session/minecraft/hasJoined";

export function normalizeUuid(raw) {
  if (typeof raw !== "string") return null;
  const u = raw.replace(/-/g, "").toLowerCase();
  return UUID_RE.test(u) ? u : null;
}

export async function serverIdFor(uuid, cape, ts, nonce) {
  const data = new TextEncoder().encode(`${PROTOCOL}|${uuid}|${cape}|${ts}|${nonce}`);
  const digest = await crypto.subtle.digest("SHA-1", data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
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

async function publish(request, uuidPart, env, now, fetchImpl) {
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
  const { name, cape, ts, nonce } = body || {};
  if (typeof name !== "string" || !NAME_RE.test(name)) return error(400, "invalid name");
  if (typeof cape !== "string" || !CAPE_RE.test(cape)) return error(400, "invalid cape");
  if (!Number.isInteger(ts) || Math.abs(now - ts) > MAX_SKEW_SECONDS) return error(400, "stale or invalid ts");
  if (typeof nonce !== "string" || !NONCE_RE.test(nonce)) return error(400, "invalid nonce");

  const serverId = await serverIdFor(uuid, cape, ts, nonce);
  const check = `${HAS_JOINED}?username=${encodeURIComponent(name)}&serverId=${serverId}`;
  let res;
  try {
    res = await fetchImpl(check, { headers: { "user-agent": "Night-Cosmetics/1" } });
  } catch {
    return error(502, "session server unreachable");
  }
  if (res.status !== 200) {
    // 204: Mojang has no matching join. Anything else: Mojang refused or failed the check.
    console.log(`hasJoined for ${name} answered ${res.status}`);
    return error(403, `session not verified (mojang ${res.status})`);
  }
  let profile;
  try {
    profile = await res.json();
  } catch {
    return error(403, "session not verified");
  }
  if (!profile || normalizeUuid(profile.id) !== uuid) return error(403, "session is for another account");

  // Write only on change: KV writes are the scarce resource on the free plan.
  const current = await env.CAPES.get(uuid, "json");
  if (cape === "none") {
    if (current) await env.CAPES.delete(uuid);
  } else if (!current || current.cape !== cape) {
    await env.CAPES.put(uuid, JSON.stringify({ cape, updated: now }));
  }
  return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
}

export async function handle(request, env, { now = Math.floor(Date.now() / 1000), fetchImpl = fetch } = {}) {
  const url = new URL(request.url);
  if (url.pathname === "/v1/capes" && request.method === "GET") return lookup(url, env);
  const m = url.pathname.match(/^\/v1\/capes\/([^/]+)$/);
  if (m && request.method === "PUT") return publish(request, m[1], env, now, fetchImpl);
  if (url.pathname === "/" && request.method === "GET") return json(200, { service: "night-cosmetics", protocol: PROTOCOL });
  return error(404, "not found");
}

export function internalError() {
  return error(500, "internal error");
}
