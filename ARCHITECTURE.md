# Architecture

Oeil is a single-activity Android app. Conversation history, account session,
appearance preference and backend URL are stored locally in SQLite. The app
supports a local profile as well as a backend account.

## Reply flow

```text
OeilScreen
  └── OeilChatState
        └── ConversationReplySource
              └── BackendReplySource
                    └── POST {backend URL}/chat
                          └── Python FastAPI backend
                                └── Nebius OpenAI-compatible chat completions
```

The Android client sends the last 50 text messages and the selected response
mode. It does not send an account token or provider API key. The backend adds a
mode-specific system prompt and keeps `NEBIUS_API_KEY` on the server. An
unconfigured backend URL or missing provider key produces an explicit error;
the running app never silently falls back to mock replies.

## Main pieces

| Area | Responsibility |
|---|---|
| `OeilApp` | Loads the local profile and persisted backend URL; opens chat or settings |
| `OeilScreen` | Chat layout, drawer, message list and composer |
| `SettingsScreen` | Appearance, account and backend settings |
| `AuthScreen` | Email/password sign-up and sign-in; launches provider OAuth |
| `AuthClient` | Calls account endpoints and validates OAuth callback sessions |
| `OeilChatState` | Message/thread state, reply lifecycle and local persistence |
| `BackendReplySource` | JSON HTTP client for the Python `/chat` endpoint |
| `OeilDatabase` | SQLite local profile, threads, messages and settings |
| `backend/auth.py` | Password hashing, revocable sessions and provider OAuth |
| `backend/main.py` | Validates chat requests and calls Nebius Token Factory |

`MockConversationReplySource` remains available for unit tests; it is not wired
into the installed app.

## Backend API

`POST /chat` accepts a JSON object with `mode` (`AUTO`, `PRECISE`, or
`CREATIVE`) and a non-empty list of `{role, content}` messages. `role` is
`user` or `assistant`. Successful responses are `{ "reply": "..." }`.
`GET /health` checks that the relay is running; it does not verify provider
credentials.

Account endpoints are `POST /auth/signup`, `POST /auth/login`, `GET /auth/me`,
and `POST /auth/logout`. Passwords are PBKDF2-hashed, and sessions are signed
bearer tokens backed by revocable SQLite records. `GET /auth/oauth/{provider}/start`
begins Google, Microsoft or GitHub authorization; the backend handles the
provider callback and returns the session to the app through
`oeil://auth/callback`. Configure `OEIL_AUTH_SECRET`, `OEIL_PUBLIC_BASE_URL`, and
provider client credentials on the server. OAuth requires a public HTTPS backend.

Configuration is read from the backend process environment:

- `NEBIUS_API_KEY` is required and must never be placed in Android resources,
  Gradle properties, or the APK.
- `NEBIUS_API_BASE_URL` defaults to `https://api.tokenfactory.nebius.com/v1`.
- `NEBIUS_MODEL` defaults to `nvidia/Llama-3.1-Nemotron-70B-Instruct`.

`/chat` remains guest-accessible and is not tied to account sessions. Before
public deployment, enable HTTPS and configure request rate limiting; enforce
bearer auth on `/chat` if guest access is not intended.

## Data and privacy

Conversations and the backend URL are stored in the app's local SQLite database.
Only text message history is sent to the relay. The current attachment flow
stores and displays a label; image bytes are not sent to the model. Accessibility
service actions, user approval gates, screenshot vision, and multi-step agent
execution are not implemented yet; see [PLAN.md](PLAN.md).
