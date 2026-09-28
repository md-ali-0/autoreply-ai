package com.claw.autoreplyai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happened while the user was not looking.
 *
 * Every message the engine touches is recorded here, so the user can later ask
 * "কী মিস করলাম?" and get an answer that is built from real events rather than
 * from the reply log, which only shows successful sends.
 */
object DigestStore {

    const val ACTION_REPLIED = "replied"
    const val ACTION_HELD = "held"
    const val ACTION_IGNORED = "ignored"
    const val ACTION_APPROVAL = "approval"
    const val ACTION_BLOCKED = "blocked"

    private const val FILE = "reply_digest"
    private const val KEY_EVENTS = "events"
    private const val KEY_LAST = "last_digest_at"
    private const val MAX = 400

    /** Events older than this are dropped — a digest is about "recently". */
    private const val RETENTION_MS = 3 * 24 * 60 * 60 * 1000L

    data class Event(
        val at: Long,
        val pkg: String,
        val sender: String,
        val message: String,
        val action: String,
        val reply: String
    )

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun record(ctx: Context, event: Event) {
        try {
            val list = read(ctx).toMutableList()
            list.add(0, event)
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            val trimmed = list.filter { it.at >= cutoff }.take(MAX)
            write(ctx, trimmed)
        } catch (_: Exception) {
            // bookkeeping must never break a reply
        }
    }

    fun read(ctx: Context): List<Event> {
        val raw = sp(ctx).getString(KEY_EVENTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Event>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Event(
                        at = o.optLong("at", 0L),
                        pkg = o.optString("pkg", ""),
                        sender = o.optString("sender", ""),
                        message = o.optString("message", ""),
                        action = o.optString("action", ""),
                        reply = o.optString("reply", "")
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove(KEY_EVENTS).apply()
    }

    /** When the user last asked for a digest; 0 when never. */
    fun lastDigestAt(ctx: Context): Long = sp(ctx).getLong(KEY_LAST, 0L)

    fun markDigestNow(ctx: Context) {
        sp(ctx).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
    }

    private fun write(ctx: Context, list: List<Event>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("at", e.at)
                    .put("pkg", e.pkg)
                    .put("sender", e.sender)
                    .put("message", e.message)
                    .put("action", e.action)
                    .put("reply", e.reply)
            )
        }
        sp(ctx).edit().putString(KEY_EVENTS, arr.toString()).apply()
    }
}

/**
 * Builds the human-readable "what did I miss" brief.
 */
object Digest {

    private const val DEFAULT_WINDOW_MS = 8 * 60 * 60 * 1000L

    /**
     * Summarises everything recorded since the previous digest. Returns a short
     * Bangla brief. Uses the AI when one is configured, and falls back to a plain
     * list so the feature still works with no API key at all.
     */
    suspend fun build(ctx: Context, useAi: Boolean): String {
        val now = System.currentTimeMillis()
        val last = DigestStore.lastDigestAt(ctx)
        val from = if (last > 0) last else now - DEFAULT_WINDOW_MS

        val events = DigestStore.read(ctx).filter { it.at in from..now }
        if (events.isEmpty()) {
            DigestStore.markDigestNow(ctx)
            return "এই সময়ে কোনো নতুন মেসেজ আসেনি। শান্তি! 😌"
        }

        val plain = plainBrief(events, from, now)

        if (!useAi) {
            DigestStore.markDigestNow(ctx)
            return plain
        }

        val ai = try {
            AiClient.askWithFallback(
                ctx,
                listOf(
                    AiClient.Msg("system", DIGEST_PROMPT),
                    AiClient.Msg("user", plain)
                )
            )
        } catch (e: Exception) {
            LogStore.add(ctx, "ডাইজেস্ট সারসংক্ষেপ ব্যর্থ: ${e.message}")
            ""
        }

        DigestStore.markDigestNow(ctx)
        return ai.ifBlank { plain }
    }

    private val DIGEST_PROMPT = """
        নিচে গত কিছুক্ষণে আসা মেসেজের একটা তালিকা দিচ্ছি — কে লিখেছে, কী লিখেছে,
        আর অটো-রিপ্লাই কী করেছে। এটাকে ছোট একটা ব্রিফিং বানাও যাতে আমি এক নজরে
        বুঝতে পারি কী মিস করেছি।

        নিয়ম:
        - প্রথমে এক লাইনে সারসংক্ষেপ: কতগুলো মেসেজ, কতজন থেকে
        - তারপর যাদের উত্তর দেওয়া হয়নি বা যেগুলো গুরুত্বপূর্ণ মনে হয়, শুধু সেগুলোর নাম ও কথা
        - যে কন্টাক্ট সম্পর্কে কিছু জানার দরকার নেই, বাদ দাও
        - বাংলায় লিখো, ছোট বাক্যে, ইমোজি খুব কম
        - বুলেট ব্যবহার করতে পারো
        - তালিকায় না থাকা কোনো তথ্য বানিয়ে লিখবে না
    """.trimIndent()

    private fun plainBrief(events: List<DigestStore.Event>, from: Long, now: Long): String {
        val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.US)
        val sb = StringBuilder()

        val bySender = events.groupBy { it.sender }
        val unanswered = events.filter {
            it.action == DigestStore.ACTION_HELD ||
                    it.action == DigestStore.ACTION_APPROVAL ||
                    it.action == DigestStore.ACTION_BLOCKED
        }

        sb.append(fmt.format(Date(from))).append(" → ").append(fmt.format(Date(now))).append('\n')
        sb.append("মোট ").append(events.size).append(" টা মেসেজ, ")
            .append(bySender.size).append(" জনের থেকে।\n")

        if (unanswered.isNotEmpty()) {
            sb.append("\n⚠️ যেগুলোর উত্তর যায়নি (").append(unanswered.size).append("):\n")
            for (e in unanswered.take(8)) {
                sb.append("• ").append(e.sender).append(": ")
                    .append(e.message.replace("\n", " ").take(70)).append('\n')
            }
        }

        sb.append("\nকন্টাক্ট:\n")
        for ((sender, list) in bySender.entries.sortedByDescending { it.value.size }.take(8)) {
            val last = list.first()
            sb.append("• ").append(sender).append(" — ").append(list.size).append(" টা · ")
                .append(MessagingApps.label(last.pkg)).append('\n')
        }
        return sb.toString().trim()
    }
}
