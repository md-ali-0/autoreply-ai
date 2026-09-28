package com.claw.autoreplyai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved AI provider profile: name + endpoint + key + model.
 */
data class AiProvider(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String
) {
    /**
     * Serialise this profile for storage in the provider list.
     *
     * The API key is deliberately **not** written here by default. The provider list
     * lives in ordinary `SharedPreferences`, which is readable by anything that can
     * read the app's data directory — while `Prefs.apiKey`, `transcribeApiKey` and
     * `cloudToken` all go through [SecurePrefs]. Leaving the per-provider keys in the
     * clear made the protection inconsistent: the same class of secret was encrypted in
     * one place and plaintext in another. `AiProviderStore` now keeps each key in
     * [SecurePrefs] and re-attaches it on read.
     *
     * [includeSecret] remains for one purpose: writing a snapshot that is explicitly
     * meant to carry credentials. Nothing calls it with `true` any more; backups and
     * storage both exclude the key.
     */
    fun toJson(includeSecret: Boolean = false): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("baseUrl", baseUrl)
        if (includeSecret) o.put("apiKey", apiKey)
        o.put("model", model)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): AiProvider {
            return AiProvider(
                name = o.optString("name", ""),
                baseUrl = o.optString("baseUrl", ""),
                apiKey = o.optString("apiKey", ""),
                model = o.optString("model", "")
            )
        }

        val DEFAULT = AiProvider(
            name = "xkiro free",
            baseUrl = "https://api.xkiro.com/v1",
            apiKey = "",
            model = "qwen/qwen3.8-max:free"
        )
    }
}

/**
 * Persists provider profiles as a JSON array in SharedPreferences.
 */
object AiProviderStore {

    private const val PREFS = "ai_providers"
    private const val KEY_LIST = "providers"
    private const val KEY_SELECTED = "selected"

    /**
     * Hosts we never call, even if a profile for one is already on the device or
     * arrives through a restored backup.
     *
     * `apinex.bond` answered HTTP 200 with an empty body six times in a row on
     * 28 Sep and was the only provider that did this. Its failures were invisible
     * in the log (a blank answer is not an exception, so `askAi` never logged it)
     * and it silently burned a retry on every message. Rather than trust a manual
     * delete on one device, the host is blocked here: every read path filters it,
     * and it is stripped the next time the list is written.
     */
    private val BLOCKED_HOSTS = listOf("apinex.bond")

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * SecurePrefs name for one provider's key, addressed by its position in the list.
     *
     * Indexed by position because that is what the whole store is already keyed on —
     * `selected` is an index, and every read path resolves a profile by its slot. A key
     * therefore moves with the profile when the user reorders or deletes, so [save] has
     * to rewrite the whole key set on every write, which it does.
     */
    private fun secretName(index: Int) = "providerApiKey_$index"

    /** True when this profile points at a host we have retired. */
    fun isBlocked(p: AiProvider): Boolean {
        val url = p.baseUrl.lowercase()
        return BLOCKED_HOSTS.any { url.contains(it) }
    }

    fun all(ctx: Context): List<AiProvider> {
        val raw = rawList(ctx)
        return raw.filterNot { isBlocked(it) }
    }

    /**
     * Every stored profile, blocked ones included, with its key attached.
     *
     * The key is read from [SecurePrefs] by the profile's **position in the stored
     * list**, which is the same slot `save` writes to and the same thing `selected`
     * indexes. Legacy plaintext keys (written inline by builds before v1.52.3) are
     * migrated here, one slot at a time. A slot is only scrubbed from the list after its
     * own migration is confirmed, so a partial Keystore failure costs nothing — the
     * un-migrated key stays on disk and is retried on the next read.
     */
    private fun rawList(ctx: Context): List<AiProvider> {
        val raw = sp(ctx).getString(KEY_LIST, "[]") ?: "[]"
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return emptyList()
        }

        // 1. Read every profile and collect any legacy plaintext keys in the same pass.
        val profiles = ArrayList<AiProvider>(arr.length())
        val legacy = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            profiles.add(AiProvider.fromJson(o))
            legacy.add(o.optString("apiKey", ""))
        }

        // 2. Migrate the plaintext keys into SecurePrefs, once.
        //
        // Per-slot, and the scrub is per-slot too. `SecurePrefs.put` returns false when
        // the Keystore is unavailable, and that can happen for one write and not the
        // next. Scrubbing the whole list as soon as *any* write succeeded would delete
        // the plaintext of a slot that was never migrated — the key would be gone from
        // both stores, permanently. So each slot is scrubbed only after its own write
        // was confirmed, and a slot that failed is left exactly as it was for the next
        // attempt.
        val migratedSlots = ArrayList<Int>()
        for (i in legacy.indices) {
            val key = legacy[i]
            if (key.isBlank()) continue
            if (SecurePrefs.put(ctx, secretName(i), key)) migratedSlots.add(i)
        }
        if (migratedSlots.isNotEmpty()) {
            scrubSecrets(ctx, migratedSlots)
            LogStore.add(ctx, "প্রোভাইডার key সুরক্ষিত স্টোরেজে সরানো হলো (${migratedSlots.size} টা)")
        }

        // 3. Attach the key for each profile: the migrated/persisted one if the secure
        // store has it, otherwise the plaintext still sitting in the list. Falling back
        // matters — without it a slot whose migration failed would read back as blank
        // for this whole boot, and the reply pipeline would report "no provider
        // configured" while the key was on disk the entire time.
        return profiles.mapIndexed { i, p ->
            val secure = SecurePrefs.get(ctx, secretName(i))
            p.copy(apiKey = secure ?: legacy[i])
        }
    }

    /**
     * Drop the `apiKey` field from the named slots only.
     *
     * [slots] is the set of indices whose key is now confirmed present in [SecurePrefs].
     * Every other slot keeps its value — see the migration comment in [rawList] for why
     * a blanket scrub is unsafe.
     */
    private fun scrubSecrets(ctx: Context, slots: List<Int>) {
        if (slots.isEmpty()) return
        try {
            val raw = sp(ctx).getString(KEY_LIST, "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in slots) {
                if (i in 0 until arr.length()) arr.getJSONObject(i).remove("apiKey")
            }
            sp(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
        } catch (e: Exception) {
            // Best effort. A failure leaves the list readable and the key still in the
            // secure store, so the worst case is a redundant plaintext copy that the
            // next read will try to scrub again.
        }
    }

    /**
     * Persist the whole list and each key. Returns the number of keys that could **not**
     * be written to secure storage, so the caller can say so.
     *
     * Called with the in-memory list, which carries keys. The keys go to [SecurePrefs],
     * the rest goes to the plain preference — and every slot is rewritten so a delete or
     * reorder cannot leave a previous provider's key attached to a different profile.
     *
     * A failed write is reported rather than swallowed. Losing a key here is the user's
     * own edit not persisting, which is recoverable — but only if they are told.
     */
    fun save(ctx: Context, list: List<AiProvider>): Int {
        // A blocked host can never be written back, whatever the caller passes.
        val clean = list.filterNot { isBlocked(it) }

        val arr = JSONArray()
        clean.forEach { arr.put(it.toJson(includeSecret = false)) }
        sp(ctx).edit().putString(KEY_LIST, arr.toString()).apply()

        var failed = 0
        for ((i, p) in clean.withIndex()) {
            if (p.apiKey.isBlank()) {
                SecurePrefs.remove(ctx, secretName(i))
            } else if (!SecurePrefs.put(ctx, secretName(i), p.apiKey)) {
                failed++
                LogStore.add(ctx, "⚠️ প্রোভাইডার key সংরক্ষণ করা যায়নি (#${i + 1})")
            }
        }
        // Clear slots beyond the new end, or a deleted provider's key would linger.
        for (i in clean.size until clean.size + 8) {
            if (!SecurePrefs.has(ctx, secretName(i))) break
            SecurePrefs.remove(ctx, secretName(i))
        }
        return failed
    }

    private fun rawListStoredIndex(ctx: Context): Int =
        sp(ctx).getInt(KEY_SELECTED, 0).coerceAtLeast(0)

    /**
     * Drops retired hosts from storage. Called from the store's own write path, so a
     * profile that was already on the device disappears the first time any settings
     * screen saves — no manual delete, and nothing to forget on a new handset.
     */
    fun purgeBlocked(ctx: Context) {
        val raw = rawList(ctx)
        val kept = raw.filterNot { isBlocked(it) }
        if (kept.size == raw.size) return

        val stored = rawListStoredIndex(ctx)
        val removedBefore = raw.take(stored).count { isBlocked(it) }
        save(ctx, kept)
        if (kept.isNotEmpty()) {
            setSelected(ctx, (stored - removedBefore).coerceIn(0, kept.lastIndex))
        } else {
            setSelected(ctx, 0)
        }
    }

    fun selectedIndex(ctx: Context): Int {
        // The stored index counts *raw* entries, including any blocked ones. `all()`
        // has already dropped those, so the index has to be shifted by however many
        // were filtered out ahead of the selection — otherwise a blocked provider at
        // slot 0 leaves the selection pointing one entry too far down the list.
        val raw = rawList(ctx)
        val cleanCount = raw.count { !isBlocked(it) }
        if (cleanCount == 0) return 0
        val stored = rawListStoredIndex(ctx)
        val removedBefore = raw.take(stored).count { isBlocked(it) }
        return (stored - removedBefore).coerceIn(0, cleanCount - 1)
    }

    fun setSelected(ctx: Context, index: Int) {
        sp(ctx).edit().putInt(KEY_SELECTED, index.coerceAtLeast(0)).apply()
    }

    /**
     * Returns providers ordered by priority: the selected one first,
     * then the rest. Used for auto-fallback when the primary fails.
     */
    fun prioritized(ctx: Context): List<AiProvider> {
        val list = all(ctx)
        if (list.isEmpty()) return emptyList()
        val sel = selectedIndex(ctx).coerceIn(0, list.lastIndex)
        val ordered = mutableListOf<AiProvider>()
        ordered.add(list[sel])
        for (i in list.indices) {
            if (i != sel) ordered.add(list[i])
        }
        return ordered
    }

    /** Migrate from legacy single-provider prefs if providers list is empty. */
    fun migrateIfNeeded(ctx: Context, p: Prefs) {
        if (all(ctx).isNotEmpty()) return
        val list = mutableListOf(
            AiProvider(
                name = "xkiro",
                baseUrl = p.baseUrl.ifBlank { "https://api.xkiro.com/v1" },
                apiKey = p.apiKey,
                model = p.model.ifBlank { "qwen/qwen3.8-max:free" }
            )
        )
        save(ctx, list)
        setSelected(ctx, 0)
    }
}
