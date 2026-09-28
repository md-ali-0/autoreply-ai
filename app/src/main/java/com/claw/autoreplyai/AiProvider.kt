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
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("baseUrl", baseUrl)
        o.put("apiKey", apiKey)
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

    /** True when this profile points at a host we have retired. */
    fun isBlocked(p: AiProvider): Boolean {
        val url = p.baseUrl.lowercase()
        return BLOCKED_HOSTS.any { url.contains(it) }
    }

    fun all(ctx: Context): List<AiProvider> {
        val raw = sp(ctx).getString(KEY_LIST, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<AiProvider>()
        for (i in 0 until arr.length()) {
            val p = AiProvider.fromJson(arr.getJSONObject(i))
            if (!isBlocked(p)) out.add(p)
        }
        return out
    }

    /** Every stored profile, blocked ones included. Used only for index arithmetic. */
    private fun rawList(ctx: Context): List<AiProvider> {
        val raw = sp(ctx).getString(KEY_LIST, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<AiProvider>()
        for (i in 0 until arr.length()) {
            out.add(AiProvider.fromJson(arr.getJSONObject(i)))
        }
        return out
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

    fun save(ctx: Context, list: List<AiProvider>) {
        // A blocked host can never be written back, whatever the caller passes.
        val clean = list.filterNot { isBlocked(it) }
        val arr = JSONArray()
        clean.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
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
