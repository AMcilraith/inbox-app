package com.amcilraith.inboxhub.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Instant

/**
 * The last inbox and accounts, kept on the phone so the app opens straight onto your mail and
 * refreshes in the background instead of starting from a spinner. Private app storage only.
 */
class DiskCache(context: Context) {
    private val file = File(context.cacheDir, "inbox-v1.json")

    data class Snapshot(val accounts: List<Account>, val threads: List<ThreadSummary>)

    suspend fun load(): Snapshot? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching null
            val v = json.parseToJsonElement(file.readText())
            Snapshot(
                accounts = v["accounts"].arr().mapNotNull { a ->
                    Account(a["email"].str() ?: return@mapNotNull null, a["primary"].bool() ?: false, a["color"].long() ?: 0xFF6B6B73, a["label"].str() ?: "")
                },
                threads = v["threads"].arr().mapNotNull { t ->
                    ThreadSummary(
                        account = t["account"].str() ?: return@mapNotNull null,
                        id = t["id"].str() ?: return@mapNotNull null,
                        lastMessageId = t["last"].str().orEmpty(),
                        subject = t["subject"].str().orEmpty(),
                        snippet = t["snippet"].str().orEmpty(),
                        from = t["from"].str().orEmpty(),
                        fromName = t["fromName"].str().orEmpty(),
                        date = Instant.ofEpochMilli(t["date"].long() ?: 0),
                        unread = t["unread"].bool() ?: false,
                        important = t["important"].bool() ?: false,
                        starred = t["starred"].bool() ?: false,
                        needsReply = t["needsReply"].bool() ?: false,
                        messageCount = (t["count"].long() ?: 1).toInt(),
                        labels = t["labels"].strs(),
                    )
                },
            )
        }.getOrNull()
    }

    suspend fun save(accounts: List<Account>, threads: List<ThreadSummary>) = withContext(Dispatchers.IO) {
        runCatching {
            val v = JsonObject(
                mapOf(
                    "accounts" to JsonArray(accounts.map { obj("email" to it.email, "primary" to it.primary, "color" to it.color, "label" to it.label) }),
                    "threads" to JsonArray(threads.take(80).map {
                        obj(
                            "account" to it.account, "id" to it.id, "last" to it.lastMessageId, "subject" to it.subject,
                            "snippet" to it.snippet, "from" to it.from, "fromName" to it.fromName, "date" to it.date.toEpochMilli(),
                            "unread" to it.unread, "important" to it.important, "starred" to it.starred,
                            "needsReply" to it.needsReply, "count" to it.messageCount, "labels" to it.labels,
                        )
                    }),
                ),
            )
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(v.toString())
            tmp.renameTo(file)
        }
    }

    fun clear() { file.delete() }
}
