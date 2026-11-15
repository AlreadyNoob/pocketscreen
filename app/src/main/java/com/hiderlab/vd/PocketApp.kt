package com.hiderlab.vd

import android.app.Application

class PocketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Debug.init(this)
        Apps.load(this)
    }
}
