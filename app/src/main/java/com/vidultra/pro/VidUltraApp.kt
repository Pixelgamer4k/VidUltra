package com.vidultra.pro

import android.app.Application

class VidUltraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: VidUltraApp
            private set
    }
}
