package com.justdataplease.spoon.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.justdataplease.spoon.BuildConfig
import com.justdataplease.spoon.data.DemoRecipeCatalog
import com.justdataplease.spoon.firebaseAppOrNull
import java.util.concurrent.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Reads one server-owned metadata document. Website scraping and recipe-page downloads are never
 * performed in the APK; catalog ingestion remains a trusted backend/importer responsibility.
 */
class CatalogFreshnessWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val checkedAt = System.currentTimeMillis()
        val store = CatalogFreshnessStore(applicationContext)

        if (!BuildConfig.HAS_FIREBASE_CONFIG) {
            val saved = store.recordSuccess(
                checkedAt = checkedAt,
                catalogUpdatedAt = 0L,
                recipeCount = DemoRecipeCatalog.recipes.size.toLong(),
            )
            return if (saved) Result.success() else Result.retry()
        }

        return try {
            val app = requireNotNull(firebaseAppOrNull(applicationContext)) {
                "Firebase could not be initialized"
            }
            val auth = FirebaseAuth.getInstance(app)
            if (auth.currentUser == null) {
                requireNotNull(auth.signInAnonymously().await().user) {
                    "Anonymous Firebase sign-in returned no user"
                }
            }

            val statusDocument = FirebaseFirestore.getInstance(app)
                .collection(CATALOG_COLLECTION)
                .document(STATUS_DOCUMENT)
                .get(Source.SERVER)
                .await()
            when (val validation = statusDocument.validateCatalogCheckpoint()) {
                is CatalogCheckpointValidation.Valid -> {
                    val checkpoint = validation.checkpoint
                    val saved = store.recordSuccess(
                        checkedAt = checkedAt,
                        catalogUpdatedAt = checkpoint.catalogUpdatedAt,
                        recipeCount = checkpoint.recipeCount,
                        catalogVersion = checkpoint.catalogVersion,
                        catalogHash = checkpoint.catalogHash,
                    )
                    if (saved) Result.success() else Result.retry()
                }
                CatalogCheckpointValidation.Missing -> failedCheck(
                    store = store,
                    attemptedAt = checkedAt,
                    failure = CatalogCheckFailure.STATUS_MISSING,
                    retry = true,
                    message = "Catalog status document is missing; WorkManager will retry",
                )
                is CatalogCheckpointValidation.Malformed -> failedCheck(
                    store = store,
                    attemptedAt = checkedAt,
                    failure = CatalogCheckFailure.STATUS_MALFORMED,
                    retry = false,
                    message = "Catalog status document is malformed: ${validation.reason}",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val transient = error.isTransientCatalogFailure()
            failedCheck(
                store = store,
                attemptedAt = checkedAt,
                failure = if (transient) {
                    CatalogCheckFailure.TRANSIENT_ERROR
                } else {
                    CatalogCheckFailure.PERMANENT_ERROR
                },
                retry = transient,
                message = if (transient) {
                    "Transient catalog status check failure; WorkManager will retry"
                } else {
                    "Catalog status check failed permanently"
                },
                error = error,
            )
        }
    }

    private fun DocumentSnapshot.validateCatalogCheckpoint(): CatalogCheckpointValidation {
        val fields = data.orEmpty()
        return CatalogFreshnessPolicy.validateCheckpoint(
            exists = exists(),
            language = fields[FIELD_LANGUAGE] as? String,
            recipeCount = fields[FIELD_RECIPE_COUNT] as? Long,
            lastImportedAt = (fields[FIELD_LAST_IMPORTED_AT] as? Timestamp)?.toDate()?.time,
            catalogVersion = fields[FIELD_CATALOG_VERSION] as? String,
            catalogHash = fields[FIELD_CATALOG_HASH] as? String,
        )
    }

    private fun failedCheck(
        store: CatalogFreshnessStore,
        attemptedAt: Long,
        failure: CatalogCheckFailure,
        retry: Boolean,
        message: String,
        error: Throwable? = null,
    ): Result {
        val persisted = runCatching {
            store.recordFailure(attemptedAt = attemptedAt, failure = failure)
        }.getOrElse { storeError ->
            Log.e(TAG, "Could not persist catalog check failure state", storeError)
            false
        }
        if (!persisted) {
            Log.e(TAG, "Could not persist catalog check failure state")
            return Result.retry()
        }
        if (retry) {
            if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
            return Result.retry()
        }
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
        return Result.failure()
    }

    private fun Throwable.isTransientCatalogFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            when (current) {
                is FirebaseNetworkException -> return true
                is FirebaseFirestoreException -> return CatalogFreshnessPolicy
                    .shouldRetryFirestoreCode(current.code.name)
            }
            current = current.cause
        }
        return false
    }

    companion object {
        private const val TAG = "SpoonCatalogWorker"
        const val CATALOG_COLLECTION = "spoon_catalog"
        const val STATUS_DOCUMENT = "status"
        const val FIELD_LANGUAGE = "language"
        const val FIELD_LAST_IMPORTED_AT = "lastImportedAt"
        const val FIELD_RECIPE_COUNT = "recipeCount"
        const val FIELD_CATALOG_VERSION = "catalogVersion"
        const val FIELD_CATALOG_HASH = "catalogHash"
    }
}
