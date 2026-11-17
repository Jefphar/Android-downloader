package com.fastdownloader

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Downloads.init(this)
        Notifier.createChannels(this)
    }
}
