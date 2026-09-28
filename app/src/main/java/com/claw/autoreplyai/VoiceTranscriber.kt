package com.claw.autoreplyai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Turns an incoming voice note into text.
 *
 * The hard part is not the API call, it is getting the audio: the app only ever
 * sees the notification, whose text is just "🎤 Voice message". WhatsApp happens
 * to keep its voice notes in a folder that is readable with the audio permission
 * (`Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/…`), so the
 * newest file written in the last couple of minutes is, in practice, the one that
 * just arrived.
 *
 * This is deliberately best-effort: if the permission is missing, the folder is
 * empty, the file is stale or the provider has no `/audio/transcriptions`
 * endpoint, [transcribe] returns null and the caller falls back to its normal
 * behaviour instead of failing the whole reply.
 */
object VoiceTranscriber {

    /** A voice note older than this is treated as somebody else's, not ours. */
    private const val FRESH_MS = 150_000L

    /** How deep to walk inside the voice-notes folder (it is grouped by month). */
    private const val MAX_DEPTH = 3

    private val AUDIO_EXT = setOf("opus", "ogg", "m4a", "aac", "mp3", "amr", "wav")

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------- detection

    /**
     * True when a notification body is a voice-note placeholder rather than text
     * the model could answer. Kept narrow so a message that merely mentions a
     * voice note ("ভয়েস দিয়েছিলাম") is not swallowed.
     */
    fun looksLikeVoiceNote(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase().trim()

        if (t == "🎤 voice message" || t == "voice message") return true
        if (t.startsWith("🎤 voice message")) return true
        if (t.startsWith("voice message (")) return true
        if (t.startsWith("voice message ·")) return true
        if (t.startsWith("sent a voice message")) return true
        if (t.startsWith("🎤 ভয়েস মেসেজ")) return true
        if (t.startsWith("ভয়েস মেসেজ (")) return true
        return false
    }

    /**
     * True when the app can actually reach the voice-note files.
     *
     * `READ_MEDIA_AUDIO` is NOT enough on Android 11+: it grants MediaStore access,
     * and WhatsApp's voice notes are invisible to MediaStore because their folder
     * carries a `.nomedia` file. Reaching them means reading another app's
     * `Android/media` directory directly, which needs "All files access"
     * ([Environment.isExternalStorageManager]).
     */
    fun hasVoiceAccess(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                Environment.isExternalStorageManager()
            } catch (e: Exception) {
                false
            }
        }
        val perm = Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** Kept for older devices, where plain storage permission is what matters. */
    fun hasAudioPermission(ctx: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Explains, in the app log, exactly which step of voice-note discovery is
     * failing. Called from the Logs tab's পরীক্ষা button — a silent skip is
     * impossible to debug from the outside.
     */
    fun diagnose(ctx: Context) {
        val access = hasVoiceAccess(ctx)
        val audio = hasAudioPermission(ctx)
        LogStore.add(
            ctx,
            "ভয়েস ডায়াগনোসিস: সব-ফাইল অনুমতি=${if (access) "আছে ✓" else "নেই ✗"}, " +
                    "অডিও অনুমতি=${if (audio) "আছে" else "নেই"}, SDK=${Build.VERSION.SDK_INT}"
        )

        val roots = voiceNoteRoots()
        if (roots.isEmpty()) {
            LogStore.add(ctx, "ভয়েস ডায়াগনোসিস: WhatsApp ভয়েস ফোল্ডার খুঁজেই পাওয়া যায়নি")
            return
        }
        for (root in roots) {
            val children = try {
                root.listFiles()
            } catch (e: Exception) {
                LogStore.add(ctx, "ভয়েস ডায়াগনোসিস: ${root.path} — ত্রুটি: ${e.message}")
                null
            }
            if (children == null) {
                LogStore.add(ctx, "ভয়েস ডায়াগনোসিস: ${root.path} — পড়া যায়নি (অনুমতি দিন)")
                continue
            }
            val newest = newestUnder(root)
            LogStore.add(
                ctx,
                "ভয়েস ডায়াগনোসিস: ${root.path} — ${children.size} আইটেম, " +
                        "নতুন ফাইল=${newest?.name ?: "নেই"}" +
                        (newest?.let { ", ${(System.currentTimeMillis() - it.lastModified()) / 1000}সেক আগে" } ?: "")
            )
        }
    }

    // ------------------------------------------------------------ transcribe

    /**
     * Returns the transcript, or null when it could not be produced.
     * Never throws — the caller is a reply pipeline, not an error handler.
     */
    fun transcribe(ctx: Context, p: Prefs, pkg: String, postTime: Long = 0L): String? {
        if (!MessagingApps.canTranscribeVoice(pkg)) {
            LogStore.add(ctx, "ভয়েস ট্রান্সক্রিপশন: ${MessagingApps.label(pkg)}-এর অডিও পড়া যায় না")
            return null
        }
        if (!hasVoiceAccess(ctx)) {
            LogStore.add(
                ctx,
                "ভয়েস ট্রান্সক্রিপশন: 'সব ফাইল পড়ার অনুমতি' নেই — অনুমতি ট্যাব থেকে চালু করুন"
            )
            return null
        }

        val file = pickVoiceNote(postTime) ?: run {
            LogStore.add(ctx, "ভয়েস ট্রান্সক্রিপশন: নতুন ভয়েস ফাইল পাওয়া যায়নি (পরীক্ষা বোতাম চাপুন)")
            return null
        }

        return try {
            val text = upload(p, file)
            if (text.isBlank()) {
                LogStore.add(ctx, "ভয়েস ট্রান্সক্রিপশন: খালি ফলাফল")
                null
            } else {
                text
            }
        } catch (e: Exception) {
            LogStore.add(ctx, "ভয়েস ট্রান্সক্রিপশন ব্যর্থ: ${e.message}")
            null
        }
    }

    /**
     * Same as [transcribe], but ignores the freshness window — for the headless
     * self test, where the newest voice note on the phone is almost certainly older
     * than [FRESH_MS] and would otherwise be rejected.
     */
    fun transcribeForTest(ctx: Context, p: Prefs, pkg: String): String? {
        val file = newestVoiceNote(ignoreFreshness = true)
        if (file == null) {
            LogStore.add(ctx, "ভয়েস টেস্ট: কোনো ভয়েস ফাইল পাওয়া যায়নি")
            return null
        }
        LogStore.add(ctx, "ভয়েস টেস্ট: ফাইল=${file.name} (${file.length()} বাইট)")
        return try {
            upload(p, file).ifBlank { null }
        } catch (e: Exception) {
            LogStore.add(ctx, "ভয়েস টেস্ট ব্যর্থ: ${e.message}")
            null
        }
    }

    private fun upload(p: Prefs, file: File): String {
        // Prefer a dedicated transcription endpoint; most cheap chat gateways have
        // no /audio/transcriptions route at all.
        val base = p.transcribeBaseUrl.ifBlank { p.baseUrl }
        val key = p.transcribeApiKey.ifBlank { p.apiKey }
        if (base.isBlank()) throw IOException("API Base URL খালি")
        val model = p.transcribeModel.ifBlank { "whisper-1" }
        val url = base.trim().trimEnd('/') + "/audio/transcriptions"

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaType())
            )
            .apply {
                val lang = p.transcribeLanguage.trim()
                if (lang.isNotEmpty()) addFormDataPart("language", lang)
            }
            .build()

        val builder = Request.Builder().url(url).post(body)
        if (key.isNotBlank()) builder.addHeader("Authorization", "Bearer $key")

        val resp = client.newCall(builder.build()).execute()
        resp.use {
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 404) {
                    throw IOException(
                        "এই প্রোভাইডারে ভয়েস ট্রান্সক্রিপশন নেই (404) — " +
                                "AI ট্যাবে আলাদা ট্রান্সক্রিপশন এন্ডপয়েন্ট ও কী দিন"
                    )
                }
                throw IOException("HTTP ${resp.code} — ${raw.take(180)}")
            }
            // Some gateways return {"text": "..."}, others nest it under a choice.
            val json = JSONObject(raw)
            val direct = json.optString("text", "").trim()
            if (direct.isNotEmpty()) return direct

            val nested = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()
            return nested.orEmpty()
        }
    }

    // --------------------------------------------------------- file discovery

    /** The most recently written voice note, if one landed in the last [FRESH_MS]. */
    private fun newestVoiceNote(ignoreFreshness: Boolean = false): File? {
        val all = ArrayList<File>()
        for (root in voiceNoteRoots()) walk(root, 0) { all.add(it) }
        if (all.isEmpty()) return null
        val newest = all.maxByOrNull { it.lastModified() } ?: return null
        if (ignoreFreshness) return newest
        val age = System.currentTimeMillis() - newest.lastModified()
        return if (age in 0..FRESH_MS) newest else null
    }

    /**
     * The voice note that belongs to this notification. Two notes arriving together
     * used to be indistinguishable, and one chat could be answered with another
     * chat's audio.
     */
    private fun pickVoiceNote(postTime: Long): File? {
        val all = ArrayList<File>()
        for (root in voiceNoteRoots()) walk(root, 0) { all.add(it) }
        if (all.isEmpty()) return null
        return MediaMatcher.pick(
            candidates = all,
            postTime = postTime,
            mtimeOf = { it.lastModified() },
            keyOf = { "${it.path}@${it.lastModified()}" }
        )
    }

    /** Newest audio file anywhere under [root], or null when it cannot be read. */
    private fun newestUnder(root: File): File? {
        var newest: File? = null
        var newestAt = 0L
        walk(root, 0) { f ->
            val at = f.lastModified()
            if (at > newestAt) {
                newestAt = at
                newest = f
            }
        }
        return newest
    }

    private fun voiceNoteRoots(): List<File> {
        val base = Environment.getExternalStorageDirectory() ?: return emptyList()
        val candidates = listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Voice Notes",
            "WhatsApp/Media/WhatsApp Voice Notes",
            "WhatsApp Business/Media/WhatsApp Voice Notes"
        )
        return candidates.map { File(base, it) }.filter { it.isDirectory }
    }

    private fun walk(dir: File, depth: Int, onFile: (File) -> Unit) {
        if (depth > MAX_DEPTH) return
        val children = try {
            dir.listFiles() ?: return
        } catch (e: Exception) {
            return
        }
        for (f in children) {
            if (f.isDirectory) {
                walk(f, depth + 1, onFile)
            } else if (f.extension.lowercase() in AUDIO_EXT) {
                onFile(f)
            }
        }
    }
}
