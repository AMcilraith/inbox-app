package com.amcilraith.inboxhub

import android.app.Application
import androidx.work.Configuration
import com.amcilraith.inboxhub.data.Names
import com.amcilraith.inboxhub.work.MailCheckWorker

class InboxHubApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        Names.init(this)
        MailCheckWorker.createChannel(this)
        MailCheckWorker.schedule(this)
    }
}
