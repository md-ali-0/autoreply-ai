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

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<AiProvider> {
        val raw = sp(ctx).getString(KEY_LIST, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = mutableListOf<AiProvider>()
        for (i in 0 until arr.length()) {
            out.add(AiProvider.fromJson(arr.getJSONObject(i)))
        }
        return out
    }

    fun save(ctx: Context, list: List<AiProvider>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun selectedIndex(ctx: Context): Int {
        return sp(ctx).getInt(KEY_SELECTED, 0).coerceAtLeast(0)
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
