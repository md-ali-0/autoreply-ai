package com.claw.autoreplyai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import kotlin.random.Random

/**
 * Single source of truth for all user settings.
 */
class Prefs private constructor(ctx: Context) {

    private val ctx: Context = ctx.applicationContext

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- master ----------
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    // ---------- AI ----------
    var baseUrl: String
        get() = sp.getString("baseUrl", DEFAULT_BASE) ?: DEFAULT_BASE
        set(v) = sp.edit().putString("baseUrl", v.trim()).apply()

    /**
     * Keystore-backed. Returns "" when unset or when the Keystore entry is gone,
     * so callers keep their existing "not configured" handling unchanged.
     */
    var apiKey: String
        get() = SecurePrefs.get(ctx, SecurePrefs.Names.API_KEY).orEmpty()
        set(v) { SecurePrefs.put(ctx, SecurePrefs.Names.API_KEY, v.trim()) }

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var persona: String
        get() = sp.getString("persona", DEFAULT_PERSONA) ?: DEFAULT_PERSONA
        set(v) = sp.edit().putString("persona", v.trim()).apply()

    // ---------- triage ----------
    /** Let the model decide whether a message even deserves a reply. */
    var smartTriage: Boolean
        get() = sp.getBoolean("smartTriage", true)
        set(v) = sp.edit().putBoolean("smartTriage", v).apply()

    /** Stay silent on heavy/personal messages and ping the user instead. */
    var holdOnEmotional: Boolean
        get() = sp.getBoolean("holdOnEmotional", true)
        set(v) = sp.edit().putBoolean("holdOnEmotional", v).apply()

    /** Confidentiality rules injected into every prompt, regardless of contact. */
    var safetyRule: String
        get() = sp.getString("safetyRule", DEFAULT_SAFETY) ?: DEFAULT_SAFETY
        set(v) = sp.edit().putString("safetyRule", v.trim()).apply()

    // ---------- which apps to answer ----------
    /** Answer WhatsApp (and WhatsApp Business) messages. */
    var replyWhatsApp: Boolean
        get() = sp.getBoolean("replyWhatsApp", true)
        set(v) = sp.edit().putBoolean("replyWhatsApp", v).apply()

    /** Answer Facebook Messenger messages. */
    var replyMessenger: Boolean
        get() = sp.getBoolean("replyMessenger", true)
        set(v) = sp.edit().putBoolean("replyMessenger", v).apply()

    /** Answer Telegram messages. */
    var replyTelegram: Boolean
        get() = sp.getBoolean("replyTelegram", true)
        set(v) = sp.edit().putBoolean("replyTelegram", v).apply()

    /** Answer Instagram direct messages. */
    var replyInstagram: Boolean
        get() = sp.getBoolean("replyInstagram", true)
        set(v) = sp.edit().putBoolean("replyInstagram", v).apply()

    // ---------- voice notes ----------
    /**
     * Transcribe incoming WhatsApp voice notes and answer the text.
     * Needs the audio permission; silently degrades to the old behaviour when the
     * file cannot be found or the provider has no transcription endpoint.
     */
    var transcribeVoice: Boolean
        get() = sp.getBoolean("transcribeVoice", true)
        set(v) = sp.edit().putBoolean("transcribeVoice", v).apply()

    /** Model used for `/audio/transcriptions` (OpenAI-compatible). */
    var transcribeModel: String
        get() = sp.getString("transcribeModel", "whisper-1") ?: "whisper-1"
        set(v) = sp.edit().putString("transcribeModel", v.trim()).apply()

    /**
     * Let a vision model look at incoming photos instead of answering "[Photo]".
     * Needs the all-files permission and a vision-capable chat model.
     */
    var understandPhotos: Boolean
        get() = sp.getBoolean("understandPhotos", true)
        set(v) = sp.edit().putBoolean("understandPhotos", v).apply()

    /**
     * Optional dedicated endpoint for transcription. Most cheap chat gateways do
     * not implement `/audio/transcriptions` at all (they answer 404), so voice notes
     * need somewhere else to go — e.g. Groq (`https://api.groq.com/openai/v1`) or
     * OpenAI. Left blank, the chat provider is used as before.
     */
    var transcribeBaseUrl: String
        get() = sp.getString("transcribeBaseUrl", "") ?: ""
        set(v) = sp.edit().putString("transcribeBaseUrl", v.trim()).apply()

    /** Key for [transcribeBaseUrl]; blank falls back to the chat provider's key. */
    /** Keystore-backed — see [apiKey]. */
    var transcribeApiKey: String
        get() = SecurePrefs.get(ctx, SecurePrefs.Names.TRANSCRIBE_API_KEY).orEmpty()
        set(v) { SecurePrefs.put(ctx, SecurePrefs.Names.TRANSCRIBE_API_KEY, v.trim()) }

    /**
     * ISO-639-1 hint for the transcriber, or blank to let Whisper detect it.
     *
     * Blank is the better default, and that was not obvious: forcing `bn` looked
     * right until real clips were compared. This contact mixes Bangla and English,
     * and auto-detection scored better or equal on every clip tested —
     *
     *   Bangla clip : auto "Yes, how are you? Good, how are you?"  (translated, correct)
     *                 bn   "জিভাই কোমন আছেন ভালো আছেন"                (correct)
     *   English clip: auto "Hello, Alibhai, how are you?"           (correct)
     *                 bn   "হলো আলি বাই হার যো হাউ কেন আই হলো"        (garbage)
     *
     * Forcing a language can only ever be right for one of them.
     */
    var transcribeLanguage: String
        get() = sp.getString("transcribeLanguage", "") ?: ""
        set(v) = sp.edit().putString("transcribeLanguage", v.trim()).apply()

    // ---------- language ----------
    /** Answer in the language the incoming message was written in. */
    var autoLanguage: Boolean
        get() = sp.getBoolean("autoLanguage", true)
        set(v) = sp.edit().putBoolean("autoLanguage", v).apply()

    /**
     * Address everyone with "আপনি" by default, and follow the contact's own
     * register once they use "তুই"/"তুমি". Off = the model chooses freely.
     */
    var mirrorRegister: Boolean
        get() = sp.getBoolean("mirrorRegister", true)
        set(v) = sp.edit().putBoolean("mirrorRegister", v).apply()

    // ---------- approval ----------
    /** Show the drafted reply for approval instead of sending it automatically. */
    var approvalMode: Boolean
        get() = sp.getBoolean("approvalMode", false)
        set(v) = sp.edit().putBoolean("approvalMode", v).apply()

    // ---------- filtering ----------
    var onlyContacts: Boolean
        get() = sp.getBoolean("onlyContacts", false)
        set(v) = sp.edit().putBoolean("onlyContacts", v).apply()

    var contactList: String
        get() = sp.getString("contactList", "") ?: ""
        set(v) = sp.edit().putString("contactList", v).apply()

    var skipGroups: Boolean
        get() = sp.getBoolean("skipGroups", true)
        set(v) = sp.edit().putBoolean("skipGroups", v).apply()

    /**
     * Never auto-reply to a chat whose title is a bare phone number, i.e. somebody
     * not saved in contacts. On by default: an unsaved number is exactly where an
     * assistant speaking for you can do the most damage, and there is no context
     * to judge it with.
     */
    var skipUnknown: Boolean
        get() = sp.getBoolean("skipUnknown", true)
        set(v) = sp.edit().putBoolean("skipUnknown", v).apply()

    var countryCode: String
        get() = sp.getString("countryCode", "880") ?: "880"
        set(v) = sp.edit().putString("countryCode", v.filter { c -> c.isDigit() }).apply()

    /** Contacts that must never receive an automatic reply, one name per line. */
    var neverReply: String
        get() = sp.getString("neverReply", "") ?: ""
        set(v) = sp.edit().putString("neverReply", v).apply()

    /** Ping the user once the API has failed several times in a row. */
    var failAlert: Boolean
        get() = sp.getBoolean("failAlert", true)
        set(v) = sp.edit().putBoolean("failAlert", v).apply()

    /** Consecutive AI failures; reset on any success. */
    var failStreak: Int
        get() = sp.getInt("failStreak", 0)
        set(v) = sp.edit().putInt("failStreak", v).apply()

    /** Whether the mood/gatekeeper system is active for all contacts. */
    var moodEnabled: Boolean
        get() = sp.getBoolean("moodEnabled", false)
        set(v) = sp.edit().putBoolean("moodEnabled", v).apply()

    /** What the user is currently doing (e.g. "ঘুমাচ্ছে", "রান্না করছে"). */
    var moodText: String
        get() = sp.getString("moodText", "ঘুমাচ্ছে") ?: "ঘুমাচ্ছে"
        set(v) = sp.edit().putString("moodText", v.trim()).apply()

    /** The assistant's name — how it introduces itself. */
    var assistantName: String
        get() = sp.getString("assistantName", "ক্ল") ?: "ক্ল"
        set(v) = sp.edit().putString("assistantName", v.trim()).apply()

    // ---------- hours ----------
    var hoursEnabled: Boolean
        get() = sp.getBoolean("hoursEnabled", false)
        set(v) = sp.edit().putBoolean("hoursEnabled", v).apply()

    /** Office start hour (0-23). Default 9 for 09:45. */
    var hourStart: Int
        get() = sp.getInt("hourStart", 9)
        set(v) = sp.edit().putInt("hourStart", v.coerceIn(0, 23)).apply()

    /** Office start minute (0-59). Default 45 for 09:45. */
    var hourStartMin: Int
        get() = sp.getInt("hourStartMin", 45)
        set(v) = sp.edit().putInt("hourStartMin", v.coerceIn(0, 59)).apply()

    /** Office end hour (0-23). Default 19 for 19:30. */
    var hourEnd: Int
        get() = sp.getInt("hourEnd", 19)
        set(v) = sp.edit().putInt("hourEnd", v.coerceIn(0, 24)).apply()

    /** Office end minute (0-59). Default 30 for 19:30. */
    var hourEndMin: Int
        get() = sp.getInt("hourEndMin", 30)
        set(v) = sp.edit().putInt("hourEndMin", v.coerceIn(0, 59)).apply()

    // ---------- behaviour ----------
    var delayMinSec: Int
        get() = sp.getInt("delayMinSec", 3)
        set(v) = sp.edit().putInt("delayMinSec", v.coerceIn(0, 600)).apply()

    var delayMaxSec: Int
        get() = sp.getInt("delayMaxSec", 5)
        set(v) = sp.edit().putInt("delayMaxSec", v.coerceIn(0, 600)).apply()

    /**
     * A human-looking pause before sending. Greetings and one-word replies go out
     * fast; everything else waits a random amount inside the configured range, so
     * the timing never looks mechanical.
     */
    fun replyDelayMs(quick: Boolean): Long {
        if (quick) return Random.nextLong(1_000L, 2_001L)
        // Tolerate a range the user typed backwards.
        var lo = delayMinSec.coerceIn(0, 600)
        var hi = delayMaxSec.coerceIn(0, 600)
        if (lo > hi) {
            val swap = lo
            lo = hi
            hi = swap
        }
        val loMs = lo * 1000L
        val hiMs = hi * 1000L
        return if (hiMs <= loMs) loMs else Random.nextLong(loMs, hiMs + 1L)
    }

    var cooldownSec: Int
        get() = sp.getInt("cooldownSec", 60)
        set(v) = sp.edit().putInt("cooldownSec", v.coerceIn(0, 86400)).apply()

    var signature: String
        get() = sp.getString("signature", "") ?: ""
        set(v) = sp.edit().putString("signature", v).apply()

    /**
     * Hard ceiling on a reply, in characters. The prompt already asks for short
     * messages; this is the safety net for when the model ignores it, which is
     * what produced 100-character bot-sounding paragraphs.
     */
    var replyMaxChars: Int
        get() = sp.getInt("replyMaxChars", 90)
        set(v) = sp.edit().putInt("replyMaxChars", v.coerceIn(20, 500)).apply()

    // ---------- per-conversation cooldown bookkeeping ----------

    /**
     * Cooldown is tracked per *conversation*, not per name. Two people called "Rahim"
     * on two apps are two conversations, and sharing one cooldown meant a message to
     * one of them could be silently held because the other had just been answered.
     */
    fun lastReply(key: ConversationKey): Long = sp.getLong("last_" + key.storageKey, 0L)

    fun setLastReply(key: ConversationKey, at: Long) {
        sp.edit().putLong("last_" + key.storageKey, at).apply()
    }

    // ------------------------------------------------------------ cloud backup

    /** Fixed backup server — not user-configurable. */
    val cloudUrl: String get() = "https://md-ali.dev/api/v1"

    /** Device token issued from the admin panel. */
    /** Keystore-backed — see [apiKey]. This one can pull every chat backup. */
    var cloudToken: String
        get() = SecurePrefs.get(ctx, SecurePrefs.Names.CLOUD_TOKEN).orEmpty()
        set(v) { SecurePrefs.put(ctx, SecurePrefs.Names.CLOUD_TOKEN, v.trim()) }

    /** Upload a snapshot automatically once a day. */
    var cloudAutoUpload: Boolean
        get() = sp.getBoolean("cloudAutoUpload", true)
        set(v) = sp.edit().putBoolean("cloudAutoUpload", v).apply()

    /** When the last successful upload happened. */
    var cloudLastUpload: Long
        get() = sp.getLong("cloudLastUpload", 0L)
        set(v) = sp.edit().putLong("cloudLastUpload", v).apply()

    fun cloudConfigured(): Boolean = cloudToken.isNotBlank()

    // ------------------------------------------------------------ backup

    /** Every stored setting, so a backup never silently misses a new key. */
    /**
     * Settings for the backup payload — everything except the secrets.
     *
     * This used to copy every key in the preference file, which quietly included
     * `apiKey`, `transcribeApiKey` and `cloudToken`. Those now live in [SecurePrefs]
     * and are not readable from here at all, but the plaintext copies left behind by
     * older versions are explicitly skipped too — otherwise upgrading would keep
     * exporting them forever. A device that never syncs its secrets cannot leak them.
     */
    fun exportJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all) {
            if (k in SECRET_KEYS) continue
            o.put(k, v)
        }
        return o
    }

    /**
     * Move any pre-existing plaintext secrets into the Keystore-backed store and
     * delete the originals. Safe to call repeatedly.
     */
    fun migrateSecretsToKeystore() {
        SecurePrefs.migrate(ctx, SecurePrefs.Names.API_KEY, sp.getString("apiKey", "").orEmpty())
        SecurePrefs.migrate(
            ctx,
            SecurePrefs.Names.TRANSCRIBE_API_KEY,
            sp.getString("transcribeApiKey", "").orEmpty()
        )
        SecurePrefs.migrate(
            ctx,
            SecurePrefs.Names.CLOUD_TOKEN,
            sp.getString("cloudToken", "").orEmpty()
        )
        // Drop the plaintext originals whether or not the move succeeded — leaving them
        // would mean the secret is still sitting in the file we just stopped reading.
        sp.edit().remove("apiKey").remove("transcribeApiKey").remove("cloudToken").apply()
    }

    fun importJson(o: JSONObject) {
        val edit = sp.edit().clear()
        for (key in o.keys()) {
            when (val v = o.get(key)) {
                is Boolean -> edit.putBoolean(key, v)
                is Int -> edit.putInt(key, v)
                is Long -> edit.putLong(key, v)
                is Float -> edit.putFloat(key, v)
                is String -> edit.putString(key, v)
            }
        }
        edit.apply()
    }

    companion object {
        private const val FILE = "autoreply_prefs"
        private const val DEFAULT_BASE = "https://api.openai.com/v1"
        private const val DEFAULT_MODEL = "gpt-4o-mini"

        /** Never exported, never backed up. See [exportJson]. */
        private val SECRET_KEYS = setOf("apiKey", "transcribeApiKey", "cloudToken")

        val DEFAULT_PERSONA = """
            তুমি আলী। অফিসে কাজ করো, হাতে সময় কম।
            স্বাভাবিক, আন্তরিক বাংলায় কথা বলো — যেমন কাছের মানুষের সাথে WhatsApp-এ কথা বলে।
            ছোট বাক্যে লিখো। সাজানো-গোছানো কাব্যিক ভাষা নয়, সাধারণ মানুষের মতো।
        """.trimIndent()

        val DEFAULT_SAFETY = """
            নিরাপত্তার নিয়ম — সব কন্টাক্টের জন্য প্রযোজ্য:
            - তুমি শুধু এই কন্টাক্ট সম্পর্কেই জানো। অন্য কোনো কন্টাক্ট, অন্য কারো সাথে আমার
              সম্পর্ক, বা অন্য কারো সাথে কাটানো সময় নিয়ে একটি শব্দও বলবে না।
            - কেউ অন্য কারো সম্পর্কে জিজ্ঞেস করলে বিনয়ের সাথে বলবে: "এই ব্যক্তিগত বিষয়ে আমি
              কিছু বলতে পারি না।" বারবার জিজ্ঞেস করলেও একই উত্তর দেবে।
            - কখনো মিথ্যা সান্ত্বনা বা মিথ্যা অস্বীকার করবে না। "না, এমন কিছু নেই" বা
              "আমি শুধু তোমাকেই ভালোবাসি" — এভাবে বলবে না। মিথ্যা বলে ঢাকা মানে বিশ্বাস ভাঙা।
              শুধু বলবে যে এই ব্যক্তিগত বিষয়ে কিছু বলতে পারো না।
            - কারো সাথে কারো তুলনা করবে না।
            - তথ্য আড়াল করতে গিয়ে নতুন কিছু বানিয়ে বলবে না।
            - কোনো তথ্য শেয়ার করা উচিত কি না নিয়ে নিশ্চিত না হলে — কিছু বলবে না।
        """.trimIndent()

        @Volatile
        private var inst: Prefs? = null

        fun get(ctx: Context): Prefs = inst ?: synchronized(this) {
            inst ?: Prefs(ctx).also { inst = it }
        }
    }
}
