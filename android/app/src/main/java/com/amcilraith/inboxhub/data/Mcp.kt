package com.amcilraith.inboxhub.data

import com.amcilraith.inboxhub.auth.Auth
import com.amcilraith.inboxhub.auth.SignInRequired
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicLong

/** Minimal MCP client (streamable HTTP) for Superhuman's tools — same protocol as the desktop app. */
class Mcp(private val http: OkHttpClient, private val auth: Auth, private val url: String = Auth.RESOURCE) {
    private val protocol = "2025-06-18"
    private val ids = AtomicLong(1)
    private val sessionLock = Mutex()
    private var session: String? = null
    private var ready = false

    private class Unauthorized : Exception()
    private class SessionGone : Exception()

    private suspend fun post(token: String, body: JsonObject, sessionId: String?): Pair<JsonElement?, String?> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", protocol)
            .apply { if (sessionId != null) header("Mcp-Session-Id", sessionId) }
            .post(Auth.jsonBody(body.toString()))
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) throw Unauthorized()
            if (r.code == 404 && sessionId != null) throw SessionGone()
            val sid = r.header("mcp-session-id")
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw Exception("Superhuman returned ${r.code}: ${text.take(200)}")
            if (text.isBlank()) return@use null to sid
            val want = body["id"]
            val msg = if ((r.header("content-type") ?: "").contains("text/event-stream")) {
                text.lineSequence().filter { it.startsWith("data:") }
                    .mapNotNull { runCatching { json.parseToJsonElement(it.removePrefix("data:").trim()) }.getOrNull() }
                    .firstOrNull { want == null || it["id"] == want }
            } else json.parseToJsonElement(text)
            msg to sid
        }
    }

    private suspend fun ensureSession(token: String): String? = sessionLock.withLock {
        if (ready) return session
        val init = obj(
            "jsonrpc" to "2.0", "id" to ids.getAndIncrement(), "method" to "initialize",
            "params" to obj(
                "protocolVersion" to protocol,
                "capabilities" to obj(),
                "clientInfo" to obj("name" to "inbox-hub-android", "version" to "1"),
            ),
        )
        val (resp, sid) = post(token, init, null)
        resp["error"]?.let { throw Exception("Superhuman: $it") }
        runCatching { post(token, obj("jsonrpc" to "2.0", "method" to "notifications/initialized"), sid) }
        session = sid
        ready = true
        sid
    }

    private suspend fun tryCall(token: String, name: String, args: JsonObject): JsonElement {
        val sid = ensureSession(token)
        val req = obj("jsonrpc" to "2.0", "id" to ids.getAndIncrement(), "method" to "tools/call", "params" to obj("name" to name, "arguments" to args))
        val (resp, _) = post(token, req, sid)
        resp ?: throw Exception("Empty response from $name")
        resp["error"]?.let { throw Exception("$name: ${it["message"].str() ?: it}") }
        val result = resp["result"]
        val text = result["content"].arr().mapNotNull { it["text"].str() }.joinToString("\n")
        if (result["isError"].bool() == true) throw Exception("$name: ${text.take(300)}")
        result["structuredContent"]?.takeIf { it is JsonObject }?.let { return it }
        return runCatching { json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
    }

    /** Call a Superhuman tool. [account] (an email) selects a linked account; null = primary. */
    suspend fun call(name: String, args: JsonObject = JsonObject(emptyMap()), account: String? = null): JsonElement {
        val full = if (account != null && account.contains('@')) JsonObject(args + ("acting_email" to JsonPrimitive(account))) else args
        var token = auth.accessToken()
        repeat(3) { attempt ->
            try {
                return tryCall(token, name, full)
            } catch (e: Unauthorized) {
                sessionLock.withLock { ready = false }
                if (attempt > 0) throw SignInRequired("Superhuman rejected the sign-in — sign in again")
                token = auth.accessToken(force = true)
            } catch (e: SessionGone) {
                sessionLock.withLock { ready = false }
            }
        }
        throw Exception("$name failed after retries")
    }
}
