# Oeil

**An Android assistant that can actually use your phone — with your approval.**

Oeil is a Jetpack Compose Android app backed by a small Python relay. It chats
with a model API, and when you enable Android control it can read the visible
screen and perform one approved action at a time: open an app, tap a button,
type text, scroll, go back, and more.

## What it does

- **Chat assistant** with local conversation history.
- **Android control** through an Accessibility Service: open apps, tap controls,
  enter ordinary text, scroll, go Back, go Home, open recent apps, open
  notifications, and open links. Every action requires explicit approval.
- **Floating progress pill** while Oeil works in another app.
- **No API URL or key setup** in the app; the backend URL is compiled in.
- **Provider key stays on the backend**, never in the APK.
- **Accounts** with email/password and Google, Microsoft or GitHub sign-in.
- **Light, dark and system themes**, plus Auto, Precise and Creative reply modes.
- **Voice dictation**, stop generation and conversation search.

Supported model provider: **NVIDIA NIM AI** (OpenAI-compatible).

## The idea

Most assistants answer questions but cannot act on your phone. Fully automatic
phone control is powerful but also risky. Oeil takes a middle path:

1. The model proposes **one small action**.
2. Android shows **exactly what will happen**.
3. You approve or deny it.
4. The result is sent back, and Oeil decides what to do next.

The Accessibility Service reads the accessibility tree — labels, IDs, clickable/
editable flags, installed apps — **not screenshots**. Password fields are excluded.
If you deny an action, the task stops. Tasks stop after eight actions.

## See it in action

1. Ask **“Open Settings.”**
2. Oeil shows an approval card for the Settings app.
3. Tap **Allow** — Settings opens, the floating pill shows Oeil is working.
4. Ask **“Go home.”**
5. Allow the action — the phone returns to the launcher.
6. Deny an action to show that Oeil stops immediately.

## Tech stack

| Layer | Technology |
|---|---|
| Android app | Kotlin, Jetpack Compose, SQLite |
| Device control | Android Accessibility Service |
| Backend | Python 3.10+, FastAPI, Uvicorn, SQLite |
| Model provider | NVIDIA NIM AI (OpenAI-compatible) |
| Build | Gradle 9.8, AGP 9.4, compileSdk 37 |

## Backend architecture

`backend/main.py` remains the existing FastAPI relay for NVIDIA NIM chat and
Android agent steps. Authentication is mounted as a separate router from
`backend/auth.py`, backed by the modular `backend/app/` package. This keeps the
chat/agent code path unchanged while allowing authentication to be tested and
maintained independently.

```text
backend/
├── main.py                    # Existing NVIDIA chat/agent API and app setup
├── auth.py                    # /auth router, OAuth flow and rate-limit wiring
├── test_main.py               # Existing chat, agent and auth contract tests
├── app/
│   ├── config.py              # Environment and .env loading
│   ├── security.py            # Password hashing, session tokens, secret checks
│   ├── deps.py                # Bearer-token dependency and current-user lookup
│   ├── rate_limit.py          # Process-local authentication rate limiter
│   ├── db/
│   │   ├── connection.py      # SQLite connection helper
│   │   ├── schema.py          # Table creation
│   │   ├── users.py           # User table operations
│   │   ├── sessions.py        # Session table operations
│   │   └── oauth.py           # OAuth account/state operations
│   └── services/
│       └── auth_service.py    # Signup, login and session business logic
└── tests/
    └── test_auth_security.py  # Focused authentication security tests
```

## Quick start

### 1. Run the backend

Requirements: Python 3.10+ and an NVIDIA API key. Account endpoints also require
a strong `OEIL_AUTH_SECRET`; OAuth additionally requires provider credentials and
a public HTTPS backend URL.

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env

# Edit .env and set your NVIDIA API key:
#   NVIDIA_API_KEY=your-nvidia-key
# Optional model override:
#   NVIDIA_MODEL=nvidia/nemotron-3.5-lightning-30b-a3b

# Required for account signup/login/session validation:
#   OEIL_AUTH_SECRET=<paste output of: openssl rand -hex 32>

# Optional OAuth provider settings:
#   OEIL_PUBLIC_BASE_URL=https://your-public-backend.example
#   GOOGLE_CLIENT_ID=...       GOOGLE_CLIENT_SECRET=...
#   MICROSOFT_CLIENT_ID=...    MICROSOFT_CLIENT_SECRET=...
#   GITHUB_CLIENT_ID=...       GITHUB_CLIENT_SECRET=...

uvicorn main:app --host 0.0.0.0 --port 8000
```

On Windows, activate the virtual environment with `.venv\Scripts\activate`
and use `python` instead of `python3`.

Check it:

```bash
curl http://127.0.0.1:8000/health
```

### 2. Build and install the Android app

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open Oeil, then enable **Oeil Android control** in Android Accessibility
settings. Control turns on automatically once the service connects.

### 3. Point the app at your backend

The app has no URL field. The address is compiled in at build time:

```bash
# Default backend URL: https://oeil.onrender.com
./gradlew assembleDebug

# Real deployment
./gradlew assembleDebug -PoeilBackendUrl=https://oeil.example.com
# or
OEIL_BACKEND_URL=https://oeil.example.com ./gradlew assembleDebug
```

## Configuration

`backend/.env` is loaded automatically. Shell environment variables take
precedence over the file. A blank `NVIDIA_MODEL` falls back to the default (`nvidia/nemotron-3.5-lightning-30b-a3b`).

| Variable | Purpose |
|---|---|
| `NVIDIA_API_KEY` | Provider API key (required for chat and agent routes) |
| `NVIDIA_MODEL` | Model name |
| `NVIDIA_BASE_URL` | Provider base URL (`NVIDIA_API_BASE_URL` is accepted as a legacy alias) |
| `OEIL_AUTH_SECRET` | Required for account auth; at least 32 random characters |
| `OEIL_AUTH_DB_PATH` | Optional auth database path; defaults to `oeil-auth.sqlite3` |
| `OEIL_PUBLIC_BASE_URL` | Public HTTPS origin used for OAuth callbacks |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Optional Google OAuth credentials |
| `MICROSOFT_CLIENT_ID` / `MICROSOFT_CLIENT_SECRET` | Optional Microsoft OAuth credentials |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | Optional GitHub OAuth credentials |

`OEIL_AUTH_SECRET` must be stable across restarts and at least 32 characters.
Generate one with:

```bash
openssl rand -hex 32
```

Obvious weak or predictable secrets are rejected. OAuth is enabled only for a
provider whose client ID, client secret, and `OEIL_PUBLIC_BASE_URL` are all set;
`OEIL_PUBLIC_BASE_URL` must be the public HTTPS origin of the backend. Accounts
use email/password, Google, Microsoft or GitHub sign-in. Configure provider OAuth
secrets on the backend only. The app receives OAuth sessions through
`oeil://auth/callback`. Never put provider keys in the app or commit them to Git.

## Authentication and accounts

### SQLite database and user storage

Authentication data is stored in a SQLite file selected by `OEIL_AUTH_DB_PATH`
(default `oeil-auth.sqlite3` in the backend working directory). Tables are created
automatically by `backend/app/db/schema.py`:

| Table | Purpose |
|---|---|
| `auth_users` | User id, unique normalized email, display name, optional password hash, creation time |
| `auth_sessions` | Revocable bearer-session records: session id, user id, expiry, revoked flag |
| `oauth_accounts` | Provider identity links: `(provider, provider_user_id)` mapped to a user |
| `oauth_states` | Short-lived OAuth state values bound to a provider and redirect URI |

Connections enable `PRAGMA foreign_keys = ON`. The auth database is separate from
the Android app's local SQLite database and should not be committed to Git.

### Email/password signup and login

`POST /auth/signup` accepts `name`, `email`, and `password`. Email is trimmed and
case-folded; passwords must be 8–256 characters. A duplicate normalized email
returns `409`. Successful signup returns a bearer token and the public user
payload (`id`, `name`, `email`).

`POST /auth/login` accepts `email` and `password`. Invalid credentials return a
generic `401` without revealing whether the email exists.

Passwords are hashed with PBKDF2-HMAC-SHA256, 310,000 rounds, and a fresh 16-byte
random salt. The stored format is
`pbkdf2_sha256$<rounds>$<salt>$<digest>`. Verification uses a constant-time
comparison.

### Sessions and logout

Successful signup/login returns a signed bearer token. The token contains the
user id (`sub`), a session id (`jti`), and a 30-day expiry (`exp`), and is signed
with `OEIL_AUTH_SECRET`. A matching row is stored in `auth_sessions`.

`GET /auth/me` requires `Authorization: Bearer <token>`. The backend verifies the
signature, confirms the session exists and is neither revoked nor expired, and
loads the user. `POST /auth/logout` marks the current session revoked; the same
token then fails with `401`.

### Authentication API

| Method | Endpoint | Auth | Purpose |
|---|---|---|---|
| `POST` | `/auth/signup` | None | Create an email/password account; returns token and user |
| `POST` | `/auth/login` | None | Verify credentials; returns token and user |
| `GET` | `/auth/me` | Bearer | Return the current user |
| `POST` | `/auth/logout` | Bearer | Revoke the current session |
| `GET` | `/auth/oauth/{provider}/start` | None | Start Google, Microsoft or GitHub OAuth |
| `GET` | `/auth/oauth/{provider}/callback` | None | Provider callback; redirects to the app |

Signup, login, and OAuth endpoints are rate-limited. Current limits are signup
10 requests per 15 minutes, login 20 per 5 minutes, and OAuth start/callback 20
per 10 minutes per client IP. Rejections return `429` with `Retry-After`.

### OAuth login and account-linking behavior

Supported providers are `google`, `microsoft`, and `github`. OAuth start requires
the provider's client ID/secret and `OEIL_PUBLIC_BASE_URL` set to the public HTTPS
backend origin. The app redirect is fixed at `oeil://auth/callback`. On success
the callback redirects to `oeil://auth/callback#token=...`. State, profile, and
account-linking failures redirect to `oeil://auth/callback?error=...`;
configuration or upstream provider failures may return an HTTP error.

OAuth state is random, single-use, bound to the provider and redirect URI, and
expires after 10 minutes. The callback validates and consumes the state before
exchanging the authorization code.

Linking rules:

- An existing provider link signs the user in.
- A new provider link is created only when the provider confirms the email is
  verified and no local account already owns that email.
- If the provider email matches an existing local account, the callback returns a
  controlled error instead of automatically linking. This prevents OAuth account
  takeover of a password account.
- For a new link, an unverified provider email is rejected.
- Microsoft `mail` and `userPrincipalName` are not treated as proof. New
  Microsoft sign-ins require an authoritative `email_verified` or
  `verified_primary_email` claim; otherwise they are rejected. Existing linked
  Microsoft accounts continue to work.
- OAuth state, profile, and account-linking errors redirect to
  `oeil://auth/callback?error=...`.

### Authentication security limitations

- Rate limiting is process-local and keyed by the immediate client IP. It is not
  shared across multiple workers and may see only a proxy IP behind a reverse
  proxy.
- Production deployments should add reverse-proxy or shared-store rate limiting
  (for example Redis). Do not trust `X-Forwarded-For` unless a trusted proxy
  sanitizes it.
- Local email signup does not verify email ownership. An attacker could
  pre-register an address and block the legitimate owner from OAuth sign-in, but
  cannot take over the account because automatic linking is disabled.
- New Microsoft sign-ins require an authoritative email-verification claim;
  Microsoft Graph often does not provide one.
- Existing OAuth links created before this behavior was introduced should be
  audited if earlier automatic linking is a concern.

## Safety and privacy

- Every Android action requires explicit approval.
- The model cannot enter passwords, payment details or security codes.
- Only accessible screen data is sent: labels, IDs, clickable/editable flags,
  and the launchable app list. Image bytes are not sent.
- Provider keys stay on the backend.
- Account email, display name, password hash, session records, and OAuth provider
  links are stored in the backend auth SQLite database.
- Tasks stop after eight actions.
- The floating status pill only appears while Oeil is working in another app.
- Check current Google Play policy before release; accessibility use may be
  restricted.

## Current limitations

- Screenshot vision is not implemented; Oeil relies on accessible labels.
- No arbitrary touch gestures or gesture recording.
- No app-specific integrations.
- Authentication is implemented; review **Authentication security limitations**
  above before public deployment.

## Tests

```bash
# Backend: existing chat/agent tests plus auth security tests
cd backend
python3 -m unittest -v

# Focused authentication security tests
python3 -m unittest -v tests.test_auth_security

# Android tests
./gradlew test
```

On Windows, use `python -m unittest -v` and
`python -m unittest -v tests.test_auth_security` from the `backend` directory.

## Project layout

```text
app/src/main/java/com/example/oeil/
├── MainActivity.kt            # Single activity and auth deep link
├── OeilApp.kt                 # Profile, settings, account and screen wiring
├── model/Models.kt            # Messages, threads, actions and local profile
├── data/
│   ├── OeilApi.kt             # Relay, auth, agent clients and URL rules
│   └── OeilDatabase.kt        # SQLite profile, threads, messages and settings
├── state/OeilChatState.kt     # Conversation state and reply orchestration
├── ui/theme/Theme.kt          # Palette and typography
├── components/
│   ├── AppDrawer.kt           # Assistant-style sidebar
│   ├── ChatComponents.kt      # Messages, typing dots, prompts, touch surface
│   └── Composer.kt            # Header and composer
├── screen/
│   ├── OeilScreen.kt          # Chat layout, drawer and message list
│   └── AccountScreens.kt      # Settings, appearance, account and sign-in
└── system/OeilAccessibilityService.kt  # Android control, approvals, status pill
backend/
├── main.py                    # Existing FastAPI relay to NVIDIA model provider and agent
├── auth.py                    # /auth router, OAuth flow and rate-limit wiring
├── test_main.py               # Existing backend contract, chat/agent and auth tests
├── app/
│   ├── config.py              # Environment and .env loading
│   ├── security.py            # Password hashing, session tokens and secret checks
│   ├── deps.py                # Bearer-session dependency
│   ├── rate_limit.py          # Process-local auth rate limiter
│   ├── db/
│   │   ├── connection.py      # SQLite connection helper
│   │   ├── schema.py          # Table creation
│   │   ├── users.py           # User table operations
│   │   ├── sessions.py        # Session table operations
│   │   └── oauth.py           # OAuth account and state operations
│   └── services/
│       └── auth_service.py    # Signup, login and session logic
└── tests/
    └── test_auth_security.py  # Focused authentication security tests
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for data flow.
