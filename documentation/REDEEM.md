# Redeem codes

Codes unlock Night cosmetics for **one Minecraft account**. Enter them on the launcher's
**Redeem** page while that account is the active one; the page also lists what the account
owns and the codes it has redeemed (code, date, rewards).

| Code | Reward |
|---|---|
| `LOVEMOJANG` | The official Minecraft cape collection (every cape in the Mojang category) |

- Each account redeems a code once. Any number of accounts can redeem the same code (unless a
  code has a total limit). Rewards never move between accounts.
- Rewards stay through restarts, updates, every instance and every PC, because the cosmetics
  service keeps them. Offline accounts can't redeem: there is no Microsoft account to bind the
  code to.
- Switching accounts (in the launcher or in game) shows and uses only that account's rewards.
  An equipped reward the new account doesn't own stays selected but isn't drawn, and comes
  back when you switch to an account that owns it.

## How it works

The service is the Night cosmetics Worker (`services/cosmetics`).

1. **Redeeming** (`POST /v1/redeem`): the launcher refreshes the account's sign-in, gets its
   Mojang player certificate, and signs `night-redeem-v1|<uuid>|<code>|<time>|<nonce>` with it.
   The service checks Mojang's signature on the certificate and the account's signature, so a
   code can only be redeemed for an account by someone who can sign in to it. Requests older than
   two minutes are refused. The redemption is stored under the account's UUID; asking again
   answers "already redeemed" with the same rewards, so retries and lost replies never grant
   twice or leave a half state.
2. **Entitlements** (`GET /v1/entitlements/<uuid>`): the account's rewards and redemption
   history, signed by the service with an RSA key that exists only as the Worker secret
   `ENTITLEMENT_SIGNING_KEY`. The launcher and the game check the signature with the public key
   in `client/src/main/resources/assets/kestrel/entitlement_public_key.txt` and that the
   document names their account. The launcher caches it in `<data folder>\entitlements\` and
   gives each game a copy before it starts and before an in-game account switch completes; the
   game also checks with the service itself. Editing a cached file, or copying another
   account's, unlocks nothing.
3. **Where it's enforced**: the launcher's Cosmetics page (locked capes can be previewed, not
   equipped), the in-game cape list, drawing your own cape, and cape sharing (the service
   refuses to publish, and hides, official capes of accounts without the collection).

Limits: your own game is a program on your PC, so a modified client can draw anything on your
own screen; what the service guarantees is what other Night players see. A code's total limit
(if it has one) is approximate, because Workers KV has no atomic counters.

## Adding a code or reward

Edit `services/cosmetics/src/rewards.js` and run `npx wrangler deploy` there:

```js
export const CODES = {
  LOVEMOJANG: { rewards: ["official_capes"], description: "…", enabled: true,
                expires: null, globalLimit: null, perAccountLimit: 1 },
  SPOOKY2027: { rewards: ["ghost_cape"], description: "Halloween cape", enabled: true,
                expires: "2027-11-01T00:00:00Z", globalLimit: 500, perAccountLimit: 1 },
};
```

Rewards have an id, a `type` (`cape_collection`, `cape`, `animated_cape`, `sky`, `kill_effect`,
`bundle` with `includes`, `ui`), a name and a description. A code that unlocks something
clients already know needs no new release; a new kind of cosmetic needs the client and
launcher to check `entitlements::has_reward` / `EntitlementStore.has` where it is offered,
equipped and drawn. Set `enabled: false` to stop new redemptions (existing ones stay).
`npm test` in `services/cosmetics` checks the tables.
