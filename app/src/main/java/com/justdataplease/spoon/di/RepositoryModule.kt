package com.justdataplease.spoon.di

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.justdataplease.spoon.BuildConfig
import com.justdataplease.spoon.data.local.BundledRecipeCatalog
import com.justdataplease.spoon.data.local.LocalSpoonRepository
import com.justdataplease.spoon.data.local.RecipeCatalog
import com.justdataplease.spoon.data.remote.FirestoreSpoonRepository
import com.justdataplease.spoon.data.remote.NoBackupOwnerBootstrapStore
import com.justdataplease.spoon.data.remote.configurePersistentPersonalCache
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
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val recipeCatalog = BundledRecipeCatalog(context, json)
        if (!BuildConfig.HAS_FIREBASE_CONFIG) {
            return localRepository(context, json, recipeCatalog)
        }

        val firebaseApp = runCatching {
            FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
        }.getOrNull() ?: return localRepository(context, json, recipeCatalog)

        return runCatching {
            val firestore = configurePersistentPersonalCache(
                FirebaseFirestore.getInstance(firebaseApp),
            )
            FirestoreSpoonRepository(
                auth = FirebaseAuth.getInstance(firebaseApp),
                firestore = firestore,
                recipeCatalog = recipeCatalog,
                ownerBootstrapStore = NoBackupOwnerBootstrapStore(context),
            )
        }.getOrElse { localRepository(context, json, recipeCatalog) }
    }

    private fun localRepository(
        context: Context,
        json: Json,
        recipeCatalog: RecipeCatalog,
    ): SpoonRepository = LocalSpoonRepository(
        preferences = context.getSharedPreferences(
            LocalSpoonRepository.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ),
        json = json,
        recipeCatalog = recipeCatalog,
    )
}
