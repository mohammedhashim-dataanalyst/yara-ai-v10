"""Shared brain for Yara: prompt, LLM calls (Gemini / OpenAI-compatible), tag parsing,
memory file and free job search. Used by the FastAPI backend AND the Windows app."""
import hashlib
import json
import re
import sys
import threading
import time
from pathlib import Path

import requests

TAG_RE = re.compile(r"\[\[\s*(\w+)\s*(?::\s*(.*?))?\s*\]\]", re.S)
SENSITIVE_RE = re.compile(r"(password|passcode|otp|pin\b|cvv|card number|aadhaar|passport|\b\d{9,}\b)", re.I)

def _persona_path():
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).parent))
    return base / "persona.txt"


PERSONA = _persona_path().read_text(encoding="utf-8")

MODES = {
    "companion": "MODE: companion chat. Stay fully in girlfriend texting style.",
    "work": "MODE: work. Be precise and structured: SQL, Python/pandas, Excel, Power BI/DAX, Tableau, statistics, ML/GenAI, coding (write, explain, debug, test). Put code in fenced blocks. Keep Yara's warm tone but brief.",
    "interview": "MODE: interview practice. Act as a professional interviewer for the role the user names (ask once if unknown). Ask ONE question at a time, wait for the answer, then give brief feedback (what was good, what to improve) and a stronger sample answer, then ask the next question. This is for practice.",
}


def build_system_prompt(mode, user_name="", memories=None, cv="", voice_on=False):
    parts = [PERSONA, MODES.get(mode, MODES["companion"])]
    parts.append("Now: " + time.strftime("%A, %d %b %Y %H:%M"))
    if user_name:
        parts.append("His name (use rarely): " + user_name)
    if memories:
        parts.append("THINGS YOU KNOW ABOUT HIM AND HIS INSTRUCTIONS TO YOU (follow them):\n- " + "\n- ".join(memories[-60:]))
    if cv:
        parts.append("HIS CV (use for job help and interview practice):\n" + cv[:3000])
    if voice_on:
        parts.append("VOICE IS ON: your reply will be spoken aloud. If you wrote Malayalam in English letters, add the [[say: ...]] tag with the same message in Malayalam script.")
    return "\n\n".join(parts)


def parse_tags(raw):
    tags = []
    for m in TAG_RE.finditer(raw or ""):
        args = [a.strip() for a in (m.group(2) or "").split("|") if a.strip()]
        tags.append({"name": m.group(1).lower(), "args": args})
    return TAG_RE.sub("", raw or "").strip(), tags


class LlmError(Exception):
    pass


def _err_text(resp):
    try:
        return resp.json().get("error", {}).get("message") or resp.text[:200]
    except Exception:
        return resp.text[:200]


def friendly(code, msg):
    if code == 429:
        return "Free limit reached for now. Wait a bit, or switch model/provider in settings."
    if code in (401, 403):
        return "The API key was rejected. Check the key in settings."
    if code == 404:
        return "Model name not found. Change the model in settings."
    return f"AI error {code}: {msg}"


def _gemini(cfg, system, history, user_text, image_b64):
    model = cfg.get("model_gemini") or "gemini-flash-latest"
    hist = list(history)
    while hist and hist[0]["role"] == "yara":
        hist.pop(0)
    contents = [{"role": "model" if h["role"] == "yara" else "user", "parts": [{"text": h["text"]}]} for h in hist]
    parts = [{"text": user_text}]
    if image_b64:
        parts.append({"inlineData": {"mimeType": "image/jpeg", "data": image_b64}})
    contents.append({"role": "user", "parts": parts})
    body = {"systemInstruction": {"parts": [{"text": system}]}, "contents": contents,
            "generationConfig": {"temperature": 1.0}}
    r = requests.post(f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                      headers={"x-goog-api-key": cfg["gemini_key"]}, json=body, timeout=120)
    if r.status_code != 200:
        raise LlmError(friendly(r.status_code, _err_text(r)))
    cands = r.json().get("candidates") or []
    if not cands:
        raise LlmError("No reply (blocked or empty).")
    ps = (cands[0].get("content") or {}).get("parts") or []
    return "".join(p.get("text", "") for p in ps).strip()


def _groq(cfg, system, history, user_text):
    base = (cfg.get("base_url") or "https://api.groq.com/openai/v1").rstrip("/")
    model = cfg.get("model_groq") or "llama-3.3-70b-versatile"
    msgs = [{"role": "system", "content": system}]
    msgs += [{"role": "assistant" if h["role"] == "yara" else "user", "content": h["text"]} for h in history]
    msgs.append({"role": "user", "content": user_text})
    r = requests.post(base + "/chat/completions", headers={"Authorization": "Bearer " + cfg["groq_key"]},
                      json={"model": model, "messages": msgs, "temperature": 1.0}, timeout=120)
    if r.status_code != 200:
        raise LlmError(friendly(r.status_code, _err_text(r)))
    return (r.json()["choices"][0]["message"]["content"] or "").strip()


def call_llm(cfg, system, history, user_text, image_b64=None, screen_text=None):
    """cfg: provider ('gemini'|'groq' = main brain), gemini_key, groq_key, model_gemini, model_groq, base_url.
    Tries the main brain first and falls back to the other one. Images need Gemini."""
    if screen_text:
        user_text = f"{user_text}\n\n[SCREEN TEXT - what is currently on my screen]\n{screen_text}"
    order = ["gemini", "groq"] if cfg.get("provider", "gemini") == "gemini" else ["groq", "gemini"]
    usable = [p for p in order if cfg.get(p + "_key")]
    if not usable:
        raise LlmError("No API key set (add a free Gemini key and/or Groq key).")
    if image_b64:
        usable = [p for p in usable if p == "gemini"]
        if not usable:
            raise LlmError("Photos and screenshots need a free Gemini key (Groq can't see images).")
    last = None
    for p in usable:
        try:
            return _gemini(cfg, system, history, user_text, image_b64) if p == "gemini" else _groq(cfg, system, history, user_text)
        except LlmError as e:
            last = e
    raise last


class Memory:
    """Tiny JSON-file memory (list of short facts)."""

    def __init__(self, path):
        self.path = Path(path)
        self.lock = threading.Lock()

    def all(self):
        with self.lock:
            try:
                return json.loads(self.path.read_text(encoding="utf-8"))
            except Exception:
                return []

    def _save(self, items):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(items, ensure_ascii=False, indent=1), encoding="utf-8")

    def add(self, fact):
        fact = (fact or "").strip()[:200]
        if not fact or SENSITIVE_RE.search(fact):
            return False
        with self.lock:
            try:
                items = json.loads(self.path.read_text(encoding="utf-8"))
            except Exception:
                items = []
            if fact.lower() in (i.lower() for i in items):
                return False
            items.append(fact)
            self._save(items[-200:])
        return True

    def delete(self, fact):
        with self.lock:
            try:
                items = json.loads(self.path.read_text(encoding="utf-8"))
            except Exception:
                items = []
            self._save([i for i in items if i != fact])


def run_chat(cfg, mem, mode, user_name, history, message, image_b64=None, screen_text=None, cv="", voice_on=False):
    system = build_system_prompt(mode, user_name, mem.all(), cv, voice_on)
    raw = call_llm(cfg, system, history, message, image_b64, screen_text)
    clean, tags = parse_tags(raw)
    out = []
    for t in tags:
        if t["name"] == "remember":
            if t["args"]:
                mem.add(t["args"][0])
        elif t["name"] == "jobs":
            out.append({"name": "jobs", "args": t["args"], "results": search_jobs(" ".join(t["args"]))})
        else:
            out.append(t)
    return {"reply": clean or "...", "tags": out, "memories": mem.all()}


def search_jobs(query, limit=8):
    """Free job boards (Remotive + Arbeitnow). Returns [{title, company, location, url, source}]."""
    out = []
    try:
        r = requests.get("https://remotive.com/api/remote-jobs", params={"search": query, "limit": limit}, timeout=20)
        for j in r.json().get("jobs", [])[:limit]:
            out.append({"title": j.get("title", ""), "company": j.get("company_name", ""),
                        "location": j.get("candidate_required_location", ""), "url": j.get("url", ""), "source": "Remotive"})
    except Exception:
        pass
    try:
        words = [w for w in re.findall(r"\w+", query.lower()) if len(w) > 2]
        r = requests.get("https://www.arbeitnow.com/api/job-board-api", timeout=20)
        n = 0
        for j in r.json().get("data", []):
            title = j.get("title", "")
            if words and any(w in title.lower() for w in words):
                out.append({"title": title, "company": j.get("company_name", ""), "location": j.get("location", ""),
                            "url": j.get("url", ""), "source": "Arbeitnow"})
                n += 1
                if n >= 5:
                    break
    except Exception:
        pass
    return out


def user_id(sync_key):
    return hashlib.sha256(sync_key.encode()).hexdigest()[:16]
