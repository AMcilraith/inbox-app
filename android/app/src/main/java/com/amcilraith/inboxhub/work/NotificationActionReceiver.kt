package com.amcilraith.inboxhub.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.amcilraith.inboxhub.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles the Done / Mark read buttons on a mail notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val DONE = "com.amcilraith.inboxhub.DONE"
        const val READ = "com.amcilraith.inboxhub.READ"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val account = intent.getStringExtra(MailCheckWorker.EXTRA_ACCOUNT) ?: return
        val thread = intent.getStringExtra(MailCheckWorker.EXTRA_THREAD) ?: return
        val last = intent.getStringExtra(MailCheckWorker.EXTRA_LAST_MESSAGE) ?: thread
        NotificationManagerCompat.from(context).cancel(MailCheckWorker.notificationId(account, thread))
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = Repository.get(context)
                when (intent.action) {
                    DONE -> repo.updateById(account, thread, last, done = true, read = true)
                    READ -> repo.updateById(account, thread, last, read = true)
                }
            } catch (_: Exception) {
                // Best effort: the thread stays as it was and shows up again in the app.
            } finally {
                pending.finish()
            }
        }
    }
}
