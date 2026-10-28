package com.yara.ai

import android.content.Context
import android.content.SharedPreferences

class MasterStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("yara_v10_prefs", Context.MODE_PRIVATE)

    var ownerName: String
        get() = prefs.getString("owner_name", "HASHIM") ?: "HASHIM"
        set(value) = prefs.edit().putString("owner_name", value).apply()

    var ownerInstagram: String
        get() = prefs.getString("owner_instagram", "@hashim_m4x") ?: "@hashim_m4x"
        set(value) = prefs.edit().putString("owner_instagram", value).apply()

    var apiKey1: String
        get() = prefs.getString("api_key_1", "") ?: ""
        set(value) = prefs.edit().putString("api_key_1", value).apply()

    var apiKey2: String
        get() = prefs.getString("api_key_2", "") ?: ""
        set(value) = prefs.edit().putString("api_key_2", value).apply()

    var apiKey3: String
        get() = prefs.getString("api_key_3", "") ?: ""
        set(value) = prefs.edit().putString("api_key_3", value).apply()

    var apiKey4: String
        get() = prefs.getString("api_key_4", "") ?: ""
        set(value) = prefs.edit().putString("api_key_4", value).apply()

    var offlineBrainActive: Boolean
        get() = prefs.getBoolean("offline_brain_active", true)
        set(value) = prefs.edit().putBoolean("offline_brain_active", value).apply()

    var autoCallSmsActive: Boolean
        get() = prefs.getBoolean("auto_call_sms_active", true)
        set(value) = prefs.edit().putBoolean("auto_call_sms_active", value).apply()

    var liveScreenShareVisionActive: Boolean
        get() = prefs.getBoolean("live_screen_vision_active", true)
        set(value) = prefs.edit().putBoolean("live_screen_vision_active", value).apply()

    var cloudSyncActive: Boolean
        get() = prefs.getBoolean("cloud_sync_active", true)
        set(value) = prefs.edit().putBoolean("cloud_sync_active", value).apply()

    var githubKaggleIntegrationActive: Boolean
        get() = prefs.getBoolean("github_kaggle_active", true)
        set(value) = prefs.edit().putBoolean("github_kaggle_active", value).apply()
}
