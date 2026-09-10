package com.justdataplease.spoon

import android.app.Application
import android.content.Context
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
                val firebaseApp = requireNotNull(firebaseAppOrNull(this))
                configurePersistentPersonalCache(FirebaseFirestore.getInstance(firebaseApp))
            }.onFailure { error ->
                Log.e("SpoonApplication", "Could not configure the offline personal cache", error)
            }
        }
        CatalogFreshnessScheduler.schedule(this)
        com.justdataplease.spoon.widget.widgetCoordinator(this).startIfNeeded()
    }
}

/** Reuses the default app when one exists; initialization yields null without a config. */
internal fun firebaseAppOrNull(context: Context): FirebaseApp? =
    FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
