package com.claw.autoreplyai

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted storage for the handful of values that are genuinely secret — AI provider
 * API keys, the transcription key, and the cloud backup token.
 *
 * Everything else stays in ordinary `SharedPreferences`, which is a deliberate split.
 * Preferences are readable by anyone with the unlocked device (and, for this app, by
 * `adb shell run-as` while it is debuggable). That is an acceptable trade for a theme
 * colour. It is not acceptable for a key that can spend the user's money, or for a
 * token that can pull down every backup of their chat history.
 *
 * The encryption key lives in the Android Keystore, which is hardware-backed on most
 * modern devices, and never leaves it — this class only ever asks the Keystore to
 * encrypt or decrypt, and stores the resulting ciphertext. An attacker who copies the
 * preferences file off the device gets ciphertext with no way to derive the key.
 *
 * A secret stored here decrypts to null if the Keystore entry is gone (factory reset,
 * app data cleared, or a Keystore invalidation). Callers must treat null as "not
 * configured" rather than crashing — the user simply re-enters the key.
 */
object SecurePrefs {

    private const val FILE = "secure_prefs"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY = "autoreplyai_secrets_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /**
     * Per-value IV. GCM is catastrophically broken if an IV is ever reused with the
     * same key, so the IV is generated fresh by the cipher and stored alongside the
     * ciphertext, prefixed — never fixed, never derived.
     */
    private const val IV_BYTES = 12

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun masterKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(MASTER_KEY, null) as? KeyStore.SecretKeyEntry
        existing?.secretKey ?: createMasterKey()
    } catch (e: Throwable) {
        null
    }

    private fun createMasterKey(): SecretKey? = try {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // Deliberately not requiring user authentication: this app must reply
            // while the phone is locked and nobody is there to unlock anything.
            .setUserAuthenticationRequired(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) gen.init(spec)
        gen.generateKey()
    } catch (e: Throwable) {
        null
    }

    /**
     * Store a secret. Passing blank removes it. Returns false when the Keystore is
     * unavailable, and the caller must then decide what to do — writing plaintext as a
     * "fallback" would defeat the entire point of this class.
     */
    fun put(ctx: Context, name: String, value: String): Boolean {
        if (value.isBlank()) {
            sp(ctx).edit().remove(name).apply()
            return true
        }
        return try {
            val key = masterKey() ?: return false
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                    ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
            sp(ctx).edit().putString(name, packed).apply()
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Read a secret. Returns null when unset *or* undecryptable — the two are the same
     * thing to every caller, because both mean "ask the user to re-enter it".
     */
    fun get(ctx: Context, name: String): String? {
        val packed = sp(ctx).getString(name, null) ?: return null
        return try {
            val parts = packed.split(":", limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            if (iv.size != IV_BYTES) return null
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val key = masterKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Throwable) {
            null
        }
    }

    fun has(ctx: Context, name: String): Boolean = get(ctx, name)?.isNotBlank() == true

    fun remove(ctx: Context, name: String) {
        sp(ctx).edit().remove(name).apply()
    }

    /**
     * Every stored secret whose name begins with [prefix].
     *
     * Exists so a caller holding a set of *live* names can find the ones left behind.
     * The provider store needs this after a delete or a restore: its secrets are named
     * by a generated id, so there is no arithmetic that reaches "the names past the
     * end of the list" — the only way to find an orphan is to enumerate what is
     * actually there.
     */
    fun namesWithPrefix(ctx: Context, prefix: String): List<String> =
        try {
            sp(ctx).all.keys.filter { it.startsWith(prefix) }
        } catch (e: Throwable) {
            emptyList()
        }

    /**
     * One-time move of a value from plaintext preferences into encrypted storage.
     * Idempotent: once the encrypted copy exists the plaintext one is dropped, and a
     * second call does nothing.
     */
    fun migrate(ctx: Context, name: String, plaintext: String) {
        if (plaintext.isBlank()) return
        if (has(ctx, name)) return
        if (put(ctx, name, plaintext)) {
            LogStore.add(ctx, "সিক্রেট সুরক্ষিত স্টোরেজে সরানো হলো — $name")
        }
    }

    /** Names used by the app; keep them in one place so a typo cannot silently miss. */
    object Names {
        const val API_KEY = "apiKey"
        const val TRANSCRIBE_API_KEY = "transcribeApiKey"
        const val CLOUD_TOKEN = "cloudToken"
    }
}
