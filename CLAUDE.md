# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Local development (original Express server, no Firebase)
npm start           # Run server on :3000
npm run dev         # Run with auto-restart (node --watch)

# Firebase local development
npm run emulate     # firebase emulators:start (hosting :5000, functions :5001, firestore :8080, ui :4000)

# Deploy to Firebase
npm run deploy      # firebase deploy (hosting + functions + firestore rules)
firebase deploy --only hosting    # hosting only
firebase deploy --only functions  # functions only
```

No build step, test suite, or linter is configured.

## Architecture

This is a full-stack WebAuthn/Passkeys demo app. The root `server.js` is kept for local Express development. The Firebase deployment lives in `functions/`.

### Firebase deployment (`functions/`)

- `functions/index.js` — wraps the Express app as a single Firebase Function named `api` (Gen 2, `us-central1`)
- `functions/server.js` — Express app with Firestore replacing in-memory storage; no `app.listen()`, no `express.static()`
- Firebase Hosting rewrites `/api/**` to the `api` function; all other paths serve from `public/`

**Firestore collections:**
- `users/{username}` — `{ id, username, passkeys[] }`
- `challenges/{sessionID}` — `{ challenge, username, rpID, createdAt }`

**Important:** `@simplewebauthn/server` returns `credential.publicKey` as `Uint8Array`, which Firestore cannot store. The helpers in `functions/server.js` use `isoBase64URL.fromBuffer()` on write and `isoBase64URL.toBuffer()` on read for every passkey's `publicKey` field.

**RP config** comes from env vars: `RPID`, `RPDISPLAYNAME`, `EXPECTED_ORIGIN`. Defaults to `localhost`/`:3000` for local Express dev. For Firebase emulator, set `EXPECTED_ORIGIN=http://localhost:5000` in `functions/.env` (Hosting emulator port, not Functions port).

**Session management:** `express-session` is kept solely for a stable `req.sessionID` cookie. Session data is not stored in express-session — challenges go directly to Firestore. Cookie `secure` flag is `false` locally and `true` in production (via `NODE_ENV`).

### Backend (`server.js` — local dev only)

Single-file Express server, in-memory Maps (`users`, `challenges`). Run with `npm start` or `npm run dev`. Not used in Firebase deployment.

**API endpoints (same in both `server.js` and `functions/server.js`):**
- `POST /api/generate-registration-options` — accepts user-supplied WebAuthn config params, returns challenge
- `POST /api/verify-registration` — verifies authenticator response, stores passkey credential
- `POST /api/generate-authentication-options` — generates authentication challenge
- `POST /api/verify-authentication` — verifies authentication response
- `GET /api/users` — lists all registered users and their passkeys (debug)

### Frontend (`public/`)

- `index.html` — home page with registered user list
- `signup.html` — passkey registration form with all configurable WebAuthn params (attestation, user verification, resident key, authenticator attachment, algorithms, timeout, etc.)
- `login.html` — passkey authentication form
- `app.js` — shared JS logic for both flows; uses SimpleWebAuthn browser lib via CDN (`startRegistration`, `startAuthentication`). All API calls use relative paths (`/api/...`).
- `styles.css` — all styling

**Authentication flow (both registration and login):**
1. Client collects form config and POSTs to generate-options endpoint
2. Server returns WebAuthn options/challenge
3. Client calls browser credential API (via SimpleWebAuthn)
4. User interacts with authenticator
5. Client POSTs authenticator response to verify endpoint
6. Server verifies and returns result; client displays JSON output
