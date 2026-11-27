package com.amcilraith.inboxhub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amcilraith.inboxhub.data.Message
import com.amcilraith.inboxhub.data.ThreadSummary
import com.amcilraith.inboxhub.data.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(vm: AppViewModel, s: ThreadSummary) {
    var reply by rememberSaveable(s.account, s.id) { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    BackHandler(enabled = vm.openSummary != null) { vm.closeThread() }
    val t = vm.open?.takeIf { it.id == s.id && it.account == s.account }
    val accent = accentColor()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.closeThread() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {},
                actions = {
                    IconButton(onClick = { vm.toggleStar(s) }) {
                        if (s.starred) Icon(Icons.Filled.Star, "Unstar", tint = Tones.med) else Icon(Icons.Outlined.StarOutline, "Star")
                    }
                    IconButton(onClick = { vm.markDone(s) }) { Icon(Icons.Filled.Check, "Done") }
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val zone = vm.zone
                        val now = java.time.ZonedDateTime.now(zone)
                        val tomorrow8 = now.toLocalDate().plusDays(1).atTime(8, 0).atZone(zone)
                        val monday8 = now.toLocalDate().with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).atTime(8, 0).atZone(zone)
                        val icon: @Composable () -> Unit = { Icon(Icons.Filled.Schedule, null) }
                        DropdownMenuItem(text = { Text("Remind me in 3 hours") }, leadingIcon = icon, onClick = { menu = false; vm.remind(s, now.plusHours(3).toInstant(), "later today") })
                        DropdownMenuItem(text = { Text("Remind me tomorrow, 8 AM") }, leadingIcon = icon, onClick = { menu = false; vm.remind(s, tomorrow8.toInstant(), "tomorrow 8 AM") })
                        DropdownMenuItem(text = { Text("Remind me Monday, 8 AM") }, leadingIcon = icon, onClick = { menu = false; vm.remind(s, monday8.toInstant(), "Monday 8 AM") })
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (s.important) "Move to Other" else "Move to Important") },
                            onClick = { menu = false; vm.setImportant(s, !s.important) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (s.unread) "Mark as read" else "Mark as unread") },
                            onClick = { menu = false; vm.toggleRead(s) },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { Composer(vm, s, reply, { reply = it }, enabled = t != null) { confirm = true } },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "head") {
                Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)) {
                    Text(t?.subject?.ifBlank { null } ?: s.subject, style = MaterialTheme.typography.headlineSmall, fontSize = 22.sp, lineHeight = 28.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(vm.labelOf(s.account), Color(vm.colorOf(s.account)))
                        if (s.needsReply) Pill("Needs reply", Tones.high)
                        if (s.important) Pill("Important", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (t == null) {
                item(key = "loading") { LoadingCard(vm.openLoading) }
            } else {
                val n = t.messages.size
                itemsIndexed(t.messages, key = { i, m -> m.id + i }) { i, m ->
                    MessageCard(vm, m, s.account, startExpanded = i == n - 1 || n <= 2, accent = accent)
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Send this reply?") },
            text = { Text("From ${s.account}. Superhuman lets you undo for about a minute.") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.reply(reply, send = true) { reply = "" } }) { Text("Send", fontWeight = FontWeight.SemiBold) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirm = false; vm.reply(reply, send = false) { reply = "" } }) { Text("Save draft") }
                    TextButton(onClick = { confirm = false }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun LoadingCard(loading: Boolean) {
    Surface(
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(vertical = 8.dp)) { if (loading) SkeletonRows(2) else Text("Couldn't load this conversation.", Modifier.padding(16.dp)) }
    }
}

@Composable
private fun MessageCard(vm: AppViewModel, m: Message, account: String, startExpanded: Boolean, accent: Color) {
    var expanded by rememberSaveable(m.id) { mutableStateOf(startExpanded) }
    val mine = m.from.contains(account, ignoreCase = true)
    val name = if (mine) "You" else displayName(m.from)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = if (expanded) 6.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(if (mine) "You" else displayName(m.from), 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        m.sentAt?.let { Text(ago(it, vm.zone), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Text(
                        if (expanded) "to " + m.to.joinToString(", ") { if (it.equals(account, true)) "me" else displayName(it) }
                        else m.body.replace(Regex("\\s+"), " "),
                        fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AnimatedVisibility(expanded, enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp)) {
                    val text = androidx.compose.runtime.remember(m.body, accent) { linkified(m.body, accent) }
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface) }
                    if (m.attachments.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            m.attachments.forEach { a ->
                                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.AttachFile, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(6.dp))
                                        Text(a, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(vm: AppViewModel, s: ThreadSummary, text: String, onText: (String) -> Unit, enabled: Boolean, onSend: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { vm.draftWithAi(text) { onText(it) } },
                    enabled = enabled && !vm.drafting,
                    label = { Text(if (vm.drafting) "Drafting…" else if (text.isBlank()) "Draft with Superhuman AI" else "Redraft with these notes") },
                    leadingIcon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp), tint = accentColor()) },
                    shape = RoundedCornerShape(50),
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                TextField(
                    value = text, onValueChange = onText,
                    placeholder = { Text("Reply from ${vm.labelOf(s.account)}…") },
                    modifier = Modifier.weight(1f), maxLines = 6, shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = onSend,
                    enabled = enabled && !vm.sending && text.isNotBlank(),
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}
