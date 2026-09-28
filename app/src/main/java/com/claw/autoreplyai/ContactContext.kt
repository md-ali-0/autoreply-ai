package com.claw.autoreplyai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Background notes about a contact, injected into the AI prompt **for that contact
 * only**. Nothing here is ever included when replying to somebody else, so
 * cross-contact leakage is impossible by construction rather than by instruction.
 */
object ContactContext {

    private const val FILE = "contact_context"
    private const val KEY = "entries"

    /**
     * [close] means the owner's relationship with this contact is intimate enough
     * that warm, affectionate replies are appropriate. Off by default: a bot that
     * assumes closeness with everyone is far worse than one that assumes it with
     * nobody.
     */
    data class Entry(val name: String, val context: String, val close: Boolean = false)

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Entry> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Entry(
                        o.optString("name").trim(),
                        o.optString("context").trim(),
                        o.optBoolean("close", false)
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            if (e.name.isBlank() && e.context.isBlank()) continue
            arr.put(
                JSONObject()
                    .put("name", e.name)
                    .put("context", e.context)
                    .put("close", e.close)
            )
        }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * Notes for one chat title. WhatsApp titles often carry decoration the stored
     * name does not ("Hayati" vs "Hayati ❤️"), so fall back to a loose match.
     */
    fun forContact(ctx: Context, contact: String): String = match(ctx, contact)?.context ?: ""

    /** Whether this contact is one the owner is close to. */
    fun isClose(ctx: Context, contact: String): Boolean = match(ctx, contact)?.close == true

    private fun match(ctx: Context, contact: String): Entry? {
        val entries = all(ctx)
        val c = contact.trim().lowercase()
        if (c.isEmpty()) return null

        entries.firstOrNull { it.name.lowercase() == c }?.let { return it }
        return entries.firstOrNull {
            it.name.isNotBlank() &&
                    (c.contains(it.name.lowercase()) || it.name.lowercase().contains(c))
        }
    }
}
