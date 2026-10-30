import os
from typing import Literal

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from auth import router as auth_router

app = FastAPI(title="Oeil API", version="1.0.0")
app.include_router(auth_router)

Mode = Literal["AUTO", "PRECISE", "CREATIVE"]
Role = Literal["user", "assistant"]

SYSTEM_PROMPTS = {
    "AUTO": "You are Oeil, a helpful, warm, and clear AI assistant. Answer using the conversation context. Use plain text and never claim to be another model.",
    "PRECISE": "You are Oeil. Give direct, factual, concise answers. Prefer the shortest answer that fully addresses the request.",
    "CREATIVE": "You are Oeil, an imaginative assistant. Offer fresh angles and vivid examples while staying useful and accurate.",
}
TEMPERATURES = {"AUTO": 0.7, "PRECISE": 0.2, "CREATIVE": 1.0}


class ChatMessage(BaseModel):
    role: Role
    content: str = Field(min_length=1, max_length=16_000)


class ChatRequest(BaseModel):
    messages: list[ChatMessage] = Field(min_length=1, max_length=50)
    mode: Mode = "AUTO"


def provider_payload(request: ChatRequest) -> dict:
    return {
        "model": os.getenv(
            "NEBIUS_MODEL", "nvidia/Llama-3.1-Nemotron-70B-Instruct"
        ),
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPTS[request.mode]},
            *[message.model_dump() for message in request.messages],
        ],
        "temperature": TEMPERATURES[request.mode],
        "max_tokens": 1024,
    }


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/chat")
async def chat(request: ChatRequest) -> dict[str, str]:
    api_key = os.getenv("NEBIUS_API_KEY", "").strip()
    if not api_key:
        raise HTTPException(
            status_code=503,
            detail="Nebius is not configured. Set NEBIUS_API_KEY on the backend.",
        )

    base_url = os.getenv(
        "NEBIUS_API_BASE_URL", "https://api.tokenfactory.nebius.com/v1"
    ).rstrip("/")
    headers = {"Authorization": f"Bearer {api_key}"}

    try:
        async with httpx.AsyncClient(timeout=90.0) as client:
            response = await client.post(
                f"{base_url}/chat/completions",
                headers=headers,
                json=provider_payload(request),
            )
    except httpx.RequestError as error:
        raise HTTPException(
            status_code=502, detail="Could not connect to the Nebius API."
        ) from error

    if not response.is_success:
        if response.status_code in (401, 403):
            detail = "Nebius rejected the API key or model access. Check backend configuration."
        else:
            detail = f"Nebius API request failed (HTTP {response.status_code})."
        raise HTTPException(status_code=502, detail=detail)

    try:
        reply = response.json()["choices"][0]["message"]["content"]
    except (ValueError, KeyError, IndexError, TypeError) as error:
        raise HTTPException(
            status_code=502, detail="Nebius returned an unreadable response."
        ) from error
    if not isinstance(reply, str) or not reply.strip():
        raise HTTPException(status_code=502, detail="Nebius returned an empty reply.")
    return {"reply": reply.strip()}
