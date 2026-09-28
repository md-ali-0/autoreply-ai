package com.claw.autoreplyai

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal OpenAI-compatible chat client.
 * Works with OpenAI, OpenRouter, Groq, DeepSeek, Together, local llama.cpp, etc.
 */
object AiClient {

    /**
     * Hard ceiling on a chat-completion response body. A real completion is a few
     * kilobytes; anything near this means the base URL is not pointing at a chat API.
     */
    private const val MAX_RESPONSE_BYTES = 1_000_000L

    /**
     * One chat message. [imageBase64] turns it into a multimodal message — the
     * OpenAI content-parts shape, which is what every vision gateway accepts.
     */
    data class Msg(
        val role: String,
        val content: String,
        val imageBase64: String? = null
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .build()

    /**
     * Turn a user-entered base URL into a chat-completions endpoint, or explain why it
     * cannot be one.
     *
     * Accepts what people actually type: a missing scheme (`api.example.com/v1`), a
     * trailing slash, or the full endpoint (`…/v1/chat/completions`). Anything that is
     * not `http`/`https`, has no host, or contains whitespace is rejected — those are
     * never a valid API base and would otherwise fail deep inside OkHttp or, in the
     * worst case, send the API key somewhere unintended.
     */
    @Throws(IOException::class)
    fun normalizeBaseUrl(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) throw IOException("API Base URL খালি")

        if (s.any { it.isWhitespace() }) throw IOException("API Base URL-এ ফাঁকা জায়গা আছে")

        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "https://$s"
        }
        val lower = s.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw IOException("API Base URL http:// বা https:// দিয়ে শুরু হতে হবে")
        }

        val afterScheme = s.substringAfter("://")
        val host = afterScheme.substringBefore('/').substringBefore('?')
        if (host.isBlank()) throw IOException("API Base URL-এ কোনো হোস্ট নেই")
        if (!host.contains('.') && !host.contains(':') && !host.equals("localhost", true)) {
            throw IOException("API Base URL-এর হোস্ট ঠিক দেখাচ্ছে না: $host")
        }

        s = s.trimEnd('/')
        // The user may have pasted the full endpoint; do not append a second time.
        if (s.endsWith("/chat/completions")) return s
        return "$s/chat/completions"
    }

    @Throws(IOException::class)
    fun chat(baseUrl: String, apiKey: String, model: String, messages: List<Msg>): String {
        if (baseUrl.isBlank()) throw IOException("API Base URL খালি")
        if (model.isBlank()) throw IOException("মডেল খালি")

        // Validate before building the request. A typo'd or hand-edited URL otherwise
        // surfaces as whatever the network stack says — "no address associated with
        // hostname", or worse, a request quietly sent to the wrong origin, which would
        // take the API key with it. Cheap to check, so check here rather than per call.
        val url = normalizeBaseUrl(baseUrl)

        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject().put("role", m.role)
            val img = m.imageBase64
            if (img.isNullOrBlank()) {
                o.put("content", m.content)
            } else {
                // "low" detail: a fixed ~85 tokens instead of up to ~1500. Plenty for
                // "what is this and does it need an answer".
                val parts = JSONArray()
                parts.put(JSONObject().put("type", "text").put("text", m.content))
                parts.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject()
                            .put("url", "data:image/jpeg;base64,$img")
                            .put("detail", "low")
                    )
                )
                o.put("content", parts)
            }
            arr.put(o)
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", arr)
            .put("temperature", 0.85)
            .put("max_tokens", 260)

        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body)
        if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")

        client.newCall(builder.build()).execute().use { resp ->
            // Read with a ceiling rather than `body.string()`, which will happily
            // allocate whatever the server sends. A misconfigured base URL pointing at
            // something that is not a chat API can return megabytes, and the whole
            // reply pipeline runs on a background thread the app cannot afford to lose
            // to an OOM. 1 MB is far more than any real completion needs.
            val raw = readBounded(resp.body, MAX_RESPONSE_BYTES)
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} — ${raw.take(240)}")
            }
            val json = JSONObject(raw)
            val choices = json.optJSONArray("choices")
                ?: throw IOException("অপ্রত্যাশিত রেসপন্স: ${raw.take(200)}")
            if (choices.length() == 0) throw IOException("AI খালি রেসপন্স দিয়েছে")
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: throw IOException("message ফিল্ড নেই")

            // Reasoning models (deepseek-r1 family and the many clones of it) split
            // their output in two: the visible answer goes in `content`, the thinking
            // goes in `reasoning_content`.
            //
            // Only `content` is ever sent to a human. `reasoning_content` is the model's
            // private deliberation — "the user is asking how I am, I should reply
            // politely, maybe say…" — and delivering that reads as the bot thinking out
            // loud with the real answer missing. It is used only to decide *whether* to
            // retry, never as the reply text itself.
            val content = message.optString("content", "").trim()
            if (content.isNotEmpty()) return content

            val reasoning = message.optString("reasoning_content", "").trim()
            val finish = choices.getJSONObject(0).optString("finish_reason", "?")

            if (reasoning.isNotEmpty()) {
                // The gateway filled in the thinking but not the answer. Retrying is the
                // right move: the same model very often returns a proper `content` on a
                // second pass, and if it does not, the caller falls through to the next
                // provider instead of shipping the model's internal monologue.
                throw BlankAnswerException(
                    "খালি content (শুধু reasoning এসেছে) — মডেল: $model, finish_reason=$finish"
                )
            }

            // Nothing at all: say *why* rather than returning "". A bare empty string is
            // indistinguishable from a model that chose to say nothing, and that is
            // exactly what made this bug invisible for a whole day.
            throw BlankAnswerException(
                "খালি উত্তর — content ও reasoning_content দুটোই ফাঁকা " +
                        "(মডেল: $model, finish_reason=$finish)"
            )
        }
    }

    /**
     * A response that carried no usable answer — distinct from a transport failure, so
     * the caller can retry or fall through to another provider rather than surfacing a
     * half-formed reply.
     */
    class BlankAnswerException(message: String) : IOException(message)

    /**
     * Read an HTTP body with a hard byte ceiling. Returns what was read; throws as soon
     * as the ceiling is crossed, so a hostile or misconfigured endpoint cannot exhaust
     * memory before we notice.
     */
    private fun readBounded(body: okhttp3.ResponseBody?, limit: Long): String {
        if (body == null) return ""
        val declared = body.contentLength()
        if (declared > limit) {
            throw IOException("রেসপন্স অনেক বড় ($declared বাইট > $limit) — বাদ দেওয়া হলো")
        }
        val buf = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        body.byteStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > limit) {
                    throw IOException("রেসপন্স সীমা ছাড়িয়ে গেছে (>$limit বাইট) — বাদ দেওয়া হলো")
                }
                out.write(buf, 0, n)
            }
        }
        return out.toString("UTF-8")
    }

    /**
     * Same call, but walks the saved provider list (selected first) until one
     * answers. Used by one-off features such as the digest, where a per-request
     * provider switch would be surprising.
     */
    @Throws(IOException::class)
    fun askWithFallback(ctx: Context, messages: List<Msg>): String {
        val ordered = AiProviderStore.prioritized(ctx)
        if (ordered.isEmpty()) throw IOException("কোনো AI প্রোভাইডার সেট আপ নেই")

        var last: Exception? = null
        for (provider in ordered) {
            try {
                val out = chat(provider.baseUrl, provider.apiKey, provider.model, messages)
                if (out.isNotBlank()) return out
                last = IOException("${provider.name} খালি উত্তর দিয়েছে")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("সব প্রোভাইডার ব্যর্থ")
    }
}
