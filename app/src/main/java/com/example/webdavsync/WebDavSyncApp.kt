package com.example.webdavsync

import android.app.Application
import com.example.webdavsync.di.AppContainer

class WebDavSyncApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
