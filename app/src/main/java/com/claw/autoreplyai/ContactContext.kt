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

    data class Entry(val name: String, val context: String)

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Entry> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Entry(o.optString("name").trim(), o.optString("context").trim()))
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
            arr.put(JSONObject().put("name", e.name).put("context", e.context))
        }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * Notes for one chat title. WhatsApp titles often carry decoration the stored
     * name does not ("Hayati" vs "Hayati ❤️"), so fall back to a loose match.
     */
    fun forContact(ctx: Context, contact: String): String {
        val entries = all(ctx)
        val c = contact.trim().lowercase()
        if (c.isEmpty()) return ""

        entries.firstOrNull { it.name.lowercase() == c }?.let { return it.context }
        entries.firstOrNull {
            it.name.isNotBlank() &&
                    (c.contains(it.name.lowercase()) || it.name.lowercase().contains(c))
        }?.let { return it.context }

        return ""
    }
}
