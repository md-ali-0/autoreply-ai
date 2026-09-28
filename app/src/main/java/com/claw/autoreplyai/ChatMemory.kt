package com.claw.autoreplyai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Short rolling conversation memory, one bucket per contact.
 * Gives the AI enough context to reply coherently.
 *
 * Turns carry a timestamp. Without one, a message from last week and a message
 * from a minute ago are indistinguishable to the model, which then answers as if
 * the old thread were still live — "তাই তো বললাম না" about something said days ago.
 * Stale turns are therefore dropped rather than sent: a short, correct history
 * beats a long, misleading one.
 */
object ChatMemory {

    private const val FILE = "chat_memory"

    /**
     * Hard ceiling on stored turns per contact. This bounds memory *growth* only —
     * it is not what the model sees. What the model sees is [MAX_TURNS] worth of
     * fresh turns, and it must stay comfortably below this cap, otherwise a busy
     * hour could push the live conversation out of storage by sheer turnover.
     */
    private const val MAX_SEEN = 40

    /** Turns handed to the model for one contact. */
    private const val MAX_TURNS = 20

    /**
     * Turns older than this are not sent to the model at all. Six hours covers a
     * single back-and-forth session; beyond that the topic has almost always moved on.
     */
    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    /**
     * Turns older than this are dropped when writing, so storage does not grow
     * without bound across days of silence.
     */
    private const val PRUNE_AGE_MS = 48 * 60 * 60 * 1000L

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun keyFor(contact: String) = "h_" + contact.lowercase().hashCode().toString()

    private class Turn(val role: String, val content: String, val at: Long)

    private fun load(ctx: Context, contact: String): List<Turn> {
        val raw = sp(ctx).getString(keyFor(contact), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Turn>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // Entries written before timestamps existed have no "at" — treat them
                // as ancient so they age out, rather than pretending they are current.
                out.add(
                    Turn(
                        o.optString("role", "user"),
                        o.optString("content", ""),
                        o.optLong("at", 0L)
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Recent turns for this contact, oldest first. Anything past [MAX_AGE_MS] is
     * omitted, and a gap marker is inserted in its place so the model knows the
     * conversation did not simply start here.
     */
    fun history(ctx: Context, contact: String): List<AiClient.Msg> {
        val turns = load(ctx, contact)
        if (turns.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val fresh = turns.filter { it.at > 0L && now - it.at <= MAX_AGE_MS }
        if (fresh.isEmpty()) return emptyList()

        // Keep the tail — the most recent turns are the ones that matter — and never
        // hand the model more than MAX_TURNS, however much survived the age filter.
        val trimmed = if (fresh.size > MAX_TURNS) fresh.subList(fresh.size - MAX_TURNS, fresh.size)
        else fresh

        val out = ArrayList<AiClient.Msg>(trimmed.size + 1)
        // The oldest surviving turn is not the start of the conversation — say so,
        // otherwise the model reads a mid-thread fragment as a fresh opening.
        if (trimmed.size < turns.size) {
            out.add(
                AiClient.Msg(
                    "system",
                    "এখান থেকে আগের কথা বলতে গেলে — এর আগে এই কন্টাক্টের সাথে আরও কথা হয়েছে।"
                )
            )
        }
        for (t in trimmed) out.add(AiClient.Msg(t.role, t.content))
        return out
    }

    fun add(ctx: Context, contact: String, role: String, content: String) {
        val now = System.currentTimeMillis()
        val list = load(ctx, contact)
            .filter { it.at > 0L && now - it.at <= PRUNE_AGE_MS }
            .toMutableList()

        list.add(Turn(role, content, now))

        // Hard cap so a long back-and-forth cannot grow the list without bound, and
        // so the JSON string stays inside what a single preference can hold.
        while (list.size > MAX_SEEN) list.removeAt(0)

        // Everything left was pruned by age, so there is nothing worth keeping —
        // drop the bucket instead of leaving an empty entry behind per contact.
        if (list.isEmpty()) {
            sp(ctx).edit().remove(keyFor(contact)).apply()
            return
        }

        val arr = JSONArray()
        for (t in list) {
            arr.put(JSONObject().put("role", t.role).put("content", t.content).put("at", t.at))
        }
        sp(ctx).edit().putString(keyFor(contact), arr.toString()).apply()
    }

    /** Wipe one contact's memory, including anything still stored but expired. */
    fun clear(ctx: Context, contact: String) {
        sp(ctx).edit().remove(keyFor(contact)).apply()
    }

    fun clearAll(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }

    /**
     * How many turns are actually in play for this contact. Counts only real fresh
     * turns — deliberately not `history().size`, because [history] prepends a
     * gap-marker system message that is not a conversation turn. Counting it made
     * the UI claim one more message than the model really receives.
     */
    fun activeCount(ctx: Context, contact: String): Int {
        val now = System.currentTimeMillis()
        return load(ctx, contact).count { it.at > 0L && now - it.at <= MAX_AGE_MS }
    }

    /** How many turns are stored, fresh or not — used by the log screen. */
    fun storedCount(ctx: Context, contact: String): Int = load(ctx, contact).size

    // ------------------------------------------------------------ backup

    fun snapshot(ctx: Context): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp(ctx).all) if (v is String) o.put(k, v)
        return o
    }

    fun restore(ctx: Context, o: JSONObject) {
        val edit = sp(ctx).edit().clear()
        for (key in o.keys()) {
            val v = o.optString(key)
            if (v.isNotEmpty()) edit.putString(key, v)
        }
        edit.apply()
    }
}
