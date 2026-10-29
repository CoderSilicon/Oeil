# Oeil

Oeil is an Android chat assistant built with Jetpack Compose. The current app
provides local conversation history and a real AI reply path through a small
Python relay. Android accessibility actions and vision are future work described
in [PLAN.md](PLAN.md); the current image picker only attaches a label to chat.

## What works today

- Dark Compose chat UI with conversation history stored locally in SQLite.
- A local device profile; there is no pretend email/password or social login.
- Auto, Precise and Creative response modes.
- A Python FastAPI relay that calls an OpenAI-compatible NVIDIA model through
  Nebius Token Factory.
- Backend URL configuration from the app's drawer under **Settings**. The
  provider key stays on the backend and is never compiled into or sent from the APK.
- Text replies, stop generation, conversation search, and voice dictation.

## Run the Python backend

Requirements: Python 3.10+ and a Nebius Token Factory API key.

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export NEBIUS_API_KEY="your-Nebius-key"
uvicorn main:app --host 0.0.0.0 --port 8000
```

The backend defaults to:

- API base URL: `https://api.tokenfactory.nebius.com/v1`
- Model: `nvidia/Llama-3.1-Nemotron-70B-Instruct`

Override with `NEBIUS_API_BASE_URL` or `NEBIUS_MODEL` if your Nebius account
uses a different endpoint/model. `GET /health` checks the relay; `POST /chat`
is the app endpoint.

**Keep this relay on a trusted network for development.** It has no user
authentication yet; do not expose it directly to the public internet. Use HTTPS
and add an authentication layer before hosting it publicly.

In the Android app, open the drawer → **Settings** and enter:

- Android emulator: `http://10.0.2.2:8000`
- Physical device: a reachable HTTPS backend URL (or a LAN URL in a debug build
  configured for your network)

The app appends `/chat` automatically. You do not need to enter the Nebius API
key in the Android app or provide it to us. A valid `NEBIUS_API_KEY` on the
backend is required for live replies.

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
├── OeilApp.kt                 # Local profile, settings, and dependency wiring
├── data/                      # Relay client, endpoint validation, SQLite store
├── screen/                    # Chat and backend settings screens
├── state/                     # Conversation state and reply orchestration
├── components/                # Chat UI components
└── model/                     # Messages, threads, and local profile
backend/
├── main.py                    # FastAPI relay to Nebius
└── test_main.py               # Backend contract/configuration tests
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the current data flow and
[PLAN.md](PLAN.md) for the planned AAS and vision work.
