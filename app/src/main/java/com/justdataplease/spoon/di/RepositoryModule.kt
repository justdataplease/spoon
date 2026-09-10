package com.justdataplease.spoon.di

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.justdataplease.spoon.BuildConfig
import com.justdataplease.spoon.data.local.BundledRecipeCatalog
import com.justdataplease.spoon.data.local.LocalSpoonRepository
import com.justdataplease.spoon.data.local.RecipeCatalog
import com.justdataplease.spoon.data.remote.FirestoreSpoonRepository
import com.justdataplease.spoon.data.remote.NoBackupMealPlanOutbox
import com.justdataplease.spoon.data.remote.NoBackupOwnerBootstrapStore
import com.justdataplease.spoon.data.remote.configurePersistentPersonalCache
import com.justdataplease.spoon.data.preferences.MealPreferenceSettingsStore
import com.justdataplease.spoon.domain.repository.SpoonRepository
import com.justdataplease.spoon.firebaseAppOrNull
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.first

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides
    @Singleton
    fun provideSpoonRepository(
        @ApplicationContext context: Context,
        preferenceStore: MealPreferenceSettingsStore,
    ): SpoonRepository {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val recipeCatalog = BundledRecipeCatalog(context, json)
        val personalStore = com.justdataplease.spoon.data.local.SqlitePersonalDataStore(context)
        val firebaseApp = if (BuildConfig.HAS_FIREBASE_CONFIG)
            runCatching { firebaseAppOrNull(context) }.getOrNull() else null
        val firebaseAuth = firebaseApp?.let { runCatching { FirebaseAuth.getInstance(it) }.getOrNull() }
        val firestore = firebaseApp?.let { runCatching {
            configurePersistentPersonalCache(FirebaseFirestore.getInstance(it))
        }.getOrNull() }
        val legacy = localRepository(context, json, recipeCatalog, preferenceStore)
        return FirestoreSpoonRepository(
            auth = firebaseAuth,
            firestore = firestore,
            recipeCatalog = recipeCatalog,
            mealPlanOutbox = NoBackupMealPlanOutbox(context, json),
            ownerBootstrapStore = NoBackupOwnerBootstrapStore(context),
            preferenceStore = preferenceStore,
            personalDataStore = personalStore,
            accountTransferStore = com.justdataplease.spoon.data.local.NoBackupAccountTransferStore(context),
            legacyLocalSnapshot = {
                com.justdataplease.spoon.data.local.PersonalDataSnapshot(
                    mealPlans = legacy.mealPlans.first(),
                    favorites = legacy.favoriteRecipeIds.first().map { id ->
                        com.justdataplease.spoon.data.model.FavoriteRecipe(id, id, 1L)
                    },
                    shoppingItems = legacy.shoppingItems.first(),
                    recipeNotes = legacy.recipeNotes.first(),
                    customRecipes = legacy.customRecipes.first(),
                    cookedHistory = legacy.cookedHistory.first(),
                )
            },
        )
    }

    private fun localRepository(
        context: Context,
        json: Json,
        recipeCatalog: RecipeCatalog,
        preferenceStore: MealPreferenceSettingsStore,
    ): SpoonRepository = LocalSpoonRepository(
        preferences = context.getSharedPreferences(
            LocalSpoonRepository.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ),
        json = json,
        recipeCatalog = recipeCatalog,
        preferenceStore = preferenceStore,
    )
}
