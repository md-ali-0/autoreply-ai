package com.claw.autoreplyai

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase encryption for the cloud backup payload.
 *
 * The backup contains every per-contact context and the whole chat memory — the most
 * personal data the app holds, and the one part that cannot be recreated. The backup
 * server is somebody else's machine, so it stores ciphertext and nothing else: the
 * passphrase is never uploaded and never leaves the device.
 *
 * This is deliberately *not* [SecurePrefs]. That class protects a value *at rest on
 * this device* with a Keystore key that cannot leave the hardware, which is the wrong
 * tool here: a backup whose key exists only on the phone it backs up is worthless
 * after that phone is lost. A passphrase the user remembers is the only key that
 * survives the disaster the backup is for.
 *
 * PBKDF2-HMAC-SHA256 at 120k iterations, then AES-256-GCM. GCM gives both
 * confidentiality and a tag that fails loudly on the wrong passphrase, so a bad
 * passphrase surfaces as an error rather than as silently garbage data.
 */
object BackupCrypto {

    /** Marks the payload shape so an old plaintext backup is still readable. */
    private const val MARKER = "arai-enc-v1"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    private val rng = SecureRandom()

    fun isEncrypted(payload: String): Boolean =
        payload.trimStart().startsWith("{") &&
                runCatching { JSONObject(payload).optString("enc") }.getOrNull() == MARKER

    /**
     * Wrap [plaintext] in an encrypted envelope. Returns a self-describing JSON object
     * so the server never has to know anything about the format.
     */
    fun encrypt(plaintext: String, passphrase: String): String {
        val salt = ByteArray(SALT_BYTES).also { rng.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("enc", MARKER)
            .put("iter", PBKDF2_ITERATIONS)
            .put("salt", b64(salt))
            .put("iv", b64(cipher.iv))
            .put("ct", b64(ct))
            .toString()
    }

    /**
     * Unwrap an envelope. Throws [WrongPassphraseException] when the tag does not
     * verify, so the caller can say something useful instead of "restore failed".
     */
    fun decrypt(payload: String, passphrase: String): String {
        val o = JSONObject(payload)
        if (o.optString("enc") != MARKER) throw IllegalArgumentException("এনক্রিপ্টেড ব্যাকআপ নয়")
        val iter = o.optInt("iter", PBKDF2_ITERATIONS)
        val salt = Base64.decode(o.optString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(o.optString("iv"), Base64.NO_WRAP)
        val ct = Base64.decode(o.optString("ct"), Base64.NO_WRAP)
        if (iv.size != IV_BYTES || salt.isEmpty() || ct.isEmpty()) {
            throw IllegalArgumentException("ব্যাকআপ ফাইলটা অসম্পূর্ণ")
        }
        try {
            val key = deriveKey(passphrase, salt, iter)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            return String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPassphraseException()
        } catch (e: WrongPassphraseException) {
            throw e
        } catch (e: Exception) {
            // Some providers surface a bad tag as a generic BadPaddingException.
            throw WrongPassphraseException()
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    /** Least-significant base64 with no wrapping, matching [SecurePrefs]'s packing. */
    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    class WrongPassphraseException : Exception("পাসফ্রেজ মেলেনি")
}
