package com.claw.autoreplyai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Alerts that must actually reach the user: a message the bot deliberately left
 * for a human, and the bot having stopped working.
 *
 * Both buzz the phone — a silent "reply yourself" alert is useless when the whole
 * point is that the user is busy.
 */
object Notify {

    /**
     * A NEW id is required to change channel settings. Deleting a channel and
     * recreating it with the same id does NOT reset it — Android deliberately
     * restores the saved settings, so the vibration would silently stay off.
     */
    private const val CHANNEL = "needs_you_v2"
    private const val LEGACY_CHANNEL = "needs_you"

    private var seq = 0

    private fun channelId(ctx: Context): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // retire the vibration-less channel from earlier builds
        if (nm.getNotificationChannel(LEGACY_CHANNEL) != null) {
            nm.deleteNotificationChannel(LEGACY_CHANNEL)
        }

        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(
                CHANNEL,
                "নিজে উত্তর দিন",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "যেসব মেসেজের উত্তর আপনি নিজে দেবেন, আর বট বন্ধ হয়ে গেলে"
                enableVibration(true)
                // short–short–long: noticeable without being obnoxious
                vibrationPattern = longArrayOf(0, 250, 150, 250, 150, 450)
                enableLights(true)
            }
            nm.createNotificationChannel(ch)
        }
        return CHANNEL
    }

    /** A message the bot left alone — the user should answer it personally. */
    fun needsYou(ctx: Context, sender: String, message: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = NotificationCompat.Builder(ctx, channelId(ctx))
                .setSmallIcon(R.drawable.ic_stat_auto)
                .setContentTitle("নিজে উত্তর দিন — $sender")
                .setContentText(message.replace("\n", " ").take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
                .setVibrate(longArrayOf(0, 250, 150, 250, 150, 450))
                .setAutoCancel(true)
                .build()
            nm.notify(nextId(), n)
        } catch (_: Exception) {
            // never let a notification failure break the reply pipeline
        }
    }

    /** Fired when the AI has failed repeatedly — the bot is effectively dead. */
    fun botProblem(ctx: Context, reason: String) {        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = NotificationCompat.Builder(ctx, channelId(ctx))
                .setSmallIcon(R.drawable.ic_stat_auto)
                .setContentTitle("AutoReply AI কাজ করছে না")
                .setContentText("টানা কয়েকটা রিপ্লাই ব্যর্থ — API Key বা ইন্টারনেট দেখুন")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "টানা কয়েকটা রিপ্লাই ব্যর্থ, তাই এখন কোনো অটো-রিপ্লাই যাচ্ছে না।\n\n" +
                                "কারণ: $reason\n\nAI ট্যাবে গিয়ে API Key ও মডেল দেখে নিন।"
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
                .setVibrate(longArrayOf(0, 400, 200, 400))
                .setAutoCancel(true)
                .build()
            nm.notify(nextId(), n)
        } catch (_: Exception) {
        }
    }

    /** The "what did I miss" brief, posted so it can be read without opening the app. */
    fun digest(ctx: Context, summary: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = NotificationCompat.Builder(ctx, channelId(ctx))
                .setSmallIcon(R.drawable.ic_stat_auto)
                .setContentTitle("কী মিস করলাম")
                .setContentText(summary.replace("\n", " ").take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .build()
            nm.notify(nextId(), n)
        } catch (_: Exception) {
        }
    }

    /** Distinct ids so several pending alerts can sit in the shade together. */
    private fun nextId(): Int {
        seq = (seq + 1) % 40
        return 4100 + seq
    }
}
