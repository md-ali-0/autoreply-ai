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

    @Throws(IOException::class)
    fun chat(baseUrl: String, apiKey: String, model: String, messages: List<Msg>): String {
        if (baseUrl.isBlank()) throw IOException("API Base URL খালি")
        if (model.isBlank()) throw IOException("মডেল খালি")

        val url = baseUrl.trim().trimEnd('/') + "/chat/completions"

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
            val raw = resp.body?.string().orEmpty()
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
            // goes in `reasoning_content`. Some gateways put the whole answer in
            // `reasoning_content` and leave `content` empty — which is what apinex.bond
            // did six times in a row on 28 Sep. Prefer `reasoning_content` over giving
            // up: an answer that needs a trim beats no answer at all.
            val content = message.optString("content", "").trim()
            if (content.isNotEmpty()) return content

            val reasoning = message.optString("reasoning_content", "").trim()
            if (reasoning.isNotEmpty()) return reasoning

            // Both empty: say *why* rather than returning "". A bare empty string is
            // indistinguishable from a model that chose to say nothing, and that is
            // exactly what made this bug invisible for a whole day.
            throw IOException(
                "খালি উত্তর — content ও reasoning_content দুটোই ফাঁকা " +
                        "(মডেল: $model, finish_reason=" +
                        choices.getJSONObject(0).optString("finish_reason", "?") + ")"
            )
        }
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
