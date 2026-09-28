package com.claw.autoreplyai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import kotlin.random.Random

/**
 * Single source of truth for all user settings.
 */
class Prefs private constructor(ctx: Context) {

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

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

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
     * Optional dedicated endpoint for transcription. Most cheap chat gateways do
     * not implement `/audio/transcriptions` at all (they answer 404), so voice notes
     * need somewhere else to go — e.g. Groq (`https://api.groq.com/openai/v1`) or
     * OpenAI. Left blank, the chat provider is used as before.
     */
    var transcribeBaseUrl: String
        get() = sp.getString("transcribeBaseUrl", "") ?: ""
        set(v) = sp.edit().putString("transcribeBaseUrl", v.trim()).apply()

    /** Key for [transcribeBaseUrl]; blank falls back to the chat provider's key. */
    var transcribeApiKey: String
        get() = sp.getString("transcribeApiKey", "") ?: ""
        set(v) = sp.edit().putString("transcribeApiKey", v.trim()).apply()

    // ---------- language ----------
    /** Answer in the language the incoming message was written in. */
    var autoLanguage: Boolean
        get() = sp.getBoolean("autoLanguage", true)
        set(v) = sp.edit().putBoolean("autoLanguage", v).apply()

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

    // ---------- per-contact cooldown bookkeeping ----------
    fun lastReply(contact: String): Long = sp.getLong("last_" + contact.lowercase(), 0L)

    fun setLastReply(contact: String, at: Long) {
        sp.edit().putLong("last_" + contact.lowercase(), at).apply()
    }

    // ------------------------------------------------------------ backup

    /** Every stored setting, so a backup never silently misses a new key. */
    fun exportJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all) o.put(k, v)
        return o
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
