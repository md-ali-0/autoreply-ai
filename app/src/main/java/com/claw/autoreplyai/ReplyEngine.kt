package com.claw.autoreplyai

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
        direct: DirectReplier.Handle?
    ) {
        val app = ctx.applicationContext
        val p = Prefs.get(app)

        if (!p.enabled) return
        if (message.isBlank()) return

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
            return
        }

        if (p.onlyContacts && !p.moodEnabled) {
            val allowed = p.contactList
                .split('\n', ',', ';')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
            if (allowed.none { sender.lowercase().contains(it) }) {
                log(app, "ফিল্টার লিস্টে নেই — $sender")
                return
            }
        }

        // Inside the cooldown: never drop the message. Remember the newest one and
        // answer it once the window closes — otherwise the contact gets a reply to
        // an older message and silence on the one they actually just sent, which
        // reads as the bot answering the wrong thing.
        val remaining = p.cooldownSec * 1000L - (System.currentTimeMillis() - p.lastReply(sender))
        if (remaining > 0) {
            pending[sender] = Pending(pkg, sender, message, phoneHint, direct)
            log(app, "কুলডাউনে — $sender (নতুন মেসেজটা পরে উত্তর দেওয়া হবে)")
            schedulePending(app, sender, remaining)
            return
        }

        if (!inFlight.add(sender)) {
            // Same reasoning as the cooldown — never lose the message, queue it.
            pending[sender] = Pending(pkg, sender, message, phoneHint, direct)
            log(app, "আগের রিপ্লাই চলছে — $sender (এই মেসেজটা পরে উত্তর দেওয়া হবে)")
            schedulePending(app, sender, 3_000L)
            return
        }

        scope.launch {
            try {
                handle(app, p, pkg, sender, message, phoneHint, direct)
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
        val direct: DirectReplier.Handle?
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
                handle(app, p, msg.pkg, msg.sender, msg.message, msg.phoneHint, msg.direct)
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
        direct: DirectReplier.Handle?
    ) {
        if (p.moodEnabled) {
            handleMood(app, p, pkg, sender, message, phoneHint, direct)
            return
        }

        val messages = ArrayList<AiClient.Msg>()
        val contactContext = ContactContext.forContact(app, sender)
        messages.add(AiClient.Msg("system", buildSystemPrompt(p, sender, contactContext)))
        messages.addAll(trimmedHistory(app, sender))
        messages.add(AiClient.Msg("user", message))

        val raw = try {
            askAi(p, messages)
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
        val preview = message.replace("\n", " ").take(70)

        when (decision.action) {
            ReplyDecision.Action.IGNORE -> {
                log(app, "উত্তর দেওয়া হলো না (দরকার নেই) — $sender: $preview")
                // Record it anyway: a history with holes is what makes the next
                // reply look like it came out of nowhere.
                ChatMemory.add(app, sender, "user", message)
                return
            }

            ReplyDecision.Action.HOLD -> {
                if (p.holdOnEmotional) {
                    log(app, "⚠️ নিজে উত্তর দিন — $sender: $preview")
                    ChatMemory.add(app, sender, "user", message)
                    Notify.needsYou(app, sender, message)
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

        deliverReply(app, p, pkg, sender, message, finalText, reply, contactContext.length, phoneHint, direct)
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
        direct: DirectReplier.Handle?
    ) {
        val messages = ArrayList<AiClient.Msg>()
        messages.add(AiClient.Msg("system", buildGatekeeperPrompt(p, sender)))
        messages.addAll(trimmedHistory(app, sender))
        messages.add(AiClient.Msg("user", message))

        val raw = try {
            askAi(p, messages)
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
        deliverReply(app, p, pkg, sender, message, reply, reply, 0, phoneHint, direct)
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
        direct: DirectReplier.Handle?
    ) {
        // Route 1: the notification's own reply action (works while locked).
        if (direct != null && DirectReplier.send(app, direct, finalText)) {
            onSent(app, p, sender, message, reply, contextChars)
            return
        }
        if (direct == null) {
            log(app, "নোটিফিকেশনে reply অ্যাকশন নেই — বিকল্প পথে যাচ্ছি")
        }

        // Route 2: open the chat and tap Send (needs an unlocked screen).
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

        val deferred = CompletableDeferred<Boolean>()
        SendAccessibilityService.send(app, pkg, waNumber, finalText) { deferred.complete(it) }

        scope.launch {
            val ok = withTimeoutOrNull(35_000L) { deferred.await() } ?: false
            if (ok) {
                onSent(app, p, sender, message, reply, contextChars)
            } else {
                log(app, "✗ পাঠানো যায়নি → $sender (ফোন লক থাকলে লক খুলে রাখুন)")
            }
        }
    }

    private fun onSent(
        app: Context,
        p: Prefs,
        sender: String,
        message: String,
        reply: String,
        contextChars: Int
    ) {
        ChatMemory.add(app, sender, "user", message)
        ChatMemory.add(app, sender, "assistant", reply)
        p.setLastReply(sender, System.currentTimeMillis())
        // Remember what we just sent so we don't reply to ourselves when
        // WhatsApp echoes it back as a new notification.
        SentMessageTracker.record(reply)
        // The context length is shown so a broken contact-name match is visible
        // instead of silently producing replies that ignore the context.
        log(
            app,
            "✓ রিপ্লাই → $sender · কন্টেক্সট $contextChars অক্ষর: ${reply.replace("\n", " ").take(100)}"
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

    /** One retry — mobile networks and free API tiers drop requests regularly. */
    private suspend fun askAi(p: Prefs, messages: List<AiClient.Msg>): String {
        return try {
            AiClient.chat(p.baseUrl, p.apiKey, p.model, messages)
        } catch (e: Exception) {
            delay(1_500L)
            AiClient.chat(p.baseUrl, p.apiKey, p.model, messages)
        }
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

    private fun buildSystemPrompt(p: Prefs, sender: String, contactContext: String): String {
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

        sb.append('\n')
        sb.append(if (p.smartTriage) TRIAGE_RULES else PLAIN_RULES)
        return sb.toString()
    }

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

    private fun buildGatekeeperPrompt(p: Prefs, sender: String): String {
        val sb = StringBuilder()
        sb.append("তুমি ").append(p.assistantName).append("। Ali-র assistant।\n")
        sb.append("Ali এখন ").append(p.moodText).append("।\n")
        sb.append("তোমার কাজ কারো মেসেজের উত্তর দেওয়া এবং জরুরি মেসেজ চিনতে পারা।\n\n")
        sb.append("কন্টাক্টের নাম: ").append(sender).append('\n')
        sb.append("এখন সময়: ").append(clockText()).append("\n\n")
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
