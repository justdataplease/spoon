package com.justdataplease.spoon.data.remote

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
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
import com.justdataplease.spoon.data.model.MealCourse
import com.justdataplease.spoon.data.model.coursePlan
import com.justdataplease.spoon.data.model.completionTimestamp
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

import com.justdataplease.spoon.data.local.AccountTransferStore
import com.justdataplease.spoon.data.local.InMemoryAccountTransferStore
import com.justdataplease.spoon.data.local.PersonalDataStore
import com.justdataplease.spoon.data.local.PersonalDataSnapshot
import com.justdataplease.spoon.data.local.PersonalDataChange
import com.justdataplease.spoon.data.local.PersonalRowKey
import com.justdataplease.spoon.data.local.PersonalCollection
import com.justdataplease.spoon.data.local.PendingPersonalWrite
import com.justdataplease.spoon.data.local.PersonalDataJson
import com.justdataplease.spoon.data.model.allCourses
import com.justdataplease.spoon.data.model.withCourse
import com.justdataplease.spoon.data.model.toCookedMeal
import com.justdataplease.spoon.domain.repository.PersonalSyncState
import com.google.firebase.firestore.Source
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString

/** Every personal mutation commits to SQLite first. Firebase is an optional background replica. */
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreSpoonRepository internal constructor(
    private val auth: FirebaseAuth?,
    private val firestore: FirebaseFirestore?,
    private val recipeCatalog: RecipeCatalog,
    private val mealPlanOutbox: MealPlanOutbox,
    @Suppress("UNUSED_PARAMETER") ownerBootstrapStore: OwnerBootstrapStore,
    private val preferenceStore: MealPreferenceSettingsStore? = null,
    private val personalDataStore: PersonalDataStore,
    private val accountTransferStore: AccountTransferStore = InMemoryAccountTransferStore(),
    private val legacyLocalSnapshot: suspend () -> PersonalDataSnapshot = { PersonalDataSnapshot() },
) : SpoonRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutationMutex = Mutex()
    private val accountMutex = Mutex()
    private val accountRefreshGate = AccountRefreshGate()
    private val uid = MutableStateFlow(auth?.currentUser?.uid)
    private val _accountState = MutableStateFlow(if (auth == null) AccountState.Unavailable else auth.currentUser.toAccountState())
    private val _backendState = MutableStateFlow<BackendState>(BackendState.Connecting)
    private val _syncState = MutableStateFlow<PersonalSyncState>(PersonalSyncState.LocalOnly)
    private val _mealPlans = MutableStateFlow<List<DayMealPlan>>(emptyList())
    private val _favoriteRecipeIds = MutableStateFlow<Set<String>>(emptySet())
    private val _shoppingItems = MutableStateFlow<List<ShoppingListItem>>(emptyList())
    private val _recipeNotes = MutableStateFlow<List<RecipeNote>>(emptyList())
    private val _customRecipes = MutableStateFlow<List<CustomRecipe>>(emptyList())
    private val _cookedHistory = MutableStateFlow<List<CookedMeal>>(emptyList())
    private val _mealPreferenceSettings = MutableStateFlow(MealPreferenceSettings())
    private val _referencedCatalogRecipes = MutableStateFlow<List<Recipe>>(emptyList())
    private val acknowledgementEpoch = java.util.concurrent.atomic.AtomicLongArray(PersonalCollection.entries.size)
    private val serverReady = mutableSetOf<PersonalCollection>()
    private var initialServerWaitExpired = false
    private val localRecoveryFailures = mutableSetOf<String>()
    private val syncFailures = mutableMapOf<PersonalCollection, BackendFailure>()
    private val retryAfter = mutableMapOf<PersonalCollection, Long>()
    private val syncWake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var accountOperationInProgress = false
    private var authRefreshJob: Job? = null

    override val backendState = _backendState.asStateFlow()
    override val syncState = _syncState.asStateFlow()
    override val accountState = _accountState.asStateFlow()
    override val recipes = combine(recipeCatalog.cachedRecipes, _referencedCatalogRecipes, _customRecipes,
        ::mergeCatalogRecipes).stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val mealPlans = _mealPlans.asStateFlow()
    override val favoriteRecipeIds = _favoriteRecipeIds.asStateFlow()
    override val shoppingItems = _shoppingItems.asStateFlow()
    override val recipeNotes = _recipeNotes.asStateFlow()
    override val customRecipes = _customRecipes.asStateFlow()
    override val cookedHistory = combine(_mealPlans, _cookedHistory, ::mergeCookedHistory)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val mealPreferenceSettings = _mealPreferenceSettings.asStateFlow()

    private val ready = scope.async {
        recipeCatalog.ensureReady()
        mutationMutex.withLock {
            importLegacyGuest()
            val owner = uid.value
            if (owner != null) {
                importLegacyOwner(owner)
                personalDataStore.claimGuest(owner)
                auth?.currentUser?.let(::recoverAccountTransfer)
                hydrateCachedOwner(owner)
            }
            publish(personalDataStore.read(owner))
            _backendState.value = BackendState.Local
            updateSyncState()
        }
    }

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        if (!accountOperationInProgress) scope.launch {
            ready.await()
            mutationMutex.withLock { activateOwner(firebaseAuth.currentUser) }
        }
    }

    init {
        auth?.addAuthStateListener(authListener)
        scope.launch {
            ready.await()
            combine(_mealPlans, _favoriteRecipeIds, _cookedHistory, ::boundedReferencedRecipeIds)
                .distinctUntilChanged().collect { _referencedCatalogRecipes.value = recipeCatalog.getRecipesByIds(it) }
        }
        scope.launch {
            ready.await()
            uid.collectLatest { owner ->
                if (owner != null && firestore != null) coroutineScope {
                    PersonalCollection.entries.forEach { collection -> launch { observe(owner, collection) } }
                    launch { synchronize(owner) }
                    launch {
                        // One deadline per owner subscription, independent of local actions.
                        // collectLatest cancels it when account ownership changes.
                        delay(INITIAL_PERSONAL_SYNC_WAIT_MILLIS)
                        mutationMutex.withLock {
                            if (uid.value == owner && serverReady.size != PersonalCollection.entries.size) {
                                initialServerWaitExpired = true
                                updateSyncState()
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun ensureReady() {
        ready.await()
        auth?.currentUser?.let(::refreshCachedUser)
        syncWake.trySend(Unit)
    }

    override suspend fun getRecipeDetails(recipeId: String): Recipe? {
        ready.await()
        return recipeCatalog.getRecipeDetailsIncludingCustom(requireSafeRecipeDocumentId(recipeId), _customRecipes.value)
    }
    override suspend fun getRecipesByIds(recipeIds: Set<String>): List<Recipe> {
        ready.await()
        return recipeCatalog.getRecipesByIdsIncludingCustom(recipeIds, _customRecipes.value)
    }
    override suspend fun queryRecipes(criteria: ExploreCriteria, limit: Int, offset: Int,
        preferences: MealPreferenceSettings): RecipePage {
        ready.await()
        return recipeCatalog.queryIncludingCustomRecipes(_customRecipes.value, criteria, limit, offset, preferences)
    }
    override suspend fun getCatalogFacetOptions(): CatalogFacetOptions {
        ready.await()
        return recipeCatalog.facetOptionsIncludingCustom(_customRecipes.value)
    }
    override suspend fun selectRandomRecipe(filters: RecipeFilters, excludingRecipeId: String?,
        randomSeed: Long, preferences: MealPreferenceSettings): Recipe? {
        ready.await()
        return selectIncludingCustomRecipes(recipeCatalog, _customRecipes.value, filters, excludingRecipeId, randomSeed, preferences)
    }

    /** Persistence succeeds before any flow publishes success; no network/auth task is awaited. */
    private suspend fun change(transform: (PersonalDataSnapshot) -> PersonalDataSnapshot): PersonalDataSnapshot =
        changeWithDeletions { PersonalDataChange(transform(it)) }

    private suspend fun changeWithDeletions(transform: (PersonalDataSnapshot) -> PersonalDataChange): PersonalDataSnapshot {
        ready.await()
        val expectedOwner = uid.value
        return withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                check(uid.value == expectedOwner && auth?.currentUser?.uid == expectedOwner) {
                    "The account changed before this local action was saved"
                }
                personalDataStore.mutateWithDeletions(expectedOwner, transform).also {
                    publish(it)
                    updateSyncState()
                    syncWake.trySend(Unit)
                }
            }
        }
    }

    override suspend fun upsertMealPlan(plan: DayMealPlan) {
        require(plan.date.isNotBlank())
        change { current ->
            val history = mergeCookedHistory(current.mealPlans, current.cookedHistory)
            normalizeLegacyHistory(current.copy(mealPlans = mergeMealPlanSnapshots(current.mealPlans, listOf(plan)), cookedHistory = history))
        }
    }
    override suspend fun upsertInitialMealPlan(plan: DayMealPlan) {
        ready.await()
        if (uid.value == null || firestore == null) { upsertMealPlan(plan); return }
        withContext(Dispatchers.IO) { mutationMutex.withLock {
            val owner = uid.value ?: return@withLock
            if (auth?.currentUser?.uid != owner || _mealPlans.value.any { it.date == plan.date }) return@withLock
            val result = if (PersonalCollection.MEAL_PLANS !in serverReady) {
                // An automatic first-launch suggestion must yield to a previously saved cloud
                // day, even if that cloud day is older than this installation. Explicit edits
                // get normal revisions and remain durable while offline.
                personalDataStore.importLegacy(owner, PersonalDataSnapshot(mealPlans = listOf(
                    plan.copy(id = plan.date, updatedAtEpochMillis = 1L))), "initial_day_${plan.date}")
            } else personalDataStore.mutate(owner) { it.copy(mealPlans = it.mealPlans + plan.copy(id = plan.date)) }
            publish(result); updateSyncState(); syncWake.trySend(Unit)
        } }
    }

    override suspend fun setMealCompleted(date: String, completed: Boolean) = setCourseCompleted(date, MealCourse.MAIN, completed)
    override suspend fun setCourseCompleted(date: String, course: MealCourse, completed: Boolean) {
        require(date.isNotBlank())
        changeWithDeletions { current ->
            val result = projectMealCompletion(current.mealPlans, mergeCookedHistory(current.mealPlans, current.cookedHistory),
                date, completed, System.currentTimeMillis(), newCookedMealEventId(), course)
            PersonalDataChange(
                current.copy(mealPlans = result.plans, cookedHistory = result.storedHistory),
                result.historyIdsToDelete.mapTo(mutableSetOf()) { PersonalRowKey(PersonalCollection.COOKED_HISTORY, it) },
            )
        }
    }
    override suspend fun deleteCookedHistoryEntry(historyId: String) {
        requireSafeRecipeDocumentId(historyId)
        changeWithDeletions { current ->
            val result = projectHistoryDeletion(current.mealPlans, mergeCookedHistory(current.mealPlans, current.cookedHistory),
                historyId, System.currentTimeMillis())
            PersonalDataChange(
                current.copy(mealPlans = result.plans, cookedHistory = result.storedHistory),
                listOfNotNull(result.historyIdToDelete).mapTo(mutableSetOf()) { PersonalRowKey(PersonalCollection.COOKED_HISTORY, it) },
            )
        }
    }
    override suspend fun toggleFavorite(recipeId: String): Boolean {
        requireSafeRecipeDocumentId(recipeId)
        val result = change { current ->
            val existing = current.favorites.any { it.recipeId == recipeId }
            current.copy(favorites = if (existing) current.favorites.filterNot { it.recipeId == recipeId }
                else current.favorites + FavoriteRecipe(recipeId, recipeId, System.currentTimeMillis()))
        }
        return result.favorites.any { it.recipeId == recipeId }
    }
    override suspend fun upsertShoppingItems(items: List<ShoppingListItem>) {
        if (items.isEmpty()) return
        require(items.size <= MAX_SHOPPING_ITEMS_PER_WRITE)
        items.forEach(ShoppingListItem::requireValid)
        change { current ->
            val ids = items.mapTo(mutableSetOf()) { it.id }
            current.copy(shoppingItems = (current.shoppingItems.filterNot { it.id in ids } + items).sortedBy { it.createdAtEpochMillis })
        }
    }
    override suspend fun setShoppingItemChecked(itemId: String, checked: Boolean) {
        requireSafeRecipeDocumentId(itemId)
        change { current -> current.copy(shoppingItems = current.shoppingItems.map {
            if (it.id == itemId) it.copy(checked = checked, updatedAtEpochMillis = System.currentTimeMillis()) else it
        }) }
    }
    override suspend fun deleteShoppingItem(itemId: String) {
        requireSafeRecipeDocumentId(itemId)
        change { it.copy(shoppingItems = it.shoppingItems.filterNot { item -> item.id == itemId }) }
    }
    override suspend fun clearCheckedShoppingItems() {
        change { it.copy(shoppingItems = it.shoppingItems.filterNot(ShoppingListItem::checked)) }
    }
    override suspend fun upsertRecipeNote(note: RecipeNote) {
        val id = requireSafeRecipeDocumentId(note.recipeId.ifBlank { note.id })
        require(note.id.isBlank() || note.id == id)
        val stored = note.copy(id = id, recipeId = id)
        if (stored.text.isNotBlank()) stored.requireValid()
        change { it.copy(recipeNotes = (it.recipeNotes.filterNot { old -> old.recipeId == id } +
            listOfNotNull(stored.takeIf { value -> value.text.isNotBlank() })).sortedBy(RecipeNote::recipeId)) }
    }
    override suspend fun upsertCustomRecipe(recipe: CustomRecipe) {
        recipe.requireValid()
        change { it.copy(customRecipes = (it.customRecipes.filterNot { old -> old.id == recipe.id } + recipe)
            .sortedByDescending(CustomRecipe::updatedAtEpochMillis)) }
    }
    override suspend fun deleteCustomRecipe(recipeId: String) {
        require(recipeId.isCustomRecipeId())
        change { it.copy(customRecipes = it.customRecipes.filterNot { recipe -> recipe.id == recipeId },
            favorites = it.favorites.filterNot { favorite -> favorite.recipeId == recipeId },
            recipeNotes = it.recipeNotes.filterNot { note -> note.recipeId == recipeId }) }
    }
    override suspend fun updateMealPreferenceSettings(settings: MealPreferenceSettings) {
        change { it.copy(preferences = settings.normalizedForSync(nextPreferenceTimestamp(
            System.currentTimeMillis(), it.preferences.updatedAtEpochMillis))) }
    }

    private fun publish(value: PersonalDataSnapshot) {
        _mealPlans.value = value.mealPlans
        _favoriteRecipeIds.value = value.favorites.mapTo(mutableSetOf()) { it.recipeId }
        _shoppingItems.value = value.shoppingItems
        _recipeNotes.value = value.recipeNotes
        _customRecipes.value = value.customRecipes
        _cookedHistory.value = value.cookedHistory
        _mealPreferenceSettings.value = value.preferences
    }

    private suspend fun importLegacyGuest() {
        val snapshotToken = "local_preferences_v1"
        if (!personalDataStore.isLegacyImported(null, snapshotToken)) {
            val value = optionalLegacyRead("legacy_guest") {
                val legacy = legacyLocalSnapshot()
                val settings = preferenceStore?.readLegacyForLocalStore(null)
                normalizeLegacyHistory(legacy.copy(preferences = settings ?: legacy.preferences))
            }
            if (value != null) {
                // Database failures remain fatal: only optional legacy reads are isolated.
                personalDataStore.importLegacy(null, value, snapshotToken)
                localRecoveryFailures.remove("legacy_guest")
            }
        } else localRecoveryFailures.remove("legacy_guest")
        val outboxToken = "meal_outbox_guest_v1"
        if (!personalDataStore.isLegacyImported(null, outboxToken)) {
            val value = optionalLegacyRead("legacy_guest_outbox") {
                normalizeLegacyHistory(PersonalDataSnapshot(mealPlans = mealPlanOutbox.ownerlessPlans()))
            }
            if (value != null) {
                personalDataStore.importLegacy(null, value, outboxToken)
                localRecoveryFailures.remove("legacy_guest_outbox")
            }
        } else localRecoveryFailures.remove("legacy_guest_outbox")
    }

    private suspend fun importLegacyOwner(owner: String): Boolean {
        val token = "meal_outbox_owner_v1"
        if (personalDataStore.isLegacyImported(owner, token)) {
            localRecoveryFailures.remove("legacy_owner")
            return false
        }
        val value = optionalLegacyRead("legacy_owner") {
            val preferences = preferenceStore?.readLegacyForLocalStore(owner) ?: MealPreferenceSettings()
            normalizeLegacyHistory(PersonalDataSnapshot(
                mealPlans = mealPlanOutbox.pendingPlans(owner), preferences = preferences))
        } ?: return false
        personalDataStore.importLegacy(owner, value, token)
        localRecoveryFailures.remove("legacy_owner")
        return true
    }

    private suspend fun <T : Any> optionalLegacyRead(key: String, read: suspend () -> T): T? = try {
        read()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        localRecoveryFailures += key
        Log.w(FIRESTORE_TAG, "Legacy data remains available for recovery; local storage is usable", error)
        null
    }

    /** Called under the mutation mutex, after Firebase has actually accepted this identity. */
    private suspend fun activateOwner(user: FirebaseUser?) {
        val owner = user?.uid
        if (auth?.currentUser?.uid != owner) return
        if (uid.value != owner) {
            val previous = uid.value
            _syncState.value = if (owner == null) PersonalSyncState.LocalOnly else PersonalSyncState.Syncing(0)
            publish(PersonalDataSnapshot())
            serverReady.clear()
            initialServerWaitExpired = false
            syncFailures.clear()
            retryAfter.clear()
            localRecoveryFailures.remove("legacy_owner")
            localRecoveryFailures.remove("account_transfer")
            if (owner != null) {
                importLegacyOwner(owner)
                if (previous == null) personalDataStore.claimGuest(owner)
                recoverAccountTransfer(checkNotNull(user))
                hydrateCachedOwner(owner)
            }
            uid.value = owner
            publish(personalDataStore.read(owner))
        } else if (user != null) {
            // An account refresh can repair optional migration without changing ownership.
            // Healthy local data remains usable between retries.
            val imported = importLegacyOwner(user.uid)
            val recovered = recoverAccountTransfer(user)
            if (imported || recovered) publish(personalDataStore.read(user.uid))
        }
        _accountState.value = if (auth == null) AccountState.Unavailable else user.toAccountState()
        updateSyncState()
        syncWake.trySend(Unit)
    }

    override suspend fun createAccountWithEmail(email: String, password: String) =
        authenticateWithEmail(email, password) { firebaseAuth, normalizedEmail, suppliedPassword ->
            checkNotNull(firebaseAuth.createUserWithEmailAndPassword(normalizedEmail, suppliedPassword).await().user)
        }

    override suspend fun signInWithEmail(email: String, password: String) =
        authenticateWithEmail(email, password) { firebaseAuth, normalizedEmail, suppliedPassword ->
            checkNotNull(firebaseAuth.signInWithEmailAndPassword(normalizedEmail, suppliedPassword).await().user)
        }

    /** Registration and sign-in both attach the existing local profile after Firebase accepts it. */
    private suspend fun authenticateWithEmail(
        email: String,
        password: String,
        authenticate: suspend (FirebaseAuth, String, String) -> FirebaseUser,
    ) {
        val normalizedEmail = requireAccountEmail(email)
        requireAccountPassword(password)
        ready.await()
        accountMutex.withLock {
            val firebaseAuth = auth ?: throw AccountOperationException(com.justdataplease.spoon.domain.repository.unavailableAccountFailure())
            val previousAnonymous = firebaseAuth.currentUser?.takeIf { it.isAnonymous }?.uid
            if (previousAnonymous != null) {
                warmAnonymousCache(previousAnonymous)
                withContext(Dispatchers.IO) { accountTransferStore.prepare(previousAnonymous, normalizedEmail) }
            }
            accountOperationInProgress = true
            try {
                runAccountOperation {
                    val user = authenticate(firebaseAuth, normalizedEmail, password)
                    withContext(Dispatchers.IO) { mutationMutex.withLock { activateOwner(user) } }
                }
            } finally {
                accountOperationInProgress = false
                scope.launch { mutationMutex.withLock { activateOwner(firebaseAuth.currentUser) } }
            }
        }
    }
    override suspend fun sendPasswordReset(email: String) {
        val normalized = requireAccountEmail(email)
        val firebaseAuth = auth ?: throw AccountOperationException(com.justdataplease.spoon.domain.repository.unavailableAccountFailure())
        runAccountOperation { firebaseAuth.sendPasswordResetEmail(normalized).await() }
    }
    override suspend fun signOut() {
        ready.await()
        accountMutex.withLock {
            accountOperationInProgress = true
            try { withContext(Dispatchers.IO) { mutationMutex.withLock { auth?.signOut(); activateOwner(null) } } }
            finally { accountOperationInProgress = false }
        }
    }
    private fun refreshCachedUser(user: FirebaseUser) {
        if (authRefreshJob?.isActive == true || accountOperationInProgress ||
            !accountRefreshGate.shouldRefresh(user.uid, android.os.SystemClock.elapsedRealtime())) return
        authRefreshJob = scope.launch {
            runCatching { user.reload().await() }.onSuccess {
                mutationMutex.withLock {
                    if (!accountOperationInProgress && auth?.currentUser?.uid == user.uid) activateOwner(auth.currentUser)
                }
            }
        }
    }
    private fun recoverAccountTransfer(user: FirebaseUser): Boolean {
        val result = recoverPendingAccountTransfer(
            store = accountTransferStore, ownerUid = user.uid, email = user.email,
            isAnonymous = user.isAnonymous,
        ) { source, destination -> personalDataStore.claimAnonymous(source, destination) }
        if (result is AccountTransferRecoveryOutcome.Failed) {
            localRecoveryFailures += "account_transfer"
            Log.w(FIRESTORE_TAG, "Account transfer is retained for recovery; local storage is usable", result.error)
        } else localRecoveryFailures.remove("account_transfer")
        return result == AccountTransferRecoveryOutcome.Recovered ||
            (result is AccountTransferRecoveryOutcome.Failed && result.claimCompleted)
    }

    /** Cache reads never need a token or network, including an upgrade from the former repository. */
    private suspend fun hydrateCachedOwner(owner: String) {
        if (firestore == null) return
        for (collection in PersonalCollection.entries) {
            val snapshot = runCatching { query(owner, collection).get(Source.CACHE).await() }.getOrNull() ?: continue
            personalDataStore.mergeRemote(owner, collection, componentSnapshot(collection, snapshot.documents), false)
            val pendingDocuments = snapshot.documents.filter { it.metadata.hasPendingWrites() }
            if (pendingDocuments.isNotEmpty()) personalDataStore.importLegacy(owner,
                normalizeLegacyHistory(componentSnapshot(collection, pendingDocuments)), "firebase_pending_v1_${collection.name}")
        }
    }

    /** Used by isolated repository tests; the application instance lives for the process. */
    internal suspend fun close() {
        auth?.removeAuthStateListener(authListener)
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    private suspend fun warmAnonymousCache(owner: String) = coroutineScope {
        PersonalCollection.entries.map { collection -> async {
            val snapshot = runCatching { query(owner, collection).get(Source.CACHE).await() }.getOrNull() ?: return@async
            val values = componentSnapshot(collection, snapshot.documents)
            mutationMutex.withLock {
                if (uid.value == owner) publish(personalDataStore.mergeRemote(owner, collection, values, false))
            }
        } }.forEach { it.await() }
    }

    private suspend fun observe(owner: String, collection: PersonalCollection) {
        query(owner, collection).rawSnapshots { acknowledgementEpoch.get(collection.ordinal) }
            .onEach { captured ->
                // A callback may have been queued before a write acknowledgement. Refresh that
                // stale observation rather than letting it erase newly accepted local data.
                val event = if (captured.epoch != acknowledgementEpoch.get(collection.ordinal)) {
                    val epoch = acknowledgementEpoch.get(collection.ordinal)
                    PersonalSnapshotEvent(withTimeout(15_000) { query(owner, collection).get(Source.SERVER).await() }, epoch)
                } else captured
                val snapshot = event.snapshot
                val value = componentSnapshot(collection, snapshot.documents)
                mutationMutex.withLock {
                    if (uid.value != owner || auth?.currentUser?.uid != owner ||
                        event.epoch != acknowledgementEpoch.get(collection.ordinal)) return@withLock
                    if (!snapshot.metadata.isFromCache) serverReady += collection
                    val merged = personalDataStore.mergeRemote(owner, collection, value,
                        !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites())
                    publish(merged)
                    if (!snapshot.metadata.isFromCache && collection !in retryAfter) syncFailures.remove(collection)
                    updateSyncState()
                    syncWake.trySend(Unit)
                }
            }.retryWhen { error, attempt ->
                if (error is CancellationException && error !is TimeoutCancellationException) return@retryWhen false
                mutationMutex.withLock {
                    if (uid.value == owner) { syncFailures[collection] = classifyFirebaseFailure(error); updateSyncState() }
                }
                delay(retryDelayMillis(attempt))
                uid.value == owner
            }.collect()
    }

    private suspend fun synchronize(owner: String) {
        while (currentCoroutineContext().isActive && uid.value == owner) {
            val pending = mutationMutex.withLock {
                personalDataStore.pending(owner).filter { !it.acknowledged && !it.imported && it.collection in serverReady &&
                    android.os.SystemClock.elapsedRealtime() >= (retryAfter[it.collection] ?: 0L) &&
                    (it.collection !in listOf(PersonalCollection.MEAL_PLANS, PersonalCollection.COOKED_HISTORY) ||
                        serverReady.containsAll(listOf(PersonalCollection.MEAL_PLANS, PersonalCollection.COOKED_HISTORY))) }
            }
            // History creation precedes completed plans; undo plans precede history deletion.
            val groups = pending.groupBy { syncOrder(it) }.toSortedMap().values
            for (group in groups) {
                for (chunk in boundedSyncChunks(group)) {
                    if (uid.value != owner || auth?.currentUser?.uid != owner) return
                    try {
                        val submitted = upload(owner, chunk)
                        mutationMutex.withLock {
                            submitted.map { it.collection }.distinct().forEach { acknowledgementEpoch.incrementAndGet(it.ordinal) }
                            submitted.forEach { personalDataStore.acknowledge(owner, it) }
                            if (uid.value == owner && submitted.isNotEmpty()) {
                                syncFailures.remove(chunk.first().collection)
                                retryAfter.remove(chunk.first().collection)
                                updateSyncState()
                            }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        Log.w(FIRESTORE_TAG, "Personal changes remain saved locally; sync will retry", error)
                        mutationMutex.withLock {
                            if (uid.value == owner) {
                                syncFailures[chunk.first().collection] = classifyFirebaseFailure(error)
                                retryAfter[chunk.first().collection] = android.os.SystemClock.elapsedRealtime() + 15_000L
                                updateSyncState()
                            }
                        }
                        break // Other collections remain independent of this failed upload.
                    }
                }
            }
            withTimeoutOrNull(15_000) { syncWake.receive() }
        }
    }

    private suspend fun upload(owner: String, writes: List<PendingPersonalWrite>): List<PendingPersonalWrite> {
        val db = checkNotNull(firestore)
        val existingFavorites = mutableSetOf<String>()
        for (write in writes) {
            if (write.collection == PersonalCollection.FAVORITES && write.payload != null &&
                ownerCollection(owner, write.collection).document(write.documentId).get(Source.SERVER).await().exists())
                existingFavorites += write.documentId
        }
        // Recheck revisions after network reads and enqueue under the local mutation lock.
        // A concurrent undo, edit or import rekey must never submit an obsolete snapshot.
        val (submitted, task) = mutationMutex.withLock {
            if (uid.value != owner || auth?.currentUser?.uid != owner) return emptyList()
            val current = personalDataStore.pending(owner).associateBy { it.collection to it.documentId }
            val selected = writes.filter { write -> current[write.collection to write.documentId]?.let {
                it.revision == write.revision && !it.acknowledged && !it.imported
            } == true }
            val batch = db.batch()
            var count = 0
            for (write in selected) {
                if (write.collection == PersonalCollection.FAVORITES && write.payload != null &&
                    write.documentId in existingFavorites) continue
                val document = ownerCollection(owner, write.collection).document(write.documentId)
                if (write.payload == null) batch.delete(document) else batch.set(document, write.firestoreFields())
                count++
            }
            selected to if (count > 0) batch.commit() else null
        }
        if (task != null) {
            // Await the same queued task through outages; never enqueue repeated copies.
            while (!task.isComplete) {
                if (withTimeoutOrNull(10_000) { task.await(); true } == true) break
                mutationMutex.withLock {
                    if (uid.value == owner) _syncState.value = PersonalSyncState.Waiting(
                        personalDataStore.pending(owner).size,
                        needsLocalRecovery = localRecoveryFailures.isNotEmpty(),
                    )
                }
            }
            task.await()
        }
        return submitted
    }

    private fun updateSyncState() {
        val owner = uid.value
        _syncState.value = personalSyncStatus(
            canSynchronize = owner != null && auth != null && firestore != null,
            pendingWrites = owner?.let { personalDataStore.pending(it).size } ?: 0,
            allServerSnapshotsReady = serverReady.size == PersonalCollection.entries.size,
            initialWaitExpired = initialServerWaitExpired,
            needsLocalRecovery = localRecoveryFailures.isNotEmpty(),
            failures = syncFailures.values,
        )
    }

    private fun ownerCollection(owner: String, collection: PersonalCollection) = checkNotNull(firestore)
        .collection(USER_ROOT_COLLECTION).document(owner).collection(collection.path())
    private fun query(owner: String, collection: PersonalCollection): Query =
        if (collection == PersonalCollection.PREFERENCES) ownerCollection(owner, collection)
            .whereEqualTo(FieldPath.documentId(), MEAL_PREFERENCE_DOCUMENT_ID)
        else ownerCollection(owner, collection)

    private suspend fun runAccountOperation(operation: suspend () -> Unit) {
        try { operation() }
        catch (error: CancellationException) { throw error }
        catch (error: AccountOperationException) { throw error }
        catch (error: FirebaseNetworkException) { throw AccountOperationException(accountFailureForFirebaseCode("ERROR_NETWORK_REQUEST_FAILED"), error) }
        catch (error: FirebaseTooManyRequestsException) { throw AccountOperationException(accountFailureForFirebaseCode("ERROR_TOO_MANY_REQUESTS"), error) }
        catch (error: FirebaseAuthException) { throw AccountOperationException(accountFailureForFirebaseCode(error.errorCode), error) }
        catch (error: Exception) { throw AccountOperationException(accountFailureForFirebaseCode(null), error) }
    }

    companion object {
        const val USER_ROOT_COLLECTION = "spoon"
        const val MEAL_PLANS_COLLECTION = "mealPlans"
        const val FAVORITES_COLLECTION = "favorites"
        const val SHOPPING_ITEMS_COLLECTION = "shoppingItems"
        const val RECIPE_NOTES_COLLECTION = "recipeNotes"
        const val CUSTOM_RECIPES_COLLECTION = "customRecipes"
        const val COOKED_HISTORY_COLLECTION = "cookedHistory"
        const val MEAL_PREFERENCES_COLLECTION = "preferences"
    }
}

private fun PersonalCollection.path(): String = when (this) {
    PersonalCollection.MEAL_PLANS -> FirestoreSpoonRepository.MEAL_PLANS_COLLECTION
    PersonalCollection.FAVORITES -> FirestoreSpoonRepository.FAVORITES_COLLECTION
    PersonalCollection.SHOPPING -> FirestoreSpoonRepository.SHOPPING_ITEMS_COLLECTION
    PersonalCollection.NOTES -> FirestoreSpoonRepository.RECIPE_NOTES_COLLECTION
    PersonalCollection.CUSTOM_RECIPES -> FirestoreSpoonRepository.CUSTOM_RECIPES_COLLECTION
    PersonalCollection.COOKED_HISTORY -> FirestoreSpoonRepository.COOKED_HISTORY_COLLECTION
    PersonalCollection.PREFERENCES -> FirestoreSpoonRepository.MEAL_PREFERENCES_COLLECTION
}

private fun PendingPersonalWrite.firestoreFields(): Map<String, Any> = when (collection) {
    PersonalCollection.MEAL_PLANS -> PersonalDataJson.decodeFromString<DayMealPlan>(checkNotNull(payload)).toFirestoreDocument()
    PersonalCollection.FAVORITES -> PersonalDataJson.decodeFromString<FavoriteRecipe>(checkNotNull(payload)).toFirestoreDocument()
    PersonalCollection.SHOPPING -> PersonalDataJson.decodeFromString<ShoppingListItem>(checkNotNull(payload)).toFirestoreDocument()
    PersonalCollection.NOTES -> PersonalDataJson.decodeFromString<RecipeNote>(checkNotNull(payload)).toFirestoreDocument()
    PersonalCollection.CUSTOM_RECIPES -> PersonalDataJson.decodeFromString<CustomRecipe>(checkNotNull(payload)).toFirestoreDocument()
    PersonalCollection.COOKED_HISTORY -> PersonalDataJson.decodeFromString<CookedMeal>(checkNotNull(payload)).let {
        cookedMealDocument(it.date, it.recipeId, it.recipeTitle, it.completedAtEpochMillis)
    }
    PersonalCollection.PREFERENCES -> PersonalDataJson.decodeFromString<MealPreferenceSettings>(checkNotNull(payload)).toFirestoreDocument()
}

private data class PersonalSnapshotEvent(val snapshot: QuerySnapshot, val epoch: Long)

private fun Query.rawSnapshots(epoch: () -> Long): Flow<PersonalSnapshotEvent> = callbackFlow {
    val registration = addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
        if (error != null) close(error) else if (snapshot != null) trySend(PersonalSnapshotEvent(snapshot, epoch()))
    }
    awaitClose { registration.remove() }
}.buffer(Channel.CONFLATED)

private suspend fun componentSnapshot(collection: PersonalCollection, documents: List<DocumentSnapshot>): PersonalDataSnapshot {
    suspend fun <T : Any> values(type: Class<T>): List<T> = convertFirestoreSnapshotOffMain({ documents }) { it.toObjectOrLog(type) }
    return when (collection) {
        PersonalCollection.MEAL_PLANS -> PersonalDataSnapshot(mealPlans = values(DayMealPlan::class.java))
        PersonalCollection.FAVORITES -> PersonalDataSnapshot(favorites = values(FavoriteRecipe::class.java))
        PersonalCollection.SHOPPING -> PersonalDataSnapshot(shoppingItems = values(ShoppingListItem::class.java))
        PersonalCollection.NOTES -> PersonalDataSnapshot(recipeNotes = values(RecipeNote::class.java))
        PersonalCollection.CUSTOM_RECIPES -> PersonalDataSnapshot(customRecipes = values(CustomRecipe::class.java))
        PersonalCollection.COOKED_HISTORY -> PersonalDataSnapshot(cookedHistory = values(CookedMeal::class.java))
        PersonalCollection.PREFERENCES -> PersonalDataSnapshot(preferences = values(MealPreferenceDocument::class.java)
            .firstNotNullOfOrNull { it.toSettingsOrNull() } ?: MealPreferenceSettings())
    }
}

private data class LocallyLoadedPreferenceOwner(val ownerUid: String?)

private data class LocalPlanningSnapshot(
    val ownerUid: String?,
    val mealPlanState: CloudComponentState,
    val customRecipeState: CloudComponentState,
)

internal fun localPlanningCanStart(
    hasOwner: Boolean,
    mealPlanState: CloudComponentState,
    customRecipeState: CloudComponentState,
): Boolean = !hasOwner ||
    (
        mealPlanState !is CloudComponentState.Pending &&
            customRecipeState !is CloudComponentState.Pending
        )

internal fun mergeRemoteAndPendingMealPlans(
    remote: List<DayMealPlan>,
    pending: List<DayMealPlan>,
): List<DayMealPlan> = mergeMealPlanSnapshots(remote, pending)

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
 * Ownerless settings were entered while authentication was unavailable and belong to the first
 * owner that follows. Real owner changes still clear preferences to prevent cross-account leaks.
 */
internal fun ownerlessMealPreferencesToPreserve(
    previousOwnerUid: String?,
    nextOwnerUid: String?,
    settings: MealPreferenceSettings,
): MealPreferenceSettings? = settings.takeIf {
    previousOwnerUid == null && nextOwnerUid != null
}

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
    "locked" to locked,
    "completedAtEpochMillis" to if (completed) completionTimestamp else 0L,
    "completionEventId" to completionEventId,
    "updatedAtEpochMillis" to updatedAtEpochMillis,
) + buildMap {
    coursePlan(MealCourse.SIDE)?.let { put("side", it.toFirestoreDocument() - "date") }
    coursePlan(MealCourse.DESSERT)?.let { put("dessert", it.toFirestoreDocument() - "date") }
}

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
    this == null -> AccountState.SignedOut
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
private fun localPreferenceStoreFailure(
    @Suppress("UNUSED_PARAMETER") error: Exception,
) = BackendFailure(
    BackendFailureKind.CONFIGURATION,
    false,
    "Local meal preference storage is unavailable",
)
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
    (5_000L shl attempt.coerceIn(0, 3).toInt()).coerceAtMost(30_000L)

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
