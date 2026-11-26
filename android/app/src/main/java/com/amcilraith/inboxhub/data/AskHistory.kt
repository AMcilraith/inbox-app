package com.amcilraith.inboxhub.data

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Ask conversation shared with the desktop app. It lives in a private snippet in the
 * primary Superhuman account ("Inbox Hub — Ask history"), in exactly the same format as the
 * desktop's hub.rs: `inboxhub-ask-v1:` + base64(JSON {cleared_at, msgs:[{id, role, text, at, device}]}).
 */
data class ChatMsg(val id: String, val role: String, val text: String, val at: Long, val device: String)

data class AskHistory(val clearedAt: Long = 0, val msgs: List<ChatMsg> = emptyList()) {
    companion object {
        const val SNIPPET = "Inbox Hub — Ask history"
        private const val TAG = "inboxhub-ask-v1:"
        private const val KEEP = 100

        fun decode(body: String): AskHistory? {
            val start = body.indexOf(TAG).takeIf { it >= 0 }?.plus(TAG.length) ?: return null
            val b64 = body.substring(start).takeWhile { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
            return runCatching {
                val v = json.parseToJsonElement(String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8))
                AskHistory(
                    clearedAt = v["cleared_at"].long() ?: 0,
                    msgs = v["msgs"].arr().mapNotNull { m ->
                        ChatMsg(
                            id = m["id"].str() ?: return@mapNotNull null,
                            role = m["role"].str() ?: "ai",
                            text = m["text"].str().orEmpty(),
                            at = m["at"].long() ?: 0,
                            device = m["device"].str().orEmpty(),
                        )
                    },
                )
            }.getOrNull()
        }
    }

    fun encode(): String {
        val v: JsonElement = JsonObject(
            mapOf(
                "cleared_at" to JsonPrimitive(clearedAt),
                "msgs" to JsonArray(msgs.map {
                    JsonObject(mapOf("id" to JsonPrimitive(it.id), "role" to JsonPrimitive(it.role), "text" to JsonPrimitive(it.text), "at" to JsonPrimitive(it.at), "device" to JsonPrimitive(it.device)))
                }),
            ),
        )
        val b64 = Base64.encodeToString(v.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "<p>Inbox Hub keeps your Ask conversation here so your phone and computer stay in sync. You can ignore this snippet.</p><p>$TAG$b64</p>"
    }

    /** Union by id, drop anything before the latest "clear", oldest first, capped. */
    fun merge(other: AskHistory): AskHistory {
        val cut = maxOf(clearedAt, other.clearedAt)
        val all = (msgs + other.msgs).distinctBy { it.id }.filter { it.at > cut }.sortedBy { it.at }.takeLast(KEEP)
        return AskHistory(cut, all)
    }
}
