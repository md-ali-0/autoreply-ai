package com.claw.autoreplyai

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ring buffer of human readable log lines, persisted so it survives process death.
 */
object LogStore {

    private const val FILE = "reply_logs"
    private const val KEY = "logs"
    private const val MAX = 200

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun add(ctx: Context, line: String) {
        val ts = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(Date())
        val list = read(ctx).toMutableList()
        list.add(0, "[$ts] $line")
        while (list.size > MAX) list.removeAt(list.size - 1)
        val arr = JSONArray()
        for (s in list) arr.put(s)
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun read(ctx: Context): List<String> {
        val raw = sp(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) out.add(arr.optString(i))
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove(KEY).apply()
    }
}
