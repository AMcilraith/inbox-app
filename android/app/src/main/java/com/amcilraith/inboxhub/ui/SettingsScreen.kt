package com.amcilraith.inboxhub.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: AppViewModel, opener: UrlOpener) {
    val scope = rememberCoroutineScope()
    var signOutAsk by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)) }

        if (vm.demo) item {
            Card {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Science, null, tint = accentColor())
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("You're looking at sample data", fontWeight = FontWeight.SemiBold)
                        Text("Nothing here is sent or saved. Sign in to see your own accounts.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(10.dp))
                        Button(onClick = { vm.exitDemo() }, shape = RoundedCornerShape(12.dp)) { Text("Sign in with Superhuman") }
                    }
                }
            }
        }

        item { SectionLabel("Accounts", Modifier.padding(start = 6.dp, top = 6.dp)) }
        item {
            Card {
                vm.accounts.forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(a.color)), contentAlignment = Alignment.Center) {
                            Text(a.label.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(a.email, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (a.primary) Pill("Primary", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (vm.accounts.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ActionRow(Icons.Filled.Add, "Link another Superhuman account", if (vm.demo) "Sign in first" else "Approve in the browser, then pull to refresh", enabled = !vm.demo) {
                    scope.launch { vm.linkAccountUrl()?.let { opener.open(it) } }
                }
                ActionRow(Icons.Filled.Refresh, "Reload accounts", null) { vm.accountsChanged() }
            }
        }

        item { SectionLabel("Notifications", Modifier.padding(start = 6.dp, top = 6.dp)) }
        item {
            Card {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Notifications, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("New important mail", fontWeight = FontWeight.Medium)
                        Text("Checks every 15 minutes (Android's minimum), in every account.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = vm.notificationsOn, onCheckedChange = { vm.setNotifications(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = accentColor()),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ActionRow(Icons.Filled.Refresh, "Check for new mail now", null, enabled = !vm.demo) { vm.checkNow() }
            }
        }

        item { SectionLabel("Session", Modifier.padding(start = 6.dp, top = 6.dp)) }
        item {
            Card {
                if (vm.demo) ActionRow(Icons.AutoMirrored.Filled.Logout, "Leave sample data", null) { vm.exitDemo() }
                else ActionRow(Icons.AutoMirrored.Filled.Logout, "Sign out", "Notifications stop until you sign in again", tint = Tones.high) { signOutAsk = true }
            }
        }
        item {
            Text(
                "Inbox Hub $version · works with the Inbox Hub desktop app",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 6.dp),
            )
        }
    }
    if (signOutAsk) {
        AlertDialog(
            onDismissRequest = { signOutAsk = false },
            title = { Text("Sign out?") },
            text = { Text("Notifications stop until you sign in again.") },
            confirmButton = { TextButton(onClick = { signOutAsk = false; vm.signOut() }) { Text("Sign out", color = Tones.high) } },
            dismissButton = { TextButton(onClick = { signOutAsk = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth(),
    ) { Column(content = content) }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, note: String?, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = (tint ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = alpha))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, color = (tint ?: MaterialTheme.colorScheme.onSurface).copy(alpha = alpha))
            note?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)) }
        }
    }
}
