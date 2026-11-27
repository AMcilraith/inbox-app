package com.amcilraith.inboxhub.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amcilraith.inboxhub.data.CalEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private fun whenText(e: CalEvent, zone: ZoneId): String {
    val s = e.start.atZone(zone)
    val en = e.end.atZone(zone)
    return if (e.allDay) "${dayFmt.format(s)} · all day" else "${dayFmt.format(s)} · ${timeFmt.format(s)} – ${timeFmt.format(en)}"
}

private fun openLink(ctx: android.content.Context, link: String) {
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(vm: AppViewModel) {
    var selected by remember { mutableStateOf<CalEvent?>(null) }
    val today = LocalDate.now(vm.zone)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Calendar", style = MaterialTheme.typography.headlineSmall)
                    val todayCount = vm.events.count { it.start.atZone(vm.zone).toLocalDate() == today }
                    Text(
                        if (!vm.eventsReady) "Loading…" else "Next 7 days · $todayCount today",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        PullToRefreshBox(isRefreshing = vm.pulling, onRefresh = { vm.refresh(userPulled = true) }, modifier = Modifier.fillMaxSize()) {
            val byDay = remember(vm.events) { vm.events.groupBy { it.start.atZone(vm.zone).toLocalDate() }.toSortedMap() }
            if (!vm.eventsReady && byDay.isEmpty()) {
                SkeletonRows(5)
                return@PullToRefreshBox
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                if (byDay.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.EventAvailable, null, Modifier.size(40.dp), tint = Tones.good)
                        Spacer(Modifier.height(10.dp))
                        Text("Nothing scheduled this week", style = MaterialTheme.typography.titleMedium)
                    }
                }
                byDay.forEach { (day, evs) ->
                    stickyHeader(key = "d:$day") { DayHeader(day, today) }
                    val now = Instant.now()
                    var nowShown = day != today
                    evs.forEach { e ->
                        if (!nowShown && !e.allDay && e.start > now) {
                            nowShown = true
                            item(key = "now") { NowLine(vm) }
                        }
                        item(key = "e:${e.account}/${e.id}/${e.start}") {
                            EventCard(vm, e, past = e.end < now && !e.allDay, modifier = Modifier.animateItem()) { selected = e }
                        }
                    }
                    if (!nowShown) item(key = "now") { NowLine(vm) }
                }
            }
        }
    }
    selected?.let { e -> EventSheet(vm, e) { selected = null } }
}

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate) {
    val isToday = day == today
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(top = 18.dp, bottom = 8.dp, start = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(if (isToday) accentColor() else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("${day.dayOfMonth}", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (isToday) Color.White else MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                when (day) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) },
                fontWeight = FontWeight.Bold, fontSize = 15.sp,
            )
            Text(shortFmt.format(day) + (if (day == today || day == today.plusDays(1)) " · " + day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) else ""),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NowLine(vm: AppViewModel) {
    val c = Tones.high
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(timeFmt.format(Instant.now().atZone(vm.zone)), fontSize = 11.sp, color = c, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Box(Modifier.weight(1f).height(1.5.dp).background(c))
    }
}

@Composable
fun EventCard(vm: AppViewModel, e: CalEvent, past: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = Color(vm.colorOf(e.account))
    val ctx = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (e.clash && !past) Tones.high.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).alpha(if (past) 0.55f else 1f),
    ) {
        Row(Modifier.clickable(onClick = onClick).height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(color))
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                Text(
                    if (e.allDay) "All day" else "${timeFmt.format(e.start.atZone(vm.zone))} – ${timeFmt.format(e.end.atZone(vm.zone))}",
                    fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(e.title, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(vm.labelOf(e.account), fontSize = 12.5.sp, color = color, fontWeight = FontWeight.SemiBold)
                    e.location?.let {
                        Icon(Icons.Filled.Place, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(it, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if ((e.clash || e.needsRsvp) && !past) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (e.clash) Pill("Overlaps", Tones.high)
                        if (e.needsRsvp) Pill("Needs RSVP", Tones.med)
                    }
                }
            }
            if (e.link != null && !past) {
                Box(Modifier.fillMaxHeight().padding(end = 10.dp), contentAlignment = Alignment.Center) {
                    FilledTonalButton(
                        onClick = { openLink(ctx, e.link) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Icon(Icons.Filled.Videocam, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Join", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventSheet(vm: AppViewModel, e: CalEvent, onClose: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(vm.colorOf(e.account), 10)
                Spacer(Modifier.width(8.dp))
                Text(vm.labelOf(e.account) + " · " + e.account, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(e.title, style = MaterialTheme.typography.headlineSmall)
            Text(whenText(e, vm.zone), fontSize = 15.sp)
            e.location?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Place, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(it)
                }
            }
            if (e.clash) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = Tones.high)
                Spacer(Modifier.width(6.dp))
                Text("Overlaps another event", color = Tones.high)
            }
            Spacer(Modifier.height(4.dp))
            Text("Going?", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("accepted" to "Yes", "tentative" to "Maybe", "declined" to "No").forEach { (r, label) ->
                    OutlinedButton(onClick = { vm.rsvp(e, r); onClose() }, modifier = Modifier.weight(1f)) { Text(label) }
                }
            }
            e.link?.let { link ->
                FilledTonalButton(onClick = { openLink(ctx, link) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.filledTonalButtonColors()) {
                    Icon(Icons.Filled.Videocam, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Join call")
                }
            }
        }
    }
}
