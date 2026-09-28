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

    /** Burst-coalescing state for the duplicate and noise log lines. */
    private var lastDupTitle = ""
    private var lastDupAt = 0L
    private var dupSuppressed = 0
    private var lastNoiseTitle = ""
    private var lastNoiseAt = 0L

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
            // Two chat notifications will legitimately produce one ✓ and one ✗ here —
            // a group summary often carries no free-form reply while the per-message
            // notification does. A ✗ is only meaningful for the chat that owns it, so
            // name the notification rather than implying a single overall result.
            val withReply = chats.count { DirectReplier.findReplyAction(it.notification) != null }
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
            LogStore.add(
                applicationContext,
                "পরীক্ষা: চ্যাট নোটিফিকেশন ${chats.size} টার মধ্যে $withReply টায় সরাসরি reply আছে"
            )
        } catch (e: Exception) {
            LogStore.add(applicationContext, "পরীক্ষা ব্যর্থ: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // A listener that throws is a listener Android stops calling, which silently
        // turns auto-reply off. One malformed notification must not do that.
        try {
            handlePosted(sbn)
        } catch (e: Throwable) {
            LogStore.add(
                applicationContext,
                "‼️ নোটিফিকেশন প্রসেস করতে গিয়ে ত্রুটি — ${e.javaClass.simpleName}: ${e.message ?: ""}"
            )
        }
    }

    private fun handlePosted(sbn: StatusBarNotification?) {
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

        // ---- cheapest filters first -------------------------------------------
        // Everything below this point costs work (parcel reads, a hash build, contact
        // lookups inside the engine). Chat apps re-post the same notification many
        // times a second, so the duplicate check has to happen *before* that work, not
        // after it. It used to sit at the very bottom of this method, which is how one
        // second produced sixteen identical "ডুপ্লিকেট" lines.
        val now = System.currentTimeMillis()
        val hash = "$pkg|$title|$text"

        val iter = recentHashes.iterator()
        while (iter.hasNext()) {
            if (now - iter.next().value > 15_000L) iter.remove() else break
        }

        if (recentHashes.containsKey(hash)) {
            logDuplicateSuppressed(title)
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

        // Meta posts its own housekeeping through the Messenger channel — "Chat heads
        // active" with the body "Start a conversation" is a UI hint, not somebody
        // talking. It used to be answered ("হ্যালো, কেমন চলছে সব?") and written into
        // ChatMemory, which then made the model think the contact had said it.
        if (isSystemNoise(title, text)) {
            logNoiseDropped(title)
            return
        }

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
            applicationContext, pkg, title, text, isGroup, phoneHint, directReply, contentIntent,
            sbn.postTime
        )
    }

    /**
     * Chat apps emit housekeeping notifications that look like messages. Meta is the
     * worst offender: "Chat heads active" / "Start a conversation" arrives through the
     * Messenger channel and is not a person speaking. Answering it sends a greeting to
     * nobody, and storing it in ChatMemory poisons the next real reply.
     *
     * Title and body are checked separately and deliberately not interchangeably: a
     * person can legitimately be called "Start a Conversation", and dropping them for
     * ever is worse than the occasional stray greeting.
     */
    private fun isSystemNoise(title: String, text: String): Boolean {
        val t = title.lowercase()
        val b = text.lowercase()
        // A noisy title is enough on its own — these are never contact names.
        if (NOISE_TITLES.any { t == it || t.startsWith("$it ") }) return true
        // A noisy body only counts when the title is not a plausible person's name,
        // i.e. it is one of the generic/system titles we already know about.
        if (NOISE_BODIES.any { b == it } && SYSTEM_TITLES.any { t == it }) return true
        return false
    }

    /**
     * One line per burst, not one per duplicate. Sixteen identical lines in the same
     * second is noise that buries the lines that matter.
     */
    private fun logDuplicateSuppressed(title: String) {
        val now = System.currentTimeMillis()
        if (title == lastDupTitle && now - lastDupAt < 15_000L) {
            dupSuppressed++
            return
        }
        flushDuplicateCount()
        lastDupTitle = title
        lastDupAt = now
        dupSuppressed = 1
        LogStore.add(applicationContext, "ডুপ্লিকেট মেসেজ বাদ — $title")
    }

    /** Append "(আর Nটি)" to the burst once it ends, so nothing is silently lost. */
    private fun flushDuplicateCount() {
        if (dupSuppressed > 1) {
            LogStore.add(applicationContext, "  ↑ আরও ${dupSuppressed - 1}টি একই ডুপ্লিকেট চাপা পড়েছে")
        }
        dupSuppressed = 0
    }

    /** Same coalescing for dropped system noise, which also arrives in bursts. */
    private fun logNoiseDropped(title: String) {
        val now = System.currentTimeMillis()
        if (title == lastNoiseTitle && now - lastNoiseAt < 15_000L) return
        lastNoiseTitle = title
        lastNoiseAt = now
        LogStore.add(applicationContext, "সিস্টেম নোটিফিকেশন বাদ (মেসেজ নয়) — $title")
    }

    private fun telFromPerson(person: Person?): String? {
        val uri = person?.uri ?: return null
        return if (uri.startsWith("tel:")) uri.removePrefix("tel:") else null
    }

    companion object {
        /**
         * Titles/body text that chat apps use for their own UI notifications rather
         * than for something a person sent. Matched lowercased.
         *
         * Deliberately narrow. "Messenger" is NOT in this list: someone genuinely
         * named "Messenger" (or a group called that) would be dropped forever, and a
         * silently unanswered real message is worse than an occasional stray greeting.
         */
        private val NOISE_TITLES = listOf(
            "chat heads active",
            "chat head",
            "start a conversation"
        )
        private val NOISE_BODIES = listOf(
            "start a conversation",
            "tap to chat"
        )

        /**
         * Titles that are a generic app banner rather than a person. Only these can
         * have their body treated as noise, so a real contact called "Start a
         * Conversation" still gets answered.
         */
        private val SYSTEM_TITLES = listOf(
            "chat heads active",
            "chat head",
            "messenger",
            "messages"
        )

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
