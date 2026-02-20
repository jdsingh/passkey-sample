# Project Context — passkeys-sample

A full-stack **WebAuthn/Passkeys demo** with a shared backend (Firebase) and three clients: web browser, Android, and iOS.

---

## Repository Layout

```
passkeys-sample/
├── server.js              # Local-only Express server (in-memory storage)
├── public/                # Web frontend (served by Firebase Hosting)
│   ├── index.html
│   ├── signup.html
│   ├── login.html
│   ├── app.js
│   └── styles.css
├── functions/             # Firebase deployment
│   ├── index.js           # Cloud Function wrapper
│   ├── server.js          # Express + Firestore
│   ├── package.json
│   └── .env               # RPID, RPDISPLAYNAME, EXPECTED_ORIGIN, SESSION_SECRET
├── AndroidApp/            # Kotlin/Jetpack Compose Android app
├── iOSApp/                # SwiftUI iOS app
├── firebase.json
├── firestore.rules        # All direct client access blocked; server-side only
├── .firebaserc            # project: passkey-sample-e9304
└── package.json
```

---

## Firebase Backend (`functions/`)

### Entry point

`functions/index.js` — wraps the Express app as a single HTTP Cloud Function named `api` (Gen 2, `us-central1`, 120 s timeout).

### Express app

`functions/server.js` — same API surface as the local `server.js` but with Firestore replacing in-memory Maps.

#### Firestore collections

| Collection | Document key | Fields |
|---|---|---|
| `users` | `{username}` | `id`, `username`, `passkeys[]` |
| `challenges` | `{challengeId}` (UUID) | `challenge`, `username\|null`, `rpID`, `createdAt` |

#### Passkey serialization (critical)

`@simplewebauthn/server` returns `credential.publicKey` as a `Uint8Array`. Firestore cannot store `Uint8Array`, so every write/read converts it:

- **Write**: `isoBase64URL.fromBuffer(uint8array)` → stored as string
- **Read**: `isoBase64URL.toBuffer(string)` → reconstructed as `Uint8Array`

Helper functions: `deserializePasskey`, `addPasskeyToUser`, `updatePasskeyCounter`.

#### RP configuration

From env vars in `functions/.env`:

| Var | Local emulator | Production |
|---|---|---|
| `RPID` | `localhost` | `passkey-sample-e9304.web.app` |
| `RPDISPLAYNAME` | `Passkeys Sample` | same |
| `EXPECTED_ORIGIN` | `http://localhost:5000` | `https://passkey-sample-e9304.web.app` |

`EXPECTED_ORIGIN` supports a comma-separated list.

### API endpoints

All endpoints live under `/api/`.

#### `POST /api/generate-registration-options`

Request:
```json
{
  "username": "alice",
  "attestationType": "none|direct",
  "userVerification": "discouraged|preferred|required",
  "residentKey": "discouraged|preferred|required",
  "authenticatorAttachment": "any|platform|cross-platform",
  "timeout": 60000,
  "supportedAlgorithmIDs": [-7, -257],
  "preferredAuthenticatorType": "none|securityKey|localDevice|remoteDevice",
  "customRpName": "",
  "customRpID": ""
}
```

Response: WebAuthn options JSON + `challengeId` (UUID). Challenge stored in Firestore.

#### `POST /api/verify-registration`

Request:
```json
{ "response": <authenticator response>, "challengeId": "<uuid>" }
```

Response:
```json
{ "verified": true, "registrationInfo": { "credentialID", "credentialDeviceType", "credentialBackedUp" } }
```

Stores passkey to `users/{username}.passkeys[]`:
```json
{ "id", "publicKey (Base64URL)", "counter", "transports", "deviceType", "backedUp", "createdAt" }
```

#### `POST /api/generate-authentication-options`

Request:
```json
{ "username": "alice", "userVerification": "preferred", "timeout": 60000 }
```

`username` is optional — omitting it triggers discoverable credential flow (empty `allowCredentials`).

Response: WebAuthn options JSON + `challengeId`.

#### `POST /api/verify-authentication`

Request:
```json
{ "response": <authenticator response>, "challengeId": "<uuid>" }
```

Response:
```json
{ "verified": true, "username": "alice" }
```

Scans all users to find credential owner, verifies assertion, increments counter, deletes challenge.

#### `GET /api/users` (debug)

Lists all users and passkey metadata.

---

## Local Express Server (`server.js`)

Identical API surface, but uses in-memory `Map`s:

```js
const users = new Map();      // username → { id, username, passkeys[] }
const challenges = new Map(); // sessionID → challenge
```

No Firestore, no serialization concerns. Run with `npm start` or `npm run dev`.

---

## Web Frontend (`public/`)

| File | Purpose |
|---|---|
| `index.html` | Home page; live list of registered users/passkeys |
| `signup.html` | Registration form with all configurable WebAuthn params |
| `login.html` | Authentication form (username optional) |
| `app.js` | Shared JS: registration + authentication flows, error handling |
| `styles.css` | Responsive CSS, 600 px mobile breakpoint |

### `app.js` flows

**Registration** (lines 36–129):
1. Collect form config → POST `/api/generate-registration-options` → receive `challengeId`
2. `startRegistration({ optionsJSON })` (SimpleWebAuthn browser CDN)
3. POST `/api/verify-registration` with response + `challengeId`

**Authentication** (lines 131–210):
1. Collect optional username → POST `/api/generate-authentication-options` → receive `challengeId`
2. `startAuthentication({ optionsJSON })`
3. POST `/api/verify-authentication` with response + `challengeId`

**Error mapping**: `InvalidStateError` → already registered; `NotAllowedError` → cancelled.

---

## Android App (`AndroidApp/`)

**Language**: Kotlin
**UI**: XML layouts (View Binding) + Navigation Component
**Architecture**: MVVM
**Min SDK**: 30 · Target SDK: 36
**Base URL**: `https://passkey-sample-e9304.web.app`

### Package: `me.jagdeep.passkeysample`

| File | Role |
|---|---|
| `MainActivity.kt` | Host activity; NavController + edge-to-edge insets |
| `MainFragment.kt` | Launch screen; "Sign In" button → `SignInFragment` |
| `SignInFragment.kt` | Sign-in UI; autofill integration + manual button |
| `SignInViewModel.kt` | ViewModel; auth state machine |
| `HomeFragment.kt` | Welcome screen after success; Sign Out pops back stack |
| `auth/AuthRepository.kt` | Data layer: generates options, verifies responses |
| `auth/PasskeyManager.kt` | Wraps Android `CredentialManager` API |
| `network/ApiClient.kt` | HTTP client using `HttpURLConnection` |

### UI state machine (`SignInViewModel`)

```kotlin
sealed class SignInUiState {
    object Idle
    object Loading
    data class Success(val username: String)
    data class Error(val message: String)
}
```

### Authentication flow

1. `SignInViewModel.prefetchOptions()` runs at init — fetches auth options early, creates a `PendingGetCredentialRequest`, stores it in `_pendingRequest`.
2. `SignInFragment` attaches the pending request to the username `TextInputEditText` — system shows passkey picker in the QuickType/autofill bar on focus.
3. **Autofill path**: user taps passkey in bar → `handleAutofillCredential(credential)` → `AuthRepository.verifyCredential(credential, challengeId)` → success.
4. **Manual path**: "Sign In" button → `SignInViewModel.signIn(username)` → `AuthRepository.signIn(username)` (full round-trip: generate → get credential → verify).

### Key dependencies

```kotlin
androidx.credentials:credentials
androidx.credentials:credentials-play-services-auth
kotlinx.serialization.json
androidx.navigation:navigation-fragment-ktx
androidx.lifecycle:lifecycle-viewmodel-ktx
```

---

## iOS App (`iOSApp/`)

**Language**: Swift
**UI**: SwiftUI
**Architecture**: MVVM + NavigationStack
**Base URL**: `https://passkey-sample-e9304.web.app`
**Associated domain**: `webcredentials:passkey-sample-e9304.web.app` (entitlement)

### Source files

| File | Role |
|---|---|
| `PasskeySampleApp.swift` | `@main` entry point |
| `ContentView.swift` | NavigationStack root; route enum `.signIn` / `.home(String)` |
| `Views/MainView.swift` | Launch screen; "Sign In with Passkey" → `.signIn` route |
| `Views/SignInView.swift` | Sign-in UI; autofill + manual button |
| `Views/HomeView.swift` | Welcome screen; Sign Out pops navigation |
| `ViewModels/SignInViewModel.swift` | `@Observable` ViewModel; auth state machine |
| `Auth/PasskeyManager.swift` | Wraps `ASAuthorizationController` |
| `Auth/AuthRepository.swift` | Data layer: generate options + verify |
| `Network/ApiClient.swift` | URLSession HTTP client |

### UI state machine (`SignInViewModel`)

```swift
enum SignInUiState: Equatable {
    case idle
    case loading
    case success(String)
    case error(String)
}
```

### Authentication flow

1. `SignInView.onAppear` → `viewModel.prefetchForAutofill()` — fetches auth options, calls `PasskeyManager.setupAutofill(requestOptionsJSON, onCredential:)`.
2. System registers the app for passkey autofill on the username `TextField` (`textContentType(.username)`).
3. **Autofill path**: user taps passkey in QuickType → `onCredential` callback → `viewModel.handleAutofillCredential(authResponseJSON)` → verify → navigate.
4. **Manual path**: "Sign In" button → `viewModel.signIn(username)` → `PasskeyManager.signIn(requestJSON)` → `ASAuthorizationController` modal → verify → navigate.

### `PasskeyManager` internals

- `makeController(from json)` — parses challenge from Base64URL, creates `ASAuthorizationPlatformPublicKeyCredentialProvider` and `ASAuthorizationController`.
- `buildResponseJSON(from credential)` — serializes `ASAuthorizationPlatformPublicKeyCredentialAssertion` fields to Base64URL JSON string for the server.
- Implements `ASAuthorizationControllerDelegate` and `ASAuthorizationControllerPresentationContextProviding`.
- Cancellation detected via `ASAuthorizationError.canceled`.

---

## WebAuthn Flow (All Platforms)

### Registration

```
Client                              Server
  |                                   |
  |-- POST generate-registration-options -->|
  |<-- { options, challengeId } ------------|
  |                                   |
  | [User authenticator interaction]  |
  |                                   |
  |-- POST verify-registration ------>|
  |   { response, challengeId }       |
  |<-- { verified, registrationInfo }-|
```

### Authentication

```
Client                              Server
  |                                   |
  |-- POST generate-authentication-options -->|
  |   { username? }                   |
  |<-- { options, challengeId } ------|
  |                                   |
  | [User authenticator interaction]  |
  |                                   |
  |-- POST verify-authentication ---->|
  |   { response, challengeId }       |
  |<-- { verified, username } --------|
```

---

## Environment / Deployment Reference

| Target | Command | Frontend port | Backend port | Firestore port |
|---|---|---|---|---|
| Local Express | `npm start` | 3000 | 3000 | — |
| Local (watch) | `npm run dev` | 3000 | 3000 | — |
| Firebase emulators | `npm run emulate` | 5000 | 5001 | 8080 (UI: 4000) |
| Firebase production | `npm run deploy` | web.app | web.app (Cloud Run) | Firestore prod |

Production URL: `https://passkey-sample-e9304.web.app`

---

## Security Notes

- Firestore rules block all direct client SDK access — everything goes through Firebase Admin SDK.
- Challenges are single-use and deleted after verification.
- `express-session` is kept only for a stable `req.sessionID` cookie; no server-side session data.
- Cookie `secure` flag is `false` locally, `true` in production (`NODE_ENV=production`).
- Counter is updated atomically in Firestore to detect cloned authenticators.
- `excludeCredentials` prevents duplicate passkey registration for the same device.
