package com.amcilraith.inboxhub

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import com.amcilraith.inboxhub.auth.Auth
import com.amcilraith.inboxhub.ui.App
import com.amcilraith.inboxhub.ui.AppViewModel
import com.amcilraith.inboxhub.ui.InboxHubTheme
import com.amcilraith.inboxhub.ui.UrlOpener
import com.amcilraith.inboxhub.work.MailCheckWorker

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val opener = UrlOpener { url -> CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, Uri.parse(url)) }
        setContent { InboxHubTheme { App(vm, opener) } }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data
        // Sign-in result from Superhuman.
        if (data != null && data.toString().startsWith(Auth.REDIRECT)) {
            vm.completeSignIn(data)
            return
        }
        // Tapped a mail notification: open that thread.
        val account = intent?.getStringExtra(MailCheckWorker.EXTRA_ACCOUNT)
        val thread = intent?.getStringExtra(MailCheckWorker.EXTRA_THREAD)
        if (account != null && thread != null) vm.openThread(account, thread)
    }
}
