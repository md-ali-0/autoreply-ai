package com.claw.autoreplyai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Reads the photo behind a "[Photo]" notification so a vision model can see it.
 *
 * Same shape of problem as voice notes, and the same solution: the app only ever
 * gets a placeholder string, and the file has to be found on disk in WhatsApp's
 * `Android/media` tree — which needs "All files access", not a media permission.
 *
 * The image is downscaled and re-encoded before upload. A modern phone photo is
 * several megabytes, and base64 inflates that by a third; a 1024px JPEG keeps the
 * request small without losing anything a chat reply needs to notice.
 */
object ImageReader {

    /** Older than this and it belongs to an earlier conversation. */
    private const val FRESH_MS = 150_000L

    /** Longest edge after downscaling. */
    private const val MAX_DIM = 1024

    private const val JPEG_QUALITY = 80

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    // ------------------------------------------------------------- detection

    /**
     * True when the notification body is a photo placeholder rather than text.
     * Anchored on the whole string so a message that merely mentions a photo
     * ("ছবিটা দেখো") is not mistaken for one.
     */
    fun looksLikePhoto(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.lowercase().trim()

        if (t == "photo" || t == "[photo]" || t == "[image]") return true
        if (t.startsWith("📷 sent a photo")) return true
        if (t.startsWith("📷 photo")) return true
        if (t.startsWith("📸 photo")) return true
        if (t.startsWith("sent a photo")) return true
        if (t.startsWith("sent an image")) return true
        if (t.startsWith("🖼")) return true
        if (t.contains("ছবি পাঠিয়েছ")) return true
        if (t.startsWith("📷 একটি ছবি")) return true
        return false
    }

    // ------------------------------------------------------------------ load

    /**
     * The newest incoming photo as base64 JPEG, or null when there is none to read.
     * Never throws — the caller is a reply pipeline, not an error handler.
     */
    fun loadLatestBase64(ctx: Context, ignoreFreshness: Boolean = false): String? {
        val file = newestImage(ignoreFreshness) ?: return null
        return try {
            val bytes = downscale(file)
            if (bytes == null) {
                LogStore.add(ctx, "ছবি: ডিকোড করা যায়নি — ${file.name}")
                null
            } else {
                LogStore.add(ctx, "ছবি: ${file.name} (${file.length() / 1024}KB → ${bytes.size / 1024}KB)")
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            LogStore.add(ctx, "ছবি পড়া যায়নি: ${e.message}")
            null
        }
    }

    /** Explains which discovery step is failing — mirrors the voice diagnostic. */
    fun diagnose(ctx: Context) {
        val roots = imageRoots()
        if (roots.isEmpty()) {
            LogStore.add(ctx, "ছবি ডায়াগনোসিস: WhatsApp ছবির ফোল্ডার খুঁজেই পাওয়া যায়নি")
            return
        }
        for (root in roots) {
            val children = try {
                root.listFiles()
            } catch (e: Exception) {
                LogStore.add(ctx, "ছবি ডায়াগনোসিস: ${root.path} — ত্রুটি: ${e.message}")
                null
            }
            if (children == null) {
                LogStore.add(ctx, "ছবি ডায়াগনোসিস: ${root.path} — পড়া যায়নি (সব-ফাইল অনুমতি দিন)")
                continue
            }
            val newest = newestUnder(root)
            LogStore.add(
                ctx,
                "ছবি ডায়াগনোসিস: ${root.path} — ${children.size} আইটেম, " +
                        "নতুন ছবি=${newest?.name ?: "নেই"}" +
                        (newest?.let { ", ${(System.currentTimeMillis() - it.lastModified()) / 1000}সেক আগে" } ?: "")
            )
        }
    }

    // ------------------------------------------------------------ file search

    private fun newestImage(ignoreFreshness: Boolean): File? {
        var newest: File? = null
        var newestAt = 0L
        for (root in imageRoots()) {
            val hit = newestUnder(root) ?: continue
            val at = hit.lastModified()
            if (at > newestAt) {
                newestAt = at
                newest = hit
            }
        }
        val hit = newest ?: return null
        if (ignoreFreshness) return hit
        val age = System.currentTimeMillis() - newestAt
        return if (age in 0..FRESH_MS) hit else null
    }

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

    private fun imageRoots(): List<File> {
        val base = Environment.getExternalStorageDirectory() ?: return emptyList()
        val candidates = listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Images",
            "WhatsApp/Media/WhatsApp Images",
            "WhatsApp Business/Media/WhatsApp Images"
        )
        return candidates.map { File(base, it) }.filter { it.isDirectory }
    }

    private fun walk(dir: File, depth: Int, onFile: (File) -> Unit) {
        if (depth > 2) return
        val children = try {
            dir.listFiles() ?: return
        } catch (e: Exception) {
            return
        }
        for (f in children) {
            if (f.isDirectory) {
                // "Sent" holds pictures *we* sent — replying to those makes no sense.
                if (f.name.equals("Sent", true)) continue
                walk(f, depth + 1, onFile)
            } else if (f.extension.lowercase() in IMAGE_EXT) {
                onFile(f)
            }
        }
    }

    // ------------------------------------------------------------- downscale

    private fun downscale(file: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_DIM || bounds.outHeight / sample > MAX_DIM) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        return try {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }
}
