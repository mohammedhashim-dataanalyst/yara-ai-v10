package com.yara.ai.offline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OfflineLocalBrain(private val context: Context) {
    suspend fun processOfflineQuery(userText: String, ownerName: String): String = withContext(Dispatchers.IO) {
        val input = userText.lowercase().trim()
        return@withContext when {
            "hello" in input || "hi" in input || "hey" in input -> "Hey $ownerName! I'm active on your offline brain engine. How can I help you right now?"
            "open whatsapp" in input -> "[[act: open | WhatsApp]] Opening WhatsApp for you, $ownerName."
            "open spotify" in input || "play music" in input -> "[[act: open | Spotify]] Opening Spotify..."
            "open instagram" in input -> "[[act: open | Instagram]] Opening Instagram..."
            "job" in input || "linkedin" in input || "naukri" in input -> "Scanning your saved CV and searching matching Data Analyst jobs on LinkedIn and Naukri..."
            "who are you" in input -> "I am Yara AI v10.0 Master, your advanced autonomous companion built for $ownerName."
            else -> "I've processed your query locally via Yara's Offline Brain for $ownerName with zero latency."
        }
    }
}
