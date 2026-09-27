package com.claw.autoreplyai

/**
 * Tracks messages the bot itself recently sent, so that when WhatsApp re-posts
 * them as new notifications we can skip them instead of replying to ourselves.
 *
 * WhatsApp's notification structure is unreliable for detecting own messages
 * (sender_person may be null for both incoming and outgoing), so we use the
 * actual text content + a time window.
 */
object SentMessageTracker {

    /** How long to remember a sent message (ms). */
    private const val WINDOW_MS = 30_000L

    private data class Entry(val text: String, val at: Long)

    private val entries = mutableListOf<Entry>()

    /** Call this immediately after a reply is successfully delivered. */
    fun record(text: String) {
        val now = System.currentTimeMillis()
        synchronized(entries) {
            entries.removeAll { now - it.at > WINDOW_MS }
            entries.add(Entry(text, now))
        }
    }

    /**
     * Returns true if [text] matches a message we sent in the last [WINDOW_MS].
     * Comparison is exact — the text WhatsApp echoes back is identical.
     */
    fun isOwnMessage(text: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(entries) {
            entries.removeAll { now - it.at > WINDOW_MS }
            return entries.any { it.text == text }
        }
    }
}
