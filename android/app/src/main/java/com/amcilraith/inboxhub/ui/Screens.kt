package com.amcilraith.inboxhub.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amcilraith.inboxhub.data.CalEvent
import com.amcilraith.inboxhub.data.ThreadSummary
import com.amcilraith.inboxhub.data.displayName
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
private val dayFmt = DateTimeFormatter.ofPattern("EEEE, MMM d")
private val shortFmt = DateTimeFormatter.ofPattern("MMM d")

fun ago(t: Instant, zone: ZoneId): String {
    val d = Duration.between(t, Instant.now())
    return when {
        d.toMinutes() < 60 -> "${d.toMinutes().coerceAtLeast(1)}m"
        d.toHours() < 24 -> "${d.toHours()}h"
        d.toDays() < 7 -> "${d.toDays()}d"
        else -> shortFmt.format(t.atZone(zone))
    }
}

@Composable
fun Dot(color: Long, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(color)))
}

/** Text the user opens a sign-in or link URL with. Provided by MainActivity. */
fun interface UrlOpener { fun open(url: String) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel, opener: UrlOpener) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.toast) { vm.toast?.let { snack.showSnackbar(it); vm.toast = null } }
    LaunchedEffect(vm.error) { vm.error?.let { snack.showSnackbar(it); vm.error = null } }
    LaunchedEffect(vm.signedIn) { if (vm.signedIn) vm.refresh() }

    if (!vm.signedIn) {
        Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad -> SignInScreen(vm, opener, Modifier.padding(pad)) }
        return
    }
    if (vm.openSummary != null) {
        Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad -> ThreadScreen(vm, Modifier.padding(pad)) }
        return
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf(Tab.Inbox to Icons.Filled.Email, Tab.Ask to Icons.Filled.Search, Tab.Calendar to Icons.Filled.DateRange, Tab.Settings to Icons.Filled.Settings).forEach { (t, icon) ->
                    NavigationBarItem(selected = vm.tab == t, onClick = { vm.selectTab(t) }, icon = { Icon(icon, null) }, label = { Text(t.name) })
                }
            }
        },
    ) { pad ->
        when (vm.tab) {
            Tab.Inbox -> InboxScreen(vm, Modifier.padding(pad))
            Tab.Ask -> AskScreen(vm, Modifier.padding(pad))
            Tab.Calendar -> CalendarScreen(vm, Modifier.padding(pad))
            Tab.Settings -> SettingsScreen(vm, opener, Modifier.padding(pad))
        }
    }
}

@Composable
fun SignInScreen(vm: AppViewModel, opener: UrlOpener, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.MailOutline, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp)) }
        }
        Spacer(Modifier.height(20.dp))
        Text("Inbox Hub", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "All your Superhuman accounts and calendars in one place, with notifications for new important mail.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { scope.launch { vm.authorizeUrl()?.let { opener.open(it.toString()) } } },
            enabled = !vm.signingIn,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
        ) { Text(if (vm.signingIn) "Finish signing in in the browser…" else "Sign in with Superhuman", fontWeight = FontWeight.SemiBold) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(vm: AppViewModel, modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Inbox", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        if (vm.accounts.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = vm.accountFilter == null, onClick = { vm.selectAccount(null) }, label = { Text("All accounts") })
                vm.accounts.forEach { a ->
                    FilterChip(
                        selected = vm.accountFilter == a.email,
                        onClick = { vm.selectAccount(a.email) },
                        label = { Text(a.email.substringBefore('@')) },
                        leadingIcon = { Dot(a.color) },
                    )
                }
            }
        }
        SecondaryTabRow(selectedTabIndex = vm.split.ordinal, containerColor = MaterialTheme.colorScheme.background) {
            Split.entries.forEach { s ->
                Tab(selected = vm.split == s, onClick = { vm.selectSplit(s) }, text = { Text(s.label, fontWeight = if (vm.split == s) FontWeight.SemiBold else FontWeight.Normal) })
            }
        }
        PullToRefreshBox(isRefreshing = vm.loading, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
            if (vm.threads.isEmpty() && !vm.loading) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Check, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("Inbox zero", fontWeight = FontWeight.SemiBold)
                    Text("Pull down to refresh", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(vm.threads, key = { it.account + it.id }) { t -> SwipeRow(vm, t) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeRow(vm: AppViewModel, t: ThreadSummary) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> { vm.markDone(t); true }
            SwipeToDismissBoxValue.EndToStart -> { vm.toggleRead(t); false }
            else -> false
        }
    })
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val dir = state.dismissDirection
            val bg by animateColorAsState(
                when (dir) {
                    SwipeToDismissBoxValue.StartToEnd -> Color(0xFF2F855A)
                    SwipeToDismissBoxValue.EndToStart -> Color(0xFF5C5C64)
                    else -> Color.Transparent
                }, label = "swipe",
            )
            Box(
                Modifier.fillMaxSize().background(bg).padding(horizontal = 24.dp),
                contentAlignment = if (dir == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Text(if (dir == SwipeToDismissBoxValue.StartToEnd) "Done" else if (t.unread) "Mark read" else "Mark unread", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        },
    ) { ThreadRow(vm, t) }
}

@Composable
fun ThreadRow(vm: AppViewModel, t: ThreadSummary) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().clickable { vm.openThread(t.account, t.id) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(vm.colorOf(t.account))
                Spacer(Modifier.width(10.dp))
                Text(
                    t.fromName.ifBlank { t.from },
                    fontWeight = if (t.unread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (t.needsReply) {
                    Text("Reply", fontSize = 11.sp, color = Tones.high, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
                }
                if (t.starred) Icon(Icons.Filled.Star, null, Modifier.size(14.dp).padding(end = 2.dp), tint = Tones.med)
                Text(ago(t.date, vm.zone), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                t.subject, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (t.unread) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(start = 18.dp, top = 2.dp),
            )
            Text(
                t.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 18.dp, top = 2.dp),
            )
        }
        HorizontalDivider(Modifier.padding(start = 34.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(vm: AppViewModel, modifier: Modifier) {
    val s = vm.openSummary ?: return
    var reply by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { vm.closeThread() }
    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = { vm.closeThread() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text(vm.open?.subject?.ifBlank { null } ?: s.subject, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp) },
            actions = {
                IconButton(onClick = { vm.toggleStar(s) }) { Icon(Icons.Filled.Star, "Star", tint = if (s.starred) Tones.med else MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { vm.markDone(s) }) { Icon(Icons.Filled.Check, "Done") }
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val zone = vm.zone
                    val now = java.time.ZonedDateTime.now(zone)
                    val tomorrow8 = now.toLocalDate().plusDays(1).atTime(8, 0).atZone(zone)
                    val monday8 = now.toLocalDate().with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).atTime(8, 0).atZone(zone)
                    DropdownMenuItem(text = { Text("Remind me in 3 hours") }, onClick = { menu = false; vm.remind(s, now.plusHours(3).toInstant(), "later today") })
                    DropdownMenuItem(text = { Text("Remind me tomorrow 8 AM") }, onClick = { menu = false; vm.remind(s, tomorrow8.toInstant(), "tomorrow 8 AM") })
                    DropdownMenuItem(text = { Text("Remind me Monday 8 AM") }, onClick = { menu = false; vm.remind(s, monday8.toInstant(), "Monday 8 AM") })
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (s.important) "Move to Other" else "Move to Important") },
                        onClick = { menu = false; vm.setImportant(s, !s.important) },
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        val t = vm.open
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (t == null) item { Text(if (vm.openLoading) "Loading…" else "", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            items(t?.messages ?: emptyList(), key = { it.id + it.sentAt }) { m ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(displayName(m.from), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            m.sentAt?.let { Text(ago(it, vm.zone), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text("to " + m.to.joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        SelectionContainer { Text(m.body, fontSize = 14.sp, lineHeight = 20.sp) }
                        if (m.attachments.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("📎 " + m.attachments.joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
          Column {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { vm.draftWithAi(reply) { reply = it } },
                    enabled = t != null && !vm.drafting,
                ) { Text(if (vm.drafting) "Drafting…" else "✦ Draft with Superhuman AI") }
                Spacer(Modifier.weight(1f))
                Text("Type instructions first, or leave empty", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = reply, onValueChange = { reply = it },
                    placeholder = { Text("Reply from ${s.account.substringBefore('@')}…") },
                    modifier = Modifier.weight(1f), maxLines = 6, shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { if (reply.isNotBlank()) confirm = true }, enabled = !vm.sending && reply.isNotBlank() && t != null) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
          }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Send reply?") },
            text = { Text("From ${s.account}") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.reply(reply, send = true) { reply = "" } }) { Text("Send") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirm = false; vm.reply(reply, send = false) { reply = "" } }) { Text("Save draft") }
                    TextButton(onClick = { confirm = false }) { Text("Cancel") }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: AppViewModel, modifier: Modifier) {
    var selected by remember { mutableStateOf<CalEvent?>(null) }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Next 7 days", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        PullToRefreshBox(isRefreshing = vm.loading, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
            val byDay = vm.events.groupBy { it.start.atZone(vm.zone).toLocalDate() }.toSortedMap()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (byDay.isEmpty() && !vm.loading) item {
                    Text("Nothing scheduled this week", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                }
                byDay.forEach { (day, evs) ->
                    item(key = day.toString()) {
                        Text(
                            (if (day == LocalDate.now(vm.zone)) "Today · " else "") + dayFmt.format(day),
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
                        )
                    }
                    items(evs, key = { it.account + it.id + it.start }) { e -> EventRow(vm, e) { selected = e } }
                }
            }
        }
    }
    selected?.let { e ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(e.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(whenText(e, vm.zone))
                    Text(e.account, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    e.location?.let { Text(it) }
                    if (e.clash) Text("Overlaps another event", color = Tones.high)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("accepted" to "Yes", "tentative" to "Maybe", "declined" to "No").forEach { (r, label) ->
                            OutlinedButton(onClick = { vm.rsvp(e, r); selected = null }) { Text(label) }
                        }
                    }
                }
            },
            confirmButton = {
                e.link?.let { link ->
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    TextButton(onClick = {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)))
                    }) { Text("Join call") }
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
}

private fun whenText(e: CalEvent, zone: ZoneId): String {
    val s = e.start.atZone(zone)
    val en = e.end.atZone(zone)
    return if (e.allDay) "${dayFmt.format(s)} · all day" else "${dayFmt.format(s)} · ${timeFmt.format(s)} – ${timeFmt.format(en)}"
}

@Composable
fun EventRow(vm: AppViewModel, e: CalEvent, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(78.dp)) {
            if (e.allDay) Text("All day", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                Text(timeFmt.format(e.start.atZone(vm.zone)), fontSize = 13.sp)
                Text(timeFmt.format(e.end.atZone(vm.zone)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.width(3.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(Color(vm.colorOf(e.account))))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(e.account.substringBefore('@'), e.location).joinToString(" · "),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (e.clash) Text("Clash", fontSize = 11.sp, color = Tones.high, fontWeight = FontWeight.SemiBold)
                if (e.needsRsvp) Text("RSVP", fontSize = 11.sp, color = Tones.med, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, opener: UrlOpener, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var signOutAsk by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
        item { SectionTitle("Accounts") }
        items(vm.accounts, key = { it.email }) { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(a.color, 10)
                Spacer(Modifier.width(10.dp))
                Text(a.email, modifier = Modifier.weight(1f))
                if (a.primary) Text("primary", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            OutlinedButton(onClick = { scope.launch { vm.linkAccountUrl()?.let { opener.open(it) } } }, modifier = Modifier.fillMaxWidth()) {
                Text("Link another Superhuman account")
            }
            Text("Approve in the browser, then pull to refresh your inbox.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { SectionTitle("Notifications") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("New important mail")
                    Text("Checks every 15 minutes (Android's minimum), per account.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = vm.notificationsOn, onCheckedChange = { vm.setNotifications(it) })
            }
        }
        item { OutlinedButton(onClick = { vm.checkNow() }, modifier = Modifier.fillMaxWidth()) { Text("Check for new mail now") } }
        item { SectionTitle("Session") }
        item { OutlinedButton(onClick = { signOutAsk = true }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") } }
    }
    if (signOutAsk) {
        AlertDialog(
            onDismissRequest = { signOutAsk = false },
            title = { Text("Sign out?") },
            text = { Text("Notifications stop until you sign in again.") },
            confirmButton = { TextButton(onClick = { signOutAsk = false; vm.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { signOutAsk = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskScreen(vm: AppViewModel, modifier: Modifier) {
    var input by rememberSaveable { mutableStateOf("") }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(vm.chat.size, vm.asking) { if (vm.chat.isNotEmpty()) list.animateScrollToItem(vm.chat.size - 1 + if (vm.asking) 1 else 0) }
    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Column {
                    Text("Ask Superhuman AI", fontWeight = FontWeight.Bold)
                    Text("Shared with Inbox Hub on your computer", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = { TextButton(onClick = { vm.clearAsk() }) { Text("New") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        PullToRefreshBox(isRefreshing = false, onRefresh = { vm.loadAsk() }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (vm.chat.isEmpty() && !vm.asking) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ask anything about your email and calendars, across every account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf("What needs a reply today?", "What's on my calendar this week?", "Any bills or deadlines coming up?", "Who am I waiting on?").forEach { q ->
                            OutlinedButton(onClick = { vm.ask(q) }) { Text(q) }
                        }
                    }
                }
                items(vm.chat, key = { it.id }) { m ->
                    val mine = m.role == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(0.86f).let { if (mine) it else it },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                SelectionContainer {
                                    Text(
                                        if (mine) m.text else m.text.replace("**", ""),
                                        color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        fontSize = 14.sp, lineHeight = 20.sp,
                                    )
                                }
                                if (!mine && m.device == "desktop") {
                                    Text("asked on your computer", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
                if (vm.asking) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Superhuman AI is thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    placeholder = { Text("Ask about your email or calendar…") },
                    modifier = Modifier.weight(1f), maxLines = 4, shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { vm.ask(input); input = "" }, enabled = input.isNotBlank() && !vm.asking) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Ask")
                }
            }
        }
    }
}
