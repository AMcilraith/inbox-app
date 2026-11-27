package com.amcilraith.inboxhub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amcilraith.inboxhub.R
import com.amcilraith.inboxhub.data.ThreadSummary
import kotlinx.coroutines.launch

/** Opens a sign-in or link URL. Provided by MainActivity. */
fun interface UrlOpener { fun open(url: String) }

private data class NavItem(val tab: Tab, val on: ImageVector, val off: ImageVector)

private val NAV = listOf(
    NavItem(Tab.Inbox, Icons.Filled.Inbox, Icons.Outlined.Inbox),
    NavItem(Tab.Ask, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    NavItem(Tab.Calendar, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    NavItem(Tab.Settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun App(vm: AppViewModel, opener: UrlOpener) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.toast) { vm.toast?.let { vm.toast = null; snack.showSnackbar(it) } }
    LaunchedEffect(vm.error) { vm.error?.let { vm.error = null; snack.showSnackbar(it, duration = SnackbarDuration.Long) } }
    LaunchedEffect(vm.pending) {
        val p = vm.pending ?: return@LaunchedEffect
        val r = snack.showSnackbar(p.message, actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) vm.undo() else vm.flushPending()
    }
    LaunchedEffect(vm.active) { if (vm.active) vm.refresh() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Crossfade(vm.active, animationSpec = tween(320), label = "signin") { active ->
            if (!active) SignInScreen(vm, opener) else MainShell(vm, opener)
        }

        // The conversation slides in over the list, so the list keeps its scroll position.
        val last = remember { arrayOfNulls<ThreadSummary>(1) }
        vm.openSummary?.let { last[0] = it }
        AnimatedVisibility(
            visible = vm.active && vm.openSummary != null,
            enter = slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { it / 3 } + fadeOut(tween(180)),
        ) {
            last[0]?.let { ThreadScreen(vm, vm.openSummary ?: it) }
        }

        SnackbarHost(
            snack,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (vm.openSummary != null) 120.dp else 84.dp),
        ) { data ->
            Snackbar(
                data,
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                actionColor = Tones.accentDark,
            )
        }
    }
}

@Composable
private fun MainShell(vm: AppViewModel, opener: UrlOpener) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Each screen's top bar handles the status bar itself.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                NAV.forEach { item ->
                    val selected = vm.tab == item.tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { vm.selectTab(item.tab) },
                        icon = {
                            BadgedBox(badge = {
                                if (item.tab == Tab.Inbox && vm.unreadCount > 0) {
                                    Badge(containerColor = accentColor(), contentColor = Color.White) { Text(if (vm.unreadCount > 99) "99+" else "${vm.unreadCount}") }
                                }
                            }) { Icon(if (selected) item.on else item.off, contentDescription = item.tab.label) }
                        },
                        label = { Text(item.tab.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
                    )
                }
            }
        },
    ) { pad ->
        AnimatedContent(
            targetState = vm.tab,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(220, delayMillis = 60), initialScale = 0.985f)) togetherWith fadeOut(tween(90))
            },
            label = "tab",
            modifier = Modifier.padding(pad),
        ) { tab ->
            when (tab) {
                Tab.Inbox -> InboxScreen(vm)
                Tab.Ask -> AskScreen(vm)
                Tab.Calendar -> CalendarScreen(vm)
                Tab.Settings -> SettingsScreen(vm, opener)
            }
        }
    }
    if (vm.openSummary == null && vm.tab != Tab.Inbox) BackHandler { vm.selectTab(Tab.Inbox) }
}

@Composable
fun SignInScreen(vm: AppViewModel, opener: UrlOpener) {
    val scope = rememberCoroutineScope()
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(shown, enter = fadeIn(tween(420)) + slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it / 6 }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF2A2A2E)),
                    contentAlignment = Alignment.Center,
                ) { Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(84.dp)) }
                Spacer(Modifier.height(24.dp))
                Text("Inbox Hub", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Every Superhuman account and calendar in one place.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(28.dp))
                Feature(Icons.Filled.Inbox, "One inbox", "Important mail from all accounts, newest first")
                Feature(Icons.Filled.AutoAwesome, "Superhuman AI", "Ask questions and draft replies, synced with your computer")
                Feature(Icons.Filled.NotificationsActive, "Notifications", "New important mail, with Done and Mark read buttons")
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { scope.launch { vm.authorizeUrl()?.let { opener.open(it.toString()) } } },
            enabled = !vm.signingIn,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text(if (vm.signingIn) "Finish signing in in the browser…" else "Sign in with Superhuman", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { vm.enterDemo() },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text("Try with sample data", fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface) }
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Sample data" strip with a way out, shown at the top of the inbox in demo mode. */
@Composable
fun DemoStrip(vm: AppViewModel) {
    if (!vm.demo) return
    Surface(
        color = accentColor().copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sample data: four example accounts", fontSize = 13.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            androidx.compose.material3.TextButton(onClick = { vm.exitDemo() }) { Text("Sign in", fontWeight = FontWeight.SemiBold, color = accentColor()) }
        }
    }
}
