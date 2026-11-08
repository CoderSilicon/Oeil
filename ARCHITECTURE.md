# Architecture

Oeil is a single-activity Android app. Conversation history, account session and
appearance preference are stored locally in SQLite. The app supports a local
profile as well as a backend account. The relay URL is compiled in at build time.

## Reply flow

```text
OeilScreen
  └── OeilChatState
        └── ConversationReplySource
              └── BackendReplySource
                    └── POST {backend URL}/chat
                          └── Python FastAPI backend
                                └── NVIDIA NIM chat completions
```

Android control path:

```text
OeilChatState (maximum 8 steps)
      ├── OeilAccessibility.screenElements()
      ├── OeilAccessibility.installedApps()
      ├── POST {backend URL}/agent/step
      │     └── NVIDIA NIM tool calling (fixed action declarations)
      ├── OeilAccessibility.approveAndPerform()
      │     └── Android Accessibility approval overlay
      └── OeilAccessibility.updateTaskStatus()
            └── Floating progress banner while another app is in front
```

The Android client sends the last 20 text messages, the selected response mode,
visible screen elements, and the launchable app list. It does not send an account
token or provider API key. The backend adds a mode-specific system prompt and keeps
`NVIDIA_API_KEY` on the server. The relay URL is configured at build time, not
in the UI; an unconfigured build or missing provider key produces an explicit
error, and the running app never silently falls back to mock replies.

## Main pieces

| Area | Responsibility |
|---|---|
| `OeilApp` | Loads the local profile, build-time service URL and settings; opens chat or settings |
| `OeilScreen` | Chat layout, drawer, message list and composer |
| `AccountScreens` | Appearance, account, backend settings and sign-in |
| `AuthClient` | Calls account endpoints and validates OAuth callback sessions |
| `OeilChatState` | Message/thread state, reply lifecycle and local persistence |
| `BackendReplySource` | JSON HTTP client for the Python `/chat` and `/agent/step` endpoints |
| `OeilDatabase` | SQLite local profile, threads, messages and settings |
| `backend/main.py` | Existing FastAPI app, chat/agent validation and NVIDIA model calls |
| `backend/auth.py` | `/auth` router: signup/login/me/logout, OAuth start/callback and rate-limit wiring |
| `backend/app/config.py` | Loads `.env`, reads auth/database/public URL settings |
| `backend/app/security.py` | PBKDF2 hashing, signed bearer tokens and weak-secret checks |
| `backend/app/deps.py` | Bearer dependency and current-user/session validation |
| `backend/app/db/` | SQLite connection, schema, users, sessions and OAuth accounts/states |
| `backend/app/services/auth_service.py` | Signup, login and session business logic |
| `backend/app/rate_limit.py` | Process-local per-IP authentication rate limiter |

The app always uses the live relay; there is no mock reply path in the installed
build.

## Modular backend architecture

`backend/main.py` owns the existing chat and Android-control surface. It creates
the FastAPI app, configures CORS, includes the auth router, and defines `/health`,
`/chat`, and `/agent/step`. Authentication is implemented behind the separate
`backend/auth.py` router and the `backend/app/` package:

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

Responsibilities:

- `app/config.py` loads `backend/.env` when present, lets shell environment
  variables win, and exposes environment values for the database path, auth
  secret, public backend URL, and provider credentials. Most values are trimmed;
  the database path is preserved as-is.
- `app/security.py` contains password hashing and verification, signed bearer
  token creation/decoding, the 30-day session lifetime, and weak-secret
  validation.
- `app/deps.py` defines the optional `HTTPBearer` dependency and the
  `current_user` dependency used by `GET /auth/me` and `POST /auth/logout`.
- `app/db/connection.py` opens SQLite connections with a 10-second timeout,
  `sqlite3.Row` rows, and foreign keys enabled. `app/db/schema.py` creates the
  four auth tables with `CREATE TABLE IF NOT EXISTS`.
- `app/db/users.py`, `sessions.py`, and `oauth.py` contain the SQL operations for
  users, revocable sessions, OAuth identity links, and OAuth states.
- `app/services/auth_service.py` implements signup, login, duplicate-email
  handling, password hashing, and session issuance.
- `backend/auth.py` validates requests, applies rate limits, handles OAuth
  start/callback, and maps service errors to HTTP responses.
- `backend/main.py` remains the provider-facing relay; it does not contain auth
  business logic and does not wrap chat/agent requests in account sessions.

## Authentication architecture

### SQLite user, session and OAuth storage

The backend auth database is separate from the Android app's local SQLite
database. `OEIL_AUTH_DB_PATH` selects the file; the default is
`oeil-auth.sqlite3` in the backend working directory. `app/db/schema.py` creates:

| Table | Contents |
|---|---|
| `auth_users` | `id`, unique normalized `email`, `name`, optional `password_hash`, `created_at` |
| `auth_sessions` | `id`, `user_id`, `expires_at`, `revoked` |
| `oauth_accounts` | `(provider, provider_user_id)` primary key and `user_id` |
| `oauth_states` | `state` primary key, `provider`, `redirect_uri`, `expires_at` |

Foreign keys are enabled per connection. OAuth provider access tokens are not
stored in this database; only the provider identity link is kept.

### Password hashing and bearer-session flow

Signup normalizes email with trim/case-fold and stores a PBKDF2-HMAC-SHA256 hash
with 310,000 rounds and a fresh 16-byte random salt. Verification uses
`hmac.compare_digest` for constant-time comparison. A duplicate email insert is
converted into a `409` response.

`app/security.py` creates a compact JWT-like token with base64url header/payload,
an HMAC-SHA256 signature using `OEIL_AUTH_SECRET`, and claims `sub`, `jti`, and
`exp`. The session lifetime is 30 days, and a matching row is inserted into
`auth_sessions`. `app/deps.py` validates the signature, session id/user id pair,
revocation flag, expiry, and user existence. Logout sets `revoked = 1`; a later
request with the same token receives `401`.

### OAuth state validation and account-linking rules

`GET /auth/oauth/{provider}/start` creates a random
`secrets.token_urlsafe(32)` state, stores it with the provider and redirect URI,
and expires it after 10 minutes. The callback consumes the state under
`BEGIN IMMEDIATE`, then requires the state to match the provider and the fixed
`oeil://auth/callback` redirect.

Linking rules:

- An existing provider link signs the user in.
- A new link requires a provider-verified email and no existing local account
  with that email.
- A provider email matching an existing local account returns a controlled
  `409`; automatic linking is disabled.
- For a new link, an unverified provider email returns `403`.
- Microsoft `mail` and `userPrincipalName` are display data only. New Microsoft
  sign-ins require `email_verified` or `verified_primary_email`; existing linked
  Microsoft accounts continue to work.
- OAuth state, profile, and account-linking errors redirect to
  `oeil://auth/callback?error=...`.

OAuth account creation uses `BEGIN IMMEDIATE` and catches
`sqlite3.IntegrityError` to re-read a concurrently created identity. Other SQLite
errors become controlled `503` or redirect responses rather than HTTP 500s.

### Rate-limiting design

`app/rate_limit.py` implements a thread-safe, process-local sliding-window
limiter keyed by action and `request.client.host`. It is wired into signup,
login, OAuth start, and OAuth callback. Current limits are:

| Action | Limit |
|---|---|
| Signup | 10 requests / 15 minutes |
| Login | 20 requests / 5 minutes |
| OAuth start | 20 requests / 10 minutes |
| OAuth callback | 20 requests / 10 minutes |

Rejected requests return `429` with a `Retry-After` header. The limiter is a
speed bump, not distributed protection: counters are per worker, a reverse proxy
can collapse clients to one address, and `X-Forwarded-For` is intentionally not
trusted. Production should add reverse-proxy or shared-store rate limiting.

### Coexistence with NVIDIA / OpenAI-compatible chat and agent

`backend/main.py` still creates the FastAPI app, includes the auth router once,
and defines the existing provider routes. The chat and agent routes do not depend
on `current_user`, so guest chat behavior is unchanged. Auth uses its own SQLite
tables and `OEIL_AUTH_SECRET`; NVIDIA uses `NVIDIA_API_KEY`, `NVIDIA_MODEL`, and
`NVIDIA_BASE_URL`. The auth package never calls NVIDIA, and `main.py` only mounts
the auth router; it does not reimplement auth logic or wrap chat/agent requests in
account sessions.

`get_nvidia_client()` returns an `AsyncOpenAI` client pointed at
`nvidia_base_url()` with `NVIDIA_API_KEY`, so the backend uses the OpenAI-compatible
client library against NVIDIA NIM. There is no separate OpenAI API key in the
current code.

## Backend API

`POST /chat` accepts a JSON object with `mode` (`AUTO`, `PRECISE`, or
`CREATIVE`) and a non-empty list of `{role, content}` messages. `role` is
`user` or `assistant`. Successful responses are `{ "reply": "..." }`.
`GET /health` checks that the relay is running and reports the configured model
and whether a provider key is present.

Account endpoints:

- `POST /auth/signup` accepts `name`, `email`, and `password`, creates an account,
  and returns `{ "token": "...", "user": { "id", "name", "email" } }`. A
  duplicate normalized email returns `409`; a missing name returns `422`.
- `POST /auth/login` accepts `email` and `password` and returns the same
  token/user payload. Invalid credentials return `401`.
- `GET /auth/me` requires a bearer token and returns the current public user
  payload.
- `POST /auth/logout` requires a bearer token, revokes the current session, and
  returns `{ "status": "ok" }`.
- `GET /auth/oauth/{provider}/start` begins Google, Microsoft or GitHub
  authorization. It requires the provider client credentials and
  `OEIL_PUBLIC_BASE_URL`.
- `GET /auth/oauth/{provider}/callback` handles the provider callback. On success
  it redirects to `oeil://auth/callback#token=...`. State, profile, and
  account-linking failures redirect to `oeil://auth/callback?error=...`;
  configuration or upstream provider failures may return an HTTP error.

Passwords are PBKDF2-hashed, and sessions are signed bearer tokens backed by
revocable SQLite records. OAuth requires a public HTTPS backend. Signup, login,
and OAuth endpoints return `429` with `Retry-After` when their process-local rate
limit is exceeded.

Configuration is read from the backend process environment:

- `NVIDIA_API_KEY` is required and must never be placed in Android resources,
  Gradle properties, or the APK.
- `NVIDIA_BASE_URL` defaults to `https://integrate.api.nvidia.com/v1`;
  `NVIDIA_API_BASE_URL` is accepted as a legacy alias.
- `NVIDIA_MODEL` defaults to `nvidia/nemotron-3.5-lightning-30b-a3b`.
- `OEIL_AUTH_SECRET` is required for account auth and must be a strong value of
  at least 32 characters. Generate it with `openssl rand -hex 32`.
- `OEIL_AUTH_DB_PATH` is optional and defaults to `oeil-auth.sqlite3`.
- `OEIL_PUBLIC_BASE_URL` must be the public HTTPS backend origin for OAuth.
- `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`,
  `MICROSOFT_CLIENT_ID` / `MICROSOFT_CLIENT_SECRET`, and
  `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` are optional provider credentials.

`POST /agent/step` accepts a task, the recent conversation, up to 100 visible
screen elements, and up to 300 launchable apps. NVIDIA model may return one of nine
declared actions (`tap`, `type`, `scroll`, `back`, `home`, `recents`,
`notifications`, `open_app`, `open_url`) or a final message. Apps can be selected
by package name or visible label. The app re-reads the screen between steps and
stops after eight actions. Every action is shown in an Android overlay and must
be approved; the accessibility service is never enabled automatically.

`/chat` and `/agent/step` remain guest-accessible and are not tied to account
sessions. The auth rate limiter does not apply to them. Before public deployment,
enable HTTPS and configure request rate limiting for these routes; enforce bearer
auth if guest access is not intended.

## Data and privacy

Conversations and appearance preferences are stored in the app's local SQLite
database. The service URL is compiled into the APK. Text conversation history
and, when Android control is active, visible screen labels and the launchable app
list are sent to the relay. The current attachment flow stores and displays a
label; image bytes are not sent to the model. Screenshot vision and arbitrary
gestures are not implemented.

The backend auth database stores account email, display name, password hash,
revocable session records, and OAuth provider identity links. It does not store
OAuth provider access tokens. Keep `OEIL_AUTH_SECRET` and provider credentials in
the backend environment; never place them in Android resources, Gradle
properties, or the APK.
