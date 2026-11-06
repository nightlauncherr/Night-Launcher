# Night cosmetics service

A tiny Cloudflare Worker that lets Night players see each other's capes. It stores
one thing per Minecraft account: the id of the Night cape that account has equipped.

## How it works

- **Publishing** (`PUT /v1/capes/<uuid>`): when you change your cape, your Night client
  asks Mojang's session server to record a "join" for
  `sha1("night-cape-v1|<uuid>|<cape>|<timestamp>|<nonce>")`, which is the same thing every
  online Minecraft server does when you connect. The Worker checks that join with Mojang
  (`hasJoined`) before it stores the cape, so only the owner of an account can set its cape.
  The server id covers the cape and the time, so a captured request can't be replayed with a
  different cape. Your Minecraft login token goes only to Mojang and never to this service.
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
Put that URL in `client/src/main/resources/assets/kestrel/cosmetics_service.json`:

```json
{ "url": "https://night-cosmetics.<your-subdomain>.workers.dev" }
```

The next Night release then shares capes. While `url` is empty, capes stay on your PC only.

## Test

```
npm test
```

The tests use Node's built-in test runner, with stand-ins for KV and Mojang.
