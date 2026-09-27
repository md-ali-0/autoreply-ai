package com.claw.autoreplyai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Whole-app snapshot: settings, per-contact contexts and chat memory.
 *
 * The contexts are the part that cannot be recreated, so a backup deliberately
 * captures everything rather than a curated subset.
 */
object Backup {

    const val SUGGESTED_NAME = "autoreply-backup.json"
    private const val APP_TAG = "AutoReplyAI"

    fun build(ctx: Context): String {
        val p = Prefs.get(ctx)
        val root = JSONObject()
        root.put("app", APP_TAG)
        root.put("version", 1)
        root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        root.put("settings", p.exportJson())

        val arr = JSONArray()
        for (e in ContactContext.all(ctx)) {
            arr.put(JSONObject().put("name", e.name).put("context", e.context))
        }
        root.put("contexts", arr)
        root.put("memory", ChatMemory.snapshot(ctx))
        return root.toString(2)
    }

    /** Parses a snapshot back and describes it. Throws if the file is not readable. */
    fun verify(raw: String): String {
        val root = JSONObject(raw)
        if (root.optString("app") != APP_TAG) {
            throw IllegalArgumentException("ব্যাকআপ ফাইলের গঠন ঠিক নয়")
        }
        val settings = root.optJSONObject("settings")?.length() ?: 0
        val contexts = root.optJSONArray("contexts")?.length() ?: 0
        val memory = root.optJSONObject("memory")?.length() ?: 0
        return "$settings টা সেটিংস, $contexts টা কন্টাক্ট কন্টেক্সট, $memory টা মেমোরি"
    }

    /** Restores a snapshot and returns a short summary. Throws on a foreign file. */
    fun restore(ctx: Context, raw: String): String {
        val root = JSONObject(raw)
        if (root.optString("app") != APP_TAG) {
            throw IllegalArgumentException("এই ফাইলটা AutoReply AI-র ব্যাকআপ নয়")
        }

        root.optJSONObject("settings")?.let { Prefs.get(ctx).importJson(it) }

        var contacts = 0
        root.optJSONArray("contexts")?.let { arr ->
            val list = ArrayList<ContactContext.Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val body = o.optString("context").trim()
                if (name.isNotEmpty() || body.isNotEmpty()) {
                    list.add(ContactContext.Entry(name, body))
                }
            }
            ContactContext.save(ctx, list)
            contacts = list.size
        }

        root.optJSONObject("memory")?.let { ChatMemory.restore(ctx, it) }

        return "$contacts টা কন্টাক্ট কন্টেক্সট সহ সব ফিরিয়ে আনা হলো"
    }
}
