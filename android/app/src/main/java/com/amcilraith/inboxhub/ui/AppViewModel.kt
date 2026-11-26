package com.amcilraith.inboxhub.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amcilraith.inboxhub.auth.SignInRequired
import com.amcilraith.inboxhub.data.Account
import com.amcilraith.inboxhub.data.CalEvent
import com.amcilraith.inboxhub.data.Repository
import com.amcilraith.inboxhub.data.ThreadDetail
import com.amcilraith.inboxhub.data.ThreadSummary
import com.amcilraith.inboxhub.work.MailCheckWorker
import kotlinx.coroutines.launch
import java.time.ZoneId

enum class Tab { Inbox, Calendar, Settings }
enum class Split(val label: String, val api: String?) { Important("Important", "Important"), Other("Other", "Other"), All("All", null) }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository.get(app)
    private val prefs = app.getSharedPreferences("notified", 0)
    val zone: ZoneId = ZoneId.systemDefault()

    var signedIn by mutableStateOf(repo.auth.signedIn); private set
    var signingIn by mutableStateOf(false); private set
    var tab by mutableStateOf(Tab.Inbox)
    var split by mutableStateOf(Split.Important); private set
    var accountFilter by mutableStateOf<String?>(null); private set
    var accounts by mutableStateOf<List<Account>>(emptyList()); private set
    var threads by mutableStateOf<List<ThreadSummary>>(emptyList()); private set
    var events by mutableStateOf<List<CalEvent>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    var open by mutableStateOf<ThreadDetail?>(null); private set
    var openSummary by mutableStateOf<ThreadSummary?>(null); private set
    var openLoading by mutableStateOf(false); private set
    var sending by mutableStateOf(false); private set
    var notificationsOn by mutableStateOf(prefs.getBoolean("enabled", true)); private set

    fun colorOf(email: String): Long = accounts.firstOrNull { it.email == email }?.color ?: 0xFF6B6B73

    private fun fail(e: Throwable) {
        if (e is SignInRequired) {
            signedIn = false
        }
        error = e.message ?: e.toString()
    }

    fun refresh() {
        if (!signedIn) return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                if (accounts.isEmpty()) accounts = repo.accounts()
                val targets = accountFilter?.let { listOf(it) } ?: accounts.map { it.email }
                when (tab) {
                    Tab.Calendar -> events = repo.events(targets, zone)
                    else -> {
                        val (list, errors) = repo.inbox(targets, split.api)
                        threads = list
                        if (errors.isNotEmpty()) error = errors.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                    }
                }
            } catch (e: Throwable) {
                fail(e)
            } finally {
                loading = false
            }
        }
    }

    fun reloadAccounts() {
        accounts = emptyList()
        refresh()
    }

    fun selectSplit(s: Split) { split = s; threads = emptyList(); refresh() }
    fun selectAccount(email: String?) { accountFilter = email; refresh() }
    fun selectTab(t: Tab) { tab = t; if (t != Tab.Settings) refresh() }

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
                MailCheckWorker.checkNow(getApplication())
                reloadAccounts()
            } catch (e: Throwable) {
                fail(e)
            } finally {
                signingIn = false
            }
        }
    }

    fun signOut() {
        repo.auth.signOut()
        signedIn = false
        accounts = emptyList(); threads = emptyList(); events = emptyList(); open = null
    }

    // ---- threads ----
    fun openThread(account: String, id: String) {
        openSummary = threads.firstOrNull { it.account == account && it.id == id }
        open = null
        openLoading = true
        viewModelScope.launch {
            try {
                val t = repo.thread(account, id)
                open = t
                val s = openSummary
                if (s != null && s.unread) {
                    runCatching { repo.update(s, read = true) }
                    threads = threads.map { if (it.id == s.id && it.account == s.account) it.copy(unread = false) else it }
                }
            } catch (e: Throwable) {
                fail(e)
            } finally {
                openLoading = false
            }
        }
        if (openSummary == null) openSummary = ThreadSummary(account, id, id, "", "", "", "", repo.now(), false, false, false, false, 1, emptyList())
    }

    fun closeThread() { open = null; openSummary = null }

    /** Swipe or button: archive (Superhuman "Done"). Optimistic, rolls back on failure. */
    fun markDone(t: ThreadSummary) {
        val before = threads
        threads = threads.filterNot { it.id == t.id && it.account == t.account }
        if (openSummary?.id == t.id) closeThread()
        viewModelScope.launch {
            try { repo.update(t, done = true); toast = "Marked done" } catch (e: Throwable) { threads = before; fail(e) }
        }
    }

    fun toggleRead(t: ThreadSummary) {
        val before = threads
        threads = threads.map { if (it.id == t.id && it.account == t.account) it.copy(unread = !t.unread) else it }
        viewModelScope.launch {
            try { repo.update(t, read = t.unread) } catch (e: Throwable) { threads = before; fail(e) }
        }
    }

    fun toggleStar(t: ThreadSummary) {
        val before = threads
        threads = threads.map { if (it.id == t.id && it.account == t.account) it.copy(starred = !t.starred) else it }
        viewModelScope.launch {
            try { repo.update(t, starred = !t.starred) } catch (e: Throwable) { threads = before; fail(e) }
        }
    }

    fun reply(text: String, send: Boolean, onDone: () -> Unit) {
        val t = open ?: return
        sending = true
        viewModelScope.launch {
            try {
                toast = repo.reply(t.account, t.id, text, send)
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
        viewModelScope.launch {
            try { repo.rsvp(e.account, e.id, response); toast = "Response sent"; refresh() } catch (x: Throwable) { fail(x) }
        }
    }

    // ---- settings ----
    fun setNotifications(on: Boolean) {
        notificationsOn = on
        prefs.edit().putBoolean("enabled", on).apply()
        if (on) MailCheckWorker.schedule(getApplication()) else MailCheckWorker.cancel(getApplication())
    }

    fun checkNow() { MailCheckWorker.checkNow(getApplication()); toast = "Checking for new mail…" }

    suspend fun linkAccountUrl(): String? = try { repo.linkAccountUrl() } catch (e: Throwable) { fail(e); null }
}
