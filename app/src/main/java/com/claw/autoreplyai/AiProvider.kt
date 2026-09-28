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
     * indexes. Doing this in one pass (rather than a migration per entry) means the
     * plaintext scrub happens once, not once per provider.
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
        var sawPlaintext = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = AiProvider.fromJson(o)
            profiles.add(p)
            val inline = o.optString("apiKey", "")
            legacy.add(inline)
            if (inline.isNotBlank()) sawPlaintext = true
        }

        // 2. Migrate the plaintext keys into SecurePrefs, once.
        if (sawPlaintext) {
            var migrated = 0
            for (i in legacy.indices) {
                val key = legacy[i]
                if (key.isBlank()) continue
                if (SecurePrefs.put(ctx, secretName(i), key)) migrated++
            }
            if (migrated > 0) {
                rewriteWithoutSecrets(ctx)
                LogStore.add(ctx, "প্রোভাইডার key সুরক্ষিত স্টোরেজে সরানো হলো ($migrated টা)")
            }
        }

        // 3. Attach whatever is now in secure storage (migrated or previously stored).
        return profiles.mapIndexed { i, p ->
            p.copy(apiKey = SecurePrefs.get(ctx, secretName(i)).orEmpty())
        }
    }

    /** Rewrite the stored list, dropping any `apiKey` field left by an older build. */
    private fun rewriteWithoutSecrets(ctx: Context) {
        try {
            val raw = sp(ctx).getString(KEY_LIST, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val clean = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                o.remove("apiKey")
                clean.put(o)
            }
            sp(ctx).edit().putString(KEY_LIST, clean.toString()).apply()
        } catch (e: Exception) {
            // Best effort — a failure leaves the list readable, just not yet scrubbed.
        }
    }

    /**
     * Persist the whole list and each key.
     *
     * Called with the in-memory list, which carries keys. The keys go to [SecurePrefs],
     * the rest goes to the plain preference — and every slot is rewritten so a delete or
     * reorder cannot leave a previous provider's key attached to a different profile.
     */
    fun save(ctx: Context, list: List<AiProvider>) {
        // A blocked host can never be written back, whatever the caller passes.
        val clean = list.filterNot { isBlocked(it) }

        val arr = JSONArray()
        clean.forEach { arr.put(it.toJson(includeSecret = false)) }
        sp(ctx).edit().putString(KEY_LIST, arr.toString()).apply()

        for ((i, p) in clean.withIndex()) {
            if (p.apiKey.isBlank()) SecurePrefs.remove(ctx, secretName(i))
            else SecurePrefs.put(ctx, secretName(i), p.apiKey)
        }
        // Clear slots beyond the new end, or a deleted provider's key would linger.
        for (i in clean.size until clean.size + 8) {
            if (!SecurePrefs.has(ctx, secretName(i))) break
            SecurePrefs.remove(ctx, secretName(i))
        }
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
