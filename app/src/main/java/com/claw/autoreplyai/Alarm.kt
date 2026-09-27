package com.claw.autoreplyai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

/**
 * Wakes the user with sound + vibration when the AI detects an urgent message
 * while the user is in sleeping mode.
 */
object Alarm {

    private const val CHANNEL = "urgent_alarm"
    private const val NOTIF_ID = 99_001

    fun trigger(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        ensureChannel(app)

        // Vibration — strong, rhythmic pattern
        vibrate(app)

        // Sound — use system alarm or notification sound
        val soundUri = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
            ?: android.provider.Settings.System.DEFAULT_NOTIFICATION_URI

        val intent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("alarm_reason", reason)
        }
        val pending = PendingIntent.getActivity(
            app, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(app, CHANNEL).apply {
            setSmallIcon(android.R.drawable.ic_dialog_alert)
            setContentTitle("জরুরি — Ali ভাইয়াকে ডাকা হচ্ছে")
            setContentText(reason)
            setPriority(NotificationCompat.PRIORITY_MAX)
            setCategory(NotificationCompat.CATEGORY_ALARM)
            setAutoCancel(true)
            setSound(soundUri)
            setVibrate(longArrayOf(0, 800, 300, 800, 300, 800))
            setContentIntent(pending)
            setFullScreenIntent(pending, true)
        }

        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, builder.build())
    }

    private fun vibrate(ctx: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(
                longArrayOf(0, 600, 200, 600, 200, 600), -1
            )
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 600, 200, 600, 200, 600), -1)
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return

        val soundUri = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
            ?: android.provider.Settings.System.DEFAULT_NOTIFICATION_URI

        val ch = NotificationChannel(
            CHANNEL,
            "জরুরি অ্যালার্ম",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "ঘুমের মোডে জরুরি মেসেজ পেলে Ali ভাইয়াকে ডাকা হয়"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
            setSound(soundUri, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
        }
        nm.createNotificationChannel(ch)
    }
}
