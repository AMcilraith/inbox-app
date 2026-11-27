package com.amcilraith.inboxhub.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amcilraith.inboxhub.auth.SignInRequired
import com.amcilraith.inboxhub.data.Account
import com.amcilraith.inboxhub.data.AskHistory
import com.amcilraith.inboxhub.data.CalEvent
import com.amcilraith.inboxhub.data.ChatMsg
import com.amcilraith.inboxhub.data.DemoSource
import com.amcilraith.inboxhub.data.DiskCache
import com.amcilraith.inboxhub.data.MailSource
import com.amcilraith.inboxhub.data.Repository
import com.amcilraith.inboxhub.data.ThreadDetail
import com.amcilraith.inboxhub.data.ThreadSummary
import com.amcilraith.inboxhub.work.MailCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.ZoneId

enum class Tab(val label: String) { Inbox("Inbox"), Ask("Ask"), Calendar("Calendar"), Settings("Settings") }
enum class Split(val label: String, val api: String?) { Important("Important", "Important"), Other("Other", "Other"), All("All", null) }

/** A change shown with Undo; it reaches Superhuman only when the snackbar goes away. */
data class Pending(val message: String, val id: Long)

class AppViewModel(app: Application, demoSource: MailSource?) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, null)

    private val repo = Repository.get(app)
    private val prefs = app.getSharedPreferences("notified", 0)
    private val disk = DiskCache(app)
    private val demoData = demoSource
    private var demoImpl: MailSource? = null
    val zone: ZoneId = ZoneId.systemDefault()

    var demo by mutableStateOf(demoSource != null || prefs.getBoolean("demo", false)); private set
    private val src: MailSource get() = if (demo) (demoImpl ?: (demoData ?: DemoSource()).also { demoImpl = it }) else repo

    var signedIn by mutableStateOf(repo.auth.signedIn); private set
    /** Signed in, or looking at sample data. */
    val active: Boolean get() = signedIn || demo
    var signingIn by mutableStateOf(false); private set
    var tab by mutableStateOf(Tab.Inbox)
    var split by mutableStateOf(Split.Important); private set
    var accountFilter by mutableStateOf<String?>(null); private set
    var accounts by mutableStateOf<List<Account>>(emptyList()); private set
    var threads by mutableStateOf<List<ThreadSummary>>(emptyList()); private set
    var events by mutableStateOf<List<CalEvent>>(emptyList()); private set
    /** Any fetch running. */
    var loading by mutableStateOf(false); private set
    /** A fetch the user asked for by pulling down. */
    var pulling by mutableStateOf(false); private set
    /** True once the current inbox view has had at least one answer (or came from cache). */
    var inboxReady by mutableStateOf(false); private set
    var eventsReady by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    var pending by mutableStateOf<Pending?>(null); private set
    var open by mutableStateOf<ThreadDetail?>(null); private set
    var openSummary by mutableStateOf<ThreadSummary?>(null); private set
    var openLoading by mutableStateOf(false); private set
    var sending by mutableStateOf(false); private set
    var notificationsOn by mutableStateOf(prefs.getBoolean("enabled", true)); private set
    var chat by mutableStateOf<List<ChatMsg>>(emptyList()); private set
    var asking by mutableStateOf(false); private set
    var drafting by mutableStateOf(false); private set
    var query by mutableStateOf("")

    /** Inbox rows after the search box. Searching is local, so it's instant. */
    val visibleThreads by derivedStateOf {
        val q = query.trim()
        if (q.isEmpty()) threads else threads.filter {
            it.fromName.contains(q, true) || it.from.contains(q, true) || it.subject.contains(q, true) || it.snippet.contains(q, true)
        }
    }
    val unreadCount by derivedStateOf { threads.count { it.unread } }

    /** Draft Superhuman's writer saved for the open thread; Send updates it instead of making another. */
    private var aiDraft: Pair<String, String>? = null
    private val inboxCache = HashMap<String, List<ThreadSummary>>()
    private val details = object : LinkedHashMap<String, ThreadDetail>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ThreadDetail>?) = size > 60
    }
    private val prefetchGate = Semaphore(3)
    /** Outlives the screen, so a "Done" made just before leaving the app still reaches Superhuman. */
    private val commitScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var refreshGen = 0
    private var pendingJob: Job? = null
    private var pendingCommit: (suspend () -> Unit)? = null
    private var pendingRollback: (() -> Unit)? = null

    private fun key() = "${split.name}|${accountFilter ?: "*"}"
    private fun dkey(account: String, id: String) = "$account/$id"

    init {
        if (!demo && signedIn) viewModelScope.launch {
            // Open straight onto the last inbox; the network refresh replaces it.
            disk.load()?.let { snap ->
                if (threads.isEmpty() && snap.threads.isNotEmpty()) {
                    if (accounts.isEmpty()) accounts = snap.accounts
                    threads = snap.threads
                    inboxCache[key()] = snap.threads
                    inboxReady = true
                }
            }
        }
    }

    fun colorOf(email: String): Long = accounts.firstOrNull { it.email == email }?.color ?: 0xFF6B6B73
    fun labelOf(email: String): String = accounts.firstOrNull { it.email == email }?.label ?: email.substringBefore('@')

    private fun fail(e: Throwable) {
        if (e is SignInRequired && !demo) signedIn = false
        error = e.message ?: e.toString()
    }

    fun refresh(userPulled: Boolean = false) {
        if (!active) return
        refreshJob?.cancel()
        val wantTab = tab
        val wantKey = key()
        val gen = ++refreshGen
        refreshJob = viewModelScope.launch {
            loading = true
            if (userPulled) pulling = true
            try {
                if (accounts.isEmpty()) accounts = src.accounts()
                val targets = accountFilter?.let { listOf(it) } ?: accounts.map { it.email }
                if (wantTab == Tab.Calendar) {
                    events = src.events(targets, zone)
                    eventsReady = true
                } else {
                    val (list, errors) = src.inbox(targets, split.api)
                    inboxCache[wantKey] = list
                    if (key() == wantKey) { threads = list; inboxReady = true }
                    if (errors.isNotEmpty()) error = errors.entries.joinToString("\n") { "${labelOf(it.key)}: ${it.value}" }
                    if (!demo && wantKey == "${Split.Important.name}|*") disk.save(accounts, list)
                    prefetch(list)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            } finally {
                if (gen == refreshGen) {
                    loading = false
                    pulling = false
                }
            }
        }
    }

    /** Fetch the first few conversations in the background so opening them is instant. */
    private fun prefetch(list: List<ThreadSummary>) {
        list.take(8).filter { dkey(it.account, it.id) !in details }.forEach { t ->
            viewModelScope.launch {
                prefetchGate.withPermit {
                    runCatching { src.thread(t.account, t.id) }.getOrNull()?.let { details[dkey(t.account, t.id)] = it }
                }
            }
        }
    }

    private fun showCached() {
        val cached = inboxCache[key()]
        threads = cached ?: emptyList()
        inboxReady = cached != null
    }

    fun selectSplit(s: Split) { if (s == split) return; split = s; showCached(); refresh() }
    fun selectAccount(email: String?) { if (email == accountFilter) return; accountFilter = email; showCached(); eventsReady = false; refresh() }
    fun selectTab(t: Tab) {
        if (t == tab) return
        tab = t
        when (t) {
            Tab.Ask -> loadAsk()
            Tab.Settings -> {}
            Tab.Calendar -> refresh()
            Tab.Inbox -> refresh()
        }
    }

    // ---- sample data ----
    fun enterDemo() {
        prefs.edit().putBoolean("demo", true).apply()
        demo = true
        resetData()
        refresh()
    }

    fun exitDemo() {
        prefs.edit().putBoolean("demo", false).apply()
        demo = false
        demoImpl = null
        resetData()
        if (signedIn) refresh()
    }

    private fun resetData() {
        refreshJob?.cancel()
        accounts = emptyList(); threads = emptyList(); events = emptyList(); chat = emptyList()
        inboxCache.clear(); details.clear()
        inboxReady = false; eventsReady = false
        open = null; openSummary = null; accountFilter = null; query = ""
    }

    // ---- Ask Superhuman AI (conversation shared with the desktop app) ----
    private suspend fun primary(): String {
        if (accounts.isEmpty()) accounts = src.accounts()
        return accounts.firstOrNull { it.primary }?.email ?: accounts.first().email
    }

    private fun msg(role: String, text: String) =
        ChatMsg(java.lang.Long.toHexString(java.util.Random().nextLong()), role, text, System.currentTimeMillis(), "phone")

    fun loadAsk() {
        if (!active) return
        viewModelScope.launch {
            try { chat = src.loadAskHistory(primary()).msgs } catch (e: Throwable) { fail(e) }
        }
    }

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || asking) return
        val mine = msg("user", q)
        chat = chat + mine
        asking = true
        viewModelScope.launch {
            try {
                if (accounts.isEmpty()) accounts = src.accounts()
                val answer = src.askSuperhuman(q, accounts)
                val ai = msg("ai", answer)
                chat = chat + ai
                asking = false
                chat = src.syncAskHistory(primary(), AskHistory(0, listOf(mine, ai))).msgs
            } catch (e: Throwable) {
                fail(e)
            } finally {
                asking = false
            }
        }
    }

    fun clearAsk() {
        chat = emptyList()
        viewModelScope.launch {
            try { src.syncAskHistory(primary(), AskHistory(System.currentTimeMillis(), emptyList())) } catch (e: Throwable) { fail(e) }
        }
    }

    // ---- Draft with Superhuman AI ----
    fun draftWithAi(instructions: String, onText: (String) -> Unit) {
        val t = open ?: return
        drafting = true
        viewModelScope.launch {
            try {
                val (text, draftId) = src.draftWithSuperhuman(t.account, t.id, instructions)
                aiDraft = draftId?.let { t.id to it }
                onText(text)
            } catch (e: Throwable) {
                fail(e)
            } finally {
                drafting = false
            }
        }
    }

    // ---- changes with Undo ----

    private fun same(a: ThreadSummary, b: ThreadSummary) = a.id == b.id && a.account == b.account

    /** Hide [t] from every cached list now; Superhuman hears about it once Undo has passed. */
    private fun removeWithUndo(t: ThreadSummary, message: String, commit: suspend () -> Unit) {
        flushPending()
        val beforeThreads = threads
        val beforeCache = HashMap(inboxCache)
        threads = threads.filterNot { same(it, t) }
        inboxCache.replaceAll { _, l -> l.filterNot { same(it, t) } }
        if (openSummary?.let { same(it, t) } == true) closeThread()
        pendingRollback = { threads = beforeThreads; inboxCache.clear(); inboxCache.putAll(beforeCache) }
        pendingCommit = commit
        val p = Pending(message, System.nanoTime())
        pending = p
        pendingJob = viewModelScope.launch {
            delay(4_500)
            if (pending == p) flushPending()
        }
    }

    /** Send the change waiting behind the Undo snackbar now. */
    fun flushPending() {
        val c = pendingCommit ?: return
        val rollback = pendingRollback
        pendingCommit = null; pendingRollback = null; pending = null
        pendingJob?.cancel()
        commitScope.launch {
            try { c() } catch (e: Throwable) { rollback?.invoke(); fail(e) }
        }
    }

    fun undo() {
        pendingJob?.cancel()
        pendingRollback?.invoke()
        pendingCommit = null; pendingRollback = null; pending = null
    }

    fun markDone(t: ThreadSummary) = removeWithUndo(t, "Marked done") { src.update(t, done = true) }

    fun remind(t: ThreadSummary, at: java.time.Instant, label: String) =
        removeWithUndo(t, "Reminder set for $label") { src.remind(t, at) }

    fun setImportant(t: ThreadSummary, important: Boolean) {
        val msg = if (important) "Moved to Important" else "Moved to Other"
        if (split == Split.All) {
            viewModelScope.launch {
                try { src.setImportant(t, important); toast = msg; inboxCache.clear() } catch (e: Throwable) { fail(e) }
            }
        } else {
            removeWithUndo(t, msg) { src.setImportant(t, important); inboxCache.clear() }
        }
    }

    private fun patch(t: ThreadSummary, f: (ThreadSummary) -> ThreadSummary) {
        threads = threads.map { if (same(it, t)) f(it) else it }
        inboxCache.replaceAll { _, l -> l.map { if (same(it, t)) f(it) else it } }
        openSummary?.let { if (same(it, t)) openSummary = f(it) }
    }

    fun toggleRead(t: ThreadSummary) {
        val now = threads.firstOrNull { same(it, t) } ?: t
        patch(t) { it.copy(unread = !now.unread) }
        viewModelScope.launch {
            try { src.update(now, read = now.unread) } catch (e: Throwable) { patch(t) { it.copy(unread = now.unread) }; fail(e) }
        }
    }

    fun toggleStar(t: ThreadSummary) {
        val now = openSummary?.takeIf { same(it, t) } ?: threads.firstOrNull { same(it, t) } ?: t
        patch(t) { it.copy(starred = !now.starred) }
        viewModelScope.launch {
            try { src.update(now, starred = !now.starred) } catch (e: Throwable) { patch(t) { it.copy(starred = now.starred) }; fail(e) }
        }
    }

    // ---- sign-in ----
    suspend fun authorizeUrl(): Uri? = try {
        signingIn = true
        repo.auth.authorizeUrl()
    } catch (e: Throwable) {
        signingIn = false
        fail(e); null
    }

    fun completeSignIn(uri: Uri) {
        viewModelScope.launch {
            try {
                repo.auth.complete(uri)
                signedIn = true
                error = null
                if (demo) exitDemo() else { resetData(); refresh() }
                MailCheckWorker.checkNow(getApplication())
            } catch (e: Throwable) {
                fail(e)
            } finally {
                signingIn = false
            }
        }
    }

    fun signOut() {
        repo.auth.signOut()
        disk.clear()
        signedIn = false
        if (demo) exitDemo() else resetData()
    }

    // ---- threads ----
    fun openThread(account: String, id: String) {
        flushPending()
        val s = threads.firstOrNull { it.account == account && it.id == id }
            ?: ThreadSummary(account, id, id, "", "", "", "", java.time.Instant.now(), false, false, false, false, 1, emptyList())
        openSummary = s
        val cached = details[dkey(account, id)]
        open = cached
        openLoading = cached == null
        aiDraft = null
        viewModelScope.launch {
            try {
                val t = src.thread(account, id)
                details[dkey(account, id)] = t
                if (openSummary?.id == id) open = t
                if (s.unread) {
                    patch(s) { it.copy(unread = false) }
                    runCatching { src.update(s, read = true) }
                }
            } catch (e: Throwable) {
                if (cached == null) fail(e)
            } finally {
                openLoading = false
            }
        }
    }

    fun closeThread() { openSummary = null }

    fun reply(text: String, send: Boolean, onDone: () -> Unit) {
        val t = open ?: return
        sending = true
        viewModelScope.launch {
            try {
                val draftId = aiDraft?.takeIf { it.first == t.id }?.second
                toast = src.reply(t.account, t.id, text, send, draftId)
                if (send) aiDraft = null
                onDone()
            } catch (e: Throwable) {
                fail(e)
            } finally {
                sending = false
            }
        }
    }

    // ---- calendar ----
    fun rsvp(e: CalEvent, response: String) {
        events = events.map { if (it.id == e.id && it.account == e.account) it.copy(needsRsvp = false) else it }
        viewModelScope.launch {
            try {
                src.rsvp(e.account, e.id, response)
                toast = when (response) { "accepted" -> "Accepted"; "declined" -> "Declined"; else -> "Marked as maybe" }
            } catch (x: Throwable) {
                events = events.map { if (it.id == e.id && it.account == e.account) it.copy(needsRsvp = e.needsRsvp) else it }
                fail(x)
            }
        }
    }

    // ---- settings ----
    fun setNotifications(on: Boolean) {
        notificationsOn = on
        prefs.edit().putBoolean("enabled", on).apply()
        if (on) MailCheckWorker.schedule(getApplication()) else MailCheckWorker.cancel(getApplication())
    }

    fun checkNow() { MailCheckWorker.checkNow(getApplication()); toast = "Checking for new mail…" }

    suspend fun linkAccountUrl(): String? = try { src.linkAccountUrl() } catch (e: Throwable) { fail(e); null }

    fun accountsChanged() { accounts = emptyList(); inboxCache.clear(); refresh() }

    override fun onCleared() { flushPending(); super.onCleared() }
}
