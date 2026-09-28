package com.claw.autoreplyai

/**
 * Last line of defence between the model's output and a real person's inbox.
 *
 * The system prompt already tells the model not to promise meetings, money, marriage,
 * or anything else it cannot deliver. But a prompt is a request, not a guarantee, and
 * the whole design of this app assumes the model will sometimes ignore its
 * instructions — that is why there is a gatekeeper at all.
 *
 * Everything up to here is probabilistic. This is the one deterministic step: a short,
 * readable list of the things that must never go out unreviewed. It deliberately errs
 * toward holding — a held reply costs the user a tap, while a sent one cannot be taken
 * back, and the user cannot un-say something their phone said on their behalf.
 *
 * Only patterns that are unambiguous enough to be worth acting on belong here. A
 * validator with false positives trains the user to ignore it.
 */
object ReplyValidator {

    /** Outcome of a check. [reason] is shown to the user, so it must read plainly. */
    data class Result(val allowed: Boolean, val reason: String?) {
        companion object {
            val OK = Result(true, null)
        }
    }

    /** One rule: a plain-language label for the user, plus the pattern that trips it. */
    private data class Rule(
        val label: String,
        val pattern: Regex
    )

    /**
     * Commitments the user cannot honour on the bot's behalf. Kept intentionally narrow:
     * each of these is a thing that would need a real decision from a real person.
     */
    private val RULES: List<Rule> = listOf(
        Rule(
            "টাকা পাঠানোর কথা",
            Regex(
                "\\b(i (will |can )?(send|transfer|pay|give) (you )?(the )?money" +
                        "|i('ll| will) (send|transfer|pay|give) you" +
                        "|বিকাশ কর(ে|ব)? দি|টাকা (পাঠিয়ে|দিয়ে) দি|টাকা দেব)\\b",
                RegexOption.IGNORE_CASE
            )
        ),
        Rule(
            "লেনদেনের নিশ্চয়তা",
            Regex(
                "\\b(bkash|nagad|rocket|bikash)\\b" +
                        "|\\b(account|একাউন্ট) (number|নাম্বার)\\b" +
                        "|\\b\\d{11}\\b",
                RegexOption.IGNORE_CASE
            )
        ),
        Rule(
            "বিয়ের প্রতিশ্রুতি",
            Regex(
                "\\b(i (will|want to) marry you|let'?s get married|will you marry me)\\b" +
                        "|বিয়ে কর(ব|ে নেব)|বিয়ে করার প্রতিশ্রুতি",
                RegexOption.IGNORE_CASE
            )
        ),
        Rule(
            "মিটিং/অ্যাপয়েন্টমেন্টের প্রতিশ্রুতি",
            Regex(
                "\\b(i (will|'ll) (meet|come|visit) you|see you (there|then) at)\\b" +
                        "|আস(ব|ে যাব)|দেখা করব|অ্যাপয়েন্টমেন্ট (দিয়ে|করে) দিলাম",
                RegexOption.IGNORE_CASE
            )
        ),
        Rule(
            "গোপন তথ্য চাওয়া বা দেওয়া",
            Regex(
                "\\b(otp|one[- ]time password|verification code|pin|cvv|password)\\b" +
                        "|পাসওয়ার্ড|ওটিপি|ভেরিফিকেশন কোড",
                RegexOption.IGNORE_CASE
            )
        ),
        Rule(
            "চিকিৎসা বা আইনি পরামর্শ",
            Regex(
                "\\b(you (should|must) take|diagnos|prescri(be|ption)|dosage|mg (twice|once))" +
                        "|আইনজীবী|উকিল|মামলা কর|ওষুধ (খাও|নাও)|ডোজ",
                RegexOption.IGNORE_CASE
            )
        ),
        Rule(
            "চাকরি বা চুক্তির প্রতিশ্রুতি",
            Regex(
                "\\b(you'?re hired|i'?ll (hire|give you the job)|salary will be)\\b" +
                        "|চাকরি দিয়ে দিলাম|নিয়োগ দিয়ে দিলাম|বেতন (দিয়ে|হবে)",
                RegexOption.IGNORE_CASE
            )
        )
    )

    /**
     * Immediate structural rejects — things that are certainly not a reply, regardless
     * of content. A held reply that is obviously broken only wastes attention.
     */
    private val LEAK_PATTERNS = listOf(
        Regex("<\\|.*?\\|>"),            // chat-template tokens leaking through
        Regex("^(Assistant|System|User)\\s*:", RegexOption.IGNORE_CASE),
        Regex("\\[\\s*INST\\s*\\]", RegexOption.IGNORE_CASE)
    )

    /**
     * Check a candidate reply. [context] is the incoming message, used only for the
     * special case where the *incoming* message is itself asking for credentials —
     * answering those is never safe, however innocent the wording.
     */
    fun validate(reply: String, context: String = ""): Result {
        val text = reply.trim()
        if (text.isEmpty()) return Result(false, "উত্তর খালি")

        // Model plumbing must never reach a human. These are always wrong.
        for (p in LEAK_PATTERNS) {
            if (p.containsMatchIn(text)) {
                return Result(false, "উত্তরে মডেলের ভেতরের টোকেন এসে গেছে")
            }
        }

        // A reply that is nothing but a URL or a phone number is a strong sign the
        // model is answering a different question than the one it was asked.
        if (text.length < 40 && Regex("^(https?://\\S+|\\+?\\d{9,})$").matches(text)) {
            return Result(false, "উত্তরটা শুধুই লিংক বা নাম্বার")
        }

        for (rule in RULES) {
            if (rule.pattern.containsMatchIn(text)) {
                return Result(false, "উত্তরে ${rule.label} আছে — নিজে দেখে পাঠান")
            }
        }

        return Result.OK
    }

    /**
     * Whether the *incoming* message is something the bot should never answer on the
     * user's behalf, no matter how well-formed the reply looks. Catching this before
     * the model runs saves a request and removes any chance of a plausible-sounding
     * wrong answer.
     */
    fun shouldHoldBeforeReply(incoming: String): String? {
        val t = incoming.lowercase()
        return when {
            Regex("\\b(otp|one[- ]time password|verification code)\\b").containsMatchIn(t) ->
                "ওটিপি বা ভেরিফিকেশন কোড চাওয়া হয়েছে"

            Regex("\\b(password|passcode|pin|cvv)\\b").containsMatchIn(t) ->
                "পাসওয়ার্ড বা PIN চাওয়া হয়েছে"

            Regex("\\b(bkash|nagad|rocket)\\b.*\\b\\d{6,}\\b").containsMatchIn(t) ->
                "মোবাইল ব্যাংকিং তথ্য চাওয়া হয়েছে"

            Regex("\\b(chest pain|heart attack|bleeding|unconscious|overdose|breath(e|ing) (problem|difficulty))\\b")
                .containsMatchIn(t) -> "চিকিৎসা সংক্রান্ত জরুরি অবস্থা"

            Regex("\\b(lawyer|legal action|police|fir|case file|lawsuit)\\b").containsMatchIn(t) ->
                "আইনি বিষয়"

            else -> null
        }
    }
}
