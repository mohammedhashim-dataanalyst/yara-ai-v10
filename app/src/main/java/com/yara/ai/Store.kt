package com.yara.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Link(val title: String, val url: String)
data class Msg(val fromYara: Boolean, val text: String, val links: List<Link> = emptyList())

/** All settings, memories and chat history live on the phone (SharedPreferences). */
class Store(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("yara", Context.MODE_PRIVATE)

    private fun s(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) { sp.edit().putString(k, v).apply() }
    private fun b(k: String, d: Boolean): Boolean = sp.getBoolean(k, d)
    private fun putB(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }

    var userName: String
        get() = s("userName")
        set(v) = put("userName", v)
    var provider: String // main brain: "gemini" or "groq"
        get() = s("provider", "gemini")
        set(v) = put("provider", v)
    var geminiKey: String
        get() = s("geminiKey")
        set(v) = put("geminiKey", v)
    var groqKey: String
        get() = s("groqKey")
        set(v) = put("groqKey", v)
    var modelGemini: String
        get() = s("modelGemini", "gemini-flash-latest")
        set(v) = put("modelGemini", v)
    var modelGroq: String
        get() = s("modelGroq", "llama-3.3-70b-versatile")
        set(v) = put("modelGroq", v)
    var baseUrl: String
        get() = s("baseUrl", "https://api.groq.com/openai/v1")
        set(v) = put("baseUrl", v)

    var backendUrl: String
        get() = s("backendUrl")
        set(v) = put("backendUrl", v)
    var syncKey: String
        get() = s("syncKey")
        set(v) = put("syncKey", v)
    var cv: String
        get() = s("cv")
        set(v) = put("cv", v)
    var speechLang: String
        get() = s("speechLang", "ml-IN")
        set(v) = put("speechLang", v)
    var voiceReply: Boolean
        get() = b("voiceReply", true)
        set(v) = putB("voiceReply", v)
    var allowActions: Boolean
        get() = b("allowActions", false)
        set(v) = putB("allowActions", v)
    var background: Boolean
        get() = b("background", false)
        set(v) = putB("background", v)

    fun memories(): MutableList<String> {
        val a = JSONArray(s("memories", "[]"))
        return MutableList(a.length()) { a.getString(it) }
    }

    fun setMemories(list: List<String>) = put("memories", JSONArray(list).toString())

    fun addMemory(fact: String): Boolean {
        val f = fact.trim().take(200)
        val bad = Regex("(password|passcode|otp|cvv|card number|aadhaar|passport|\\d{9,})", RegexOption.IGNORE_CASE)
        if (f.isEmpty() || bad.containsMatchIn(f)) return false
        val list = memories()
        if (list.any { it.equals(f, ignoreCase = true) }) return false
        list.add(f)
        setMemories(list.takeLast(200))
        return true
    }

    fun deleteMemory(fact: String) = setMemories(memories().filter { it != fact })

    fun loadHistory(): List<Msg> {
        val a = JSONArray(s("history", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Msg(o.getBoolean("y"), o.getString("t"))
        }
    }

    fun saveHistory(list: List<Msg>) {
        val a = JSONArray()
        list.takeLast(60).forEach { a.put(JSONObject().put("y", it.fromYara).put("t", it.text)) }
        put("history", a.toString())
    }
}
