package com.claw.autoreplyai

import java.security.MessageDigest

/**
 * A conversation is identified by the app it lives in **plus** a per-app identity.
 *
 * A contact name is not an identity. "Rahim" on WhatsApp and "Rahim" on Messenger are
 * two different people, and a saved contact named "Ali" is a third. Keying shared state
 * by name alone meant those conversations collided: they shared a cooldown, shared a
 * chat-memory bucket, and — worst — shared the single in-flight slot, so a reply queued
 * for one could be delivered into the other.
 *
 * [conversationId] is resolved from the strongest identity actually available at
 * notification time, because the sources are not equally reliable across apps:
 *
 *  1. a phone number, when the notification carries one — identifies a person exactly;
 *  2. otherwise the notification's own identity ([identity]) — stable for a thread, and
 *     the only thing Messenger gives us;
 *  3. otherwise the sender name, scoped to the package.
 *
 * Because (2) and (3) are different *kinds* of token, they are prefixed so they can
 * never be mistaken for each other. That matters more than it looks: mixing them would
 * silently merge two conversations into one bucket, which is the exact class of bug
 * this type exists to remove.
 *
 * [ConversationKey.storageKey] is the stable form persisted to disk. It is deliberately
 * a hash: names are personal data and conversation identities can be long, so the
 * stored key should leak neither.
 */
data class ConversationKey(
    val packageName: String,
    val conversationId: String
) {

    /**
     * Stable, collision-resistant key for storage — chat memory, cooldowns, the
     * pending queue. Never shown to the user; the human-readable identity is
     * carried separately wherever it is needed for logging.
     */
    val storageKey: String
        get() = sha256("$packageName:$conversationId").take(32)

    /** Short form for one-line log entries, so a collision is visible when it matters. */
    val debugLabel: String
        get() = "${MessagingApps.label(packageName)}/$conversationId"

    companion object {

        /** Identity kinds, prefixed so two different token kinds can never collide. */
        private const val KIND_PHONE = "p"
        private const val KIND_NOTIF = "n"
        private const val KIND_NAME = "m"

        /** Fallback identity when the notification carried nothing usable at all. */
        private const val UNKNOWN = "unknown"

        /**
         * Build a key from whatever the notification actually gave us.
         *
         * @param pkg      the chat app the message arrived through
         * @param phone    a phone number, if the notification exposed one
         * @param identity the notification's own key/tag — a stable per-thread handle
         * @param sender   the display name, the weakest but always-present identity
         */
        fun of(
            pkg: String,
            phone: String? = null,
            identity: String? = null,
            sender: String? = null
        ): ConversationKey {
            val id = normalizePhone(phone)
                ?: identity?.trim()?.takeIf { it.isNotEmpty() }?.let { "$KIND_NOTIF:$it" }
                ?: sender?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "$KIND_NAME:$it" }
                ?: UNKNOWN
            return ConversationKey(pkg, id)
        }

        /**
         * Numbers arrive formatted a dozen ways ("+880 1711-000000", "01711 000000").
         * Keep a leading +, strip everything else that is not a digit, so the same
         * person yields the same identity however the notification happened to
         * present them.
         */
        private fun normalizePhone(raw: String?): String? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            val plus = s.startsWith("+")
            val digits = s.filter { it.isDigit() }
            // A handful of digits is a short code or a housekeeping fragment, not
            // somebody's number — refusing it keeps noise out of the phone namespace.
            if (digits.length < 7) return null
            return "$KIND_PHONE:${if (plus) "+" else ""}$digits"
        }

        private fun sha256(s: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(d.size * 2)
            for (b in d) {
                val v = b.toInt() and 0xFF
                if (v < 16) sb.append('0')
                sb.append(Integer.toHexString(v))
            }
            return sb.toString()
        }
    }
}
