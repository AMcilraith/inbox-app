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
class Repository private constructor(context: Context) : MailSource {
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

    override suspend fun accounts(): List<Account> {
        val v = mcp.call("list_accounts")
        return v["accounts"].arr().mapNotNull { a ->
            a["accountEmail"].str()?.let { it to (a["isPrimary"].bool() ?: false) }
        }.sortedBy { !it.second }.mapIndexed { i, (email, primary) ->
            Account(email, primary, ACCOUNT_COLORS[i % ACCOUNT_COLORS.size])
        }.let { list ->
            // Two accounts at the same company would share a label; use the mailbox name then.
            val dup = list.groupingBy { it.label }.eachCount().filterValues { it > 1 }.keys
            list.map { if (it.label in dup) it.copy(label = it.email.substringBefore('@')) else it }
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
    override suspend fun inbox(accounts: List<String>, split: String?): Pair<List<ThreadSummary>, Map<String, String>> = coroutineScope {
        val results = accounts.map { a -> async { a to runCatching { threads(a, split) } } }.awaitAll()
        val errors = results.mapNotNull { (a, r) -> r.exceptionOrNull()?.let { a to (it.message ?: "error") } }.toMap()
        results.flatMap { it.second.getOrDefault(emptyList()) }.sortedByDescending { it.date } to errors
    }

    override suspend fun thread(account: String, id: String): ThreadDetail =
        parseThreadDetail(account, mcp.call("get_thread", obj("thread_id" to id, "include_comments" to false, "message_limit" to 30), account))

    override suspend fun update(t: ThreadSummary, done: Boolean?, read: Boolean?, starred: Boolean?) =
        updateById(t.account, t.id, t.lastMessageId, done, read, starred)

    suspend fun updateById(account: String, threadId: String, lastMessageId: String, done: Boolean? = null, read: Boolean? = null, starred: Boolean? = null) {
        mcp.call(
            "update_thread",
            obj("thread_id" to threadId, "last_message_id" to lastMessageId, "mark_done" to done, "mark_read" to read, "mark_starred" to starred),
            account,
        )
    }

    /** Reply from the account that owns the thread. Sending goes through Superhuman (1-minute undo there). */
    override suspend fun reply(account: String, threadId: String, text: String, send: Boolean, draftId: String?): String {
        val html = text.split("\n\n").joinToString("") { p ->
            "<p>" + p.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>") + "</p>"
        }
        // Update the draft Superhuman's writer made (if any) instead of creating a second one.
        val draft = mcp.call("create_or_update_draft", obj("type" to "reply", "thread_id" to threadId, "body" to html, "draft_id" to draftId), account)
        val savedId = draft["draft"]["draft_id"].str() ?: draft["draft_id"].str() ?: throw Exception("Superhuman returned no draft")
        if (!send) return "Saved to your Superhuman drafts"
        mcp.call("send_draft", obj("draft_id" to savedId), account)
        return "Sent. You can undo it in Superhuman for about a minute"
    }

    override suspend fun events(accounts: List<String>, zone: ZoneId, days: Long): List<CalEvent> = coroutineScope {
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

    override suspend fun rsvp(account: String, eventId: String, response: String) {
        mcp.call("set_rsvp_event", obj("event_id" to eventId, "response" to response), account)
    }

    /** Link another Superhuman account; returns the URL to approve it in a browser. */
    override suspend fun linkAccountUrl(): String? {
        val v = mcp.call("add_account")
        return v["continueUrl"].str() ?: v["url"].str()
    }

    fun now(): Instant = Instant.now()

    // ------------------------------------------------------------ Superhuman AI

    /** Ask Superhuman's assistant across every linked account; each answer labelled by account. */
    override suspend fun askSuperhuman(question: String, accounts: List<Account>): String = coroutineScope {
        val answers = accounts.map { a ->
            async { a.email to runCatching { mcp.call("query_email_and_calendar", obj("question" to question), a.email) } }
        }.awaitAll()
        val ok = answers.mapNotNull { (email, r) ->
            r.getOrNull()?.let { v ->
                val text = v.str() ?: listOf("answer", "response", "text", "result", "message", "summary").firstNotNullOfOrNull { v[it].str() } ?: v.toString()
                (email to text).takeIf { text.isNotBlank() }
            }
        }
        if (ok.isEmpty()) throw answers.firstNotNullOfOrNull { it.second.exceptionOrNull() } ?: Exception("Superhuman had no answer")
        if (ok.size == 1) ok[0].second else ok.joinToString("\n\n") { (email, text) -> "**${displayName(email)}**\n$text" }
    }

    /** Draft a reply with Superhuman's writer; returns the text and the draft it saved. */
    override suspend fun draftWithSuperhuman(account: String, threadId: String, instructions: String): Pair<String, String?> {
        val d = mcp.call(
            "create_or_update_draft",
            obj("type" to "reply", "thread_id" to threadId, "instructions" to instructions.ifBlank { "Write the most sensible reply." }),
            account,
        )
        val draftId = d["draft"]["draft_id"].str() ?: d["draft_id"].str()
        var body = d["draft"]["body"].str() ?: d["body"].str()
        if (body == null && draftId != null) {
            val full = mcp.call("get_draft", obj("draft_id" to draftId), account)
            body = full["body"].str() ?: full["draft"]["body"].str()
        }
        val text = htmlToText(body.orEmpty())
        if (text.isBlank()) throw Exception("Superhuman didn't return a draft")
        return text to draftId
    }

    // ------------------------------------------------------------ shared Ask history

    private suspend fun askSnippet(primary: String): Pair<String?, AskHistory> {
        val list = mcp.call("list_snippets", obj("query" to AskHistory.SNIPPET, "include_team" to false), primary)
        val id = list["snippets"].arr().firstOrNull { it["name"].str() == AskHistory.SNIPPET }?.get("id").str()
        val history = id?.let {
            val snip = mcp.call("get_snippet", obj("id" to it), primary)
            AskHistory.decode(snip["body"].str().orEmpty())
        } ?: AskHistory()
        return id to history
    }

    override suspend fun loadAskHistory(primary: String): AskHistory = askSnippet(primary).second.merge(AskHistory())

    override suspend fun syncAskHistory(primary: String, local: AskHistory): AskHistory {
        val (id, remote) = askSnippet(primary)
        val merged = remote.merge(local)
        mcp.call("create_or_update_snippet", obj("id" to id, "name" to AskHistory.SNIPPET, "body" to merged.encode()), primary)
        return merged
    }

    // ------------------------------------------------------------ thread actions

    override suspend fun remind(t: ThreadSummary, at: Instant) {
        mcp.call("create_or_update_reminder", obj("thread_id" to t.id, "remind_at" to at.toString()), t.account)
    }

    override suspend fun setImportant(t: ThreadSummary, important: Boolean) {
        mcp.call("update_thread", obj("thread_id" to t.id, "last_message_id" to t.lastMessageId, "mark_important" to important), t.account)
    }
}
