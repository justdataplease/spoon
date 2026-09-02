package com.justdataplease.spoon.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object CatalogFreshnessScheduler {
    const val UNIQUE_WORK_NAME = "spoon-quarterly-catalog-freshness"
    const val WORK_TAG = "spoon-catalog-freshness"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val status = CatalogFreshnessStore(appContext).read()
        val initialDelayMillis = status.delayUntilNextCheck(System.currentTimeMillis())
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<CatalogFreshnessWorker>(
            CatalogFreshnessPolicy.CHECK_INTERVAL_DAYS,
            TimeUnit.DAYS,
        )
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1L, TimeUnit.HOURS)
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
