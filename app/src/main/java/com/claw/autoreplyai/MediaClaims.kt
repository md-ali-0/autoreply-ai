package com.claw.autoreplyai

/**
 * Makes sure each media file answers exactly the message it belongs to.
 *
 * The app only ever sees a notification; the file has to be found on disk. Picking
 * "the newest file" is fine until two people send a photo (or a voice note) within
 * a few seconds of each other — then both notifications match the same file, and
 * one chat gets answered with a picture that belongs to another. That is exactly
 * what happened in production: a laughing emoji was answered with a comment about
 * someone else's profile screenshot.
 *
 * Two guards, both needed:
 *  - **Time correlation.** A notification carries `postTime`, and the file is
 *    written at roughly that moment, so the best candidate is the one whose
 *    modification time is closest to the notification — not simply the newest.
 *  - **Claiming.** Once a file has been used for one message it is marked used, so
 *    a second notification cannot consume the same file even if the times collide.
 *
 * Claims are process-lifetime only. Losing them on restart is harmless: the worst
 * case is the old behaviour, not a wrong reply.
 */
object MediaClaims {

    /** How long a claim is remembered. Long enough to cover a slow reply. */
    private const val TTL_MS = 10 * 60 * 1000L

    /** Files claimed in the recent past, oldest first (LinkedHashMap order). */
    private val used = LinkedHashMap<String, Long>()

    @Synchronized
    fun isUsed(key: String): Boolean {
        prune()
        return used.containsKey(key)
    }

    /** Marks [key] as consumed. Returns false when somebody already had it. */
    @Synchronized
    fun claim(key: String): Boolean {
        prune()
        if (used.containsKey(key)) return false
        used[key] = System.currentTimeMillis()
        return true
    }

    @Synchronized
    fun release(key: String) {
        used.remove(key)
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        val it = used.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value < cutoff) it.remove() else break
        }
    }
}

/**
 * Picks the file that belongs to a notification.
 */
object MediaMatcher {

    /** A file written this long before its notification is not a plausible match. */
    private const val BEFORE_MS = 20_000L

    /** ...nor this long after it. WhatsApp downloads are quick. */
    private const val AFTER_MS = 150_000L

    /**
     * The candidate written closest to [postTime], skipping anything already claimed.
     * Falls back to the newest file when the notification time is unknown (0).
     */
    fun <T> pick(
        candidates: List<T>,
        postTime: Long,
        mtimeOf: (T) -> Long,
        keyOf: (T) -> String
    ): T? {
        if (candidates.isEmpty()) return null

        val free = candidates.filter { !MediaClaims.isUsed(keyOf(it)) }
        if (free.isEmpty()) return null

        if (postTime <= 0L) {
            return free.maxByOrNull { mtimeOf(it) }?.also { MediaClaims.claim(keyOf(it)) }
        }

        val plausible = free.filter {
            val at = mtimeOf(it)
            at >= postTime - BEFORE_MS && at <= postTime + AFTER_MS
        }
        // No candidate near the notification time: better to send nothing than to
        // answer with a picture from some other conversation.
        val best = (plausible.ifEmpty { return null })
            .minByOrNull { kotlin.math.abs(mtimeOf(it) - postTime) }
            ?: return null

        return if (MediaClaims.claim(keyOf(best))) best else null
    }
}
