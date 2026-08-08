package com.angussoftware.fueldashboard

import android.app.Application
import android.content.Context

class FuelDashboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        context = applicationContext
    }

    companion object {
        lateinit var context: Context
            private set
    }
}
