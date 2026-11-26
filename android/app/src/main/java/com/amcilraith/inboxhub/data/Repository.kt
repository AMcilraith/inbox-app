package com.amcilraith.inboxhub.data

import android.content.Context
import com.amcilraith.inboxhub.auth.Auth
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Everything the app and the background worker need, shared as one instance. */
class Repository private constructor(context: Context) {
    companion object {
        @Volatile private var instance: Repository? = null
        fun get(context: Context): Repository =
            instance ?: synchronized(this) { instance ?: Repository(context.applicationContext).also { instance = it } }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val auth = Auth(context, http)
    private val mcp = Mcp(http, auth)

    suspend fun accounts(): List<Account> {
        val v = mcp.call("list_accounts")
        return v["accounts"].arr().mapNotNull { a ->
            a["accountEmail"].str()?.let { it to (a["isPrimary"].bool() ?: false) }
        }.sortedBy { !it.second }.mapIndexed { i, (email, primary) ->
            Account(email, primary, ACCOUNT_COLORS[i % ACCOUNT_COLORS.size])
        }
    }

    /** Inbox threads for one account. [split] is "Important", "Other" or null for the whole inbox. */
    suspend fun threads(account: String, split: String?, unreadOnly: Boolean = false, limit: Int = 40): List<ThreadSummary> {
        val args = if (split != null) {
            obj("split" to split, "limit" to limit, "is_unread" to if (unreadOnly) true else null)
        } else {
            obj("labels" to listOf("INBOX"), "limit" to limit, "is_unread" to if (unreadOnly) true else null)
        }
        return mcp.call("list_threads", args, account)["threads"].arr().mapNotNull { parseThread(account, it) }
    }

    /** One merged, newest-first inbox across [accounts]. Failed accounts are reported, not fatal. */
    suspend fun inbox(accounts: List<String>, split: String?): Pair<List<ThreadSummary>, Map<String, String>> = coroutineScope {
        val results = accounts.map { a -> async { a to runCatching { threads(a, split) } } }.awaitAll()
        val errors = results.mapNotNull { (a, r) -> r.exceptionOrNull()?.let { a to (it.message ?: "error") } }.toMap()
        results.flatMap { it.second.getOrDefault(emptyList()) }.sortedByDescending { it.date } to errors
    }

    suspend fun thread(account: String, id: String): ThreadDetail =
        parseThreadDetail(account, mcp.call("get_thread", obj("thread_id" to id, "include_comments" to false, "message_limit" to 30), account))

    suspend fun update(t: ThreadSummary, done: Boolean? = null, read: Boolean? = null, starred: Boolean? = null) =
        updateById(t.account, t.id, t.lastMessageId, done, read, starred)

    suspend fun updateById(account: String, threadId: String, lastMessageId: String, done: Boolean? = null, read: Boolean? = null, starred: Boolean? = null) {
        mcp.call(
            "update_thread",
            obj("thread_id" to threadId, "last_message_id" to lastMessageId, "mark_done" to done, "mark_read" to read, "mark_starred" to starred),
            account,
        )
    }

    /** Reply from the account that owns the thread. Sending goes through Superhuman (1-minute undo there). */
    suspend fun reply(account: String, threadId: String, text: String, send: Boolean): String {
        val html = text.split("\n\n").joinToString("") { p ->
            "<p>" + p.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>") + "</p>"
        }
        val draft = mcp.call("create_or_update_draft", obj("type" to "reply", "thread_id" to threadId, "body" to html), account)
        val draftId = draft["draft"]["draft_id"].str() ?: draft["draft_id"].str() ?: throw Exception("Superhuman returned no draft")
        if (!send) return "Saved to your Superhuman drafts"
        mcp.call("send_draft", obj("draft_id" to draftId), account)
        return "Sent — you can undo it in Superhuman for about a minute"
    }

    suspend fun events(accounts: List<String>, zone: ZoneId, days: Long = 7): List<CalEvent> = coroutineScope {
        val start = LocalDate.now(zone).atStartOfDay(zone)
        val end = start.plusDays(days)
        accounts.map { a ->
            async {
                runCatching {
                    mcp.call(
                        "list_calendar_events",
                        obj("start_date" to start.toOffsetDateTime().toString(), "end_date" to end.toOffsetDateTime().toString(), "max_results" to 150),
                        a,
                    )["events"].arr().mapNotNull { parseEvent(a, it, zone) }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().sortedBy { it.start }.also { markClashes(it) }
    }

    suspend fun rsvp(account: String, eventId: String, response: String) {
        mcp.call("set_rsvp_event", obj("event_id" to eventId, "response" to response), account)
    }

    /** Link another Superhuman account; returns the URL to approve it in a browser. */
    suspend fun linkAccountUrl(): String? {
        val v = mcp.call("add_account")
        return v["continueUrl"].str() ?: v["url"].str()
    }

    fun now(): Instant = Instant.now()
}
