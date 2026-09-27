package com.claw.autoreplyai

import org.json.JSONObject

/**
 * The model answers with a single JSON object so the app can decide *whether* and
 * *how fast* to reply — not just what to say.
 *
 * If the model ignores the format (small models sometimes do), [parse] degrades
 * gracefully by treating the whole answer as the reply text.
 */
data class ReplyDecision(
    val action: Action,
    val style: Style,
    val reply: String
) {
    enum class Action { REPLY, IGNORE, HOLD }
    enum class Style { TINY, INSTANT, NORMAL }

    companion object {

        fun plain(text: String) = ReplyDecision(Action.REPLY, Style.NORMAL, text.trim())

        fun parse(raw: String): ReplyDecision {
            val cleaned = stripFences(raw)
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) return plain(raw)

            val json = try {
                JSONObject(cleaned.substring(start, end + 1))
            } catch (e: Exception) {
                return plain(raw)
            }

            val action = when (json.optString("action").trim().lowercase()) {
                "ignore" -> Action.IGNORE
                "hold" -> Action.HOLD
                else -> Action.REPLY
            }
            val style = when (json.optString("style").trim().lowercase()) {
                "tiny" -> Style.TINY
                "instant" -> Style.INSTANT
                else -> Style.NORMAL
            }
            val reply = json.optString("reply").trim()

            // A "reply" with no text is a broken answer — fall back to the raw output.
            if (action == Action.REPLY && reply.isEmpty()) return plain(raw)

            return ReplyDecision(action, style, reply)
        }

        private fun stripFences(s: String): String {
            var t = s.trim()
            if (t.startsWith("```")) {
                t = t.removePrefix("```json").removePrefix("```").trim()
                if (t.endsWith("```")) t = t.dropLast(3).trim()
            }
            return t
        }
    }
}
