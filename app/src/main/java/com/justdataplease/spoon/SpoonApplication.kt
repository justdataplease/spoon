package com.justdataplease.spoon

import android.app.Application
import com.justdataplease.spoon.sync.CatalogFreshnessScheduler
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SpoonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CatalogFreshnessScheduler.schedule(this)
    }
}
