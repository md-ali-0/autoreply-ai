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
        root.put("version", 2)
        root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        root.put("settings", p.exportJson())

        // Provider list lives in a separate prefs file, so it must be captured
        // explicitly — otherwise a restore brings back only the selected one.
        //
        // Secrets are excluded: a backup is a portable file, and `Prefs.exportJson()`
        // above already scrubs its three secret keys. Writing the provider `apiKey`
        // here would have leaked every saved key through the very same file.
        val providerArr = JSONArray()
        for (prov in AiProviderStore.all(ctx)) {
            providerArr.put(prov.toJson(includeSecret = false))
        }
        root.put("providers", providerArr)
        root.put("providerSelected", AiProviderStore.selectedIndex(ctx))

        val arr = JSONArray()
        for (e in ContactContext.all(ctx)) {
            val o = JSONObject()
                .put("name", e.name)
                .put("context", e.context)
                .put("close", e.close)
            e.key?.let { o.put("key", it) }
            arr.put(o)
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
        val providers = root.optJSONArray("providers")?.length() ?: 0
        val contexts = root.optJSONArray("contexts")?.length() ?: 0
        val memory = root.optJSONObject("memory")?.length() ?: 0
        return "$settings টা সেটিংস, $providers টা প্রোভাইডার, $contexts টা কন্টাক্ট কন্টেক্সট, $memory টা মেমোরি"
    }

    /** Restores a snapshot and returns a short summary. Throws on a foreign file. */
    fun restore(ctx: Context, raw: String): String {
        val root = JSONObject(raw)
        if (root.optString("app") != APP_TAG) {
            throw IllegalArgumentException("এই ফাইলটা AutoReply AI-র ব্যাকআপ নয়")
        }

        root.optJSONObject("settings")?.let { Prefs.get(ctx).importJson(it) }

        // Restore the full provider list (v2+). v1 snapshots have no "providers"
        // key, so the existing providers on the device are left untouched.
        //
        // Snapshots carry no `apiKey` (see `build`), so a restored profile arrives with a
        // blank key. Where a provider on the device already matches this one — same name
        // and endpoint — keep the key that is already in secure storage. Otherwise a
        // routine restore would silently wipe every saved key and leave the user
        // wondering why the bot stopped replying. A profile with no local match, or a
        // snapshot from an older build that *does* carry a key, is taken as-is.
        root.optJSONArray("providers")?.let { arr ->
            val existing = AiProviderStore.all(ctx)
            val list = ArrayList<AiProvider>(arr.length())
            for (i in 0 until arr.length()) {
                val incoming = AiProvider.fromJson(arr.getJSONObject(i))
                val match = existing.firstOrNull {
                    it.name == incoming.name && it.baseUrl == incoming.baseUrl
                }
                list.add(
                    if (incoming.apiKey.isBlank() && match != null) {
                        incoming.copy(apiKey = match.apiKey)
                    } else {
                        incoming
                    }
                )
            }
            if (list.isNotEmpty()) {
                AiProviderStore.save(ctx, list)
                AiProviderStore.setSelected(ctx, root.optInt("providerSelected", 0))
            }
        }

        var contacts = 0
        root.optJSONArray("contexts")?.let { arr ->
            val list = ArrayList<ContactContext.Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val body = o.optString("context").trim()
                if (name.isNotEmpty() || body.isNotEmpty()) {
                    // `close` is absent in snapshots written before it existed, so it
                    // defaults to false rather than failing the whole restore. `key`
                    // is likewise optional: a snapshot from an older build has only the
                    // name, and that entry gets adopted on next contact.
                    val k = o.optString("key").trim().takeIf { it.isNotEmpty() }
                    list.add(ContactContext.Entry(name, body, o.optBoolean("close", false), k))
                }
            }
            ContactContext.save(ctx, list)
            contacts = list.size
        }

        root.optJSONObject("memory")?.let { ChatMemory.restore(ctx, it) }

        return "$contacts টা কন্টাক্ট কন্টেক্সট সহ সব ফিরিয়ে আনা হলো"
    }
}
