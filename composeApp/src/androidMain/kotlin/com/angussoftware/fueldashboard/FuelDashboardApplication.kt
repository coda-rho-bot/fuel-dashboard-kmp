package com.angussoftware.fueldashboard

import android.app.Application
import android.content.Context

class FuelDashboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        com.angussoftware.fueldashboard.status.initStatusSurfaces(applicationContext)
        // Restore the persistent notification if the user had it enabled and
        // the process restarted (foreground services restart with the app).
        if (com.angussoftware.fueldashboard.FuelStatusService.isEnabled()) {
            com.angussoftware.fueldashboard.FuelStatusService.start(applicationContext)
        }
    }

    companion object {
        lateinit var context: Context
            private set
    }
}
