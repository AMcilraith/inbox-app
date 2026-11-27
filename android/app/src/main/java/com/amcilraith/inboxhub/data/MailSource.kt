package com.amcilraith.inboxhub.data

import java.time.Instant
import java.time.ZoneId

/** What the app screens need. [Repository] talks to Superhuman; [DemoSource] is the sample data. */
interface MailSource {
    suspend fun accounts(): List<Account>
    suspend fun inbox(accounts: List<String>, split: String?): Pair<List<ThreadSummary>, Map<String, String>>
    suspend fun thread(account: String, id: String): ThreadDetail
    suspend fun update(t: ThreadSummary, done: Boolean? = null, read: Boolean? = null, starred: Boolean? = null)
    suspend fun reply(account: String, threadId: String, text: String, send: Boolean, draftId: String? = null): String
    suspend fun events(accounts: List<String>, zone: ZoneId, days: Long = 7): List<CalEvent>
    suspend fun rsvp(account: String, eventId: String, response: String)
    suspend fun linkAccountUrl(): String?
    suspend fun askSuperhuman(question: String, accounts: List<Account>): String
    suspend fun draftWithSuperhuman(account: String, threadId: String, instructions: String): Pair<String, String?>
    suspend fun loadAskHistory(primary: String): AskHistory
    suspend fun syncAskHistory(primary: String, local: AskHistory): AskHistory
    suspend fun remind(t: ThreadSummary, at: Instant)
    suspend fun setImportant(t: ThreadSummary, important: Boolean)
}
