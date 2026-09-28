package com.claw.autoreplyai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Background notes about a contact, injected into the AI prompt **for that contact
 * only**. Nothing here is ever included when replying to somebody else, so
 * cross-contact leakage is impossible by construction rather than by instruction.
 *
 * Entries are keyed by [ConversationKey], not by display name. The name is only a
 * label the user typed; the key is the same stable identity the rest of the app uses,
 * so notes follow the *person* rather than the spelling of their name in a chat title.
 * That matters because a rename used to silently orphan the notes, and a fuzzy
 * name match could attach one person's notes to another — for a feature whose whole
 * point is per-contact privacy, that is the wrong failure mode.
 *
 * Entries written before the key existed have no key, so they are matched by name once
 * and re-keyed the first time the conversation is seen: [adoptLegacy] writes the key
 * back and clears the name-only flag.
 */
object ContactContext {

    private const val FILE = "contact_context"
    private const val KEY = "entries"

    /**
     * [close] means the owner's relationship with this contact is intimate enough
     * that warm, affectionate replies are appropriate. Off by default: a bot that
     * assumes closeness with everyone is far worse than one that assumes it with
     * nobody.
     *
     * [key] is null for an entry that has not been tied to a conversation yet.
     */
    data class Entry(
        val name: String,
        val context: String,
        val close: Boolean = false,
        val key: String? = null
    )

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
                        o.optBoolean("close", false),
                        o.optString("key").trim().takeIf { it.isNotEmpty() }
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
            val o = JSONObject()
                .put("name", e.name)
                .put("context", e.context)
                .put("close", e.close)
            // Only written when known — an absent key keeps old snapshots readable.
            e.key?.let { o.put("key", it) }
            arr.put(o)
        }
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * Notes for one conversation. Falls back to the display name only for an entry
     * that has not been adopted yet, so a name that merely looks similar can never
     * pull in somebody else's notes.
     */
    fun forContact(ctx: Context, key: ConversationKey?, contact: String): String =
        match(ctx, key, contact)?.context ?: ""

    /** Whether this contact is one the owner is close to. */
    fun isClose(ctx: Context, key: ConversationKey?, contact: String): Boolean =
        match(ctx, key, contact)?.close == true

    /**
     * Tie a legacy name-only entry to its conversation, once, when that conversation is
     * first seen. Idempotent and best-effort: a failure leaves the entry usable by name.
     */
    fun adoptLegacy(ctx: Context, key: ConversationKey, name: String) {
        if (name.isBlank()) return
        try {
            val all = all(ctx)
            val target = all.firstOrNull { it.key == null && it.name.trim().equals(name.trim(), true) }
                ?: return
            val updated = all.map {
                if (it === target) it.copy(key = key.storageKey) else it
            }
            save(ctx, updated)
        } catch (e: Exception) {
            // Nothing here is worth failing a reply over.
        }
    }

    private fun match(ctx: Context, key: ConversationKey?, contact: String): Entry? {
        val entries = all(ctx)

        // The key is the identity; honour it first and exclusively.
        if (key != null) {
            entries.firstOrNull { it.key == key.storageKey }?.let { return it }
            // Adopt a name-only entry for this person, then use it.
            adoptLegacy(ctx, key, contact)
            return all(ctx).firstOrNull { it.key == key.storageKey }
        }

        // No conversation key (the settings screen works from a typed name): an exact
        // name match is the only defensible lookup. Substring matching here is what let
        // "Ali" pick up "Alim"'s notes.
        val c = contact.trim().lowercase()
        if (c.isEmpty()) return null
        return entries.firstOrNull { it.key == null && it.name.lowercase() == c }
    }
}
