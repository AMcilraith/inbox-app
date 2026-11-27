package com.amcilraith.inboxhub.data

import androidx.core.text.HtmlCompat
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Account(val email: String, val primary: Boolean, val color: Long, val label: String = accountLabel(email))

private val MAILBOX_PROVIDERS = setOf("gmail", "googlemail", "outlook", "hotmail", "live", "msn", "icloud", "me", "mac", "yahoo", "proton", "protonmail", "pm", "aol", "gmx", "fastmail", "hey", "zoho", "shaw", "telus", "rogers", "sympatico")

/** A short name for an account: the company for a work domain, the mailbox name for Gmail and the like. */
fun accountLabel(email: String): String {
    val local = email.substringBefore('@')
    val domain = email.substringAfter('@', "").lowercase().split('.').firstOrNull().orEmpty()
    return if (domain.isEmpty() || domain in MAILBOX_PROVIDERS) local else domain.replaceFirstChar { it.uppercase() }
}

data class ThreadSummary(
    val account: String,
    val id: String,
    val lastMessageId: String,
    val subject: String,
    val snippet: String,
    val from: String,
    val fromName: String,
    val date: Instant,
    val unread: Boolean,
    val important: Boolean,
    val starred: Boolean,
    val needsReply: Boolean,
    val messageCount: Int,
    val labels: List<String>,
)

data class Message(val id: String, val from: String, val to: List<String>, val sentAt: Instant?, val body: String, val attachments: List<String>)

data class ThreadDetail(val account: String, val id: String, val subject: String, val messages: List<Message>)

data class CalEvent(
    val account: String,
    val id: String,
    val title: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val location: String?,
    val link: String?,
    val needsRsvp: Boolean,
    var clash: Boolean = false,
)

/** Muted account colours that sit well on the white/charcoal theme. */
val ACCOUNT_COLORS = listOf(0xFF5B5BD6, 0xFF0F8FB3, 0xFFC27C0E, 0xFF2F855A, 0xFFB83280, 0xFF6B6B73)

fun displayName(addr: String): String = Names.displayName(addr)

private val NON_CONTENT = Regex("(?is)<(head|style|script|title)\\b.*?</\\1>")
// Newsletter preheaders pad with invisible characters (zero-width, combining grapheme joiner,
// soft hyphen, word joiner, BOM) so mail apps show no preview. They render as boxes or gaps.
private val INVISIBLE = Regex("[\u00AD\u034F\u061C\u115F\u1160\u17B4\u17B5\u180E\u200B\u200C\u200E\u200F\u202A-\u202E\u2060-\u2064\u206A-\u206F\u3164\uFE00-\uFE0E\uFEFF\uFFA0\uFFFC\uFFFD]")
private val ODD_SPACES = Regex("[\u00A0\u2000-\u200A\u202F\u205F\u3000]")
private val ENTITY = Regex("&(#\\d{1,6}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,30});")
private val RULE_LINE = Regex("(?m)^[ \t]*[─━\\-=_*·•|]{3,}[ \t]*$")

/** Remove invisible padding and odd spaces from text that came from email. */
fun cleanText(s: String): String =
    s.replace(INVISIBLE, "").replace(ODD_SPACES, " ").replace("\r\n", "\n")

fun htmlToText(html: String): String {
    val raw = if ('<' in html) {
        // Newsletter HTML carries CSS in <style>/<head>; Android's converter would show it as text.
        HtmlCompat.fromHtml(html.replace(NON_CONTENT, ""), HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
    } else {
        // Plain text: decode entities only, keep the line breaks.
        html.replace(ENTITY) { m -> HtmlCompat.fromHtml(m.value, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().ifEmpty { m.value } }
    }
    return cleanText(raw)
        .replace(RULE_LINE, "")
        .lines().joinToString("\n") { it.trimEnd() }
        .replace(Regex("[ \t]{2,}"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** A one-line preview: plain text with whitespace collapsed. */
fun previewText(s: String): String = htmlToText(s).replace(Regex("\\s+"), " ").trim()

private fun instant(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() }

fun parseThread(account: String, v: JsonElement): ThreadSummary? {
    val id = v["thread_id"].str() ?: return null
    val labels = v["labels"].strs()
    val splits = v["splits"].strs()
    val msgs = v["messages"].arr()
    val from = msgs.reversed().map { it["from"].str().orEmpty() }.firstOrNull { it.isNotEmpty() && !it.equals(account, true) }
        ?: msgs.lastOrNull()?.get("from").str()
        ?: v["participants"].strs().firstOrNull { !it.equals(account, true) }
        ?: ""
    fun has(l: String) = labels.any { it.equals(l, true) }
    return ThreadSummary(
        account = account,
        id = id,
        lastMessageId = v["last_message_id"].str() ?: msgs.lastOrNull()?.get("message_id").str() ?: id,
        subject = v["subject"].str()?.let { cleanText(it).trim() }?.takeIf { it.isNotBlank() } ?: "(no subject)",
        snippet = previewText(v["snippet"].str().orEmpty()),
        from = from,
        fromName = displayName(from),
        date = instant(v["last_message_at"].str()) ?: Instant.now(),
        unread = has("UNREAD"),
        important = has("IMPORTANT") || splits.any { it.equals("Important", true) },
        starred = has("STARRED"),
        needsReply = has("[1. Respond]"),
        messageCount = (v["message_count"].long() ?: 1).toInt(),
        labels = labels,
    )
}

fun parseThreadDetail(account: String, v: JsonElement): ThreadDetail = ThreadDetail(
    account = account,
    id = v["thread_id"].str().orEmpty(),
    subject = cleanText(v["subject"].str().orEmpty()).trim(),
    messages = v["messages"].arr().map { m ->
        Message(
            id = m["message_id"].str().orEmpty(),
            from = m["from"].str().orEmpty(),
            to = m["to"].strs(),
            sentAt = instant(m["sent_at"].str()),
            body = htmlToText(m["body"].str() ?: m["snippet"].str().orEmpty()),
            attachments = m["attachments"].strs(),
        )
    },
)

fun parseEvent(account: String, v: JsonElement, zone: ZoneId): CalEvent? {
    fun when_(k: String): Pair<Instant, Boolean>? {
        val raw = v[k].str() ?: v[k]["dateTime"].str() ?: v[k]["date"].str() ?: return null
        instant(raw)?.let { return it to false }
        return runCatching { LocalDate.parse(raw).atStartOfDay(zone).toInstant() to true }.getOrNull()
    }
    val (start, allDay) = when_("start") ?: return null
    val end = when_("end")?.first ?: start
    val status = v["my_status"].str().orEmpty().lowercase()
    val link = listOf("video_link", "meeting_link", "video_meeting_link", "conference_link", "hangout_link", "meet_link")
        .firstNotNullOfOrNull { v[it].str()?.takeIf { s -> s.isNotBlank() } }
    return CalEvent(
        account = account,
        id = v["event_id"].str().orEmpty(),
        title = v["title"].str()?.let { cleanText(it).trim() }?.takeIf { it.isNotBlank() } ?: "(untitled)",
        start = start, end = end, allDay = allDay,
        location = v["location"].str()?.takeIf { it.isNotBlank() },
        link = link,
        needsRsvp = status in setOf("needsaction", "needs_action", "none", "not_responded"),
    )
}

/** Flag overlapping timed events (across all calendars). */
fun markClashes(events: List<CalEvent>) {
    val timed = events.filter { !it.allDay }.sortedBy { it.start }
    for (i in timed.indices) for (j in i + 1 until timed.size) {
        if (timed[j].start >= timed[i].end) break
        timed[i].clash = true; timed[j].clash = true
    }
}
