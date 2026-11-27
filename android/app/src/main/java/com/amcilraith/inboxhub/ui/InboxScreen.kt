package com.amcilraith.inboxhub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amcilraith.inboxhub.data.ThreadSummary

private sealed interface Entry {
    val key: String
    data class Header(val title: String) : Entry { override val key: String get() = "h:$title" }
    data class Item(val t: ThreadSummary) : Entry { override val key: String get() = "t:${t.account}/${t.id}" }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InboxScreen(vm: AppViewModel) {
    var searching by rememberSaveable { mutableStateOf(vm.query.isNotEmpty()) }
    val list = rememberLazyListState()
    BackHandler(enabled = searching && vm.openSummary == null) { searching = false; vm.query = "" }
    LaunchedEffect(vm.split, vm.accountFilter) { list.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Inbox", style = MaterialTheme.typography.headlineSmall)
                    val unread = vm.unreadCount
                    Text(
                        when {
                            !vm.inboxReady -> "Loading…"
                            unread > 0 -> "$unread unread · ${vm.split.label.lowercase()}"
                            else -> "All caught up"
                        },
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                IconButton(onClick = { searching = !searching; if (!searching) vm.query = "" }) {
                    Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, if (searching) "Close search" else "Search")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        AnimatedVisibility(searching, enter = expandVertically(tween(200)) + fadeIn(), exit = shrinkVertically(tween(160)) + fadeOut()) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            TextField(
                value = vm.query, onValueChange = { vm.query = it },
                placeholder = { Text("Search loaded mail") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
            )
        }
        DemoStrip(vm)
        if (vm.accounts.size > 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all") {
                    FilterChip(
                        selected = vm.accountFilter == null, onClick = { vm.selectAccount(null) }, label = { Text("All accounts") },
                        shape = RoundedCornerShape(50), colors = chipColors(),
                    )
                }
                items(vm.accounts, key = { it.email }) { a ->
                    FilterChip(
                        selected = vm.accountFilter == a.email,
                        onClick = { vm.selectAccount(if (vm.accountFilter == a.email) null else a.email) },
                        label = { Text(a.label) },
                        leadingIcon = { Dot(a.color) },
                        shape = RoundedCornerShape(50), colors = chipColors(),
                    )
                }
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Split.entries.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = vm.split == s,
                    onClick = { vm.selectSplit(s) },
                    shape = SegmentedButtonDefaults.itemShape(i, Split.entries.size),
                    icon = {},
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary, activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface,
                        activeBorderColor = MaterialTheme.colorScheme.primary, inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) { Text(s.label, fontWeight = if (vm.split == s) FontWeight.SemiBold else FontWeight.Medium) }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (vm.loading && !vm.pulling && vm.inboxReady) {
                LinearProgressIndicator(Modifier.fillMaxSize(), color = accentColor(), trackColor = Color.Transparent)
            }
        }
        PullToRefreshBox(isRefreshing = vm.pulling, onRefresh = { vm.refresh(userPulled = true) }, modifier = Modifier.fillMaxSize()) {
            val rows = vm.visibleThreads
            when {
                !vm.inboxReady && rows.isEmpty() -> SkeletonRows()
                rows.isEmpty() -> EmptyInbox(vm.query.isNotBlank())
                else -> {
                    val entries = remember(rows) {
                        buildList {
                            var last = ""
                            rows.forEach { t ->
                                val b = dayBucket(t.date, vm.zone)
                                if (b != last) { add(Entry.Header(b)); last = b }
                                add(Entry.Item(t))
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(entries, key = { it.key }, contentType = { if (it is Entry.Header) 0 else 1 }) { e ->
                            when (e) {
                                is Entry.Header -> SectionLabel(e.title, Modifier.animateItem().padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                                is Entry.Item -> Box(Modifier.animateItem()) { SwipeRow(vm, e.t) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    containerColor = MaterialTheme.colorScheme.surface,
)

@Composable
private fun EmptyInbox(searching: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(if (searching) Icons.Filled.Search else Icons.Filled.DoneAll, null, Modifier.size(32.dp), tint = Tones.good)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(if (searching) "No matches" else "Inbox zero", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            if (searching) "Search looks through the mail loaded on this screen." else "Nothing here. Pull down to check again.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeRow(vm: AppViewModel, t: ThreadSummary) {
    val haptic = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.StartToEnd -> { vm.markDone(t); true }
                SwipeToDismissBoxValue.EndToStart -> { vm.toggleRead(t); false }
                else -> false
            }
        },
        positionalThreshold = { it * 0.32f },
    )
    LaunchedEffect(state.targetValue) {
        if (state.targetValue != SwipeToDismissBoxValue.Settled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val dir = state.dismissDirection
            val armed = state.targetValue != SwipeToDismissBoxValue.Settled
            val bg by animateColorAsState(
                when {
                    dir == SwipeToDismissBoxValue.StartToEnd && armed -> Tones.good
                    dir == SwipeToDismissBoxValue.EndToStart && armed -> Tones.accent
                    dir != SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.surfaceVariant
                    else -> Color.Transparent
                }, tween(160), label = "swipe-bg",
            )
            val iconScale by animateFloatAsState(if (armed) 1.15f else 0.9f, label = "swipe-icon")
            val fg = if (armed) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            val (icon, label) = if (dir == SwipeToDismissBoxValue.StartToEnd) Icons.Filled.Check to "Done"
                else if (t.unread) Icons.Filled.MarkEmailRead to "Read" else Icons.Filled.MarkEmailUnread to "Unread"
            Row(
                Modifier.fillMaxSize().background(bg).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (dir == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
            ) {
                if (dir != SwipeToDismissBoxValue.Settled) {
                    SwipeLabel(icon, label, fg, iconScale, end = dir == SwipeToDismissBoxValue.EndToStart)
                }
            }
        },
    ) { ThreadRow(vm, t) }
}

@Composable
private fun SwipeLabel(icon: ImageVector, label: String, color: Color, scale: Float, end: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (end) Text(label, color = color, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 10.dp))
        Icon(icon, null, tint = color, modifier = Modifier.scale(scale))
        if (!end) Text(label, color = color, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
fun ThreadRow(vm: AppViewModel, t: ThreadSummary) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = accentColor()
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { vm.openThread(t.account, t.id) }.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box {
                    Avatar(t.fromName.ifBlank { t.from }, 42.dp)
                    // Which account it arrived in.
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(14.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.background).padding(2.dp).clip(CircleShape).background(Color(vm.colorOf(t.account))),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (t.unread) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            t.fromName.ifBlank { t.from },
                            fontWeight = if (t.unread) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (t.messageCount > 1) {
                            Text("  ${t.messageCount}", fontSize = 13.sp, color = muted, maxLines = 1)
                        }
                        Spacer(Modifier.weight(1f).width(8.dp))
                        if (t.starred) Icon(Icons.Filled.Star, "Starred", Modifier.size(15.dp).padding(end = 3.dp), tint = Tones.med)
                        Text(ago(t.date, vm.zone), fontSize = 12.5.sp, color = if (t.unread) accent else muted, fontWeight = if (t.unread) FontWeight.SemiBold else FontWeight.Normal)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        t.subject, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.5.sp,
                        fontWeight = if (t.unread) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (t.snippet.isNotBlank()) {
                        Text(t.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.5.sp, lineHeight = 18.sp, color = muted, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (t.needsReply) {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Pill("Needs reply", Tones.high) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        }
    }
}
