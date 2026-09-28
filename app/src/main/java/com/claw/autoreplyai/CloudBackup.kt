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

    /**
     * Ceiling on a downloaded body.
     *
     * `resp.body?.string()` will allocate whatever the server sends. A backup of a
     * heavily-used device with a long chat history is legitimately large, so this is
     * generous — but it is still a ceiling, and it matches the reasoning the chat
     * client uses at [AiClient.MAX_RESPONSE_BYTES]. Without one, a wrong server or a
     * hostile response could OOM the app during a restore.
     */
    private const val MAX_DOWNLOAD_BYTES = 16_000_000L

    /**
     * Read at most [limit] bytes from a response body, then fail loudly rather than
     * silently truncating — a half-read backup would otherwise look like a valid file.
     */
    private fun readBounded(body: okhttp3.ResponseBody?, limit: Long): String {
        if (body == null) return ""
        val stream = body.byteStream()
        val buf = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            total += n
            if (total > limit) throw java.io.IOException("সার্ভার অনেক বড় রেসপন্স পাঠিয়েছে")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Send the current snapshot, encrypted with the user's passphrase. */
    fun upload(ctx: Context): Outcome {
        val p = Prefs.get(ctx)
        if (!p.cloudConfigured()) {
            return Outcome(false, "সার্ভার আর টোকেন আগে দিন")
        }
        // A backup without a passphrase would be plaintext on somebody else's disk,
        // holding every per-contact context and the whole chat memory. Refuse instead.
        if (p.cloudPassphrase.isBlank()) {
            return Outcome(false, "ক্লাউডে রাখতে আগে একটা পাসফ্রেজ ঠিক করুন")
        }

        return try {
            val snapshot = Backup.build(ctx)
            val payload = JSONObject()
                .put("label", "autoreply")
                .put("encrypted", true)
                .put("payload", BackupCrypto.encrypt(snapshot, p.cloudPassphrase))

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
                    if (unchanged) "আগেরটাই আছে, নতুন কিছু নেই ($kb KB, এনক্রিপ্টেড)"
                    else "আপলোড হয়েছে ✓ ($kb KB, এনক্রিপ্টেড)"
                )
            }
        } catch (e: Exception) {
            Outcome(false, "আপলোড ব্যর্থ: ${e.message}")
        }
    }

    /** Fetch the newest snapshot, decrypt it, and restore it over the current state. */
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
                val raw = readBounded(resp.body, MAX_DOWNLOAD_BYTES)
                if (!resp.isSuccessful) {
                    return Outcome(false, describe(resp.code, raw))
                }

                val stored = JSONObject(raw).optString("payload", "")
                if (stored.isBlank()) {
                    return Outcome(false, "সার্ভারে ব্যাকআপ খালি")
                }

                // Decrypt first, then verify, then restore — a truncated download or a
                // wrong passphrase must not be allowed to overwrite good local data.
                val payload = if (BackupCrypto.isEncrypted(stored)) {
                    if (p.cloudPassphrase.isBlank()) {
                        return Outcome(false, "এই ব্যাকআপ এনক্রিপ্টেড — পাসফ্রেজ দরকার")
                    }
                    try {
                        BackupCrypto.decrypt(stored, p.cloudPassphrase)
                    } catch (e: BackupCrypto.WrongPassphraseException) {
                        return Outcome(false, "পাসফ্রেজ মেলেনি, তাই কিছু ফিরিয়ে আনা হয়নি")
                    }
                } else {
                    stored
                }

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
