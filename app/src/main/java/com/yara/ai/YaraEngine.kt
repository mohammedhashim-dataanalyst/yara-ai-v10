package com.yara.ai

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** App-wide brain: chat state, LLM calls, tool handling. Survives the activity going to the background. */
object YaraEngine {
    private var inited = false
    lateinit var appCtx: Context
    lateinit var store: Store
    lateinit var voice: Voice
    var activity: MainActivity? = null
    var whenSpoken: (() -> Unit)? = null
    var visible = false

    val messages = mutableStateListOf<Msg>()
    var busy by mutableStateOf(false)
    var mode by mutableStateOf("companion")
    var useScreen by mutableStateOf(false)
    var conversation by mutableStateOf(false)
    var mood by mutableStateOf("loving")
    var draft by mutableStateOf("")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastUser = ""
    private val risky = Regex("(pay|transfer|purchase|buy now|place order|send money|delete|remove account|confirm payment)", RegexOption.IGNORE_CASE)

    fun moodEmoji(m: String) = when (m) {
        "excited" -> "🤩"
        "happy" -> "😊"
        "playful" -> "😜"
        "sulky" -> "😤"
        "worried" -> "🥺"
        "sleepy" -> "😴"
        "calm" -> "🙂"
        else -> "🥰"
    }

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        appCtx = ctx.applicationContext
        store = Store(appCtx)
        voice = Voice(appCtx)
        try {
            Llm.persona = appCtx.assets.open("persona.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) { }
        voice.whenDone = { scope.launch { whenSpoken?.invoke() } }
        messages.addAll(store.loadHistory())
        if (messages.isEmpty()) messages.add(Msg(true, "Hiii chakkare 🥰 ഞാൻ ഇവിടെയുണ്ട്. ennod enthelum para…"))
    }

    private fun toast(t: String) { Toast.makeText(appCtx, t, Toast.LENGTH_SHORT).show() }

    fun clearChat() {
        messages.clear()
        messages.add(Msg(true, "Fresh start 🥰 njan ivide thanne und."))
        store.saveHistory(messages.toList())
    }

    private fun voiceOn() = store.voiceReply || conversation

    fun send(text: String, image: Bitmap?) {
        val screen = useScreen
        useScreen = false
        lastUser = text
        messages.add(Msg(false, if (image != null) "📷 $text" else text))
        busy = true
        scope.launch {
            try {
                var screenText: String? = null
                var img = image
                if (screen) {
                    val g = grabScreen()
                    screenText = g.first
                    if (img == null) img = g.second
                }
                val hist = messages.dropLast(1).takeLast(14)
                val reply = withContext(Dispatchers.IO) { Llm.chat(store, hist, text, img, screenText, mode, voiceOn()) }
                handle(reply, speakAnyway = screen)
            } catch (e: Exception) {
                messages.add(Msg(true, "⚠️ " + Llm.friendly(e)))
                if (!visible && store.background) Notifier.post(appCtx, "⚠️ " + Llm.friendly(e))
            } finally {
                busy = false
                store.saveHistory(messages.toList())
            }
        }
    }

    private fun shrink(b: Bitmap): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= 1280) return b
        val f = 1280f / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    /** Goes to the background, waits so you can switch apps, then reads the screen text and takes a real screenshot. */
    private suspend fun grabScreen(): Pair<String?, Bitmap?> {
        val svc = YaraAccessibilityService.instance
        if (svc == null) {
            messages.add(Msg(true, "ℹ️ Screen needs the \"Yara AI\" accessibility service. Settings tab → Open Accessibility settings → Yara AI → On. (Android 13+: if it's greyed out, tap \"Open app info\" → ⋮ → Allow restricted settings.)"))
            return Pair(null, null)
        }
        toast("Switch to the app you want me to look at - 6 seconds…")
        if (visible) activity?.moveTaskToBack(true)
        delay(6000)
        val text = svc.readScreen().ifBlank { null }
        var bmp: Bitmap? = null
        if (store.geminiKey.isNotBlank() || store.backendUrl.isNotBlank()) {
            bmp = withTimeoutOrNull(5000) {
                suspendCancellableCoroutine<Bitmap?> { c -> svc.screenshot { if (c.isActive) c.resume(it) } }
            }
            bmp = bmp?.let { shrink(it) }
        }
        return Pair(text, bmp)
    }

    private suspend fun handle(r: Reply, speakAnyway: Boolean) {
        r.memories?.let { store.setMemories(it) }
        val links = mutableListOf<Link>()
        val acts = mutableListOf<Tag>()
        val notes = mutableListOf<String>()
        var newMood = "calm"
        var say: String? = null
        for (t in r.tags) {
            when (t.name) {
                "mood" -> newMood = t.args.firstOrNull()?.lowercase() ?: newMood
                "say" -> say = t.args.firstOrNull()
                "remember" -> if (r.memories == null) {
                    val f = t.args.firstOrNull()
                    if (f != null) store.addMemory(f)
                }
                "remind" -> {
                    val mins = t.args.getOrNull(0)?.toIntOrNull()
                    if (mins != null && mins > 0) notes.add(Tools.remind(appCtx, mins, t.args.getOrNull(1) ?: "Reminder"))
                }
                "jobs" -> {
                    val found = withContext(Dispatchers.IO) { Tools.searchJobs(t.args.firstOrNull().orEmpty()) }
                    links.addAll(found)
                    notes.add(if (found.isEmpty()) "🔎 No jobs found for that search." else "🔎 Jobs found - tap a link to open and apply:")
                }
                "act" -> acts.add(t)
            }
        }
        mood = newMood
        val body = r.text.ifBlank { if (acts.isNotEmpty()) "Okay 🥰" else "…" }
        val parts = body.split("||").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("…") }

        val shouldSpeak = store.voiceReply || speakAnyway || conversation
        val spoke = shouldSpeak && voice.speak(say ?: parts.joinToString(". "), newMood)

        for ((i, p) in parts.withIndex()) {
            if (i > 0) delay((p.length * 35L).coerceIn(700L, 2000L))
            val last = i == parts.lastIndex
            val text = if (last && notes.isNotEmpty()) p + notes.joinToString("") { "\n$it" } else p
            messages.add(Msg(true, text, if (last) links else emptyList()))
        }
        if (conversation && !spoke) scope.launch { delay(600); whenSpoken?.invoke() }
        if (!visible && store.background) Notifier.post(appCtx, parts.joinToString("\n"))

        if (acts.isNotEmpty()) {
            if (store.allowActions) runActions(acts)
            else messages.add(Msg(true, "ℹ️ I can do that on your screen, but \"Allow screen actions\" is off in Settings."))
        }
    }

    private suspend fun runActions(acts: List<Tag>) {
        var backgrounded = false
        for (a in acts.take(5)) {
            val kind = a.args.getOrNull(0)?.lowercase() ?: continue
            val arg = a.args.getOrNull(1).orEmpty()
            if (kind == "open") {
                toast("Yara: opening $arg")
                val ok = Tools.openApp(activity ?: appCtx, arg)
                if (!ok) messages.add(Msg(true, "ℹ️ I couldn't open \"$arg\"."))
                backgrounded = true
                delay(2000)
                continue
            }
            val svc = YaraAccessibilityService.instance
            if (svc == null) {
                messages.add(Msg(true, "ℹ️ Turn on \"Yara AI\" in Accessibility settings so I can act on your screen."))
                return
            }
            if (!backgrounded) {
                if (visible) activity?.moveTaskToBack(true)
                backgrounded = true
                delay(1500)
            }
            if (kind == "click" && risky.containsMatchIn(arg) && !risky.containsMatchIn(lastUser)) {
                toast("Yara skipped a risky tap: $arg")
                continue
            }
            val ok = when (kind) {
                "click" -> { toast("Yara: tapping \"$arg\""); svc.click(arg) }
                "type" -> { toast("Yara: typing"); svc.typeText(arg) }
                "scroll" -> svc.scroll(arg.lowercase() != "up")
                "back", "home", "recents" -> svc.global(kind)
                else -> false
            }
            if (!ok) toast("Yara couldn't do: $kind $arg")
            delay(1200)
        }
    }

    fun deleteMemory(fact: String) {
        store.deleteMemory(fact)
        if (store.backendUrl.isNotBlank()) scope.launch { withContext(Dispatchers.IO) { try { Llm.backendDeleteMemory(store, fact) } catch (e: Exception) { } } }
    }

    fun refreshMemories(onDone: (List<String>) -> Unit) {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { Llm.backendMemories(store) }
                store.setMemories(list)
                onDone(list)
            } catch (e: Exception) {
                toast(Llm.friendly(e))
            }
        }
    }
}
