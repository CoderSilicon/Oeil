package com.example.oeil.data

import com.example.oeil.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AuthSession(val user: User, val token: String)

/** Calls the email/password account endpoints. */
class AuthClient(backendUrl: String) {
    private val baseUrl = backendUrl.trimEnd('/')

    suspend fun signUp(name: String, email: String, password: String): AuthSession =
        withContext(Dispatchers.IO) {
            val response = request(
                "/auth/signup",
                JSONObject()
                    .put("name", name)
                    .put("email", email)
                    .put("password", password),
            )
            response.toSession()
        }

    suspend fun signIn(email: String, password: String): AuthSession =
        withContext(Dispatchers.IO) {
            request(
                "/auth/login",
                JSONObject().put("email", email).put("password", password),
            ).toSession()
        }

    suspend fun currentUser(token: String): User = withContext(Dispatchers.IO) {
        request("/auth/me", token = token).toUser()
    }

    suspend fun signOut(token: String) = withContext(Dispatchers.IO) {
        request("/auth/logout", JSONObject(), token)
    }

    private fun request(
        path: String,
        body: JSONObject? = null,
        token: String? = null,
    ): JSONObject {
        if (baseUrl.isBlank()) {
            throw IOException("Set the backend URL in Settings before signing in.")
        }
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(response).optString("detail") }.getOrNull()
                throw IOException(
                    detail?.takeIf(String::isNotBlank) ?: "Account request failed (HTTP $code)."
                )
            }
            return try {
                JSONObject(response)
            } catch (error: org.json.JSONException) {
                throw IOException("The account server returned an unreadable response.", error)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toSession(): AuthSession {
        val token = optString("token").takeIf(String::isNotBlank)
            ?: throw IOException("The account server did not return a session.")
        return AuthSession(getJSONObject("user").toUser(), token)
    }

    private fun JSONObject.toUser(): User {
        val id = optString("id").takeIf(String::isNotBlank)
            ?: throw IOException("The account server returned an invalid account.")
        return User(
            id = id,
            name = optString("name").ifBlank { "Oeil account" },
            email = optString("email").ifBlank { null },
        )
    }

}
