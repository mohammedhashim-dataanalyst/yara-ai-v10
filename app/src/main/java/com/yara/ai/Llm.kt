package com.yara.ai

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Tag(val name: String, val args: List<String>)
data class Reply(val text: String, val tags: List<Tag>, val memories: List<String>? = null)
class ApiError(val code: Int, message: String) : Exception(message)

object Llm {
    /** Loaded from assets/persona.txt by YaraEngine.init */
    var persona: String = "You are Yara, the user's loving girlfriend. Text like WhatsApp."

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val TAG_RE = Regex("""\[\[\s*(\w+)\s*(?::\s*(.*?))?\s*\]\]""", RegexOption.DOT_MATCHES_ALL)

    private fun modeText(mode: String) = when (mode) {
        "work" -> "MODE: work. Be precise and structured: SQL, Python/pandas, Excel, Power BI/DAX, Tableau, statistics, ML/GenAI, coding (write, explain, debug, test). Put code in fenced blocks. Longer answers are fine here, but keep Yara's warm voice."
        "interview" -> "MODE: interview practice. Act as a professional interviewer for the role he names (ask once if unknown). Ask ONE question at a time, wait for his answer, then give brief feedback (what was good, what to improve) and a stronger sample answer, then ask the next question. This is for practice. Stay encouraging."
        else -> "MODE: companion chat. Stay fully in girlfriend texting style."
    }

    fun systemPrompt(store: Store, mode: String, voiceOn: Boolean): String {
        val sb = StringBuilder(persona).append("\n\n").append(modeText(mode))
        sb.append("\n\nNow: ").append(SimpleDateFormat("EEEE, d MMM yyyy HH:mm", Locale.ENGLISH).format(Date()))
        if (store.userName.isNotBlank()) sb.append("\nHis name (use rarely): ").append(store.userName)
        val mem = store.memories()
        if (mem.isNotEmpty()) sb.append("\n\nTHINGS YOU KNOW ABOUT HIM AND HIS INSTRUCTIONS TO YOU (follow them):\n- ").append(mem.takeLast(60).joinToString("\n- "))
        if (store.cv.isNotBlank()) sb.append("\n\nHIS CV (use for job help and interview practice):\n").append(store.cv.take(3000))
        if (voiceOn) sb.append("\n\nVOICE IS ON: your reply will be spoken aloud. If you wrote Malayalam in English letters, add the [[say: ...]] tag with the same message in Malayalam script.")
        return sb.toString()
    }

    fun parse(raw: String, memories: List<String>? = null): Reply {
        val tags = TAG_RE.findAll(raw).map { m ->
            Tag(m.groupValues[1].lowercase(), m.groupValues[2].split("|").map { it.trim() }.filter { it.isNotEmpty() })
        }.toList()
        return Reply(TAG_RE.replace(raw, "").trim(), tags, memories)
    }

    private fun keyFor(store: Store, p: String) = if (p == "gemini") store.geminiKey else store.groqKey

    /** Blocking - call from a background thread. Tries the main brain first, then the other one if it fails. */
    fun chat(store: Store, history: List<Msg>, userText: String, image: Bitmap?, screenText: String?, mode: String, voiceOn: Boolean): Reply {
        if (store.backendUrl.isNotBlank()) return viaBackend(store, history, userText, image, screenText, mode, voiceOn)
        val full = if (screenText != null) "$userText\n\n[SCREEN TEXT - what is currently on my screen]\n$screenText" else userText
        val order = if (store.provider == "gemini") listOf("gemini", "groq") else listOf("groq", "gemini")
        var usable = order.filter { keyFor(store, it).isNotBlank() }
        if (usable.isEmpty()) throw ApiError(0, "No API key yet. Open Settings and paste a free Gemini key (best) or Groq key.")
        if (image != null) {
            usable = usable.filter { it == "gemini" }
            if (usable.isEmpty()) throw ApiError(0, "Photos and screenshots need a free Gemini key (Groq can't see images). Add it in Settings.")
        }
        val sys = systemPrompt(store, mode, voiceOn)
        var last: ApiError? = null
        for (p in usable) {
            try {
                val raw = if (p == "gemini") gemini(store, sys, history, full, image) else groq(store, sys, history, full)
                return parse(raw)
            } catch (e: ApiError) {
                last = e
            }
        }
        throw last ?: ApiError(0, "No reply.")
    }

    private fun b64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun errMsg(body: String): String = try {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty().ifBlank { body.take(200) }
    } catch (e: Exception) { body.take(200) }

    private fun gemini(store: Store, sys: String, history: List<Msg>, full: String, image: Bitmap?): String {
        val contents = JSONArray()
        for (m in history.dropWhile { it.fromYara }) {
            contents.put(JSONObject().put("role", if (m.fromYara) "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", m.text))))
        }
        val parts = JSONArray().put(JSONObject().put("text", full))
        if (image != null) parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64(image))))
        contents.put(JSONObject().put("role", "user").put("parts", parts))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 1.0))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${store.modelGemini}:generateContent")
            .header("x-goog-api-key", store.geminiKey)
            .post(body.toString().toRequestBody(JSON)).build()
        return http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError(r.code, errMsg(s))
            val cand = JSONObject(s).optJSONArray("candidates")?.optJSONObject(0) ?: throw ApiError(0, "No reply (blocked or empty).")
            val ps = cand.optJSONObject("content")?.optJSONArray("parts") ?: return@use ""
            (0 until ps.length()).joinToString("") { ps.getJSONObject(it).optString("text") }.trim()
        }
    }

    private fun groq(store: Store, sys: String, history: List<Msg>, full: String): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        for (m in history) msgs.put(JSONObject().put("role", if (m.fromYara) "assistant" else "user").put("content", m.text))
        msgs.put(JSONObject().put("role", "user").put("content", full))
        val body = JSONObject().put("model", store.modelGroq).put("messages", msgs).put("temperature", 1.0)
        val req = Request.Builder()
            .url(store.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer " + store.groqKey)
            .post(body.toString().toRequestBody(JSON)).build()
        return http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError(r.code, errMsg(s))
            JSONObject(s).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
        }
    }

    private fun viaBackend(store: Store, history: List<Msg>, userText: String, image: Bitmap?, screenText: String?, mode: String, voiceOn: Boolean): Reply {
        val hist = JSONArray()
        for (m in history) hist.put(JSONObject().put("role", if (m.fromYara) "yara" else "user").put("text", m.text))
        val body = JSONObject().put("message", userText).put("mode", mode).put("user_name", store.userName)
            .put("cv", store.cv).put("history", hist).put("voice_on", voiceOn)
        if (screenText != null) body.put("screen_text", screenText)
        if (image != null) body.put("image_b64", b64(image))
        val req = Request.Builder().url(store.backendUrl.trimEnd('/') + "/chat")
            .header("X-Sync-Key", store.syncKey)
            .post(body.toString().toRequestBody(JSON)).build()
        return http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError(r.code, try { JSONObject(s).optString("detail", s.take(200)) } catch (e: Exception) { s.take(200) })
            val j = JSONObject(s)
            val tags = mutableListOf<Tag>()
            val ta = j.optJSONArray("tags") ?: JSONArray()
            for (i in 0 until ta.length()) {
                val t = ta.getJSONObject(i)
                val a = t.optJSONArray("args") ?: JSONArray()
                tags.add(Tag(t.getString("name"), (0 until a.length()).map { a.getString(it) }))
            }
            val ma = j.optJSONArray("memories")
            val mems = if (ma != null) (0 until ma.length()).map { ma.getString(it) } else null
            Reply(j.optString("reply"), tags, mems)
        }
    }

    fun backendDeleteMemory(store: Store, fact: String) {
        if (store.backendUrl.isBlank()) return
        val req = Request.Builder().url(store.backendUrl.trimEnd('/') + "/memories/delete")
            .header("X-Sync-Key", store.syncKey)
            .post(JSONObject().put("text", fact).toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { }
    }

    fun backendMemories(store: Store): List<String> {
        val req = Request.Builder().url(store.backendUrl.trimEnd('/') + "/memories").header("X-Sync-Key", store.syncKey).build()
        return http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiError(r.code, "Backend said ${r.code}")
            val a = JSONObject(r.body?.string().orEmpty()).getJSONArray("memories")
            (0 until a.length()).map { a.getString(it) }
        }
    }

    fun friendly(e: Exception): String = when {
        e is ApiError && e.code == 429 -> "Free limit reached for now. Wait a bit, or add the other key (Gemini/Groq) in Settings."
        e is ApiError && (e.code == 401 || e.code == 403) -> "A key was rejected. Check your keys in Settings."
        e is ApiError && e.code == 404 -> "Model name not found. Change the model in Settings."
        e is ApiError -> e.message ?: "AI error ${e.code}"
        e is UnknownHostException -> "No internet connection."
        else -> e.message ?: e.javaClass.simpleName
    }
}
