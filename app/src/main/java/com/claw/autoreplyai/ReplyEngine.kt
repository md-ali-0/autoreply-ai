package com.claw.autoreplyai

import android.app.PendingIntent
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * The brain. For every incoming WhatsApp message it:
 *  1. applies the user's hard filters (switch, contacts, hours, cooldown),
 *  2. asks the model what to do — reply, stay quiet, or hand it to the human,
 *  3. delivers the reply through the notification action, falling back to the
 *     accessibility route.
 */
object ReplyEngine {

    /** Alert the user after this many consecutive AI failures. */
    private const val FAIL_ALERT_AFTER = 3

    /** How many times a queued message may be retried while another reply runs. */
    private const val MAX_PENDING_TRIES = 8

    /** Character budget for the conversation history sent with each request. */
    private const val HISTORY_CHARS = 4_000

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun onIncoming(
        ctx: Context,
        pkg: String,
        sender: String,
        message: String,
        isGroup: Boolean,
        phoneHint: String?,
        direct: DirectReplier.Handle?,
        contentIntent: PendingIntent? = null
    ) {
        val app = ctx.applicationContext
        val p = Prefs.get(app)

        if (!p.enabled) return
        if (message.isBlank()) return

        // Per-app switch: the user may want WhatsApp answers but not Messenger ones.
        if (!MessagingApps.enabledBy(p, pkg)) {
            log(app, "${MessagingApps.label(pkg)} বন্ধ রাখা আছে (সেটিংসে চালু করুন) — $sender")
            return
        }

        // Defensive: a call event should never reach here, but if another entry
        // point (accessibility, future integration) feeds one in, drop it early.
        if (CallEventFilter.isCallEvent(message)) {
            log(app, "কল ইভেন্ট বাদ (defensive) — $sender")
            return
        }

        if (isBlocked(p, sender)) {
            log(app, "কখনো রিপ্লাই দেব না লিস্টে আছে — $sender")
            return
        }

        if (isGroup && p.skipGroups) {
            log(app, "গ্রুপ মেসেজ বাদ — $sender")
            return
        }

        if (p.hoursEnabled && !withinHours(p)) {
            log(app, "সময়সীমার বাইরে (${timeLabel(p.hourStart, p.hourStartMin)}–${timeLabel(p.hourEnd, p.hourEndMin)}) — $sender")
            digest(app, pkg, sender, message, DigestStore.ACTION_BLOCKED, "")
            return
        }

        if (p.onlyContacts && !p.moodEnabled) {
            val allowed = p.contactList
                .split('\n', ',', ';')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
            if (allowed.none { sender.lowercase().contains(it) }) {
                log(app, "ফিল্টার লিস্টে নেই — $sender")
                digest(app, pkg, sender, message, DigestStore.ACTION_BLOCKED, "")
                return
            }
        }

        // Inside the cooldown: never drop the message. Remember the newest one and
        // answer it once the window closes — otherwise the contact gets a reply to
        // an older message and silence on the one they actually just sent, which
        // reads as the bot answering the wrong thing.
        val remaining = p.cooldownSec * 1000L - (System.currentTimeMillis() - p.lastReply(sender))
        if (remaining > 0) {
            pending[sender] = Pending(pkg, sender, message, phoneHint, direct, contentIntent)
            log(app, "কুলডাউনে — $sender (নতুন মেসেজটা পরে উত্তর দেওয়া হবে)")
            schedulePending(app, sender, remaining)
            return
        }

        if (!inFlight.add(sender)) {
            // Same reasoning as the cooldown — never lose the message, queue it.
            pending[sender] = Pending(pkg, sender, message, phoneHint, direct, contentIntent)
            log(app, "আগের রিপ্লাই চলছে — $sender (এই মেসেজটা পরে উত্তর দেওয়া হবে)")
            schedulePending(app, sender, 3_000L)
            return
        }

        scope.launch {
            try {
                handle(app, p, pkg, sender, message, phoneHint, direct, contentIntent)
            } catch (e: Exception) {
                log(app, "ত্রুটি — $sender: ${e.message}")
            } finally {
                inFlight.remove(sender)
            }
        }
    }

    // ---------------------------------------------------- deferred replies

    private class Pending(
        val pkg: String,
        val sender: String,
        val message: String,
        val phoneHint: String?,
        val direct: DirectReplier.Handle?,
        val contentIntent: PendingIntent?
    )

    private val pending = Collections.synchronizedMap(HashMap<String, Pending>())
    private val pendingScheduled: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private fun schedulePending(app: Context, sender: String, delayMs: Long, attempt: Int = 0) {
        if (!pendingScheduled.add(sender)) return
        scope.launch {
            delay(delayMs.coerceAtLeast(500L) + 500L)
            pendingScheduled.remove(sender)

            // Read but do NOT remove yet — the message must survive a failed attempt.
            val msg = pending[sender] ?: return@launch
            val p = Prefs.get(app)
            if (!p.enabled) {
                pending.remove(sender)
                return@launch
            }

            // Still inside the cooldown? wait it out.
            val remaining = p.cooldownSec * 1000L - (System.currentTimeMillis() - p.lastReply(sender))
            if (remaining > 0) {
                schedulePending(app, sender, remaining, attempt)
                return@launch
            }

            // Another reply is still running? try again shortly, a bounded number of times.
            if (!inFlight.add(sender)) {
                if (attempt < MAX_PENDING_TRIES) {
                    schedulePending(app, sender, 3_000L, attempt + 1)
                } else {
                    pending.remove(sender)
                    log(app, "পরে উত্তর দেওয়ার চেষ্টা ছেড়ে দেওয়া হলো — $sender")
                }
                return@launch
            }

            pending.remove(sender)
            try {
                handle(app, p, msg.pkg, msg.sender, msg.message, msg.phoneHint, msg.direct, msg.contentIntent)
            } catch (e: Exception) {
                log(app, "পরে উত্তর দিতে গিয়ে ত্রুটি — $sender: ${e.message}")
            } finally {
                inFlight.remove(sender)
            }
        }
    }

    private suspend fun handle(
        app: Context,
        p: Prefs,
        pkg: String,
        sender: String,
        message: String,
        phoneHint: String?,
        direct: DirectReplier.Handle?,
        contentIntent: PendingIntent?
    ) {
        // Voice notes arrive as a placeholder ("🎤 Voice message"). Turn them into
        // text first, otherwise the model is answering the word "Voice message".
        val incoming = resolveIncomingText(app, p, pkg, sender, message)
        val text = incoming.text
        if (text.isBlank()) return

        if (p.moodEnabled) {
            handleMood(app, p, pkg, sender, text, phoneHint, direct, contentIntent, incoming.fromVoice)
            return
        }

        val messages = ArrayList<AiClient.Msg>()
        val contactContext = ContactContext.forContact(app, sender)
        messages.add(AiClient.Msg("system", buildSystemPrompt(p, sender, contactContext, incoming.fromVoice)))
        messages.addAll(trimmedHistory(app, sender))
        messages.add(AiClient.Msg("user", text))

        val raw = try {
            askAi(app, messages)
        } catch (e: Exception) {
            noteApiFailure(app, p, e.message ?: "অজানা ত্রুটি")
            return
        }
        p.failStreak = 0

        if (raw.isBlank()) {
            log(app, "AI খালি উত্তর দিয়েছে — $sender")
            return
        }

        val decision = if (p.smartTriage) ReplyDecision.parse(raw) else ReplyDecision.plain(raw)
        val preview = text.replace("\n", " ").take(70)

        when (decision.action) {
            ReplyDecision.Action.IGNORE -> {
                log(app, "উত্তর দেওয়া হলো না (দরকার নেই) — $sender: $preview")
                // Record it anyway: a history with holes is what makes the next
                // reply look like it came out of nowhere.
                ChatMemory.add(app, sender, "user", text)
                digest(app, pkg, sender, text, DigestStore.ACTION_IGNORED, "")
                return
            }

            ReplyDecision.Action.HOLD -> {
                if (p.holdOnEmotional) {
                    log(app, "⚠️ নিজে উত্তর দিন — $sender: $preview")
                    ChatMemory.add(app, sender, "user", text)
                    Notify.needsYou(app, sender, text)
                    digest(app, pkg, sender, text, DigestStore.ACTION_HELD, "")
                    return
                }
                log(app, "আবেগপূর্ণ মেসেজ, তবে সেটিং অনুযায়ী উত্তর দেওয়া হচ্ছে — $sender")
            }

            ReplyDecision.Action.REPLY -> Unit
        }

        val reply = decision.reply
        if (reply.isBlank()) {
            log(app, "উত্তর খালি এলো — $sender")
            return
        }

        val finalText = if (p.signature.isNotBlank()) "$reply\n${p.signature}" else reply

        // Greetings and one-word replies go out quickly; everything else waits a
        // random moment inside the configured range so the timing looks human.
        val quick = decision.style != ReplyDecision.Style.NORMAL
        val waitMs = p.replyDelayMs(quick)
        if (waitMs > 0) delay(waitMs)

        deliverReply(app, p, pkg, sender, text, finalText, reply, contactContext.length, phoneHint, direct, contentIntent)
    }

    /** An incoming message, plus whether it came from a (possibly imperfect) transcript. */
    private data class Incoming(val text: String, val fromVoice: Boolean)

    /**
     * Replaces a voice-note placeholder with its transcript. Returns blank text
     * when the note cannot be read — a reply to the words "Voice message" helps
     * nobody, so the message is skipped and the reason is logged instead.
     */
    private fun resolveIncomingText(
        app: Context,
        p: Prefs,
        pkg: String,
        sender: String,
        message: String
    ): Incoming {
        if (!p.transcribeVoice) return Incoming(message, fromVoice = false)
        if (!VoiceTranscriber.looksLikeVoiceNote(message)) return Incoming(message, fromVoice = false)

        if (!MessagingApps.canTranscribeVoice(pkg)) {
            log(app, "ভয়েস মেসেজ — ${MessagingApps.label(pkg)}-এর অডিও পড়া যায় না, তাই উত্তর দেওয়া হচ্ছে না — $sender")
            digest(app, pkg, sender, message, DigestStore.ACTION_BLOCKED, "")
            return Incoming("", fromVoice = true)
        }

        log(app, "ভয়েস মেসেজ পেয়েছি — ট্রান্সক্রিপ্ট করছি — $sender")
        val transcript = VoiceTranscriber.transcribe(app, p, pkg)
        if (transcript.isNullOrBlank()) {
            log(app, "ভয়েস মেসেজ পড়া গেল না — $sender")
            digest(app, pkg, sender, message, DigestStore.ACTION_BLOCKED, "")
            return Incoming("", fromVoice = true)
        }
        log(app, "ভয়েস ট্রান্সক্রিপ্ট ($sender): ${transcript.take(90)}")
        return Incoming(transcript, fromVoice = true)
    }

    private fun digest(
        app: Context,
        pkg: String,
        sender: String,
        message: String,
        action: String,
        reply: String
    ) {
        DigestStore.record(
            app,
            DigestStore.Event(
                at = System.currentTimeMillis(),
                pkg = pkg,
                sender = sender,
                message = message,
                action = action,
                reply = reply
            )
        )
    }

    /**
     * Mood/gatekeeper mode: the AI acts on behalf of the user.
     * It greets, asks how-are-you, announces the user's activity, and
     * triggers an alarm if the message is urgent.
     * Applies to ALL contacts (the contact filter is bypassed).
     */
    private suspend fun handleMood(
        app: Context,
        p: Prefs,
        pkg: String,
        sender: String,
        message: String,
        phoneHint: String?,
        direct: DirectReplier.Handle?,
        contentIntent: PendingIntent?,
        fromVoice: Boolean = false
    ) {
        val messages = ArrayList<AiClient.Msg>()
        messages.add(AiClient.Msg("system", buildGatekeeperPrompt(p, sender, fromVoice)))
        messages.addAll(trimmedHistory(app, sender))
        messages.add(AiClient.Msg("user", message))

        val raw = try {
            askAi(app, messages)
        } catch (e: Exception) {
            noteApiFailure(app, p, e.message ?: "অজানা ত্রুটি")
            return
        }
        p.failStreak = 0

        if (raw.isBlank()) {
            log(app, "AI খালি উত্তর দিয়েছে (mood) — $sender")
            return
        }

        val gd = try {
            GatekeeperDecision.parse(raw)
        } catch (e: Exception) {
            log(app, "gatekeeper JSON পার্স করা যায়নি — $sender: ${raw.take(80)}")
            return
        }

        if (gd.action == GatekeeperDecision.Action.ALARM) {
            log(app, "🚨 জরুরি — ${p.assistantName} Ali-কে ডাকছে — $sender: ${message.replace("\n", " ").take(60)}")
            Alarm.trigger(app, "$sender: ${gd.reply.take(80)}")
        }

        val reply = gd.reply
        if (reply.isBlank()) {
            log(app, "gatekeeper উত্তর খালি — $sender")
            return
        }

        // Small delay so it doesn't look instant
        delay(p.replyDelayMs(quick = false).coerceAtMost(2_000L))
        deliverReply(app, p, pkg, sender, message, reply, reply, 0, phoneHint, direct, contentIntent)
    }

    private fun deliverReply(
        app: Context,
        p: Prefs,
        pkg: String,
        sender: String,
        message: String,
        finalText: String,
        reply: String,
        contextChars: Int,
        phoneHint: String?,
        direct: DirectReplier.Handle?,
        contentIntent: PendingIntent?
    ) {
        // Approval mode: never send on our own — show the draft and let the user
        // decide. Nothing else changes, so approving later uses the same routes.
        if (p.approvalMode) {
            val shown = Approval.request(
                app,
                Approval.Draft(pkg, sender, message, finalText, phoneHint, direct, contentIntent)
            )
            if (shown) {
                log(app, "অনুমোদনের অপেক্ষায় — $sender: ${reply.replace("\n", " ").take(60)}")
                digest(app, pkg, sender, message, DigestStore.ACTION_APPROVAL, reply)
            } else {
                log(app, "অনুমোদন দেখানো যায়নি, তাই কিছু পাঠানো হলো না — $sender")
            }
            return
        }

        // Route 1: the notification's own reply action (works while locked, no
        // phone number or open chat needed). Every supported app posts one.
        if (direct != null && DirectReplier.send(app, direct, finalText)) {
            onSent(app, p, pkg, sender, message, reply, contextChars)
            return
        }
        if (direct == null) {
            log(app, "নোটিফিকেশনে reply অ্যাকশন নেই — বিকল্প পথে যাচ্ছি")
        }

        // Route 2: open the chat and tap Send (needs an unlocked screen).
        val deferred = CompletableDeferred<Boolean>()

        if (MessagingApps.supportsPhoneLink(pkg)) {
            // WhatsApp: a wa.me link both opens the chat and pre-fills the text.
            val rawNumber = phoneHint?.takeIf { it.isNotBlank() }
                ?: ContactResolver.numberForName(app, sender)
            if (rawNumber.isNullOrBlank()) {
                log(app, "নাম্বার পাওয়া যায়নি — $sender (কন্টাক্ট পারমিশন দিন)")
                return
            }
            val waNumber = normalizeNumber(rawNumber, p.countryCode)
            if (waNumber.isNullOrBlank()) {
                log(app, "নাম্বার নরমালাইজ করা যায়নি — $rawNumber")
                return
            }
            SendAccessibilityService.send(app, pkg, waNumber, finalText) { deferred.complete(it) }
        } else {
            // Messenger: no phone-number link exists, so reopen the conversation
            // through the notification's own content intent and type the reply in.
            if (contentIntent == null) {
                log(app, "চ্যাট খোলার উপায় নেই — $sender (নোটিফিকেশনে reply অ্যাকশন পাওয়া যায়নি)")
                return
            }
            SendAccessibilityService.sendViaIntent(app, pkg, contentIntent, finalText) {
                deferred.complete(it)
            }
        }

        scope.launch {
            val ok = withTimeoutOrNull(35_000L) { deferred.await() } ?: false
            if (ok) {
                onSent(app, p, pkg, sender, message, reply, contextChars)
            } else {
                log(app, "✗ পাঠানো যায়নি → $sender (ফোন লক থাকলে লক খুলে রাখুন)")
            }
        }
    }

    private fun onSent(
        app: Context,
        p: Prefs,
        pkg: String,
        sender: String,
        message: String,
        reply: String,
        contextChars: Int
    ) {
        ChatMemory.add(app, sender, "user", message)
        ChatMemory.add(app, sender, "assistant", reply)
        p.setLastReply(sender, System.currentTimeMillis())
        // Remember what we just sent so we don't reply to ourselves when the chat
        // app echoes it back as a new notification.
        SentMessageTracker.record(reply)
        digest(app, pkg, sender, message, DigestStore.ACTION_REPLIED, reply)
        // Both sides of the exchange are logged so a bad transcript is visible
        // right next to the reply it caused, instead of two lines apart.
        log(
            app,
            "✓ রিপ্লাই → $sender · কন্টেক্সট $contextChars অক্ষর\n" +
                    "    ◀ এসেছিল: ${message.replace("\n", " ").take(90)}\n" +
                    "    ▶ গেল: ${reply.replace("\n", " ").take(90)}"
        )
    }

    /**
     * Keep the newest history entries within a character budget. A long chat plus a
     * 3 KB contact context can overflow a small model's window, and an overflowing
     * model silently starts ignoring the system prompt — which looks exactly like
     * "it isn't understanding the context".
     */
    private fun trimmedHistory(app: Context, sender: String): List<AiClient.Msg> {
        val history = ChatMemory.history(app, sender)
        if (history.isEmpty()) return history

        val out = ArrayList<AiClient.Msg>()
        var budget = HISTORY_CHARS
        for (i in history.indices.reversed()) {
            val msg = history[i]
            if (out.isNotEmpty() && msg.content.length > budget) break
            budget -= msg.content.length
            out.add(0, msg)
        }
        return out
    }

    // ------------------------------------------------------------- prompting

    /**
     * Tries the prioritized provider list until one succeeds.
     * On fallback success, automatically switches the active provider
     * so subsequent requests use the working one.
     */
    private suspend fun askAi(app: Context, messages: List<AiClient.Msg>): String {
        val allProviders = AiProviderStore.all(app)
        val ordered = AiProviderStore.prioritized(app)
        if (ordered.isEmpty()) throw java.io.IOException("কোনো AI প্রোভাইডার সেট আপ নেই")

        var lastError: Exception? = null
        for ((i, provider) in ordered.withIndex()) {
            try {
                val result = AiClient.chat(provider.baseUrl, provider.apiKey, provider.model, messages)
                if (result.isNotBlank()) {
                    if (i > 0) {
                        // Fallback succeeded — switch to this provider for future requests
                        log(app, "ফলব্যাক সফল — ${provider.name} (${provider.model}) এখন থেকে ব্যবহার হবে")
                        val originalIndex = allProviders.indexOfFirst {
                            it.name == provider.name && it.baseUrl == provider.baseUrl
                        }.coerceAtLeast(0)
                        AiProviderStore.setSelected(app, originalIndex)
                        val p = Prefs.get(app)
                        p.baseUrl = provider.baseUrl
                        p.apiKey = provider.apiKey
                        p.model = provider.model
                    }
                    return result
                }
                lastError = java.io.IOException("${provider.name} খালি উত্তর দিয়েছে")
            } catch (e: Exception) {
                lastError = e
                val reason = e.message ?: "অজানা ত্রুটি"
                log(app, "API ব্যর্থ — ${provider.name}: $reason")
                if (i < ordered.lastIndex) delay(1_500L)
            }
        }
        throw lastError ?: java.io.IOException("সব প্রোভাইডার ব্যর্থ")
    }

    private fun noteApiFailure(app: Context, p: Prefs, reason: String) {
        val streak = p.failStreak + 1
        p.failStreak = streak
        log(app, "API ব্যর্থ (টানা $streak বার): $reason")
        if (p.failAlert && streak == FAIL_ALERT_AFTER) {
            Notify.botProblem(app, reason)
        }
    }

    private fun isBlocked(p: Prefs, sender: String): Boolean {
        val s = sender.lowercase()
        return p.neverReply
            .split('\n', ',', ';')
            .map { it.trim().lowercase() }
            .any { it.isNotEmpty() && s.contains(it) }
    }

    private fun buildSystemPrompt(
        p: Prefs,
        sender: String,
        contactContext: String,
        fromVoice: Boolean = false
    ): String {
        val sb = StringBuilder()
        sb.append(p.persona.trim()).append("\n\n")
        sb.append("কন্টাক্টের নাম: ").append(sender).append('\n')
        sb.append("এখন সময়: ").append(clockText()).append('\n')

        // Office hours awareness
        if (p.hoursEnabled) {
            val atOffice = withinHours(p)
            val startLabel = timeLabel(p.hourStart, p.hourStartMin)
            val endLabel = timeLabel(p.hourEnd, p.hourEndMin)
            if (atOffice) {
                sb.append("অফিস টাইম: $startLabel থেকে $endLabel। এখন আমি অফিসে আছি, ব্যস্ত থাকতে পারি।\n")
            } else {
                sb.append("অফিস টাইম: $startLabel থেকে $endLabel। এখন আমি অফিসে না, ব্যক্তিগত সময়।\n")
            }
        }

        val last = p.lastReply(sender)
        if (last > 0) {
            val minutes = (System.currentTimeMillis() - last) / 60_000L
            if (minutes > 0) {
                sb.append("এই কন্টাক্টের সাথে শেষ কথা হয়েছিল ").append(minutes).append(" মিনিট আগে।\n")
            }
        }

        // Only THIS contact's notes go in. Other contacts' notes are never loaded,
        // so nothing about them can leak no matter what the model is asked.
        if (contactContext.isNotBlank()) {
            sb.append("\nএই কন্টাক্ট সম্পর্কে যা জানো:\n")
            sb.append(contactContext).append('\n')
        }

        if (p.safetyRule.isNotBlank()) {
            sb.append('\n').append(p.safetyRule.trim()).append('\n')
        }

        // Register comes before language/voice rules: getting the pronoun wrong is
        // the fastest way to sound either rude or creepily familiar.
        if (p.mirrorRegister) {
            sb.append('\n').append(REGISTER_RULE).append('\n')
        }

        if (p.autoLanguage) {
            sb.append('\n').append(LANGUAGE_RULE).append('\n')
        }

        if (fromVoice) {
            sb.append('\n').append(VOICE_NOTE_RULE).append('\n')
        }

        sb.append('\n')
        sb.append(if (p.smartTriage) TRIAGE_RULES else PLAIN_RULES)
        return sb.toString()
    }

    /**
     * A voice note reaches the model as an automatic transcript, and short Bengali
     * clips transcribe badly — a 3-second "নিমাই, কেমন আছেন?" came back as
     * "নিমাই কামানা ফেন". Left alone, the model treats that as real text and
     * confidently answers something the person never said. So it is told plainly
     * that the text is a guess.
     */
    private val VOICE_NOTE_RULE = """
        ⚠️ এই মেসেজটা একটা ভয়েস মেসেজ — স্বয়ংক্রিয়ভাবে টেক্সটে রূপান্তর করা, তাই
        শব্দ ভুল হতে পারে বা অর্থহীন লাগতে পারে।

        নিয়ম:
        - ট্রান্সক্রিপ্টে যা নেই, এমন কিছু ধরে নিয়ে উত্তর দেবে না
        - বুঝতে না পারলে ছোট আর নিরাপদ উত্তর দাও (যেমন "হুম", "ঠিক আছে", "বলো"),
          অথবা ভদ্রভাবে বলো ঠিক বুঝতে পারোনি, একবার আবার বলতে
        - অনুমান করে কোনো তথ্য, নাম বা প্রতিশ্রুতি বানিয়ে বলবে না
        - নিশ্চিত না হলে প্রশ্ন করে জিজ্ঞেস করো, নিজে থেকে মন্তব্য করো না
    """.trimIndent()

    /**
     * Address people the way they address you — and default to the polite form.
     *
     * Bangla has three second-person registers and picking the wrong one is loud:
     * "তুই" to a stranger is insulting, "আপনি" to your wife is cold. There is no
     * safe single choice, so the default is formal and the contact's own usage
     * overrides it. The history is in the prompt, so an established register
     * survives messages that happen to contain no pronoun at all.
     */
    private val REGISTER_RULE = """
        সম্বোধন ঠিক করার নিয়ম — উত্তর লেখার আগে সবার আগে এটা ঠিক করবে:

        ১. এই মেসেজটা দেখো: কন্টাক্ট তোমাকে কী বলে সম্বোধন করেছে?
           • "তুই" বললে → তুমিও "তুই" বলবে
           • "তুমি" বললে → তুমিও "তুমি" বলবে
           • "আপনি" বললে → তুমিও "আপনি" বলবে
        ২. এই মেসেজে কোনো সম্বোধন না থাকলে আগের চ্যাট দেখো — ওখানে যে সম্বোধন
           চলে আসছে, সেটাই ধরে রাখবে।
        ৩. কোথাও কোনো সম্বোধন না পেলে "আপনি" দিয়ে উত্তর দেবে। এটাই ডিফল্ট।
        ৪. ক্রিয়াপদও সম্বোধনের সাথে মিলিয়ে দাও —
           তুই → আছিস, খেয়েছিস, আসিস  ·  তুমি → আছো, খেয়েছো, আসো
           আপনি → আছেন, খেয়েছেন, আসেন
        ৫. নিশ্চিত না হলে "আপনি"। ভুল করে বেশি ঘনিষ্ঠ হয়ে যাওয়ার চেয়ে
           বেশি ভদ্র হয়ে যাওয়া অনেক ভালো।
        ৬. একবার কোনো কন্টাক্টের সম্বোধন ঠিক হলে সেটাই ধরে রাখবে —
           মাঝে মাঝে বদলাবে না।

        উদাহরণ:
        • কন্টাক্ট: "খেয়েছেন?"            → "হ্যাঁ, খেয়েছি। আপনি খেয়েছেন?"
        • কন্টাক্ট: "তুমি কেমন আছো?"      → "ভালো আছি, তুমি কেমন আছো?"
        • কন্টাক্ট: "তুই কেমন আছিস?"      → "ভালো আছি, তুই কেমন আছিস?"
    """.trimIndent()

    /**
     * Answering an English message in Bangla is the fastest way to look like a bot,
     * so mirror the contact's language unless the user turned this off.
     */
    private val LANGUAGE_RULE = """
        ভাষার নিয়ম: কন্টাক্ট যে ভাষায় লিখেছে, সেই ভাষাতেই উত্তর দাও।
        ইংরেজিতে লিখলে ইংরেজিতে, বাংলায় লিখলে বাংলায়, হিন্দিতে লিখলে হিন্দিতে।
        কেউ মিশিয়ে লিখলে (যেমন বাংলা + ইংরেজি) স্বাভাবিক যেভাবে চলে সেভাবেই উত্তর দাও।
        ভাষা অনুমান করতে না পারলে বাংলায় উত্তর দাও।
    """.trimIndent()

    private val TRIAGE_RULES = """
        এখন তোমাকে শুধু উত্তর লিখতে হবে না — আগে ঠিক করবে উত্তর দেওয়া উচিত কি না।

        উত্তরে শুধু এই JSON ফরম্যাট দেবে, এর বাইরে একটি অক্ষরও নয়:
        {"action":"reply","style":"instant","reply":"..."}

        action:
        - "reply"  = উত্তর দিতে হবে (এটাই স্বাভাবিক)
        - "ignore" = উত্তর দেওয়ার দরকার নেই। শুধু এগুলোর জন্য ব্যবহার করবে:
          ফরওয়ার্ড করা মেসেজ, স্টিকার, বিজ্ঞাপন, সিস্টেম মেসেজ, গ্রুপের ফালতু কিছু
        - "hold"   = খুব ব্যক্তিগত, আবেগপূর্ণ, দুঃখের বা গুরুত্বপূর্ণ মেসেজ।
          এগুলোর উত্তর একটা বটের দেওয়া উচিত নয় — মানুষকে নিজে দিতে হবে
          (মন খারাপ, কান্না, অভিমান, সংসার নিয়ে সংকট, বড় কোনো সিদ্ধান্ত)
          তবে খেয়াল রাখো: সাধারণ রোমান্টিক, আদুরে, খুনসুটি বা ফ্লার্টি কথাবার্তা
          hold নয় — সেগুলোর উত্তর দেবে "normal" দিয়ে।

        style:
        - "tiny"    = শুধু খালি সমর্থনসূচক কথা, যেখানে উত্তর দেওয়ার কিছু নেই।
          যেমন: "ok", "হুম", "ঠিক আছে", "হাহা", "👍", শুধু ইমোজি।
          এর উত্তরও তত ছোট হবে — এক-দুই শব্দ, কিছু বোঝানোর দরকার নেই।

          ⚠️ ছোট হলেই tiny নয়। জায়গার নাম, সময়, তারিখ, নাম, হ্যাঁ/না,
          কোনো প্রশ্নের উত্তর, বা কোনো তথ্য থাকলে সেটা "normal" —
          কারণ ওই মেসেজের অর্থ আছে, উত্তরও অর্থপূর্ণ হতে হবে।
          যেমন: "কুর্মিটোলা", "কাল ৫টা", "বাসায় আছি", "হয়েছে", "তাই বলো"
          — এগুলো normal, tiny নয়।
          যদি বুঝতে না পারো মেসেজটা কী বোঝাচ্ছে, tiny দিও না — normal দাও,
          আর দরকার হলে ছোট করে জিজ্ঞেস করো।

        - "instant" = সাধারণ শুভেচ্ছা বা খোঁজখবর, যেমন "সালাম", "কেমন আছো",
          "কি করছো", "খেয়েছো?", "কোথায় আছো?" — সাথে সাথে ছোট উত্তর পাবে
        - "normal"  = বাকি সব

        reply লেখার নিয়ম:
        - reply-তে শুধু পাঠানোর মতো টেক্সট থাকবে — কোনো ব্যাখ্যা, উদ্ধৃতি চিহ্ন বা JSON নয়
        - কন্টাক্ট যত ছোট লিখেছে, উত্তরও তত ছোট। লম্বা মেসেজে লম্বা, ছোট মেসেজে ছোট
        - "হুম" এর উত্তরে গল্প লিখবে না — "হুম" বা "ok" ই যথেষ্ট
        - উত্তরটা মেসেজের সাথে মিলতে হবে। মেসেজে কিছু জানানো হলে সেই বিষয়ে কথা বলবে,
          এলোমেলো ফাঁকা কথা লিখবে না
        - প্রতিটা উত্তরে "ব্যস্ত আছি" লেখার দরকার নেই। সত্যিই দরকার হলে তবেই লিখবে
        - বাংলায় স্বাভাবিক চ্যাটের ভাষায় লিখবে, বইয়ের ভাষায় নয়
        - ইমোজি খুব কম
    """.trimIndent()

    private val PLAIN_RULES = """
        নতুন মেসেজের উত্তর লেখো। শুধু পাঠানোর মতো টেক্সট দাও, কোনো ব্যাখ্যা নয়।
        কন্টাক্ট যত ছোট লিখেছে উত্তরও তত ছোট। বাংলায় স্বাভাবিক চ্যাটের ভাষায়।
    """.trimIndent()

    // ----------------------------------------------------- sleeping mode

    private fun buildGatekeeperPrompt(p: Prefs, sender: String, fromVoice: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("তুমি ").append(p.assistantName).append("। Ali-র assistant।\n")
        sb.append("Ali এখন ").append(p.moodText).append("।\n")
        sb.append("তোমার কাজ কারো মেসেজের উত্তর দেওয়া এবং জরুরি মেসেজ চিনতে পারা।\n\n")
        sb.append("কন্টাক্টের নাম: ").append(sender).append('\n')
        sb.append("এখন সময়: ").append(clockText()).append("\n\n")
        if (p.mirrorRegister) {
            sb.append(REGISTER_RULE).append("\n\n")
        }
        if (p.autoLanguage) {
            sb.append(LANGUAGE_RULE).append("\n\n")
        }
        if (fromVoice) {
            sb.append(VOICE_NOTE_RULE).append("\n\n")
            sb.append("⚠️ মনে রাখো: ট্রান্সক্রিপ্ট ভুল হলে সেটা জরুরি বলে ভেবে ভুল করে অ্যালার্ম বাজাবে না।\n\n")
        }
        sb.append(GATEKEEPER_RULES)
        return sb.toString()
    }

    private val GATEKEEPER_RULES = """
        উত্তরে শুধু এই JSON ফরম্যাট দেবে, এর বাইরে একটি অক্ষরও নয়:
        {"action":"gatekeep","reply":"..."}

        action:
        - "gatekeep" = সাধারণ মেসেজের উত্তর দাও।
        - "alarm"    = জরুরি মেসেজ! Ali-কে ডেকে দিতে হবে।
          জরুরি মানে: দুর্ঘটনা, অসুস্থতা, জরুরি সাহায্য চাওয়া, বড় সমস্যা,
          অনেক বেশি উদ্বিগ্ন/কাঁদতে থাকা, বা কোনো বিপদের কথা।
          সাধারণ খোঁজখবর, ফ্লার্ট, খুনসুটি জরুরি নয়।

        reply লেখার নিয়ম:
        1. কেউ সালাম দিলে (আসসালামু আলাইকুম, সালাম, হাই, হ্যালো) → সালামের উত্তর দাও।
           উদাহরণ: "ওয়ালাইকুম আসসালাম", "ওয়ালাইকুম সালাম, কেমন আছেন?"
        2. সাধারণ কথা (কেমন আছো, কি করছো, খেয়েছো, কোথায় আছো) → "কেমন আছেন?" বা সাদৃশ্য জিজ্ঞাসা করো।
           উদাহরণ: "আমি ঠিক আছি, আপনি কেমন আছেন?", "আমি এখানেই আছি, আপনি কেমন আছেন?"
        3. অন্য কিছু বা প্রশ্ন → "আমি [তোমার নাম], Ali এখন [activity] করছে। জরুরি কিছু হলে বলুন, আমি ডেকে দিচ্ছি।"
        4. কেউ "আপনি কে" জিজ্ঞেস করলে → "আমি [তোমার নাম], Ali-র assistant। জরুরি কিছু হলে বলুন, আমি ডেকে দিচ্ছি।"
        5. কেউ জরুরি সাহায্য চাইলে → "জরুরি মনে হচ্ছে, আমি Ali-কে ডেকে দিচ্ছি। একটু অপেক্ষা করুন।"
        6. ছোট, সরাসরি লেখো — বাংলায় স্বাভাবিক ভাষায়
        7. কোনো ব্যক্তিগত তথ্য শেয়ার করবে না
    """.trimIndent()

    private data class GatekeeperDecision(
        val action: Action,
        val reply: String
    ) {
        enum class Action { GATEKEEP, ALARM }

        companion object {
            fun parse(raw: String): GatekeeperDecision {
                val text = raw.trim()
                val json = extractJson(text) ?: text
                val a = jsonValue(json, "action").lowercase()
                val r = jsonValue(json, "reply")
                val action = when (a) {
                    "alarm" -> Action.ALARM
                    else -> Action.GATEKEEP
                }
                return GatekeeperDecision(action, r)
            }

            private fun extractJson(raw: String): String? {
                val start = raw.indexOf('{')
                val end = raw.lastIndexOf('}')
                return if (start >= 0 && end > start) raw.substring(start, end + 1) else null
            }

            private fun jsonValue(raw: String, key: String): String {
                val pat = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
                return pat.find(raw)?.groupValues?.get(1) ?: ""
            }
        }
    }

    // --------------------------------------------------------------- helpers

    fun normalizeNumber(raw: String, cc: String): String? {
        var d = raw.filter { it.isDigit() }
        if (d.isEmpty()) return null
        if (d.startsWith("00")) d = d.substring(2)
        val code = cc.ifBlank { "880" }
        d = when {
            d.startsWith(code) -> d
            d.startsWith("0") -> code + d.substring(1)
            d.length <= 10 -> code + d
            else -> d
        }
        return d.takeIf { it.length >= 8 }
    }

    private fun withinHours(p: Prefs): Boolean {
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMin = p.hourStart * 60 + p.hourStartMin
        val endMin = p.hourEnd * 60 + p.hourEndMin
        return if (startMin <= endMin) nowMin in startMin until endMin else (nowMin >= startMin || nowMin < endMin)
    }

    private fun clockText(): String =
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())

    /** "9:45" or "19:30" — for log messages. */
    private fun timeLabel(h: Int, m: Int): String =
        if (m == 0) "${h}টা" else "${h}:${String.format("%02d", m)}"

    private fun log(ctx: Context, line: String) = LogStore.add(ctx, line)
}
