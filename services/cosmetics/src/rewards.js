// Redeemable codes and the rewards they unlock. Adding a code (or a reward) is a
// change to this file and a `wrangler deploy`; clients need a new release only when a
// reward unlocks a cosmetic they don't know yet.
//
// Reward fields:
//   type         what kind of cosmetic it unlocks: "cape_collection", "cape", "animated_cape",
//                "sky", "kill_effect", "bundle", "ui" (clients ignore types they don't use)
//   name         shown in the launcher's Redeem page
//   description  one line, shown under the name
//   includes     for "bundle": the reward ids it contains (granted together)
//
// Code fields (codes are matched case-insensitively and stored upper case):
//   rewards          reward ids granted by the code (required, non-empty)
//   description      shown in the redemption history
//   enabled          false stops new redemptions; existing ones are kept
//   expires          optional ISO 8601 time after which the code can't be redeemed
//   globalLimit      optional: how many accounts may redeem it in total (approximate:
//                    Workers KV has no atomic counters, so two redemptions in the same
//                    second can both pass the last slot)
//   perAccountLimit  how many times one account may redeem it (default 1)

export const REWARDS = {
  official_capes: {
    type: "cape_collection",
    name: "Official Minecraft capes",
    description: "Every official Minecraft cape in Night Launcher, historical and new",
  },
};

export const CODES = {
  LOVEMOJANG: {
    rewards: ["official_capes"],
    description: "The complete official Minecraft cape collection",
    enabled: true,
    expires: null,
    globalLimit: null,
    perAccountLimit: 1,
  },
};

// Accounts that have a reward without redeeming a code (32-hex UUID -> reward ids).
// Clients carry the same list so these work without the service.
export const BUILTIN_GRANTS = {
  // bringing
  ba14933ea7ad48168696a788cc1b3c1f: ["official_capes"],
};

/** Upper-case, trimmed code, or null when it can't be a code. */
export function normalizeCode(raw) {
  if (typeof raw !== "string") return null;
  const c = raw.trim().toUpperCase();
  return /^[A-Z0-9][A-Z0-9_-]{0,31}$/.test(c) ? c : null;
}

/** Reward ids a list of rewards grants, with bundles expanded (unknown ids are dropped). */
export function expandRewards(ids, rewards = REWARDS) {
  const out = new Set();
  const visit = (id, depth) => {
    const r = rewards[id];
    if (!r || out.has(id) || depth > 4) return;
    out.add(id);
    if (r.type === "bundle" && Array.isArray(r.includes)) r.includes.forEach((i) => visit(i, depth + 1));
  };
  ids.forEach((id) => visit(id, 0));
  return [...out].sort();
}

/** Check the tables once at start-up (and in the tests) so a typo can't ship. */
export function checkTables(codes = CODES, rewards = REWARDS, grants = BUILTIN_GRANTS) {
  for (const [id, r] of Object.entries(rewards)) {
    if (!/^[a-z0-9_]{1,48}$/.test(id)) throw new Error(`reward ${id}: bad id`);
    if (typeof r.type !== "string" || typeof r.name !== "string") throw new Error(`reward ${id}: type and name are required`);
    if (r.type === "bundle" && (!Array.isArray(r.includes) || r.includes.some((i) => !rewards[i])))
      throw new Error(`reward ${id}: bundle includes unknown rewards`);
  }
  for (const [code, c] of Object.entries(codes)) {
    if (normalizeCode(code) !== code) throw new Error(`code ${code}: must be upper case A-Z, 0-9, _ or -`);
    if (!Array.isArray(c.rewards) || c.rewards.length === 0 || c.rewards.some((r) => !rewards[r]))
      throw new Error(`code ${code}: rewards must be known reward ids`);
    if (c.expires != null && Number.isNaN(Date.parse(c.expires))) throw new Error(`code ${code}: bad expires`);
    for (const k of ["globalLimit", "perAccountLimit"]) {
      if (c[k] != null && !(Number.isInteger(c[k]) && c[k] > 0)) throw new Error(`code ${code}: bad ${k}`);
    }
  }
  for (const [uuid, ids] of Object.entries(grants)) {
    if (!/^[0-9a-f]{32}$/.test(uuid) || ids.some((r) => !rewards[r])) throw new Error(`grant ${uuid}: bad entry`);
  }
}
