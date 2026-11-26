package com.amcilraith.inboxhub.data

import androidx.core.text.HtmlCompat
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Account(val email: String, val primary: Boolean, val color: Long)

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

fun displayName(addr: String): String {
    val lt = addr.indexOf('<')
    if (lt > 0) addr.substring(0, lt).trim().trim('"').takeIf { it.isNotEmpty() }?.let { return it }
    val local = addr.trim('<', '>').substringBefore('@')
    return local.split('.', '_', '-', '+').filter { it.isNotEmpty() }.joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }
}

fun htmlToText(html: String): String =
    HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
        .replace(Regex("￼"), "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

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
        subject = v["subject"].str()?.takeIf { it.isNotBlank() } ?: "(no subject)",
        snippet = htmlToText(v["snippet"].str().orEmpty()).replace(Regex("\\s+"), " "),
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
    subject = v["subject"].str().orEmpty(),
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
        title = v["title"].str()?.takeIf { it.isNotBlank() } ?: "(untitled)",
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
