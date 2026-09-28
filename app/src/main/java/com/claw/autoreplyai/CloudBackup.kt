package com.claw.autoreplyai

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Uploads snapshots to the backup API and pulls them back after a reset.
 *
 * The phone stays the source of truth: this is disaster recovery, not sync. Nothing
 * here runs unless the user configured a server and token, and a failure never
 * touches local data.
 *
 * Callers must run these off the main thread — both methods block on the network.
 */
object CloudBackup {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Outcome(val ok: Boolean, val message: String)

    /** Send the current snapshot. */
    fun upload(ctx: Context): Outcome {
        val p = Prefs.get(ctx)
        if (!p.cloudConfigured()) {
            return Outcome(false, "সার্ভার আর টোকেন আগে দিন")
        }

        return try {
            val snapshot = Backup.build(ctx)
            val payload = JSONObject()
                .put("label", "autoreply")
                .put("payload", snapshot)

            val request = Request.Builder()
                .url(p.cloudUrl + "/backups")
                .addHeader("Authorization", "Bearer ${p.cloudToken}")
                .post(
                    payload.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                .build()

            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Outcome(false, describe(resp.code, raw))
                }

                p.cloudLastUpload = System.currentTimeMillis()
                val unchanged = JSONObject(raw).optBoolean("unchanged", false)
                val kb = snapshot.toByteArray(Charsets.UTF_8).size / 1024
                Outcome(
                    true,
                    if (unchanged) "আগেরটাই আছে, নতুন কিছু নেই ($kb KB)"
                    else "আপলোড হয়েছে ✓ ($kb KB)"
                )
            }
        } catch (e: Exception) {
            Outcome(false, "আপলোড ব্যর্থ: ${e.message}")
        }
    }

    /** Fetch the newest snapshot and restore it over the current state. */
    fun download(ctx: Context): Outcome {
        val p = Prefs.get(ctx)
        if (!p.cloudConfigured()) {
            return Outcome(false, "সার্ভার আর টোকেন আগে দিন")
        }

        return try {
            val request = Request.Builder()
                .url(p.cloudUrl + "/backups/latest")
                .addHeader("Authorization", "Bearer ${p.cloudToken}")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Outcome(false, describe(resp.code, raw))
                }

                val payload = JSONObject(raw).optString("payload", "")
                if (payload.isBlank()) {
                    return Outcome(false, "সার্ভারে ব্যাকআপ খালি")
                }

                // Verify before touching anything: a truncated download must not be
                // allowed to overwrite good local data.
                val summary = Backup.verify(payload)
                val restored = Backup.restore(ctx, payload)
                Outcome(true, "$restored\n($summary)")
            }
        } catch (e: Exception) {
            Outcome(false, "ফিরিয়ে আনা যায়নি: ${e.message}")
        }
    }

    /** Turns an HTTP failure into something worth reading in a toast. */
    private fun describe(code: Int, raw: String): String = when (code) {
        401 -> "টোকেন ভুল বা বাতিল করা হয়েছে (401)"
        404 -> "সার্ভারে এখনো কোনো ব্যাকআপ নেই (404)"
        422 -> "সার্ভার ডেটা নেয়নি (422)"
        else -> "সার্ভার বলছে HTTP $code — ${raw.take(120)}"
    }
}
