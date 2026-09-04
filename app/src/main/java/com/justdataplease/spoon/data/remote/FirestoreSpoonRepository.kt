package com.justdataplease.spoon.data.remote

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.justdataplease.spoon.data.local.RecipeCatalog
import com.justdataplease.spoon.data.local.boundedReferencedRecipeIds
import com.justdataplease.spoon.data.local.facetOptionsIncludingCustom
import com.justdataplease.spoon.data.local.getRecipeDetailsIncludingCustom
import com.justdataplease.spoon.data.local.getRecipesByIdsIncludingCustom
import com.justdataplease.spoon.data.local.mergeCatalogRecipes
import com.justdataplease.spoon.data.local.queryIncludingCustomRecipes
import com.justdataplease.spoon.data.local.selectIncludingCustomRecipes
import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.FavoriteRecipe
import com.justdataplease.spoon.data.model.MAX_SHOPPING_ITEMS_PER_WRITE
import com.justdataplease.spoon.data.model.MealPreferenceDocument
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeFilters
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import com.justdataplease.spoon.data.preferences.MealPreferenceSettings
import com.justdataplease.spoon.data.preferences.MealPreferenceSettingsStore
import com.justdataplease.spoon.data.model.isCustomRecipeId
import com.justdataplease.spoon.data.model.mergeCookedHistory
import com.justdataplease.spoon.data.model.newCookedMealEventId
import com.justdataplease.spoon.data.model.requireValid
import com.justdataplease.spoon.data.requireSafeRecipeDocumentId
import com.justdataplease.spoon.domain.repository.BackendFailure
import com.justdataplease.spoon.domain.repository.BackendFailureKind
import com.justdataplease.spoon.domain.repository.BackendState
import com.justdataplease.spoon.domain.repository.BackendUnavailableException
import com.justdataplease.spoon.domain.repository.AccountOperationException
import com.justdataplease.spoon.domain.repository.AccountState
import com.justdataplease.spoon.domain.repository.CatalogFacetOptions
import com.justdataplease.spoon.domain.repository.RecipePage
import com.justdataplease.spoon.domain.repository.SpoonRepository
import com.justdataplease.spoon.domain.repository.accountFailureForFirebaseCode
import com.justdataplease.spoon.domain.ExploreCriteria
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Offline-first personal-data repository; the public recipe catalog is bundled in SQLite. */
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreSpoonRepository internal constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val recipeCatalog: RecipeCatalog,
    private val ownerBootstrapStore: OwnerBootstrapStore,
    private val preferenceStore: MealPreferenceSettingsStore? = null,
) : SpoonRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authMutex = Mutex()
    private val personalMutationMutex = Mutex()
    private val authJobLock = Any()
    private val ownerSnapshotLock = Any()
    private val uid = MutableStateFlow(auth.currentUser?.uid)
    private val _accountState = MutableStateFlow(auth.currentUser.toAccountState())
    private val authHealth = MutableStateFlow<CloudComponentState>(
        if (auth.currentUser == null) CloudComponentState.Pending else CloudComponentState.Ready,
    )
    private val recipesHealth = MutableStateFlow<CloudComponentState>(CloudComponentState.Pending)
    private val mealPlansHealth = MutableStateFlow<CloudComponentState>(CloudComponentState.Pending)
    private val favoritesHealth = MutableStateFlow<CloudComponentState>(CloudComponentState.Pending)
    private val shoppingHealth = MutableStateFlow<CloudComponentState>(CloudComponentState.Pending)
    private val notesHealth = MutableStateFlow<CloudComponentState>(CloudComponentState.Pending)
    private val customRecipesHealth = MutableStateFlow<CloudComponentState>(CloudComponentState.Pending)
    private val cookedHistoryHealth = MutableStateFlow<CloudComponentState>(CloudComponentState.Pending)
    private val preferencesHealth = MutableStateFlow<CloudComponentState>(CloudComponentState.Pending)
    private val ownerHealth = listOf(
        mealPlansHealth,
        favoritesHealth,
        shoppingHealth,
        notesHealth,
        customRecipesHealth,
        cookedHistoryHealth,
        preferencesHealth,
    )
    private val _referencedCatalogRecipes = MutableStateFlow<List<Recipe>>(emptyList())
    private val _mealPlans = MutableStateFlow<List<DayMealPlan>>(emptyList())
    private val _favoriteRecipeIds = MutableStateFlow<Set<String>>(emptySet())
    private val _shoppingItems = MutableStateFlow<List<ShoppingListItem>>(emptyList())
    private val _recipeNotes = MutableStateFlow<List<RecipeNote>>(emptyList())
    private val _customRecipes = MutableStateFlow<List<CustomRecipe>>(emptyList())
    private val _cookedHistory = MutableStateFlow<List<CookedMeal>>(emptyList())
    private val _mealPreferenceSettings = MutableStateFlow(MealPreferenceSettings())
    private val serverBackedOwnerComponents = MutableStateFlow<Set<String>>(emptySet())
    private val establishedCachedPersonalData = MutableStateFlow(false)
    private val ownerBootstrapComplete = MutableStateFlow(
        auth.currentUser?.uid?.let(ownerBootstrapStore::isComplete) == true,
    )

    @Volatile
    private var authJob: Job? = null
    @Volatile
    private var authRefreshJob: Job? = null

    override val backendState = combine(listOf(authHealth, recipesHealth) + ownerHealth) { states ->
        aggregateCloudState(states.toList())
    }.stateIn(scope, SharingStarted.Eagerly, BackendState.Connecting)

    override val accountState = _accountState.asStateFlow()
    override val recipes = combine(
        recipeCatalog.cachedRecipes,
        _referencedCatalogRecipes,
        _customRecipes,
        ::mergeCatalogRecipes,
    ).stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val mealPlans = _mealPlans.asStateFlow()
    override val favoriteRecipeIds = _favoriteRecipeIds.asStateFlow()
    override val shoppingItems = _shoppingItems.asStateFlow()
    override val recipeNotes = _recipeNotes.asStateFlow()
    override val customRecipes = _customRecipes.asStateFlow()
    override val cookedHistory = combine(_mealPlans, _cookedHistory, ::mergeCookedHistory)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val mealPreferenceSettings = _mealPreferenceSettings.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        publishAuthenticatedUser(user)
        if (user == null) {
            startAuthentication()
        }
    }

    init {
        startLocalCatalog()
        observeUserQueries()
        observeMealPreferenceQuery()
        auth.addAuthStateListener(authListener)
        startAuthentication()
    }

    private fun startLocalCatalog() {
        scope.launch {
            try {
                recipeCatalog.ensureReady()
                recipesHealth.value = CloudComponentState.Ready
                combine(
                    _mealPlans,
                    _favoriteRecipeIds,
                    _cookedHistory,
                    ::boundedReferencedRecipeIds,
                ).distinctUntilChanged().collect { referencedIds ->
                    _referencedCatalogRecipes.value = recipeCatalog.getRecipesByIds(referencedIds)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                recipesHealth.value = CloudComponentState.Failed(
                    BackendFailure(
                        BackendFailureKind.CONFIGURATION,
                        false,
                        "Bundled recipe catalog is unavailable",
                    ),
                )
            }
        }
    }

    private fun observeUserQueries() {
        observeQuery(
            MEAL_PLANS_COMPONENT,
            mealPlansHealth,
            DayMealPlan::class.java,
            { currentUid -> mealPlans(currentUid).orderBy(DATE_FIELD, Query.Direction.ASCENDING) },
        ) { _mealPlans.value = it }
        observeQuery(
            FAVORITES_COMPONENT,
            favoritesHealth,
            FavoriteRecipe::class.java,
            { currentUid -> favorites(currentUid) },
        ) { remote ->
            _favoriteRecipeIds.value = remote.mapNotNull { favorite ->
                favorite.recipeId.ifBlank { favorite.id }.takeIf(String::isNotBlank)
            }.toSet()
        }
        observeQuery(
            SHOPPING_COMPONENT,
            shoppingHealth,
            ShoppingListItem::class.java,
            { currentUid ->
                shoppingItems(currentUid).orderBy(CREATED_AT_FIELD, Query.Direction.ASCENDING)
            },
        ) { _shoppingItems.value = it }
        observeQuery(
            NOTES_COMPONENT,
            notesHealth,
            RecipeNote::class.java,
            { currentUid -> recipeNotes(currentUid) },
        ) { _recipeNotes.value = it.sortedBy(RecipeNote::recipeId) }
        observeQuery(
            CUSTOM_RECIPES_COMPONENT,
            customRecipesHealth,
            CustomRecipe::class.java,
            { currentUid ->
                customRecipeDocuments(currentUid)
                    .orderBy(UPDATED_AT_FIELD, Query.Direction.DESCENDING)
            },
        ) { _customRecipes.value = it }
        observeQuery(
            COOKED_HISTORY_COMPONENT,
            cookedHistoryHealth,
            CookedMeal::class.java,
            { currentUid ->
                cookedHistoryDocuments(currentUid)
                    .orderBy(COMPLETED_AT_FIELD, Query.Direction.DESCENDING)
            },
        ) { _cookedHistory.value = it }
    }

    /**
     * Loads the account-scoped DataStore mirror before subscribing to Firestore. A server
     * document wins on equal/newer revisions; a newer offline local edit is queued back only
     * after a server-backed snapshot proves what is currently online.
     */
    private fun observeMealPreferenceQuery() {
        scope.launch {
            uid.flatMapLatest { currentUid ->
                preferencesHealth.value = CloudComponentState.Pending
                if (currentUid == null) {
                    flowOf<Pair<String?, ObservedObjects<MealPreferenceDocument>?>>(null to null)
                } else {
                    flow {
                        val cached = runCatching {
                            preferenceStore?.readForOwner(currentUid)
                        }.getOrNull() ?: MealPreferenceSettings()
                        synchronized(ownerSnapshotLock) {
                            if (
                                uid.value == currentUid &&
                                cached.updatedAtEpochMillis >=
                                _mealPreferenceSettings.value.updatedAtEpochMillis
                            ) {
                                _mealPreferenceSettings.value = cached
                            }
                        }
                        emitAll(
                            resilientObjectsFlow(
                                ownerUid = currentUid,
                                health = preferencesHealth,
                                type = MealPreferenceDocument::class.java,
                                query = mealPreferenceDocuments(currentUid)
                                    .whereEqualTo(
                                        FieldPath.documentId(),
                                        MEAL_PREFERENCE_DOCUMENT_ID,
                                    ),
                                includeMetadataChanges = true,
                            ).map { snapshot -> currentUid to snapshot },
                        )
                    }
                }
            }.collect { (sourceUid, snapshot) ->
                var remoteToPersist: MealPreferenceSettings? = null
                var localToBackup: MealPreferenceSettings? = null
                synchronized(ownerSnapshotLock) {
                    if (uid.value == sourceUid && sourceUid != null && snapshot != null) {
                        val remote = snapshot.values.singleOrNull()?.toSettingsOrNull()
                        val local = _mealPreferenceSettings.value
                        val awaitingServer =
                            snapshot.fromCache || snapshot.hasPendingWrites
                        when {
                            remote != null &&
                                remote.updatedAtEpochMillis >= local.updatedAtEpochMillis -> {
                                _mealPreferenceSettings.value = remote
                                remoteToPersist = remote
                            }
                            !awaitingServer &&
                                remote == null &&
                                local.updatedAtEpochMillis == 0L &&
                                local.hasActiveSelections() -> {
                                val migrated = local.normalizedForSync(
                                    nextPreferenceTimestamp(
                                        now = System.currentTimeMillis(),
                                        current = 0L,
                                    ),
                                )
                                _mealPreferenceSettings.value = migrated
                                remoteToPersist = migrated
                                localToBackup = migrated
                            }
                            !awaitingServer &&
                                local.updatedAtEpochMillis > 0L &&
                                (remote == null ||
                                    local.updatedAtEpochMillis > remote.updatedAtEpochMillis) -> {
                                localToBackup = local
                            }
                        }
                        preferencesHealth.value = CloudComponentState.Ready
                        recordOwnerSnapshot(
                            sourceUid = sourceUid,
                            component = PREFERENCES_COMPONENT,
                            fromCache = awaitingServer,
                            hasValues = remote != null,
                        )
                    }
                }
                if (sourceUid != null) {
                    remoteToPersist?.let { remote ->
                        runCatching { preferenceStore?.replaceForOwner(sourceUid, remote) }
                            .onFailure { Log.w(FIRESTORE_TAG, "Could not cache meal preferences", it) }
                    }
                    localToBackup?.let { local ->
                        runCatching {
                            withPersonalMutation(sourceUid) {
                                val task = mealPreferenceDocuments(sourceUid)
                                    .document(MEAL_PREFERENCE_DOCUMENT_ID)
                                    .set(local.toFirestoreDocument())
                                trackQueuedWrite(
                                    sourceUid,
                                    "meal preferences",
                                    preferencesHealth,
                                    task,
                                )
                            }
                        }.onFailure {
                            Log.w(FIRESTORE_TAG, "Could not queue cached meal preferences", it)
                        }
                    }
                }
            }
        }
    }

    override suspend fun ensureReady() {
        recipeCatalog.ensureReady()
        val currentUid = awaitAuthenticatedUid()
        awaitInitialOwnerCache(currentUid)
    }

    override suspend fun getRecipeDetails(recipeId: String): Recipe? {
        val safeRecipeId = requireSafeRecipeDocumentId(recipeId)
        // Custom recipes live in the owner cache, which must be warm before it is consulted.
        if (safeRecipeId.isCustomRecipeId()) ensureReady()
        return recipeCatalog.getRecipeDetailsIncludingCustom(safeRecipeId, _customRecipes.value)
    }

    override suspend fun getRecipesByIds(recipeIds: Set<String>): List<Recipe> =
        recipeCatalog.getRecipesByIdsIncludingCustom(recipeIds, _customRecipes.value)

    override suspend fun queryRecipes(
        criteria: ExploreCriteria,
        limit: Int,
        offset: Int,
        preferences: MealPreferenceSettings,
    ): RecipePage = recipeCatalog.queryIncludingCustomRecipes(
        _customRecipes.value,
        criteria,
        limit,
        offset,
        preferences,
    )

    override suspend fun getCatalogFacetOptions(): CatalogFacetOptions =
        recipeCatalog.facetOptionsIncludingCustom(_customRecipes.value)

    override suspend fun selectRandomRecipe(
        filters: RecipeFilters,
        excludingRecipeId: String?,
        randomSeed: Long,
        preferences: MealPreferenceSettings,
    ): Recipe? = selectIncludingCustomRecipes(
        recipeCatalog = recipeCatalog,
        customRecipes = _customRecipes.value,
        filters = filters,
        excludingRecipeId = excludingRecipeId,
        randomSeed = randomSeed,
        preferences = preferences,
    )

    override suspend fun updateMealPreferenceSettings(settings: MealPreferenceSettings) {
        val currentUid = awaitUid()
        val stored = withPersonalMutation(currentUid) {
            val next = settings.normalizedForSync(
                nextPreferenceTimestamp(
                    now = System.currentTimeMillis(),
                    current = _mealPreferenceSettings.value.updatedAtEpochMillis,
                ),
            )
            _mealPreferenceSettings.value = next
            val task = mealPreferenceDocuments(currentUid)
                .document(MEAL_PREFERENCE_DOCUMENT_ID)
                .set(next.toFirestoreDocument())
            trackQueuedWrite(currentUid, "meal preferences", preferencesHealth, task)
            next
        }
        runCatching { preferenceStore?.replaceForOwner(currentUid, stored) }
            .onFailure { Log.w(FIRESTORE_TAG, "Could not persist meal preferences locally", it) }
    }

    override suspend fun upsertMealPlan(plan: DayMealPlan) {
        require(plan.date.isNotBlank())
        val currentUid = awaitUid()
        withPersonalMutation(currentUid) {
            val stored = plan.copy(id = plan.date)
            val task = mealPlans(currentUid).document(plan.date).set(stored.toFirestoreDocument())
            _mealPlans.value = (_mealPlans.value.filterNot { it.date == stored.date } + stored)
                .sortedBy(DayMealPlan::date)
            trackQueuedWrite(currentUid, "meal plan " + plan.date, mealPlansHealth, task)
        }
    }

    override suspend fun setMealCompleted(date: String, completed: Boolean) {
        require(date.isNotBlank())
        val currentUid = awaitUid()
        withPersonalMutation(currentUid) {
            val projection = projectMealCompletion(
                plans = _mealPlans.value,
                storedHistory = _cookedHistory.value,
                date = date,
                completed = completed,
                nowEpochMillis = System.currentTimeMillis(),
                newCompletionEventId = newCookedMealEventId(),
            )
            val changedPlan = projection.changedPlan ?: return@withPersonalMutation
            val batch = firestore.batch()
            batch.set(
                mealPlans(currentUid).document(changedPlan.date),
                changedPlan.toFirestoreDocument(),
            )
            projection.historyToCreate?.let { history ->
                batch.set(
                    cookedHistoryDocuments(currentUid).document(history.id),
                    cookedMealDocument(
                        date = history.date,
                        recipeId = history.recipeId,
                        recipeTitle = history.recipeTitle,
                        completedAtEpochMillis = history.completedAtEpochMillis,
                    ),
                )
            }
            projection.historyIdsToDelete.forEach { historyId ->
                batch.delete(cookedHistoryDocuments(currentUid).document(historyId))
            }
            val task = batch.commit()
            _mealPlans.value = projection.plans
            _cookedHistory.value = projection.storedHistory
            trackQueuedWrite(currentUid, "meal completion " + date, cookedHistoryHealth, task)
        }
    }

    override suspend fun deleteCookedHistoryEntry(historyId: String) {
        val safeHistoryId = requireSafeRecipeDocumentId(historyId)
        val currentUid = awaitUid()
        withPersonalMutation(currentUid) {
            val projection = projectHistoryDeletion(
                plans = _mealPlans.value,
                storedHistory = _cookedHistory.value,
                historyId = safeHistoryId,
                nowEpochMillis = System.currentTimeMillis(),
            )
            if (projection.changedPlan == null && projection.historyIdToDelete == null) {
                return@withPersonalMutation
            }
            val batch = firestore.batch()
            projection.changedPlan?.let { changedPlan ->
                batch.set(
                    mealPlans(currentUid).document(changedPlan.date),
                    changedPlan.toFirestoreDocument(),
                )
            }
            projection.historyIdToDelete?.let { id ->
                batch.delete(cookedHistoryDocuments(currentUid).document(id))
            }
            val task = batch.commit()
            _mealPlans.value = projection.plans
            _cookedHistory.value = projection.storedHistory
            trackQueuedWrite(
                currentUid,
                "history deletion " + safeHistoryId,
                cookedHistoryHealth,
                task,
            )
        }
    }

    override suspend fun toggleFavorite(recipeId: String): Boolean {
        requireSafeRecipeDocumentId(recipeId)
        val currentUid = awaitUid()
        return withPersonalMutation(currentUid) {
            val document = favorites(currentUid).document(recipeId)
            val updated = _favoriteRecipeIds.value.toMutableSet()
            val isFavorite = if (updated.remove(recipeId)) false else {
                updated.add(recipeId)
                true
            }
            val task = if (isFavorite) {
                document.set(
                    FavoriteRecipe(
                        recipeId = recipeId,
                        addedAtEpochMillis = System.currentTimeMillis(),
                    ).toFirestoreDocument(),
                )
            } else {
                document.delete()
            }
            _favoriteRecipeIds.value = updated.toSet()
            trackQueuedWrite(currentUid, "favorite " + recipeId, favoritesHealth, task)
            isFavorite
        }
    }

    override suspend fun upsertShoppingItems(items: List<ShoppingListItem>) {
        if (items.isEmpty()) return
        require(items.size <= MAX_SHOPPING_ITEMS_PER_WRITE)
        items.forEach(ShoppingListItem::requireValid)
        val currentUid = awaitUid()
        withPersonalMutation(currentUid) {
            val batch = firestore.batch()
            items.forEach { item ->
                batch.set(
                    shoppingItems(currentUid).document(item.id),
                    item.toFirestoreDocument(),
                )
            }
            val task = batch.commit()
            val incomingIds = items.mapTo(mutableSetOf(), ShoppingListItem::id)
            _shoppingItems.value = (_shoppingItems.value.filterNot { it.id in incomingIds } + items)
                .sortedBy(ShoppingListItem::createdAtEpochMillis)
            trackQueuedWrite(currentUid, "shopping items", shoppingHealth, task)
        }
    }

    override suspend fun setShoppingItemChecked(itemId: String, checked: Boolean) {
        val safeItemId = requireSafeRecipeDocumentId(itemId)
        val currentUid = awaitUid()
        withPersonalMutation(currentUid) {
            val current = checkNotNull(_shoppingItems.value.firstOrNull { it.id == safeItemId })
            val changed = current.copy(
                checked = checked,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
            val task = shoppingItems(currentUid).document(safeItemId)
                .set(changed.toFirestoreDocument())
            _shoppingItems.value = _shoppingItems.value.map { item ->
                if (item.id == safeItemId) changed else item
            }
            trackQueuedWrite(currentUid, "shopping item " + safeItemId, shoppingHealth, task)
        }
    }

    override suspend fun deleteShoppingItem(itemId: String) {
        val safeItemId = requireSafeRecipeDocumentId(itemId)
        val currentUid = awaitUid()
        withPersonalMutation(currentUid) {
            val task = shoppingItems(currentUid).document(safeItemId).delete()
            _shoppingItems.value = _shoppingItems.value.filterNot { it.id == safeItemId }
            trackQueuedWrite(currentUid, "shopping deletion " + safeItemId, shoppingHealth, task)
        }
    }

    override suspend fun clearCheckedShoppingItems() {
        val currentUid = awaitUid()
        withPersonalMutation(currentUid) {
            val checkedIds = _shoppingItems.value.asSequence()
                .filter(ShoppingListItem::checked)
                .map(ShoppingListItem::id)
                .toList()
            checkedIds.chunked(FIRESTORE_BATCH_LIMIT).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { id -> batch.delete(shoppingItems(currentUid).document(id)) }
                trackQueuedWrite(
                    currentUid,
                    "checked shopping items",
                    shoppingHealth,
                    batch.commit(),
                )
            }
            _shoppingItems.value = _shoppingItems.value.filterNot(ShoppingListItem::checked)
        }
    }

    override suspend fun upsertRecipeNote(note: RecipeNote) {
        val recipeId = requireSafeRecipeDocumentId(note.recipeId.ifBlank { note.id })
        require(note.id.isBlank() || note.id == recipeId)
        val currentUid = awaitUid()
        withPersonalMutation(currentUid) {
            val document = recipeNotes(currentUid).document(recipeId)
            val stored = note.copy(id = recipeId, recipeId = recipeId)
            val task = if (note.text.isBlank()) {
                document.delete()
            } else {
                document.set(stored.requireValid().toFirestoreDocument())
            }
            _recipeNotes.value = if (note.text.isBlank()) {
                _recipeNotes.value.filterNot { it.recipeId == recipeId }
            } else {
                (_recipeNotes.value.filterNot { it.recipeId == recipeId } + stored)
                    .sortedBy(RecipeNote::recipeId)
            }
            trackQueuedWrite(currentUid, "recipe note " + recipeId, notesHealth, task)
        }
    }

    override suspend fun upsertCustomRecipe(recipe: CustomRecipe) {
        recipe.requireValid()
        val currentUid = awaitUid()
        withPersonalMutation(currentUid) {
            val task = customRecipeDocuments(currentUid).document(recipe.id)
                .set(recipe.toFirestoreDocument())
            _customRecipes.value = (_customRecipes.value.filterNot { it.id == recipe.id } + recipe)
                .sortedByDescending(CustomRecipe::updatedAtEpochMillis)
            trackQueuedWrite(
                currentUid,
                "custom recipe " + recipe.id,
                customRecipesHealth,
                task,
            )
        }
    }

    override suspend fun deleteCustomRecipe(recipeId: String) {
        require(recipeId.isCustomRecipeId())
        val currentUid = awaitUid()
        withPersonalMutation(currentUid) {
            val batch = firestore.batch()
            batch.delete(customRecipeDocuments(currentUid).document(recipeId))
            batch.delete(recipeNotes(currentUid).document(recipeId))
            batch.delete(favorites(currentUid).document(recipeId))
            val task = batch.commit()
            _customRecipes.value = _customRecipes.value.filterNot { it.id == recipeId }
            _recipeNotes.value = _recipeNotes.value.filterNot { it.recipeId == recipeId }
            _favoriteRecipeIds.value = _favoriteRecipeIds.value - recipeId
            trackQueuedWrite(
                currentUid,
                "custom recipe deletion " + recipeId,
                customRecipesHealth,
                task,
            )
        }
    }

    override suspend fun registerEmailAccount(email: String, password: String) {
        val normalizedEmail = requireAccountEmail(email)
        requireAccountPassword(password)
        runAccountOperation {
            val currentUser = auth.currentUser ?: authenticateOnce()
            if (!currentUser.isAnonymous) {
                throw AccountOperationException(
                    accountFailureForFirebaseCode("ERROR_EMAIL_ALREADY_IN_USE"),
                )
            }
            val credential = EmailAuthProvider.getCredential(normalizedEmail, password)
            val linkedUser = checkNotNull(currentUser.linkWithCredential(credential).await().user)
            publishAuthenticatedUser(linkedUser)
        }
    }

    override suspend fun signInWithEmail(email: String, password: String) {
        val normalizedEmail = requireAccountEmail(email)
        requireAccountPassword(password)
        runAccountOperation {
            val signedInUser = checkNotNull(
                auth.signInWithEmailAndPassword(normalizedEmail, password).await().user,
            )
            publishAuthenticatedUser(signedInUser)
        }
    }

    override suspend fun sendPasswordReset(email: String) {
        val normalizedEmail = requireAccountEmail(email)
        runAccountOperation {
            auth.sendPasswordResetEmail(normalizedEmail).await()
        }
    }

    override suspend fun signOutToAnonymous() {
        auth.signOut()
        publishAuthenticatedUser(null)
        startAuthentication()
    }

    private fun startAuthentication() {
        auth.currentUser?.let { user ->
            publishAuthenticatedUser(user)
            refreshCachedUser(user)
            return
        }
        synchronized(authJobLock) {
            if (authJob?.isActive == true) return
            authJob = scope.launch { authenticateWithRetry() }
        }
    }

    /**
     * Firebase persists a cached user snapshot. Reload it on startup so an account linked by an
     * administrator on the same UID is recognized as email-backed without requiring sign-out.
     */
    private fun refreshCachedUser(user: FirebaseUser) {
        synchronized(authJobLock) {
            if (authRefreshJob?.isActive == true) return
            authRefreshJob = scope.launch {
                runCatching { user.reload().await() }
                    .onSuccess {
                        auth.currentUser
                            ?.takeIf { refreshed -> refreshed.uid == user.uid }
                            ?.let(::publishAuthenticatedUser)
                    }
            }
        }
    }

    private suspend fun authenticateWithRetry() {
        var attempt = 0L
        while (currentCoroutineContext().isActive && auth.currentUser == null) {
            authHealth.value = CloudComponentState.Pending
            val failure = tryAuthentication() ?: return
            authHealth.value = CloudComponentState.Failed(failure)
            if (failure.isRetryable == false) return
            delay(retryDelayMillis(attempt++))
        }
    }

    private suspend fun authenticateOnce() = withTimeout(AUTH_ATTEMPT_TIMEOUT_MILLIS) {
        authMutex.withLock {
            auth.currentUser ?: requireNotNull(auth.signInAnonymously().await().user)
        }
    }

    private suspend fun tryAuthentication(): BackendFailure? {
        return try {
            val user = authenticateOnce()
            publishAuthenticatedUser(user)
            null
        } catch (error: CancellationException) {
            if (error is TimeoutCancellationException) classifyFirebaseFailure(error) else throw error
        } catch (error: Exception) {
            classifyFirebaseFailure(error)
        }
    }

    /**
     * A listener's first emission is produced from Firestore's persistent local cache, including
     * pending writes. Established devices can initialize entirely from it. A new empty device
     * waits for one complete server bootstrap so it cannot overwrite an existing remote week.
     */
    private suspend fun awaitInitialOwnerCache(expectedUid: String) {
        val readiness = try {
            withTimeout(PERSONAL_CACHE_WARMUP_TIMEOUT_MILLIS) {
                combine(
                    combine(ownerHealth) { states -> states.toList() },
                    ownerBootstrapComplete,
                    establishedCachedPersonalData,
                ) { states, bootstrapComplete, establishedCache ->
                    Triple(states, bootstrapComplete, establishedCache)
                }.first { (states, bootstrapComplete, establishedCache) ->
                    personalCacheCanInitialize(
                        allLocalSnapshotsReady = states.all {
                            it is CloudComponentState.Ready
                        },
                        ownerBootstrapComplete = bootstrapComplete,
                        establishedCachedPersonalData = establishedCache,
                        ) ||
                        states.any { state ->
                            state is CloudComponentState.Failed &&
                                state.failure.isRetryable == false
                        }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw BackendUnavailableException(timeoutFailure())
        }
        if (uid.value != expectedUid) {
            awaitInitialOwnerCache(awaitAuthenticatedUid())
            return
        }
        val healthStates = readiness.first
        healthStates.firstNotNullOfOrNull { state ->
            (state as? CloudComponentState.Failed)
                ?.failure
                ?.takeIf { it.isRetryable == false }
        }?.let { failure -> throw BackendUnavailableException(failure) }
    }

    private fun recordOwnerSnapshot(
        sourceUid: String,
        component: String,
        fromCache: Boolean,
        hasValues: Boolean,
    ) {
        synchronized(ownerSnapshotLock) {
            if (uid.value != sourceUid) return
            if (fromCache) {
                if (hasValues) establishedCachedPersonalData.value = true
                return
            }
            val updated = serverBackedOwnerComponents.value + component
            serverBackedOwnerComponents.value = updated
            if (
                ownerBootstrapCanBeMarked(updated, ALL_OWNER_COMPONENTS) &&
                !ownerBootstrapComplete.value
            ) {
                val persisted = ownerBootstrapStore.markComplete(sourceUid)
                if (!persisted) {
                    Log.w(FIRESTORE_TAG, "Could not persist personal cache bootstrap marker")
                }
                // The current process is safe once all server snapshots arrived, even if the
                // no-backup marker store was temporarily unable to persist it.
                ownerBootstrapComplete.value = true
            }
        }
    }

    private fun <T : Any> observeQuery(
        component: String,
        health: MutableStateFlow<CloudComponentState>,
        type: Class<T>,
        queryFactory: (String) -> Query,
        publish: (List<T>) -> Unit,
    ) {
        scope.launch {
            uid.flatMapLatest { currentUid ->
                health.value = CloudComponentState.Pending
                if (currentUid == null) {
                    flowOf<Pair<String?, ObservedObjects<T>?>>(currentUid to null)
                } else {
                    resilientObjectsFlow(
                        ownerUid = currentUid,
                        health = health,
                        type = type,
                        query = queryFactory(currentUid),
                        includeMetadataChanges = true,
                    )
                        .map { snapshot -> currentUid to snapshot }
                }
            }.collect { (sourceUid, snapshot) ->
                // Cancellation and UID updates happen on different threads. An already-converting
                // snapshot from the previous account must never repopulate owner state after the
                // synchronous clear in publishAuthenticatedUser().
                synchronized(ownerSnapshotLock) {
                    if (uid.value == sourceUid && snapshot != null) {
                        val awaitingServer =
                            snapshot.fromCache || snapshot.hasPendingWrites
                        // Publish decoded values before declaring this component cache-ready. This
                        // ordering prevents ensureWeek from observing a ready flag with an old
                        // empty meal-plan flow. The shared lock also prevents an old account's
                        // in-flight conversion from publishing after an account switch.
                        publish(snapshot.values)
                        health.value = CloudComponentState.Ready
                        recordOwnerSnapshot(
                            sourceUid = requireNotNull(sourceUid),
                            component = component,
                            fromCache = awaitingServer,
                            hasValues = snapshot.hasValues,
                        )
                    }
                }
            }
        }
    }

    private fun <T : Any> resilientObjectsFlow(
        ownerUid: String,
        health: MutableStateFlow<CloudComponentState>,
        type: Class<T>,
        query: Query,
        includeMetadataChanges: Boolean = false,
    ): Flow<ObservedObjects<T>> = query.objectsFlow(type, includeMetadataChanges)
        .retryWhen { error, attempt ->
            retryQuery(ownerUid, health, type, error, attempt)
        }
        .catch { error ->
            if (error is CancellationException) throw error
            synchronized(ownerSnapshotLock) {
                if (uid.value == ownerUid) {
                    health.value = CloudComponentState.Failed(classifyFirebaseFailure(error))
                }
            }
        }

    private suspend fun retryQuery(
        ownerUid: String,
        health: MutableStateFlow<CloudComponentState>,
        type: Class<*>,
        error: Throwable,
        attempt: Long,
    ): Boolean {
        if (error is CancellationException) return false
        val failure = classifyFirebaseFailure(error)
        val belongsToCurrentOwner = synchronized(ownerSnapshotLock) {
            if (uid.value == ownerUid) {
                health.value = CloudComponentState.Failed(failure)
                true
            } else {
                false
            }
        }
        if (!belongsToCurrentOwner) return false
        return finishRetry(ownerUid, health, type, error, failure, attempt)
    }

    private suspend fun finishRetry(
        ownerUid: String,
        health: MutableStateFlow<CloudComponentState>,
        type: Class<*>,
        error: Throwable,
        failure: BackendFailure,
        attempt: Long,
    ): Boolean {
        Log.w(FIRESTORE_TAG, type.simpleName, error)
        if (failure.isRetryable == false) return false
        delay(retryDelayMillis(attempt))
        return synchronized(ownerSnapshotLock) {
            if (uid.value == ownerUid) {
                health.value = CloudComponentState.Pending
                true
            } else {
                false
            }
        }
    }

    private suspend fun awaitUid(): String {
        ensureReady()
        return requireNotNull(auth.currentUser?.uid ?: uid.value)
    }

    private suspend fun awaitAuthenticatedUid(): String {
        auth.currentUser?.let { user ->
            if (uid.value != user.uid) publishAuthenticatedUser(user)
            return user.uid
        }
        startAuthentication()
        val state = authHealth.first { candidate ->
            candidate is CloudComponentState.Ready || candidate is CloudComponentState.Failed
        }
        if (state is CloudComponentState.Failed) {
            throw BackendUnavailableException(state.failure)
        }
        return requireNotNull(auth.currentUser?.uid ?: uid.value)
    }

    private suspend fun <T> withPersonalMutation(
        ownerUid: String,
        mutation: () -> T,
    ): T = personalMutationMutex.withLock {
        synchronized(ownerSnapshotLock) {
            check(
                uid.value == ownerUid &&
                    auth.currentUser?.uid == ownerUid,
            ) { "The signed-in account changed before the personal change was queued" }
            mutation()
        }
    }

    /**
     * Firestore persists this mutation locally before syncing it in the background. Deliberately
     * do not await the returned task: that task completes only after server acknowledgement and
     * would otherwise leave every personal action spinning while the phone is offline.
     */
    private fun trackQueuedWrite(
        ownerUid: String,
        description: String,
        health: MutableStateFlow<CloudComponentState>,
        task: Task<*>,
    ) {
        task.addOnFailureListener { error ->
            Log.e(FIRESTORE_TAG, "Queued $description rejected during sync", error)
            synchronized(ownerSnapshotLock) {
                if (uid.value == ownerUid) {
                    health.value = CloudComponentState.Failed(classifyFirebaseFailure(error))
                }
            }
        }
    }

    private fun publishAuthenticatedUser(user: FirebaseUser?) {
        val nextUid = user?.uid
        synchronized(ownerSnapshotLock) {
            if (uid.value != nextUid) {
                clearOwnerState()
                markOwnerComponentsPending()
                serverBackedOwnerComponents.value = emptySet()
                establishedCachedPersonalData.value = false
                ownerBootstrapComplete.value =
                    nextUid?.let(ownerBootstrapStore::isComplete) == true
            }
            uid.value = nextUid
            _accountState.value = user.toAccountState()
            authHealth.value =
                if (user == null) CloudComponentState.Pending else CloudComponentState.Ready
        }
    }

    private fun clearOwnerState() {
        _mealPlans.value = emptyList()
        _favoriteRecipeIds.value = emptySet()
        _shoppingItems.value = emptyList()
        _recipeNotes.value = emptyList()
        _customRecipes.value = emptyList()
        _cookedHistory.value = emptyList()
        _mealPreferenceSettings.value = MealPreferenceSettings()
    }

    private fun markOwnerComponentsPending() {
        ownerHealth.forEach { it.value = CloudComponentState.Pending }
    }

    private suspend fun runAccountOperation(operation: suspend () -> Unit) {
        try {
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: AccountOperationException) {
            throw error
        } catch (error: FirebaseNetworkException) {
            throw AccountOperationException(
                accountFailureForFirebaseCode("ERROR_NETWORK_REQUEST_FAILED"),
                error,
            )
        } catch (error: FirebaseTooManyRequestsException) {
            throw AccountOperationException(
                accountFailureForFirebaseCode("ERROR_TOO_MANY_REQUESTS"),
                error,
            )
        } catch (error: FirebaseAuthException) {
            throw AccountOperationException(accountFailureForFirebaseCode(error.errorCode), error)
        } catch (error: Exception) {
            throw AccountOperationException(accountFailureForFirebaseCode(null), error)
        }
    }

    private fun ownerCollection(uid: String, name: String) =
        firestore.collection(USER_ROOT_COLLECTION).document(uid).collection(name)

    private fun mealPlans(uid: String) = ownerCollection(uid, MEAL_PLANS_COLLECTION)
    private fun favorites(uid: String) = ownerCollection(uid, FAVORITES_COLLECTION)
    private fun shoppingItems(uid: String) = ownerCollection(uid, SHOPPING_ITEMS_COLLECTION)
    private fun recipeNotes(uid: String) = ownerCollection(uid, RECIPE_NOTES_COLLECTION)
    private fun customRecipeDocuments(uid: String) = ownerCollection(uid, CUSTOM_RECIPES_COLLECTION)
    private fun cookedHistoryDocuments(uid: String) = ownerCollection(uid, COOKED_HISTORY_COLLECTION)
    private fun mealPreferenceDocuments(uid: String) =
        ownerCollection(uid, MEAL_PREFERENCES_COLLECTION)

    companion object {
        const val USER_ROOT_COLLECTION = "spoon"
        const val MEAL_PLANS_COLLECTION = "mealPlans"
        const val FAVORITES_COLLECTION = "favorites"
        const val SHOPPING_ITEMS_COLLECTION = "shoppingItems"
        const val RECIPE_NOTES_COLLECTION = "recipeNotes"
        const val CUSTOM_RECIPES_COLLECTION = "customRecipes"
        const val COOKED_HISTORY_COLLECTION = "cookedHistory"
        const val MEAL_PREFERENCES_COLLECTION = "preferences"
        private const val DATE_FIELD = "date"
        private const val CREATED_AT_FIELD = "createdAtEpochMillis"
        private const val UPDATED_AT_FIELD = "updatedAtEpochMillis"
        private const val COMPLETED_AT_FIELD = "completedAtEpochMillis"
        private const val FIRESTORE_BATCH_LIMIT = 450
        private const val AUTH_ATTEMPT_TIMEOUT_MILLIS = 20_000L
        private const val PERSONAL_CACHE_WARMUP_TIMEOUT_MILLIS = 15_000L
        private const val MEAL_PLANS_COMPONENT = "mealPlans"
        private const val FAVORITES_COMPONENT = "favorites"
        private const val SHOPPING_COMPONENT = "shopping"
        private const val NOTES_COMPONENT = "notes"
        private const val CUSTOM_RECIPES_COMPONENT = "customRecipes"
        private const val COOKED_HISTORY_COMPONENT = "cookedHistory"
        private const val PREFERENCES_COMPONENT = "mealPreferences"
        private val ALL_OWNER_COMPONENTS = setOf(
            MEAL_PLANS_COMPONENT,
            FAVORITES_COMPONENT,
            SHOPPING_COMPONENT,
            NOTES_COMPONENT,
            CUSTOM_RECIPES_COMPONENT,
            COOKED_HISTORY_COMPONENT,
            PREFERENCES_COMPONENT,
        )
    }
}

internal fun personalCacheCanInitialize(
    allLocalSnapshotsReady: Boolean,
    ownerBootstrapComplete: Boolean,
    establishedCachedPersonalData: Boolean,
): Boolean = allLocalSnapshotsReady &&
    (ownerBootstrapComplete || establishedCachedPersonalData)

internal fun ownerBootstrapCanBeMarked(
    serverBackedComponents: Set<String>,
    requiredComponents: Set<String>,
): Boolean = requiredComponents.isNotEmpty() &&
    serverBackedComponents.containsAll(requiredComponents)

/**
 * Builds the strict Firestore representation explicitly instead of relying on Java-bean
 * reflection, which would otherwise expose helper getters such as `RecipeFilters.isValid()`.
 */
internal fun DayMealPlan.toFirestoreDocument(): Map<String, Any> = mapOf(
    "date" to date,
    "category" to category,
    "recipeId" to recipeId,
    "recipeTitle" to recipeTitle,
    "filters" to mapOf(
        "category" to filters.category,
        "easeLevel" to filters.easeLevel,
        "minRating" to filters.minRating,
        "maxPrepMinutes" to filters.maxPrepMinutes,
    ),
    "completed" to completed,
    "completionEventId" to completionEventId,
    "updatedAtEpochMillis" to updatedAtEpochMillis,
)

internal fun FavoriteRecipe.toFirestoreDocument(): Map<String, Any> = mapOf(
    "recipeId" to recipeId,
    "addedAtEpochMillis" to addedAtEpochMillis,
)

internal fun ShoppingListItem.toFirestoreDocument(): Map<String, Any> = mapOf(
    "name" to name,
    "quantity" to quantity,
    "unit" to unit,
    "info" to info,
    "recipeId" to recipeId,
    "recipeTitle" to recipeTitle,
    "sectionTitle" to sectionTitle,
    "checked" to checked,
    "createdAtEpochMillis" to createdAtEpochMillis,
    "updatedAtEpochMillis" to updatedAtEpochMillis,
)

internal fun RecipeNote.toFirestoreDocument(): Map<String, Any> = mapOf(
    "recipeId" to recipeId,
    "text" to text,
    "updatedAtEpochMillis" to updatedAtEpochMillis,
)

internal fun CustomRecipe.toFirestoreDocument(): Map<String, Any> = mapOf(
    "title" to title,
    "description" to description,
    "category" to category,
    "prepMinutes" to prepMinutes,
    "cookMinutes" to cookMinutes,
    "servings" to servings,
    "ingredientSections" to ingredientSections.map { section ->
        mapOf(
            "title" to section.title,
            "ingredients" to section.ingredients.map { ingredient ->
                mapOf(
                    "title" to ingredient.title,
                    "unit" to ingredient.unit,
                    "quantity" to ingredient.quantity,
                    "info" to ingredient.info,
                )
            },
        )
    },
    "methodSections" to methodSections.map { section ->
        mapOf(
            "title" to section.title,
            "steps" to section.steps,
        )
    },
    "photoDataUri" to photoDataUri,
    "active" to active,
    "createdAtEpochMillis" to createdAtEpochMillis,
    "updatedAtEpochMillis" to updatedAtEpochMillis,
)

internal fun DayMealPlan.toCookedMealDocument(completedAtEpochMillis: Long): Map<String, Any> =
    cookedMealDocument(
        date = date,
        recipeId = recipeId,
        recipeTitle = recipeTitle,
        completedAtEpochMillis = completedAtEpochMillis,
    )

internal fun cookedMealDocument(
    date: String,
    recipeId: String,
    recipeTitle: String,
    completedAtEpochMillis: Long,
): Map<String, Any> = mapOf(
    "date" to date,
    "recipeId" to recipeId,
    "recipeTitle" to recipeTitle,
    "completedAtEpochMillis" to completedAtEpochMillis,
)

private fun FirebaseUser?.toAccountState(): AccountState = when {
    this == null -> AccountState.Loading
    isAnonymous -> AccountState.Anonymous(uid)
    else -> AccountState.Email(
        uid = uid,
        email = email.orEmpty(),
        emailVerified = isEmailVerified,
    )
}

private fun requireAccountEmail(email: String): String {
    val normalized = email.trim()
    if (
        normalized.isEmpty() ||
        normalized.length > 320 ||
        normalized.count { it == '@' } != 1 ||
        normalized.startsWith('@') ||
        normalized.endsWith('@')
    ) {
        throw AccountOperationException(accountFailureForFirebaseCode("ERROR_INVALID_EMAIL"))
    }
    return normalized
}

private fun requireAccountPassword(password: String) {
    if (password.length < 6) {
        throw AccountOperationException(accountFailureForFirebaseCode("ERROR_WEAK_PASSWORD"))
    }
}

internal sealed interface CloudComponentState {
    data object Pending : CloudComponentState
    data object Ready : CloudComponentState
    data class Failed(val failure: BackendFailure) : CloudComponentState
}

internal fun aggregateCloudState(states: List<CloudComponentState>): BackendState {
    val failure = states.firstNotNullOfOrNull { state ->
        (state as? CloudComponentState.Failed)?.failure
    }
    return when {
        failure != null -> BackendState.Error(failure)
        states.isNotEmpty() && states.all { it is CloudComponentState.Ready } -> BackendState.Cloud
        else -> BackendState.Connecting
    }
}

// σύνδεση
internal fun classifyFirebaseFailure(error: Throwable): BackendFailure {
    if (error is TimeoutCancellationException) return timeoutFailure()
    if (error is FirebaseNetworkException) return networkFailure()
    if (error is FirebaseTooManyRequestsException) return networkFailure()
    if (error is FirebaseFirestoreException) return firestoreFailure(error.code)
    if (error is FirebaseAuthException) return authFailure(error.errorCode)
    return unknownFailure()
}

private fun timeoutFailure() = BackendFailure(BackendFailureKind.NETWORK, true, "Timeout")
private fun unknownFailure() = BackendFailure(BackendFailureKind.UNKNOWN, false, "Failed")
private fun networkFailure() = BackendFailure(BackendFailureKind.NETWORK, true, "Network")
private fun permissionFailure() = BackendFailure(BackendFailureKind.PERMISSION, false, "Permission")
private fun configurationFailure() = BackendFailure(BackendFailureKind.CONFIGURATION, false, "Configuration")
private fun authenticationFailure() = BackendFailure(BackendFailureKind.AUTHENTICATION, false, "Authentication")

private fun firestoreFailure(code: FirebaseFirestoreException.Code): BackendFailure = when (code) {
    FirebaseFirestoreException.Code.PERMISSION_DENIED -> permissionFailure()
    FirebaseFirestoreException.Code.UNAUTHENTICATED -> authenticationFailure()
    FirebaseFirestoreException.Code.CANCELLED,
    FirebaseFirestoreException.Code.UNKNOWN,
    FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
    FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
    FirebaseFirestoreException.Code.ABORTED,
    FirebaseFirestoreException.Code.INTERNAL,
    FirebaseFirestoreException.Code.UNAVAILABLE,
    FirebaseFirestoreException.Code.DATA_LOSS,
    -> networkFailure()
    else -> configurationFailure()
}

private fun authFailure(code: String): BackendFailure = when (code) {
    "ERROR_NETWORK_REQUEST_FAILED",
    "ERROR_WEB_NETWORK_REQUEST_FAILED",
    "ERROR_TOO_MANY_REQUESTS",
    -> networkFailure()
    "ERROR_OPERATION_NOT_ALLOWED",
    "ERROR_INVALID_API_KEY",
    "ERROR_APP_NOT_AUTHORIZED",
    -> configurationFailure()
    else -> authenticationFailure()
}

internal fun retryDelayMillis(attempt: Long): Long =
    (1_000L shl attempt.coerceAtMost(5).toInt()).coerceAtMost(30_000L)

private data class ObservedObjects<T : Any>(
    val values: List<T>,
    val fromCache: Boolean,
    val hasPendingWrites: Boolean,
    val hasValues: Boolean,
)

private fun <T : Any> Query.objectsFlow(
    type: Class<T>,
    includeMetadataChanges: Boolean,
): Flow<ObservedObjects<T>> =
    callbackFlow<QuerySnapshot> {
        val listener = EventListener<QuerySnapshot> { snapshot, error ->
            when {
                error != null -> close(error)
                snapshot != null -> trySend(snapshot)
            }
        }
        val registration = if (includeMetadataChanges) {
            addSnapshotListener(MetadataChanges.INCLUDE, listener)
        } else {
            addSnapshotListener(listener)
        }
        awaitClose { registration.remove() }
    }
        // State consumers only need the newest pending snapshot. The currently converting snapshot
        // always completes first, so emitted results remain chronological without building a queue.
        .buffer(Channel.CONFLATED)
        .map { snapshot ->
            val values = convertFirestoreSnapshotOffMain(
                source = { snapshot.documents },
                convert = { document -> document.toObjectOrLog(type) },
            )
            ObservedObjects(
                values = values,
                fromCache = snapshot.metadata.isFromCache,
                hasPendingWrites = snapshot.metadata.hasPendingWrites(),
                // Upgrade compatibility should only trust locally cached data that decoded into a
                // valid owner model, never a malformed document that happened to exist on disk.
                hasValues = values.isNotEmpty(),
            )
        }

/**
 * Reads and converts an immutable Firestore snapshot on Default, never on the listener's main
 * executor. Sequential iteration preserves document order; periodic checks make a 5k+ conversion
 * promptly cancellable when auth changes or the repository is disposed.
 */
internal suspend fun <S, T : Any> convertFirestoreSnapshotOffMain(
    source: () -> List<S>,
    convert: (S) -> T?,
): List<T> = withContext(Dispatchers.Default) {
    val context = currentCoroutineContext()
    buildList {
        source().forEachIndexed { index, item ->
            if (index % SNAPSHOT_CANCELLATION_CHECK_INTERVAL == 0) context.ensureActive()
            convert(item)?.let(::add)
        }
    }
}

private fun <T : Any> DocumentSnapshot.toObjectOrLog(type: Class<T>): T? = try {
    requireNotNull(toObject(type))
} catch (error: Exception) {
    Log.e(FIRESTORE_TAG, type.simpleName, error)
    null
}

private const val FIRESTORE_TAG = "SpoonFirestore"
private const val SNAPSHOT_CANCELLATION_CHECK_INTERVAL = 64
