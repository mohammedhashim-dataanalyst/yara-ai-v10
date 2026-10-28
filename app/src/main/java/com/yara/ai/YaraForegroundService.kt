package com.yara.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder

/** The always-there notification: chat with Yara (inline reply), "Screen" and "Talk" buttons from any app. */
object Notifier {
    private const val ID = 1001

    fun ensureChannels(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("yara", "Yara background", NotificationManager.IMPORTANCE_LOW))
    }

    fun build(ctx: Context, text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openI = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(ctx, 0, openI, flags)
        val listenI = Intent(ctx, MainActivity::class.java).putExtra("listen", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val listen = PendingIntent.getActivity(ctx, 1, listenI, flags)
        val screen = PendingIntent.getBroadcast(ctx, 2, Intent(ctx, YaraActionReceiver::class.java).setAction("com.yara.ai.SCREEN"), flags)
        val reply = PendingIntent.getBroadcast(ctx, 3, Intent(ctx, YaraActionReceiver::class.java).setAction("com.yara.ai.REPLY"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val ri = RemoteInput.Builder("reply").setLabel("Message Yara").build()
        val icon = Icon.createWithResource(ctx, android.R.drawable.ic_menu_send)
        return Notification.Builder(ctx, "yara")
            .setContentTitle("Yara ❤️")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(icon, "Reply", reply).addRemoteInput(ri).build())
            .addAction(Notification.Action.Builder(icon, "👁 Screen", screen).build())
            .addAction(Notification.Action.Builder(icon, "🎤 Talk", listen).build())
            .build()
    }

    fun post(ctx: Context, text: String) {
        try {
            ensureChannels(ctx)
            ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, text))
        } catch (e: Exception) { }
    }
}

class YaraForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        startForeground(1001, Notifier.build(this, "I'm here, chakkare ❤️ message me anytime"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}

class YaraActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        YaraEngine.init(context)
        when (intent.action) {
            "com.yara.ai.REPLY" -> {
                val txt = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply")?.toString().orEmpty()
                if (txt.isNotBlank() && !YaraEngine.busy) {
                    Notifier.post(context, "Yara is typing…")
                    YaraEngine.send(txt, null)
                }
            }
            "com.yara.ai.SCREEN" -> {
                if (!YaraEngine.busy) {
                    YaraEngine.useScreen = true
                    Notifier.post(context, "Switch to the app you want me to look at…")
                    YaraEngine.send("Look at my screen and tell me what you think.", null)
                }
            }
        }
    }
}
