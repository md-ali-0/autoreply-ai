package com.claw.autoreplyai

/**
 * Registry of the chat apps the engine understands.
 *
 * Every part of the pipeline — the notification listener, the reply delivery and
 * the settings screen — asks this object instead of hard-coding a package name,
 * so adding the next app is a one-line change here plus a toggle in the UI.
 *
 * Two delivery styles exist:
 *  - WhatsApp: the reply can be sent through the notification's inline-reply
 *    action, or by opening a `wa.me` deep link that pre-fills the text and
 *    tapping Send (a phone number is required).
 *  - Everyone else (Messenger, Telegram, Instagram): there is no phone-number
 *    deep link, so the fallback route reopens the exact conversation through the
 *    notification's own content intent and types the reply in.
 */
object MessagingApps {

    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val MESSENGER = "com.facebook.orca"
    const val MESSENGER_LITE = "com.facebook.mlite"
    const val TELEGRAM = "org.telegram.messenger"
    const val TELEGRAM_X = "org.thunderdog.challegram"
    const val TELEGRAM_PLUS = "org.telegram.plus"
    const val INSTAGRAM = "com.instagram.android"
    const val INSTAGRAM_LITE = "com.instagram.lite"

    /** Packages that can be reached with a `wa.me` link (phone number required). */
    private val PHONE_LINK_APPS = setOf(WHATSAPP, WHATSAPP_BUSINESS)

    /** Packages whose notifications mark outgoing messages with a null sender. */
    private val OWN_MESSAGE_HEURISTIC_APPS = setOf(WHATSAPP, WHATSAPP_BUSINESS)

    val all: List<String> = listOf(
        WHATSAPP, WHATSAPP_BUSINESS,
        MESSENGER, MESSENGER_LITE,
        TELEGRAM, TELEGRAM_X, TELEGRAM_PLUS,
        INSTAGRAM, INSTAGRAM_LITE
    )

    fun isSupported(pkg: String?): Boolean = pkg != null && pkg in all

    fun isWhatsApp(pkg: String?): Boolean = pkg == WHATSAPP || pkg == WHATSAPP_BUSINESS

    /** True when `sender_person == null` reliably means "this is our own message". */
    fun usesOwnMessageHeuristic(pkg: String?): Boolean = pkg in OWN_MESSAGE_HEURISTIC_APPS

    /** True when the app can be opened through a wa.me link with a phone number. */
    fun supportsPhoneLink(pkg: String?): Boolean = pkg in PHONE_LINK_APPS

    /** Short human label for the log lines. */
    fun label(pkg: String?): String = when (pkg) {
        WHATSAPP, WHATSAPP_BUSINESS -> "WhatsApp"
        MESSENGER, MESSENGER_LITE -> "Messenger"
        TELEGRAM, TELEGRAM_X, TELEGRAM_PLUS -> "Telegram"
        INSTAGRAM, INSTAGRAM_LITE -> "Instagram"
        else -> pkg ?: "?"
    }

    /** The settings toggle that governs this package. */
    fun enabledBy(p: Prefs, pkg: String?): Boolean = when (pkg) {
        WHATSAPP, WHATSAPP_BUSINESS -> p.replyWhatsApp
        MESSENGER, MESSENGER_LITE -> p.replyMessenger
        TELEGRAM, TELEGRAM_X, TELEGRAM_PLUS -> p.replyTelegram
        INSTAGRAM, INSTAGRAM_LITE -> p.replyInstagram
        else -> false
    }

    /** Only WhatsApp stores voice notes in a folder the app can actually read. */
    fun canTranscribeVoice(pkg: String?): Boolean = isWhatsApp(pkg)
}
