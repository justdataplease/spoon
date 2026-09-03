package com.justdataplease.spoon.di

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.justdataplease.spoon.BuildConfig
import com.justdataplease.spoon.data.local.LocalSpoonRepository
import com.justdataplease.spoon.data.remote.FirestoreSpoonRepository
import com.justdataplease.spoon.data.UnavailableSpoonRepository
import com.justdataplease.spoon.domain.repository.BackendFailure
import com.justdataplease.spoon.domain.repository.BackendFailureKind
import com.justdataplease.spoon.domain.repository.SpoonRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides
    @Singleton
    fun provideSpoonRepository(
        @ApplicationContext context: Context,
    ): SpoonRepository {
        if (!BuildConfig.HAS_FIREBASE_CONFIG) {
            return LocalSpoonRepository(
                preferences = context.getSharedPreferences(
                    LocalSpoonRepository.PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ),
                json = Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                },
            )
        }

        val failure = BackendFailure(
            BackendFailureKind.CONFIGURATION,
            false,
            "Firebase initialization failed",
        )
        val firebaseApp = runCatching {
            FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
        }.getOrNull() ?: return UnavailableSpoonRepository(failure)

        return runCatching {
            FirestoreSpoonRepository(
                auth = FirebaseAuth.getInstance(firebaseApp),
                firestore = FirebaseFirestore.getInstance(firebaseApp),
            )
        }.getOrElse { UnavailableSpoonRepository(failure) }
    }
}
