# Night cosmetics service

A tiny Cloudflare Worker that lets Night players see each other's capes. It stores
one thing per Minecraft account: the id of the Night cape that account has equipped.

## How it works

- **Publishing** (`PUT /v1/capes/<uuid>`): when you change your cape, your Night client gets
  the account's Mojang player certificate (the key pair Minecraft 1.19+ signs chat with;
  Mojang signs its public key together with the UUID and expiry) and signs
  `night-cape-v1|<uuid>|<cape>|<timestamp>|<nonce>` with it. The Worker checks Mojang's
  signature against Mojang's published player-certificate keys (`src/mojang-keys.js`), then
  the player's signature, before it stores the cape, so only the owner of an account can set
  its cape. Requests over two minutes old are refused. The Worker never contacts Mojang, and
  your login token and private key never leave your PC. If Mojang ever rotates its keys,
  copy the new `playerCertificateKeys` from https://api.minecraftservices.com/publickeys.
- **Looking up** (`GET /v1/capes?uuids=a,b,...`, up to 100 per request): clients batch the
  players around them, cache answers for 5 minutes, and draw the capes themselves.
- Only built-in catalog capes and Mojang-catalog capes are shared. Uploaded capes are local
  images and are never sent anywhere.

Storage is one Workers KV namespace. The free plan covers small communities: about 100,000
lookups and 1,000 cape changes per day. The Worker writes only when a cape actually changes.

## Deploy (once, on your PC)

You need a free Cloudflare account and Node.js 18 or newer (`node --version`).

```
cd services\cosmetics
npx wrangler login
npx wrangler kv namespace create CAPES
```

Copy the `id` that the last command prints into `wrangler.toml` in place of
`PASTE_KV_NAMESPACE_ID_HERE`, then run:

```
npx wrangler deploy
```

It prints the service URL, for example `https://night-cosmetics.<your-subdomain>.workers.dev`.
Put that URL in `client/src/main/resources/assets/kestrel/cosmetics_service.json` (the official
service is `https://night-cosmetics.nightlauncher.workers.dev`):

```json
{ "url": "https://night-cosmetics.<your-subdomain>.workers.dev" }
```

The next Night release then shares capes. While `url` is empty, capes stay on your PC only.

## Test

```
npm test
```

The tests use Node's built-in test runner, with a stand-in for KV and test keys standing in for Mojang's.
