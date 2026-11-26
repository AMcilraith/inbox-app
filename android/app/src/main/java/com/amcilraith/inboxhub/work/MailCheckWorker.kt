package com.amcilraith.inboxhub.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.amcilraith.inboxhub.MainActivity
import com.amcilraith.inboxhub.R
import com.amcilraith.inboxhub.auth.SignInRequired
import com.amcilraith.inboxhub.data.Repository
import com.amcilraith.inboxhub.data.ThreadSummary
import java.util.concurrent.TimeUnit

/**
 * Every 15 minutes (Android's minimum for background work): look for new unread threads in each
 * account's Important split and post a notification for each, with Done / Mark read actions.
 */
class MailCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_MAIL = "mail"
        private const val UNIQUE = "mail-check"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_THREAD = "thread"
        const val EXTRA_LAST_MESSAGE = "last_message"

        fun createChannel(context: Context) {
            val ch = NotificationChannel(CHANNEL_MAIL, "New important mail", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New unread mail in each account's Important inbox"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<MailCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)

        fun checkNow(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<MailCheckWorker>().build())
        }

        fun notificationId(account: String, thread: String) = (account + thread).hashCode()
    }

    override suspend fun doWork(): Result {
        val repo = Repository.get(applicationContext)
        if (!repo.auth.signedIn) return Result.success()
        val prefs = applicationContext.getSharedPreferences("notified", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true)) return Result.success()
        return try {
            for (account in repo.accounts()) {
                val key = "seen:${account.email}"
                val firstRun = !prefs.contains(key)
                val seen = prefs.getStringSet(key, emptySet())!!.toMutableSet()
                val unread = repo.threads(account.email, "Important", unreadOnly = true, limit = 25)
                val fresh = unread.filter { "${it.id}:${it.lastMessageId}" !in seen }
                if (!firstRun) fresh.take(8).forEach { notify(it) }
                // Remember what is unread now, so each new message notifies once.
                seen.retainAll(unread.map { "${it.id}:${it.lastMessageId}" }.toSet())
                seen.addAll(unread.map { "${it.id}:${it.lastMessageId}" })
                prefs.edit().putStringSet(key, seen).apply()
            }
            Result.success()
        } catch (e: SignInRequired) {
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun notify(t: ThreadSummary) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val id = notificationId(t.account, t.id)
        val open = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ACCOUNT, t.account).putExtra(EXTRA_THREAD, t.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(kind: String, label: String) = NotificationCompat.Action(
            0, label,
            PendingIntent.getBroadcast(
                ctx, (id.toString() + kind).hashCode(),
                Intent(ctx, NotificationActionReceiver::class.java).setAction(kind)
                    .putExtra(EXTRA_ACCOUNT, t.account).putExtra(EXTRA_THREAD, t.id).putExtra(EXTRA_LAST_MESSAGE, t.lastMessageId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(t.fromName)
            .setContentText(t.subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${t.subject}\n${t.snippet}"))
            .setSubText(t.account)
            .setColor(0xFF2A2A2E.toInt())
            .setWhen(t.date.toEpochMilli())
            .setShowWhen(true)
            .setGroup(t.account)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(action(NotificationActionReceiver.DONE, "Done"))
            .addAction(action(NotificationActionReceiver.READ, "Mark read"))
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}
