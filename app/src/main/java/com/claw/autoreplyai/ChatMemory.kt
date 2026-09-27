package com.claw.autoreplyai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Short rolling conversation memory, one bucket per contact.
 * Gives the AI enough context to reply coherently.
 */
object ChatMemory {

    private const val FILE = "chat_memory"
    private const val MAX_TURNS = 20

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun keyFor(contact: String) = "h_" + contact.lowercase().hashCode().toString()

    fun history(ctx: Context, contact: String): List<AiClient.Msg> {
        val raw = sp(ctx).getString(keyFor(contact), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<AiClient.Msg>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(AiClient.Msg(o.optString("role", "user"), o.optString("content", "")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(ctx: Context, contact: String, role: String, content: String) {
        val list = history(ctx, contact).toMutableList()
        list.add(AiClient.Msg(role, content))
        while (list.size > MAX_TURNS * 2) list.removeAt(0)
        val arr = JSONArray()
        for (m in list) arr.put(JSONObject().put("role", m.role).put("content", m.content))
        sp(ctx).edit().putString(keyFor(contact), arr.toString()).apply()
    }

    fun clear(ctx: Context, contact: String) {
        sp(ctx).edit().remove(keyFor(contact)).apply()
    }

    fun clearAll(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }

    /** How many messages are remembered for this contact (user + assistant). */
    fun count(ctx: Context, contact: String): Int = history(ctx, contact).size

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
