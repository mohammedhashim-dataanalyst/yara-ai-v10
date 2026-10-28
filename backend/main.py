"""Yara Brain - FastAPI backend. Keeps the LLM key off the phone and shares memory
between Android and Windows. Configure with environment variables (see README)."""
import hashlib
import os
from pathlib import Path
from typing import List, Optional

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

import yara_core as core

app = FastAPI(title="Yara Brain", version="3.0")
DATA = Path(os.getenv("YARA_DATA_DIR", "data"))


def cfg():
    return {"provider": os.getenv("LLM_PRIMARY", "gemini"),
            "gemini_key": os.getenv("GEMINI_API_KEY", ""), "groq_key": os.getenv("GROQ_API_KEY", ""),
            "model_gemini": os.getenv("GEMINI_MODEL", ""), "model_groq": os.getenv("GROQ_MODEL", ""),
            "base_url": os.getenv("GROQ_BASE_URL", "")}


def auth(sync_key: Optional[str]) -> core.Memory:
    allowed = [k.strip() for k in os.getenv("YARA_ALLOWED_KEYS", "").split(",") if k.strip()]
    if not allowed:
        raise HTTPException(503, "Server not configured: set YARA_ALLOWED_KEYS.")
    if not sync_key or len(sync_key) < 12 or sync_key not in allowed:
        raise HTTPException(401, "Bad sync key.")
    return core.Memory(DATA / f"{core.user_id(sync_key)}.json")


class Turn(BaseModel):
    role: str  # "user" | "yara"
    text: str


class ChatIn(BaseModel):
    message: str
    mode: str = "companion"
    user_name: str = ""
    cv: str = ""
    screen_text: Optional[str] = None
    image_b64: Optional[str] = None
    voice_on: bool = False
    history: List[Turn] = []


class FactIn(BaseModel):
    text: str


@app.get("/health")
def health():
    return {"ok": True, "service": "yara-brain", "gemini_key_set": bool(os.getenv("GEMINI_API_KEY")), "groq_key_set": bool(os.getenv("GROQ_API_KEY"))}


@app.post("/chat")
def chat(body: ChatIn, x_sync_key: Optional[str] = Header(None)):
    mem = auth(x_sync_key)
    try:
        return core.run_chat(cfg(), mem, body.mode, body.user_name, [t.dict() for t in body.history[-16:]],
                             body.message, body.image_b64, body.screen_text, body.cv, body.voice_on)
    except core.LlmError as e:
        raise HTTPException(502, str(e))


@app.get("/memories")
def memories(x_sync_key: Optional[str] = Header(None)):
    return {"memories": auth(x_sync_key).all()}


@app.post("/memories/add")
def add_memory(body: FactIn, x_sync_key: Optional[str] = Header(None)):
    mem = auth(x_sync_key)
    mem.add(body.text)
    return {"memories": mem.all()}


@app.post("/memories/delete")
def delete_memory(body: FactIn, x_sync_key: Optional[str] = Header(None)):
    mem = auth(x_sync_key)
    mem.delete(body.text)
    return {"memories": mem.all()}


@app.get("/jobs")
def jobs(q: str, x_sync_key: Optional[str] = Header(None)):
    auth(x_sync_key)
    return {"jobs": core.search_jobs(q)}
