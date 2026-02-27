# Asset Links Hosting and RPID

## The Rule

The `assetlinks.json` file **must** be hosted at exactly:

```
https://<RPID>/.well-known/assetlinks.json
```

Android makes a direct HTTP request to this exact URL. There is no flexibility.

## If RPID = `jagdeep.me`

Android will fetch:

```
https://jagdeep.me/.well-known/assetlinks.json
```

### What works

| Location | Works? |
|---|---|
| `https://jagdeep.me/.well-known/assetlinks.json` | ✅ Yes |

### What does NOT work

| Location | Works? | Why |
|---|---|---|
| `https://www.jagdeep.me/.well-known/assetlinks.json` | ❌ No | `www.jagdeep.me` is a different host |
| `https://passkey.jagdeep.me/.well-known/assetlinks.json` | ❌ No | Subdomain, not the RPID host |
| `https://passkey-sample-e9304.web.app/.well-known/assetlinks.json` | ❌ No | Different domain entirely |

Android does **not** follow redirects and does **not** check any other domain or subdomain. `www.jagdeep.me` and `jagdeep.me` are treated as completely separate hosts even though humans often consider them the same site.

## Why this matters

WebAuthn's security model ties passkey credentials to the RPID. The assetlinks.json at `https://<RPID>/.well-known/assetlinks.json` is how Android verifies that the app is authorized to use passkeys for that domain. If the file is unreachable at that exact URL, passkey sign-in will fail on Android with a credential association error.

## Alternatives if you cannot host on `jagdeep.me`

1. **Use `passkey.jagdeep.me` as the RPID** — host the file at `https://passkey.jagdeep.me/.well-known/assetlinks.json` via Vercel or Firebase Hosting.
2. **Use the Firebase Hosting domain as RPID** — `passkey-sample-e9304.web.app` already serves from `public/`, so placing the file in `public/.well-known/assetlinks.json` works out of the box.
3. **Proxy `jagdeep.me` through Cloudflare** — serve only the `/.well-known/assetlinks.json` path from a Worker or Page Rule pointed at your hosting, leaving the rest of the domain untouched.
