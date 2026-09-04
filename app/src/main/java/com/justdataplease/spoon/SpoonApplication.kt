package com.justdataplease.spoon

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.justdataplease.spoon.data.remote.configurePersistentPersonalCache
import com.justdataplease.spoon.sync.CatalogFreshnessScheduler
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SpoonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.HAS_FIREBASE_CONFIG) {
            runCatching {
                val firebaseApp = FirebaseApp.getApps(this).firstOrNull()
                    ?: requireNotNull(FirebaseApp.initializeApp(this))
                configurePersistentPersonalCache(FirebaseFirestore.getInstance(firebaseApp))
            }.onFailure { error ->
                Log.e("SpoonApplication", "Could not configure the offline personal cache", error)
            }
        }
        CatalogFreshnessScheduler.schedule(this)
    }
}
