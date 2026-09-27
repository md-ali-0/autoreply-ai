package com.claw.autoreplyai

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Proves the inline-reply mechanism actually works on *this* device — including
 * while the phone is locked, which is the one thing that cannot be verified by
 * reading code.
 *
 * It posts a notification carrying a RemoteInput reply action, then answers that
 * notification through [DirectReplier] — exactly the path used for real WhatsApp
 * messages. [SelfTestReceiver] logs whether the text arrived.
 */
object ReplySelfTest {

    private const val CHANNEL = "selftest"
    const val NOTIF_ID = 4242
    const val ACTION_REPLY = "com.claw.autoreplyai.SELFTEST_REPLY"
    const val EXTRA_REPLY = "selftest_reply"
    const val MARKER = "SELFTEST-OK"

    /** Delay so the user has time to lock the phone before the reply is fired. */
    private const val DELAY_MS = 12_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun run(ctx: Context) {
        val app = ctx.applicationContext

        val input = RemoteInput.Builder(EXTRA_REPLY).setLabel("Reply").build()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyIntent = Intent(app, SelfTestReceiver::class.java).setAction(ACTION_REPLY)
        val pending = PendingIntent.getBroadcast(app, 0, replyIntent, flags)

        val action = Notification.Action.Builder(
            Icon.createWithResource(app, R.drawable.ic_stat_auto),
            "Reply",
            pending
        ).addRemoteInput(input).build()

        post(app, action)

        LogStore.add(
            app,
            "পরীক্ষা শুরু — ${DELAY_MS / 1000} সেকেন্ড পর রিপ্লাই পাঠানো হবে " +
                    "(এখন ফোন লক করে রাখলে লক অবস্থার ফলাফল পাবেন)"
        )

        scope.launch {
            delay(DELAY_MS)
            val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val lockState = when {
                !km.isKeyguardLocked -> "লক করা ছিল না"
                km.isDeviceSecure -> "লক করা ছিল (PIN/ফিঙ্গারপ্রিন্ট)"
                else -> "লক স্ক্রিনে ছিল"
            }
            LogStore.add(app, "পরীক্ষা: ফোন $lockState — এখন রিপ্লাই পাঠানো হচ্ছে")
            DirectReplier.send(app, DirectReplier.Handle(action, arrayOf(input)), MARKER)
        }
    }

    private fun post(ctx: Context, action: Notification.Action) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "AutoReply AI পরীক্ষা", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_auto)
            .setContentTitle("AutoReply AI — পরীক্ষা")
            .setContentText("এই নোটিফিকেশনের reply দিয়ে যাচাই করা হচ্ছে")
            .addAction(action)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    fun cleanup(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
    }
}

class SelfTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val results = RemoteInput.getResultsFromIntent(intent)
        val text = results?.getCharSequence(ReplySelfTest.EXTRA_REPLY)?.toString()

        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val lockNote = if (km.isKeyguardLocked) " — ফোন তখনো লক ছিল" else ""

        if (text == ReplySelfTest.MARKER) {
            LogStore.add(context, "✓✓ পরীক্ষা সফল: লক করা অবস্থাতেও রিপ্লাই পৌঁছেছে$lockNote")
        } else {
            LogStore.add(context, "✗ পরীক্ষা ব্যর্থ: রিপ্লাই পৌঁছায়নি (পেয়েছি: ${text ?: "কিছুই না"})")
        }
        ReplySelfTest.cleanup(context)
    }
}
