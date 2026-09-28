package com.claw.autoreplyai

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Catches notifications from the supported chat apps (WhatsApp, Messenger) and
 * extracts (app, sender, message, phone number, reply handles).
 *
 * NOTE: the class is still called `WhatsAppNotificationListener` on purpose —
 * the notification-access grant is stored by *component name*, so renaming it
 * would silently revoke the permission on every installed device.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    /** Ring buffer of recent message hashes for deduplication. */
    private val recentHashes = LinkedHashMap<String, Long>(16, 0.75f, true)

    private var lastKey = ""
    private var lastAt = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        // Useful diagnostics: does the chat app actually give us a reply action here?
        probeActiveNotifications()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Reports whether the chat notifications currently in the shade expose a
     * free-form reply action. If they do, replies work even while the phone is
     * locked; if not, the app has to fall back to driving the UI.
     */
    fun probeActiveNotifications() {
        try {
            val actives = activeNotifications ?: return
            val chats = actives.filter { MessagingApps.isSupported(it.packageName) }
            if (chats.isEmpty()) {
                LogStore.add(applicationContext, "পরীক্ষা: এখন কোনো চ্যাট নোটিফিকেশন নেই")
                return
            }
            for (sbn in chats) {
                val n = sbn.notification
                val hasReply = DirectReplier.findReplyAction(n) != null
                LogStore.add(
                    applicationContext,
                    "পরীক্ষা: ${MessagingApps.label(sbn.packageName)} নোটিফিকেশন — " +
                            "actions=${n.actions?.size ?: 0}, " +
                            "সরাসরি reply ${if (hasReply) "আছে ✓" else "নেই ✗"}"
                )
            }
        } catch (e: Exception) {
            LogStore.add(applicationContext, "পরীক্ষা ব্যর্থ: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (!MessagingApps.isSupported(pkg)) return

        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val extras = n.extras ?: return

        val appLabel = MessagingApps.label(pkg)

        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

        if (title.isBlank() || title.equals(appLabel, true)) return

        var phoneHint: String? = null
        var groupBySender = false
        var ownMessage = false

        // ---- MessagingStyle gives us the real last message + sender ----
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            val last = messages.lastOrNull() as? Bundle
            if (last != null) {
                last.getCharSequence("text")?.toString()?.trim()?.let { if (it.isNotEmpty()) text = it }

                @Suppress("DEPRECATION")
                val person = last.getParcelable("sender_person") as? Person
                if (person != null) {
                    telFromPerson(person)?.let { phoneHint = it }
                    val pName = person.name?.toString()?.trim()
                    if (!pName.isNullOrBlank() && !pName.equals(title, true)) {
                        groupBySender = true
                        if (title.isBlank()) title = pName
                    }
                } else {
                    // sender_person == null → this is an OUTGOING (own) message.
                    // Only WhatsApp is known to behave this way; for other apps a
                    // missing sender field just means the app didn't fill it in, so
                    // treating it as "own" would drop real incoming messages. Those
                    // apps rely on the content match below instead.
                    if (MessagingApps.usesOwnMessageHeuristic(pkg)) ownMessage = true
                }
            }
        }

        // Skip our own replies — the chat app posts a new notification when we send
        // a reply, and if we don't skip it the bot replies to itself in an infinite loop.
        // Layer 1: sender_person == null often means our own message (WhatsApp only).
        // Layer 2: exact/fuzzy text match against recently sent messages (reliable everywhere).
        if (ownMessage) {
            LogStore.add(applicationContext, "নিজের মেসেজ বাদ (sender_person=null) — $title")
            return
        }

        if (SentMessageTracker.isOwnMessage(text)) {
            LogStore.add(applicationContext, "নিজের মেসেজ বাদ (content match) — $title")
            return
        }

        if (phoneHint == null) {
            @Suppress("DEPRECATION")
            val people = extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST)
            if (people != null) {
                for (person in people) {
                    val t = telFromPerson(person)
                    if (t != null) {
                        phoneHint = t
                        break
                    }
                }
            }
        }

        // Skip call events — they are not messages and the AI wrongly classifies
        // them as urgent ("missed call" sounds like an emergency).
        if (CallEventFilter.isCallEvent(text)) {
            LogStore.add(applicationContext, "কল ইভেন্ট বাদ — $title: ${text.take(40)}")
            return
        }

        // Also drop notifications that Android itself marks as calls.
        if (n.category == Notification.CATEGORY_CALL) {
            LogStore.add(applicationContext, "কল ক্যাটেগরির নোটিফিকেশন বাদ — $title")
            return
        }

        if (title.isBlank() || text.isBlank()) return

        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ||
                extras.getBoolean("isGroupConversation", false) ||
                groupBySender

        // ---- de-duplicate: chat apps often re-post the same notification ----
        val now = System.currentTimeMillis()
        val hash = "$pkg|$title|$text"

        // Clean old entries
        val iter = recentHashes.iterator()
        while (iter.hasNext()) {
            if (now - iter.next().value > 15_000L) iter.remove() else break
        }

        if (recentHashes.containsKey(hash)) {
            LogStore.add(applicationContext, "ডুপ্লিকেট মেসেজ বাদ — $title")
            return
        }
        recentHashes[hash] = now
        if (recentHashes.size > 12) {
            recentHashes.iterator().remove()
        }

        // Also keep the simple last-key guard as a safety net
        if (hash == lastKey && now - lastAt < 8_000) return
        lastKey = hash
        lastAt = now

        // Capture the inline-reply action while the notification is still live.
        val directReply = DirectReplier.findReplyAction(n)

        // ...and the content intent, which is the only way back into a Messenger
        // conversation once the notification's inline reply is unavailable.
        val contentIntent = try {
            n.contentIntent
        } catch (e: Exception) {
            null
        }

        ReplyEngine.onIncoming(
            applicationContext, pkg, title, text, isGroup, phoneHint, directReply, contentIntent
        )
    }

    private fun telFromPerson(person: Person?): String? {
        val uri = person?.uri ?: return null
        return if (uri.startsWith("tel:")) uri.removePrefix("tel:") else null
    }

    companion object {
        @Volatile
        private var instance: WhatsAppNotificationListener? = null

        /** Re-scan the shade on demand; logs the result into the app log. */
        fun probe(ctx: Context) {
            val live = instance
            if (live == null) {
                LogStore.add(ctx, "নোটিফিকেশন লিসেনার এখনো কানেক্ট হয়নি — অনুমতি দিন")
            } else {
                live.probeActiveNotifications()
            }
        }
    }
}
