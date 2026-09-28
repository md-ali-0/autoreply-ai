package com.claw.autoreplyai

/**
 * Detects call notifications and call-log rows (WhatsApp and Messenger).
 *
 * A call is not a message. When a call happens (or is missed), the chat app can
 * post a notification whose text is "Voice call" / "Video call" / "Missed call",
 * and it also injects the call as the last MessagingStyle entry of the chat.
 * The bot used to treat that as an incoming message and reply to it — noisy in
 * general, and worse in mood mode, where the AI read "missed call" as urgent and
 * fired the gatekeeper alarm at 8:53 am for calls made half an hour earlier.
 */
object CallEventFilter {

    /** Exact call-log labels the chat apps use, lowercase. */
    private val CALL_LABELS = setOf(
        "voice call",
        "video call",
        "incoming voice call",
        "incoming video call",
        "outgoing voice call",
        "outgoing video call",
        "missed voice call",
        "missed video call",
        "declined voice call",
        "declined video call",
        "no answer",
        "call ended",
        "voice call ended",
        "video call ended",
        "calling",
        "ringing",
        // Messenger wording
        "missed call",
        "audio call",
        "call declined",
        "call not answered"
    )

    /**
     * True when [text] is a call event rather than a real message.
     * Conservative on purpose: only exact labels and label+detail forms
     * ("Voice call · 5 minutes") match, so a normal message that merely
     * mentions calling is never swallowed.
     */
    fun isCallEvent(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val norm = text.lowercase().replace(Regex("\\s+"), " ").trim()
        if (norm.isEmpty()) return false

        if (norm in CALL_LABELS) return true

        // "Voice call · 5 minutes", "Voice call, no answer" — label followed by detail.
        for (label in CALL_LABELS) {
            if (norm.startsWith("$label ") || norm.startsWith("$label·") ||
                norm.startsWith("$label,") || norm.startsWith("$label -")
            ) {
                return true
            }
        }

        // Missed-call notification body.
        if (norm.contains("tap to call back")) return true

        return false
    }
}
