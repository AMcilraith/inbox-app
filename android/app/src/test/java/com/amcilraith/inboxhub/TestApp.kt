package com.amcilraith.inboxhub

import android.app.Application
import com.amcilraith.inboxhub.data.Names

/** The real app without WorkManager and notification setup, for JVM tests. */
class TestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Names.init(this)
    }
}
