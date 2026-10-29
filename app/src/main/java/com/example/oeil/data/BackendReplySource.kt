package com.example.oeil.data

import com.example.oeil.model.ChatMessage
import com.example.oeil.model.ReplyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Calls the Oeil relay; provider credentials never leave the server. */
class BackendReplySource(backendUrl: String) : ConversationReplySource {

    private val chatUrl = "${backendUrl.trimEnd('/')}/chat"

    override suspend fun reply(messages: List<ChatMessage>, mode: ReplyMode): String =
        withContext(Dispatchers.IO) {
            if (chatUrl == "/chat") {
                throw IllegalStateException(
                    "Backend URL is not configured. Open the menu and choose Settings."
                )
            }
            request(buildBody(messages, mode))
        }

    private fun buildBody(messages: List<ChatMessage>, mode: ReplyMode): String {
        val payload = JSONArray()
        messages.takeLast(MAX_MESSAGES).forEach { message ->
            val text = buildString {
                message.attachmentLabel?.let {
                    append("[Attached image: ").append(it).append("]\n")
                }
                append(message.content)
            }
            if (text.isNotBlank()) {
                payload.put(
                    JSONObject()
                        .put("role", if (message.isUser) "user" else "assistant")
                        .put("content", text)
                )
            }
        }

        return JSONObject()
            .put("mode", mode.name.uppercase(Locale.ROOT))
            .put("messages", payload)
            .toString()
    }

    private fun request(body: String): String {
        val connection = URL(chatUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")

            connection.outputStream.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                throw IOException(errorMessage(response, code))
            }

            val json = runCatching { JSONObject(response) }.getOrElse {
                throw IOException("The chat server returned an unreadable response.")
            }
            val reply = json.optString("reply").trim()
            if (reply.isBlank()) {
                throw IOException("The chat server returned an empty reply.")
            }
            return reply
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(payload: String, code: Int): String {
        val message = runCatching {
            val json = JSONObject(payload)
            when (val error = json.opt("error")) {
                is JSONObject -> error.optString("message")
                is String -> error
                else -> json.optString("detail").ifBlank { json.optString("message") }
            }
        }.getOrNull()
        return message?.takeIf { it.isNotBlank() } ?: "AI request failed (HTTP $code)."
    }

    private companion object {
        const val MAX_MESSAGES = 50
    }
}
