# Yara AI v3 - your girlfriend on Android + Windows (free tools)

Texts like a real girlfriend on WhatsApp: short messages, real moods (her voice changes with her mood),
Malayalam / Manglish / English (+ Hindi, Tamil, Arabic), voice, live voice chat, memory, photos, screen understanding,
reminders, jobs, interview practice and coding/analytics help.

## 1. Build the APK (free, no Android Studio)
1. GitHub -> New repository -> upload the **contents of this folder** (`app/`, `build.gradle.kts`, `.github/` must be at the repo root).
2. Actions tab -> **Build Yara APK** -> Run workflow -> download artifact **Yara-APK** -> `app-debug.apk`.
3. **Uninstall any older Yara first** (signature changed), then install the new APK. Future updates install over it.
4. If the build fails, send me the red error text.

## 2. Keys (2 minutes, both free)
Settings tab -> paste **both**:
- **Gemini** key: https://aistudio.google.com/apikey - best Malayalam, sees photos and your screen.
- **Groq** key: https://console.groq.com/api-keys - fast; Yara switches to it automatically when Gemini's free limit is hit.
Model names change: if you see "Model name not found", type a current model name in Settings.

## 3. What each feature needs
| Feature | How to use | Needs |
|---|---|---|
| Chat as girlfriend | just type | any key |
| 🎤 Talk | tap, speak | mic permission; Settings -> language (മലയാളം for Manglish speech) |
| 🎧 Live chat (hands-free) | tap once: she listens -> answers aloud -> listens again | mic; keeps screen on; tap again to stop |
| 📷 / 🖼 photos | tap, then send | **Gemini key** |
| Share to Yara | any app -> Share -> Yara (screenshots, photos, text) | Gemini key for images |
| 👁 Screen | tap 👁, send; you have 6 s to switch to the app; she reads text + real screenshot and answers by voice | Accessibility ON + Gemini key for the screenshot |
| Background | Settings -> Background mode: Yara sits in your notification with **Reply**, **👁 Screen**, **🎤 Talk** buttons, works from any app | notification permission + battery "No restrictions" |
| Phone actions | Settings -> Allow screen actions, then ask ("open YouTube", "scroll down") | Accessibility ON |
| Reminders / jobs / interview practice | ask her / 🎯 mode | - |

### If 👁 Screen does not work
1. Settings -> **Open Accessibility settings** -> Yara AI -> turn on.
2. Android 13+: if it is greyed out -> **Open app info** -> ⋮ (top right) -> **Allow restricted settings**, then repeat step 1.
3. Redmi/Xiaomi/Oppo/Vivo: allow **Autostart** and set Yara's battery to **No restrictions**.

## Memory tips
Memory tab = her instructions and facts about you. Delete old/conflicting ones. Her personality lives in `app/src/main/assets/persona.txt` (same file in `backend/persona.txt`) - you can edit it.

## Windows
Actions -> Build Yara for Windows -> `YaraAI.exe` (or `pip install -r windows/requirements.txt`, `python windows/yara_desktop.py`). Same keys, same personality, asks permission before touching your PC.

## Optional backend (shared memory Android <-> Windows)
`cd backend && pip install -r requirements.txt && uvicorn main:app --host 0.0.0.0 --port 8000`.
Env vars: `GEMINI_API_KEY`, `GROQ_API_KEY`, `LLM_PRIMARY` (gemini|groq), `YARA_ALLOWED_KEYS` (comma-separated long secrets; required). Free Render disk resets on redeploy; for permanent memory run it on your laptop + Cloudflare Tunnel. Use https outside your home network.

## Not included on purpose
- Secret answers during a real interview (use 🎯 practice mode).
- Silent job auto-apply (portals block bots; she finds jobs, you press apply).
- Always-on "Hey Yara" wake word and a live face camera (need extra services; possible later). Use 🎧 Live chat and the notification buttons.

## Privacy
Chats, keys and memories are stored on your phone/PC. Text, photos and screen content you send go to Google (Gemini) / Groq. Don't share secrets with her.
