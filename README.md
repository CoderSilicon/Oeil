# Oeil

Oeil is an Android chat assistant built with Jetpack Compose. The current app
provides local conversation history and a real AI reply path through a small
Python relay. Android accessibility actions and vision are future work described
in [PLAN.md](PLAN.md); the current image picker only attaches a label to chat.

## What works today

- Compose chat UI with conversation history stored locally in SQLite.
- Account settings with email/password sign-up and sign-in, plus Google,
  Microsoft and GitHub OAuth sign-in.
- Light, dark and system appearance options saved on the device.
- Auto, Precise and Creative response modes.
- A Python FastAPI relay that calls an OpenAI-compatible NVIDIA model through
  Nebius Token Factory.
- Backend URL configuration from the app's drawer under **Settings**. The
  provider key stays on the backend and is never compiled into or sent from the APK.
- Text replies, stop generation, conversation search, and voice dictation.

## Run the Python backend

Requirements: Python 3.10+. A Nebius Token Factory API key is required for chat
replies; account endpoints work independently.

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export NEBIUS_API_KEY="your-Nebius-key"
export OEIL_AUTH_SECRET="$(openssl rand -hex 32)"
uvicorn main:app --host 0.0.0.0 --port 8000
```

The auth database is stored in `backend/oeil-auth.sqlite3` by default. Set
`OEIL_AUTH_DB_PATH` to choose another persistent location. `OEIL_AUTH_SECRET`
must be a stable random secret of at least 32 characters; changing it invalidates
existing sessions.

To enable provider sign-in, serve the backend over HTTPS, set
`OEIL_PUBLIC_BASE_URL` to its public origin, and configure each provider's client
ID and secret on the backend only:

| Provider | Environment variables | Provider callback URL |
|---|---|---|
| Google | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | `{OEIL_PUBLIC_BASE_URL}/auth/oauth/google/callback` |
| Microsoft | `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET` | `{OEIL_PUBLIC_BASE_URL}/auth/oauth/microsoft/callback` |
| GitHub | `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | `{OEIL_PUBLIC_BASE_URL}/auth/oauth/github/callback` |

The Android app receives OAuth sessions through the `oeil://auth/callback`
application link. Do not put provider secrets in the Android app or commit them.

The backend defaults to:

- API base URL: `https://api.tokenfactory.nebius.com/v1`
- Model: `nvidia/Llama-3.1-Nemotron-70B-Instruct`

Override with `NEBIUS_API_BASE_URL` or `NEBIUS_MODEL` if your Nebius account
uses a different endpoint/model. `GET /health` checks the relay; `POST /chat`
is the app endpoint.

**Use HTTPS and configure `OEIL_AUTH_SECRET` before deploying publicly.** The
email/password and OAuth account endpoints issue revocable bearer sessions.
`/chat` remains available to local/guest profiles and does not currently require
an account token; add rate limiting and enforce authentication before exposing a
production service.

In the Android app, open the drawer → **Settings** and enter:

- Android emulator: `http://10.0.2.2:8000`
- Physical device: a reachable HTTPS backend URL (or a LAN URL in a debug build
  configured for your network)

The app appends `/chat` automatically. The same backend URL is used for account
sign-in. You do not need to enter the Nebius API key in the Android app or
provide it to us. A valid `NEBIUS_API_KEY` on the backend is required for live
replies.

## Android build

Requirements: Android Studio with JDK 11+ and Android SDK 37.

```bash
./gradlew assembleDebug
./gradlew test
```

For emulator local HTTP, the debug network security config permits
`10.0.2.2`. Release builds require HTTPS.

## Backend tests

```bash
cd backend
pip install -r requirements.txt
python3 -m unittest
```

## Project layout

```text
app/src/main/java/com/example/oeil/
├── OeilApp.kt                 # Local profile, settings, account and dependency wiring
├── data/                      # Relay/auth clients, endpoint validation, SQLite store
├── screen/                    # Chat, appearance, account and settings screens
├── state/                     # Conversation state and reply orchestration
├── components/                # Chat UI components
└── model/                     # Messages, threads, and local profile
backend/
├── main.py                    # FastAPI relay to Nebius and auth router
├── auth.py                    # Password, bearer session and OAuth endpoints
└── test_main.py               # Backend contract and auth tests
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the current data flow and
[PLAN.md](PLAN.md) for the planned AAS and vision work.
