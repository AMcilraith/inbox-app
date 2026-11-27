package com.amcilraith.inboxhub

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.amcilraith.inboxhub.data.DemoSource
import com.amcilraith.inboxhub.ui.App
import com.amcilraith.inboxhub.ui.AppViewModel
import com.amcilraith.inboxhub.ui.InboxHubTheme
import com.amcilraith.inboxhub.ui.Split
import com.amcilraith.inboxhub.ui.Tab
import com.amcilraith.inboxhub.ui.UrlOpener
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens with sample data and saves PNGs to app/build/screenshots/.
 * CI uploads them, so every change to the phone app comes with pictures of it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shoot(name: String, dark: Boolean = false, demo: Boolean = true, setup: (AppViewModel) -> Unit = {}) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("notified", 0).edit().clear().commit()
        val vm = AppViewModel(app, if (demo) DemoSource(latencyMs = 0) else null)
        rule.setContent { InboxHubTheme(dark = dark) { App(vm, UrlOpener { }) } }
        rule.waitForIdle()
        setup(vm)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun signIn() = shoot("01-sign-in", demo = false)
    @Test fun inbox() = shoot("02-inbox")
    @Test fun inboxAll() = shoot("03-inbox-all-splits") { it.selectSplit(Split.All) }
    @Test fun thread() = shoot("04-thread") { it.openThread("alex@company.example", "w1") }
    @Test fun threadLinks() = shoot("05-thread-links-and-symbols") { it.openThread("you@example.com", "p1") }
    @Test fun calendar() = shoot("06-calendar") { it.selectTab(Tab.Calendar) }
    @Test fun askEmpty() = shoot("07-ask") { it.selectTab(Tab.Ask) }
    @Test fun askAnswer() = shoot("08-ask-answer") { it.selectTab(Tab.Ask); it.ask("What needs a reply today?") }
    @Test fun settings() = shoot("09-settings") { it.selectTab(Tab.Settings) }
    @Test fun inboxDark() = shoot("10-inbox-dark", dark = true)
    @Test fun threadDark() = shoot("11-thread-dark", dark = true) { it.openThread("alex@company.example", "w2") }
    @Test fun calendarDark() = shoot("12-calendar-dark", dark = true) { it.selectTab(Tab.Calendar) }
}
