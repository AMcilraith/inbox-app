package com.amcilraith.inboxhub.data

import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sample data, the same four example accounts as the desktop app's demo (src/demo.rs), so the
 * app can be tried without signing in. Changes (done, read, star) are kept in memory only.
 * [latencyMs] imitates the network so loading states can be seen; tests pass 0.
 */
class DemoSource(private val latencyMs: Long = 250) : MailSource {

    private data class T(
        val id: String, val from: String, val subject: String, val body: String,
        val labels: Set<String>, val hoursAgo: Long, val earlier: List<Pair<String, String>> = emptyList(),
        val attachments: List<String> = emptyList(),
    )

    private val accounts = listOf(
        Account("you@example.com", true, 0xFF5B5BD6, "Personal"),
        Account("alex@company.example", false, 0xFF0F8FB3, "Work"),
        Account("hello@mystudio.example", false, 0xFFC27C0E, "Side Business"),
        Account("alex@uni.example", false, 0xFF2F855A, "School"),
    )

    private val data: Map<String, MutableList<T>> = mapOf(
        "you@example.com" to mutableListOf(
            T("p1", "meadowoodpremierdentistry@gmail.com", "Appointment reminder — please confirm",
                "Hi Alex,\n\nThis is a reminder of your upcoming appointment on Dec 1 at 1:30 pm for a teeth cleaning.\n\nPlease reply YES to confirm, or call us at (204) 555-0182 to reschedule.\n\nMeadowood Premier Dentistry\nhttps://meadowooddental.example/appointments/confirm?ref=AX-20931&utm_source=email&utm_medium=reminder",
                setOf("UNREAD", "IMPORTANT", "[1. Respond]"), 4),
            T("p2", "mom@family.example", "Sunday dinner?", "Are you coming Sunday? Let me know so I can plan food 🙂\n\nDad's making his lasagna.",
                setOf("UNREAD", "IMPORTANT"), 6,
                earlier = listOf("you@example.com" to "Probably! What time were you thinking?")),
            T("p3", "billing@hydro.example", "Your bill is ready — due Dec 3",
                "Your statement is ready.\n\nAmount due: $112.40\nDue date: December 3, 2026\n\nPay online at https://hydro.example/pay",
                setOf("IMPORTANT"), 15, attachments = listOf("Statement-Nov-2026.pdf")),
            T("p4", "deals@shopnow.example", "Black Friday: 40% off everything",
                "͏​­͏ Doors open now — our biggest sale of the year.\n\nShop now: https://shopnow.example/bf?utm_campaign=bf26&utm_source=newsletter&utm_medium=email&id=88219",
                setOf("UNREAD"), 1),
            T("p5", "Jkolapo@sarariel.ca", "Important update regarding your case worker",
                "Hello,\n\nYour case worker, Tiffany, will be away for approximately one month. During this time, please contact the front desk for anything urgent.\n\nThank you,\nJ. Kolapo",
                setOf("IMPORTANT", "[2. FYI]"), 40),
        ),
        "alex@company.example" to mutableListOf(
            T("w1", "ceo@company.example", "Board deck — need your numbers by EOD",
                "Can you drop the Q4 product metrics into slide 7 before the board pre-read goes out tonight?\n\nActivation, retention and the NPS trend are the three I need. Thanks!",
                setOf("UNREAD", "IMPORTANT", "[1. Respond]"), 2,
                earlier = listOf(
                    "ceo@company.example" to "Board pre-read goes out Thursday. I'll share the deck link shortly.",
                    "alex@company.example" to "Sounds good. Send it over when it's ready and I'll fill in product.",
                )),
            T("w2", "priya@bigclient.example", "Renewal terms for 2027",
                "Thanks for the call. Attached are our proposed changes to the renewal. Could you confirm the seat count (we're thinking 140) and whether the SSO add-on is included?\n\nBest,\nPriya",
                setOf("UNREAD", "IMPORTANT"), 5, attachments = listOf("Renewal-2027-redline.docx", "Seat-plan.xlsx")),
            T("w3", "sam@company.example", "Sprint review moved to 2pm", "Heads up, sprint review is now 2–3 pm so design can join.", setOf("[2. FYI]"), 9),
            T("w4", "jira@company.example", "[JIRA] 4 issues assigned to you", "PROD-212, PROD-219, PROD-220 and PROD-231 were assigned to you.", emptySet(), 11),
            T("w5", "legal@acme.example", "DPA signature", "We're still waiting on the countersigned DPA from your side. Could you send it by Friday?", setOf("IMPORTANT", "[3. Waiting]"), 70),
        ),
        "hello@mystudio.example" to mutableListOf(
            T("b1", "orders@shopify.com", "Order #1042 — refund requested",
                "A customer has requested a refund for order #1042 (Ceramic mug set, $48.00).\n\nReason: \"Arrived chipped\"\n\nRespond within 48 hours: https://admin.shopify.example/orders/1042/refund",
                setOf("UNREAD", "IMPORTANT"), 3),
            T("b2", "nora.blake@gmail.com", "Quote for 40 custom mugs?",
                "Hi! I'm organising a team offsite and would love a quote for 40 mugs with our logo. We'd need them by January 15.\n\nThanks so much,\nNora",
                setOf("UNREAD", "[1. Respond]"), 20),
            T("b3", "newsletter@craftweekly.example", "This week in craft business",
                "Pricing tips, holiday market checklists and more.\n\n━━━━━━━━━━━━━━\n\nRead online: https://craftweekly.example/issues/212",
                setOf("UNREAD"), 26),
        ),
        "alex@uni.example" to mutableListOf(
            T("s1", "prof.ortiz@uni.example", "Assignment 3 due Friday 11:59pm",
                "Reminder that Assignment 3 submissions close Friday at 11:59 pm. No extensions without documentation.",
                setOf("UNREAD", "IMPORTANT"), 7),
            T("s2", "registrar@uni.example", "Winter term registration opens Monday", "Your registration window opens Monday at 8:00 am.", setOf("[2. FYI]"), 30),
        ),
    )

    private val now = Instant.now()
    private fun at(hoursAgo: Long) = now.minus(Duration.ofHours(hoursAgo))

    private suspend fun wait() { if (latencyMs > 0) delay(latencyMs) }

    private fun summary(account: String, t: T) = ThreadSummary(
        account = account, id = t.id, lastMessageId = t.id + "-m",
        subject = t.subject, snippet = previewText(t.body), from = t.from, fromName = displayName(t.from),
        date = at(t.hoursAgo), unread = "UNREAD" in t.labels, important = "IMPORTANT" in t.labels,
        starred = "STARRED" in t.labels, needsReply = "[1. Respond]" in t.labels,
        messageCount = 1 + t.earlier.size, labels = t.labels.toList(),
    )

    override suspend fun accounts(): List<Account> { wait(); return accounts }

    override suspend fun inbox(accounts: List<String>, split: String?): Pair<List<ThreadSummary>, Map<String, String>> {
        wait()
        val list = accounts.flatMap { a ->
            data[a].orEmpty().filter {
                when (split) {
                    "Important" -> "IMPORTANT" in it.labels
                    "Other" -> "IMPORTANT" !in it.labels
                    else -> true
                }
            }.map { summary(a, it) }
        }.sortedByDescending { it.date }
        return list to emptyMap()
    }

    override suspend fun thread(account: String, id: String): ThreadDetail {
        wait()
        val t = data[account]?.firstOrNull { it.id == id } ?: throw Exception("This sample thread is gone")
        val older = t.earlier.mapIndexed { i, (from, text) ->
            Message("$id-e$i", from, listOf(account), at(t.hoursAgo + 24L * (t.earlier.size - i)), text, emptyList())
        }
        return ThreadDetail(account, id, t.subject, older + Message(t.id + "-m", t.from, listOf(account), at(t.hoursAgo), htmlToText(t.body), t.attachments))
    }

    private fun edit(t: ThreadSummary, f: (Set<String>) -> Set<String>) {
        val list = data[t.account] ?: return
        val i = list.indexOfFirst { it.id == t.id }.takeIf { it >= 0 } ?: return
        list[i] = list[i].copy(labels = f(list[i].labels))
    }

    override suspend fun update(t: ThreadSummary, done: Boolean?, read: Boolean?, starred: Boolean?) {
        wait()
        if (done == true) { data[t.account]?.removeAll { it.id == t.id }; return }
        read?.let { r -> edit(t) { if (r) it - "UNREAD" else it + "UNREAD" } }
        starred?.let { s -> edit(t) { if (s) it + "STARRED" else it - "STARRED" } }
    }

    override suspend fun reply(account: String, threadId: String, text: String, send: Boolean, draftId: String?): String {
        wait()
        return if (send) "Sample data: nothing was sent" else "Sample data: draft not saved"
    }

    override suspend fun events(accounts: List<String>, zone: ZoneId, days: Long): List<CalEvent> {
        wait()
        val today = LocalDate.now(zone)
        fun ev(a: String, i: Int, title: String, day: Long, h: Int, m: Int, mins: Long, status: String, video: Boolean, place: String? = null): CalEvent {
            val s = today.plusDays(day).atTime(h, m).atZone(zone).toInstant()
            return CalEvent(a, "$a-$i", title, s, s.plus(Duration.ofMinutes(mins)), false, place,
                if (video) "https://meet.example/abc" else null, status == "needsAction")
        }
        val all = listOf(
            ev("alex@company.example", 1, "Product standup", 0, 9, 0, 15, "accepted", true),
            ev("alex@company.example", 2, "Renewal call — BigClient", 0, 11, 0, 60, "accepted", true),
            ev("alex@company.example", 3, "Sprint review", 0, 14, 0, 60, "needsAction", true),
            ev("alex@company.example", 4, "Product standup", 1, 9, 0, 15, "accepted", true),
            ev("alex@company.example", 5, "Roadmap workshop", 1, 13, 0, 120, "accepted", false, "Room 4B"),
            ev("alex@company.example", 6, "1:1 with Sam", 2, 10, 30, 30, "accepted", true),
            ev("alex@company.example", 7, "Board pre-read review", 3, 15, 0, 45, "tentative", true),
            ev("alex@company.example", 8, "Team lunch", 4, 12, 0, 60, "needsAction", false, "Nonna's Kitchen"),
            ev("hello@mystudio.example", 1, "Pottery studio — glaze firing", 0, 18, 0, 120, "accepted", false),
            ev("hello@mystudio.example", 2, "Supplier call — clay order", 1, 16, 0, 30, "accepted", true),
            ev("hello@mystudio.example", 3, "Holiday market setup", 3, 8, 0, 180, "accepted", false, "Exchange District"),
            ev("alex@uni.example", 1, "Stats lecture", 0, 14, 30, 80, "accepted", false, "Armes 200"),
            ev("alex@uni.example", 2, "Study group", 1, 19, 0, 90, "tentative", false),
            ev("alex@uni.example", 3, "Stats lecture", 2, 14, 30, 80, "accepted", false, "Armes 200"),
            ev("you@example.com", 1, "Gym", 0, 7, 0, 60, "accepted", false),
            ev("you@example.com", 2, "Call with Mom", 0, 19, 30, 30, "accepted", false),
            ev("you@example.com", 3, "Dentist — cleaning", 1, 13, 30, 60, "accepted", false, "Meadowood Dental"),
            ev("you@example.com", 4, "Dinner with friends", 4, 18, 30, 150, "accepted", false),
        ) + CalEvent("you@example.com", "trip", "Long weekend trip", today.plusDays(5).atStartOfDay(zone).toInstant(),
            today.plusDays(7).atStartOfDay(zone).toInstant(), true, null, null, false)
        return all.filter { it.account in accounts }.sortedBy { it.start }.also { markClashes(it) }
    }

    override suspend fun rsvp(account: String, eventId: String, response: String) { wait() }

    override suspend fun linkAccountUrl(): String? = null

    override suspend fun askSuperhuman(question: String, accounts: List<Account>): String {
        delay(latencyMs * 4)
        return "Here's what needs a reply today:\n\n" +
            "**Work**\n- **Board deck**: the CEO needs Q4 activation, retention and NPS on slide 7 by end of day.\n- **Renewal terms**: Priya asked you to confirm 140 seats and the SSO add-on.\n\n" +
            "**Side Business**\n- **Refund #1042**: respond within 48 hours.\n- **Nora Blake** wants a quote for 40 mugs by January 15.\n\n" +
            "**Personal**\n- Confirm the dentist for Dec 1 at 1:30 pm, and tell Mom about Sunday dinner."
    }

    override suspend fun draftWithSuperhuman(account: String, threadId: String, instructions: String): Pair<String, String?> {
        delay(latencyMs * 3)
        return "Hi,\n\nThanks for the note. I'll get this to you by end of day today.\n\nBest,\nAlex" to null
    }

    private var history = AskHistory()
    override suspend fun loadAskHistory(primary: String): AskHistory { wait(); return history }
    override suspend fun syncAskHistory(primary: String, local: AskHistory): AskHistory { history = history.merge(local); return history }

    override suspend fun remind(t: ThreadSummary, at: Instant) { wait(); data[t.account]?.removeAll { it.id == t.id } }

    override suspend fun setImportant(t: ThreadSummary, important: Boolean) {
        wait(); edit(t) { if (important) it + "IMPORTANT" else it - "IMPORTANT" }
    }
}
