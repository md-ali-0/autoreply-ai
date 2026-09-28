package com.claw.autoreplyai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Collections

/**
 * Approval mode: the AI drafts the reply, the user decides.
 *
 * Instead of sending, the drafted text is posted as a notification with
 * "পাঠাও" / "বাতিল" actions. Approving delivers it through exactly the same
 * routes the automatic path uses, so nothing about delivery is special-cased.
 *
 * The pending draft lives in memory only. If the process dies the draft is gone —
 * that is intentional: a stale draft that suddenly sends itself minutes later
 * would be worse than losing it, and the log says so.
 */
object Approval {

    const val ACTION_APPROVE = "com.claw.autoreplyai.APPROVE"
    const val ACTION_DISCARD = "com.claw.autoreplyai.DISCARD"
    const val EXTRA_ID = "approval_id"

    private const val CHANNEL = "approval_v1"
    private const val NOTIF_BASE = 5200

    /** Everything needed to send the draft later. */
    class Draft(
        val pkg: String,
        val sender: String,
        val message: String,
        val reply: String,
        val phoneHint: String?,
        val direct: DirectReplier.Handle?,
        val contentIntent: PendingIntent?
    )

    private val drafts = Collections.synchronizedMap(HashMap<Int, Draft>())
    private var seq = 0

    private fun channelId(ctx: Context): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(
                CHANNEL,
                "রিপ্লাই অনুমোদন",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "AI-এর বানানো রিপ্লাই পাঠানোর আগে আপনার অনুমোদন চাইবে"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 120, 180)
                enableLights(true)
            }
            nm.createNotificationChannel(ch)
        }
        return CHANNEL
    }

    /** Posts the draft for approval. Returns false when it could not be shown. */
    fun request(ctx: Context, draft: Draft): Boolean {
        return try {
            seq = (seq + 1) % 400
            val id = NOTIF_BASE + seq
            drafts[id] = draft

            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = NotificationCompat.Builder(ctx, channelId(ctx))
                .setSmallIcon(R.drawable.ic_stat_auto)
                .setContentTitle("রিপ্লাই অনুমোদন — ${draft.sender}")
                .setContentText(draft.reply.replace("\n", " ").take(120))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "মেসেজ: ${draft.message.replace("\n", " ").take(140)}\n\n" +
                                "উত্তর: ${draft.reply}"
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
                .setAutoCancel(true)
                .addAction(0, "✅ পাঠাও", action(ctx, id, ACTION_APPROVE))
                .addAction(0, "✖️ বাতিল", action(ctx, id, ACTION_DISCARD))
                .build()
            nm.notify(id, n)
            true
        } catch (e: Exception) {
            LogStore.add(ctx, "অনুমোদনের নোটিফিকেশন দেখানো যায়নি: ${e.message}")
            false
        }
    }

    private fun action(ctx: Context, id: Int, action: String): PendingIntent {
        val intent = Intent(ctx, ApprovalReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            ctx, id * 10 + (if (action == ACTION_APPROVE) 1 else 2), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Sends the approved draft through the normal delivery routes. */
    fun approve(ctx: Context, id: Int) {
        val draft = drafts.remove(id) ?: run {
            LogStore.add(ctx, "অনুমোদিত রিপ্লাই পাওয়া যায়নি (অ্যাপ বন্ধ হয়ে গিয়েছিল?)")
            return
        }
        cancel(ctx, id)

        val app = ctx.applicationContext

        // Route 1: the notification's inline reply action, if it is still alive.
        if (draft.direct != null && DirectReplier.send(app, draft.direct, draft.reply)) {
            onSent(app, draft)
            return
        }

        // Route 2: drive the UI.
        if (MessagingApps.supportsPhoneLink(draft.pkg)) {
            val p = Prefs.get(app)
            val raw = draft.phoneHint?.takeIf { it.isNotBlank() }
                ?: ContactResolver.numberForName(app, draft.sender)
            val number = raw?.let { ReplyEngine.normalizeNumber(it, p.countryCode) }
            if (number.isNullOrBlank()) {
                LogStore.add(app, "অনুমোদিত রিপ্লাই পাঠানো যায়নি — নাম্বার পাওয়া যায়নি (${draft.sender})")
                return
            }
            SendAccessibilityService.send(app, draft.pkg, number, draft.reply) { ok ->
                if (ok) onSent(app, draft)
                else LogStore.add(app, "✗ অনুমোদিত রিপ্লাই পাঠানো যায়নি → ${draft.sender}")
            }
            return
        }

        val open = draft.contentIntent
        if (open == null) {
            LogStore.add(app, "অনুমোদিত রিপ্লাই পাঠানো যায়নি — চ্যাট খোলার উপায় নেই (${draft.sender})")
            return
        }
        SendAccessibilityService.sendViaIntent(app, draft.pkg, open, draft.reply) { ok ->
            if (ok) onSent(app, draft)
            else LogStore.add(app, "✗ অনুমোদিত রিপ্লাই পাঠানো যায়নি → ${draft.sender}")
        }
    }

    fun discard(ctx: Context, id: Int) {
        val draft = drafts.remove(id)
        cancel(ctx, id)
        if (draft != null) {
            LogStore.add(ctx, "অনুমোদন বাতিল — ${draft.sender}")
        }
    }

    private fun cancel(ctx: Context, id: Int) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(id)
        } catch (_: Exception) {
        }
    }

    private fun onSent(app: Context, draft: Draft) {
        ChatMemory.add(app, draft.sender, "user", draft.message)
        ChatMemory.add(app, draft.sender, "assistant", draft.reply)
        Prefs.get(app).setLastReply(draft.sender, System.currentTimeMillis())
        SentMessageTracker.record(draft.reply)
        DigestStore.record(
            app,
            DigestStore.Event(
                at = System.currentTimeMillis(),
                pkg = draft.pkg,
                sender = draft.sender,
                message = draft.message,
                action = DigestStore.ACTION_REPLIED,
                reply = draft.reply
            )
        )
        LogStore.add(app, "✓ অনুমোদনের পর পাঠানো হলো → ${draft.sender}: ${draft.reply.replace("\n", " ").take(80)}")
    }
}

/** Receives the two notification actions. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Approval.EXTRA_ID, -1)
        if (id < 0) return
        when (intent.action) {
            Approval.ACTION_APPROVE -> Approval.approve(context, id)
            Approval.ACTION_DISCARD -> Approval.discard(context, id)
        }
    }
}
