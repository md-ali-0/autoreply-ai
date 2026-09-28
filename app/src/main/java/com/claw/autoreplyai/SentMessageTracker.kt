package com.claw.autoreplyai

/**
 * Tracks messages the bot itself recently sent, so that when WhatsApp re-posts
 * them as new notifications we can skip them instead of replying to ourselves.
 *
 * WhatsApp's notification structure is unreliable for detecting own messages
 * (sender_person may be null for both incoming and outgoing), so we use the
 * actual text content + a time window.
 *
 * Fuzzy matching is used because WhatsApp sometimes echoes back messages with
 * slight differences — extra spaces, different emoji representations, or
 * zero-width characters that were not in the original.
 */
object SentMessageTracker {

    /** How long to remember a sent message (ms). */
    private const val WINDOW_MS = 30_000L

    /** Similarity threshold: 0.0–1.0, higher = stricter. */
    private const val SIMILARITY_THRESHOLD = 0.92

    private data class Entry(val raw: String, val normalized: String, val at: Long)

    private val entries = mutableListOf<Entry>()

    /** Call this immediately after a reply is successfully delivered. */
    fun record(text: String) {
        val now = System.currentTimeMillis()
        synchronized(entries) {
            entries.removeAll { now - it.at > WINDOW_MS }
            entries.add(Entry(text, normalize(text), now))
        }
    }

    /**
     * Returns true if [text] matches a message we sent in the last [WINDOW_MS].
     * Uses both exact match and fuzzy (normalized) match to catch WhatsApp's
     * slightly different echo formatting.
     */
    fun isOwnMessage(text: String): Boolean {
        val now = System.currentTimeMillis()
        val norm = normalize(text)
        synchronized(entries) {
            entries.removeAll { now - it.at > WINDOW_MS }
            // Layer 1: exact match (fast path)
            if (entries.any { it.raw == text }) return true
            // Layer 2: fuzzy normalized match
            return entries.any { similarity(it.normalized, norm) >= SIMILARITY_THRESHOLD }
        }
    }

    /** Strip everything that can vary between our send and WhatsApp's echo. */
    private fun normalize(text: String): String {
        return text
            .lowercase()
            // Collapse all whitespace to a single space
            .replace(Regex("\\s+"), " ")
            .trim()
            // Remove zero-width and control characters WhatsApp sometimes injects
            .replace(Regex("[\u200B-\u200D\uFEFF\u2060]+"), "")
            // Normalize common emoji variant selectors
            .replace(Regex("[\uFE0E\uFE0F]"), "")
            // Remove repeated punctuation that may get collapsed
            .replace(Regex("([.!?])\\1+"), "$1")
    }

    /** Simple character-level similarity: 1.0 = identical, 0.0 = nothing alike. */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val longer = if (a.length > b.length) a else b
        val shorter = if (a.length > b.length) b else a
        val dist = levenshtein(shorter, longer)
        return (longer.length - dist).toDouble() / longer.length
    }

    /** Standard Levenshtein distance (edit distance). */
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        val prev = IntArray(n + 1)
        val curr = IntArray(n + 1)
        for (j in 0..n) prev[j] = j
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,      // insertion
                    prev[j] + 1,          // deletion
                    prev[j - 1] + cost    // substitution
                )
            }
            for (j in 0..n) prev[j] = curr[j]
        }
        return prev[n]
    }
}
