package com.justdataplease.spoon.data.remote

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import com.justdataplease.spoon.data.eligibleRemoteRecipes
import com.justdataplease.spoon.data.eligibleRecipeDetails
import com.justdataplease.spoon.data.model.CookedMeal
import com.justdataplease.spoon.data.model.CustomRecipe
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.FavoriteRecipe
import com.justdataplease.spoon.data.model.MAX_SHOPPING_ITEMS_PER_WRITE
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.model.RecipeNote
import com.justdataplease.spoon.data.model.ShoppingListItem
import com.justdataplease.spoon.data.model.isCustomRecipeId
import com.justdataplease.spoon.data.model.mergeCookedHistory
import com.justdataplease.spoon.data.model.requireValid
import com.justdataplease.spoon.data.requireSafeRecipeDocumentId
import com.justdataplease.spoon.domain.repository.BackendFailure
import com.justdataplease.spoon.domain.repository.BackendFailureKind
import com.justdataplease.spoon.domain.repository.BackendState
import com.justdataplease.spoon.domain.repository.BackendUnavailableException
import com.justdataplease.spoon.domain.repository.AccountOperationException
import com.justdataplease.spoon.domain.repository.AccountState
import com.justdataplease.spoon.domain.repository.SpoonRepository
import com.justdataplease.spoon.domain.repository.accountFailureForFirebaseCode
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Firestore implementation: public recipe metadata plus owner-scoped plans and favorites. */
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreSpoonRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : SpoonRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authMutex = Mutex()
    private val authJobLock = Any()
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
    private val _catalogRecipes = MutableStateFlow<List<Recipe>>(emptyList())
    private val _mealPlans = MutableStateFlow<List<DayMealPlan>>(emptyList())
    private val _favoriteRecipeIds = MutableStateFlow<Set<String>>(emptySet())
    private val _shoppingItems = MutableStateFlow<List<ShoppingListItem>>(emptyList())
    private val _recipeNotes = MutableStateFlow<List<RecipeNote>>(emptyList())
    private val _customRecipes = MutableStateFlow<List<CustomRecipe>>(emptyList())
    private val _cookedHistory = MutableStateFlow<List<CookedMeal>>(emptyList())

    @Volatile
    private var authJob: Job? = null
    @Volatile
    private var authRefreshJob: Job? = null

    override val backendState = combine(
        listOf(
            authHealth,
            recipesHealth,
            mealPlansHealth,
            favoritesHealth,
            shoppingHealth,
            notesHealth,
            customRecipesHealth,
            cookedHistoryHealth,
        ),
    ) { states ->
        aggregateCloudState(states.toList())
    }.stateIn(scope, SharingStarted.Eagerly, BackendState.Connecting)

    override val accountState = _accountState.asStateFlow()
    override val recipes = combine(_catalogRecipes, _customRecipes) { catalog, custom ->
        (catalog + custom.filter(CustomRecipe::active).map(CustomRecipe::toRecipe))
            .sortedBy(Recipe::title)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val mealPlans = _mealPlans.asStateFlow()
    override val favoriteRecipeIds = _favoriteRecipeIds.asStateFlow()
    override val shoppingItems = _shoppingItems.asStateFlow()
    override val recipeNotes = _recipeNotes.asStateFlow()
    override val customRecipes = _customRecipes.asStateFlow()
    override val cookedHistory = combine(_mealPlans, _cookedHistory, ::mergeCookedHistory)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        publishAuthenticatedUser(user)
        if (user == null) {
            startAuthentication()
        }
    }

    init {
        observeQuery(
            recipesHealth,
            Recipe::class.java,
            {
                firestore.collection(RECIPES_COLLECTION)
                    .whereEqualTo(ACTIVE_FIELD, true)
                    .whereEqualTo(LANGUAGE_FIELD, GREEK_LANGUAGE)
            },
        ) { remote -> _catalogRecipes.value = eligibleRemoteRecipes(remote).sortedBy(Recipe::title) }
        observeUserQueries()
        auth.addAuthStateListener(authListener)
        startAuthentication()
    }

    private fun observeUserQueries() {
        observeQuery(
            mealPlansHealth,
            DayMealPlan::class.java,
            { currentUid -> mealPlans(currentUid).orderBy(DATE_FIELD, Query.Direction.ASCENDING) },
        ) { _mealPlans.value = it }
        observeQuery(
            favoritesHealth,
            FavoriteRecipe::class.java,
            { currentUid -> favorites(currentUid) },
        ) { remote ->
            _favoriteRecipeIds.value = remote.mapNotNull { favorite ->
                favorite.recipeId.ifBlank { favorite.id }.takeIf(String::isNotBlank)
            }.toSet()
        }
        observeQuery(
            shoppingHealth,
            ShoppingListItem::class.java,
            { currentUid ->
                shoppingItems(currentUid).orderBy(CREATED_AT_FIELD, Query.Direction.ASCENDING)
            },
        ) { _shoppingItems.value = it }
        observeQuery(
            notesHealth,
            RecipeNote::class.java,
            { currentUid -> recipeNotes(currentUid) },
        ) { _recipeNotes.value = it.sortedBy(RecipeNote::recipeId) }
        observeQuery(
            customRecipesHealth,
            CustomRecipe::class.java,
            { currentUid ->
                customRecipeDocuments(currentUid)
                    .orderBy(UPDATED_AT_FIELD, Query.Direction.DESCENDING)
            },
        ) { _customRecipes.value = it }
        observeQuery(
            cookedHistoryHealth,
            CookedMeal::class.java,
            { currentUid ->
                cookedHistoryDocuments(currentUid)
                    .orderBy(COMPLETED_AT_FIELD, Query.Direction.DESCENDING)
            },
        ) { _cookedHistory.value = it }
    }

    override suspend fun ensureReady() {
        val state = backendState.first { candidate -> candidate is BackendState.Cloud || candidate is BackendState.Error }
        if (state is BackendState.Error) throw BackendUnavailableException(state.failure)
    }

    override suspend fun getRecipeDetails(recipeId: String): Recipe? {
        val safeRecipeId = requireSafeRecipeDocumentId(recipeId)
        val currentUid = awaitUid()
        if (safeRecipeId.isCustomRecipeId()) {
            val cached = _customRecipes.value.firstOrNull { it.id == safeRecipeId }
            if (cached != null) return cached.takeIf(CustomRecipe::active)?.toRecipe()

            val snapshot = customRecipeDocuments(currentUid)
                .document(safeRecipeId)
                .get(Source.SERVER)
                .await()
            if (!snapshot.exists()) return null
            val custom = snapshot.toObjectOrLog(CustomRecipe::class.java) ?: return null
            if (custom.id.isNotBlank() && custom.id != safeRecipeId) return null
            return custom.copy(id = safeRecipeId).takeIf(CustomRecipe::active)?.toRecipe()
        }

        var attempt = 0L
        while (true) {
            try {
                val snapshot = withTimeout(DETAIL_READ_TIMEOUT_MILLIS) {
                    firestore.collection(RECIPE_DETAILS_COLLECTION)
                        .document(safeRecipeId)
                        .get(Source.SERVER)
                        .await()
                }
                if (!snapshot.exists()) return null
                return eligibleRecipeDetails(
                    recipe = snapshot.toObjectOrLog(Recipe::class.java),
                    documentId = snapshot.id,
                    requestedId = safeRecipeId,
                )
            } catch (error: CancellationException) {
                if (error !is TimeoutCancellationException) throw error
                val failure = classifyFirebaseFailure(error)
                if (attempt >= DETAIL_READ_MAX_RETRIES || failure.isRetryable == false) {
                    throw BackendUnavailableException(failure)
                }
                Log.w(FIRESTORE_TAG, "Recipe details $safeRecipeId", error)
                delay(retryDelayMillis(attempt++))
            } catch (error: Exception) {
                val failure = classifyFirebaseFailure(error)
                if (attempt >= DETAIL_READ_MAX_RETRIES || failure.isRetryable == false) {
                    throw BackendUnavailableException(failure)
                }
                Log.w(FIRESTORE_TAG, "Recipe details $safeRecipeId", error)
                delay(retryDelayMillis(attempt++))
            }
        }
    }

    override suspend fun upsertMealPlan(plan: DayMealPlan) {
        require(plan.date.isNotBlank())
        val currentUid = awaitUid()
        val batch = firestore.batch()
        batch.set(mealPlans(currentUid).document(plan.date), plan.toFirestoreDocument())
        val historyDocument = cookedHistoryDocuments(currentUid).document(plan.date)
        if (plan.completed) {
            batch.set(historyDocument, plan.toCookedMealDocument(plan.updatedAtEpochMillis))
        } else {
            batch.delete(historyDocument)
        }
        batch.commit().await()
    }

    override suspend fun setMealCompleted(date: String, completed: Boolean) {
        require(date.isNotBlank())
        val currentUid = awaitUid()
        val planDocument = mealPlans(currentUid).document(date)
        val historyDocument = cookedHistoryDocuments(currentUid).document(date)
        val now = System.currentTimeMillis()
        firestore.runTransaction { transaction ->
            val plan = transaction.get(planDocument)
            check(plan.exists()) { "Cannot complete a meal plan that does not exist: $date" }
            val recipeId = checkNotNull(plan.getString(RECIPE_ID_FIELD))
            val recipeTitle = checkNotNull(plan.getString(RECIPE_TITLE_FIELD))
            transaction.update(
                planDocument,
                mapOf(
                    COMPLETED_FIELD to completed,
                    UPDATED_AT_FIELD to now,
                ),
            )
            if (completed) {
                transaction.set(
                    historyDocument,
                    cookedMealDocument(
                        date = date,
                        recipeId = recipeId,
                        recipeTitle = recipeTitle,
                        completedAtEpochMillis = now,
                    ),
                )
            } else {
                transaction.delete(historyDocument)
            }
        }.await()
    }

    override suspend fun toggleFavorite(recipeId: String): Boolean {
        requireSafeRecipeDocumentId(recipeId)
        val currentUid = awaitUid()
        val document = favorites(currentUid).document(recipeId)
        return firestore.runTransaction { transaction ->
            if (transaction.get(document).exists()) {
                transaction.delete(document)
                false
            } else {
                transaction.set(
                    document,
                    FavoriteRecipe(
                        recipeId = recipeId,
                        addedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
                true
            }
        }.await()
    }

    override suspend fun upsertShoppingItems(items: List<ShoppingListItem>) {
        if (items.isEmpty()) return
        require(items.size <= MAX_SHOPPING_ITEMS_PER_WRITE)
        items.forEach(ShoppingListItem::requireValid)
        val currentUid = awaitUid()
        val batch = firestore.batch()
        items.forEach { item ->
            batch.set(
                shoppingItems(currentUid).document(item.id),
                item.toFirestoreDocument(),
            )
        }
        batch.commit().await()
    }

    override suspend fun setShoppingItemChecked(itemId: String, checked: Boolean) {
        val safeItemId = requireSafeRecipeDocumentId(itemId)
        val currentUid = awaitUid()
        shoppingItems(currentUid).document(safeItemId).update(
            mapOf(
                CHECKED_FIELD to checked,
                UPDATED_AT_FIELD to System.currentTimeMillis(),
            ),
        ).await()
    }

    override suspend fun deleteShoppingItem(itemId: String) {
        val safeItemId = requireSafeRecipeDocumentId(itemId)
        val currentUid = awaitUid()
        shoppingItems(currentUid).document(safeItemId).delete().await()
    }

    override suspend fun clearCheckedShoppingItems() {
        val currentUid = awaitUid()
        val checkedDocuments = shoppingItems(currentUid)
            .whereEqualTo(CHECKED_FIELD, true)
            .get()
            .await()
            .documents
        checkedDocuments.chunked(FIRESTORE_BATCH_LIMIT).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    override suspend fun upsertRecipeNote(note: RecipeNote) {
        val recipeId = requireSafeRecipeDocumentId(note.recipeId.ifBlank { note.id })
        require(note.id.isBlank() || note.id == recipeId)
        val currentUid = awaitUid()
        val document = recipeNotes(currentUid).document(recipeId)
        if (note.text.isBlank()) {
            document.delete().await()
        } else {
            document.set(note.copy(id = recipeId, recipeId = recipeId).requireValid().toFirestoreDocument())
                .await()
        }
    }

    override suspend fun upsertCustomRecipe(recipe: CustomRecipe) {
        recipe.requireValid()
        val currentUid = awaitUid()
        customRecipeDocuments(currentUid).document(recipe.id)
            .set(recipe.toFirestoreDocument())
            .await()
    }

    override suspend fun deleteCustomRecipe(recipeId: String) {
        require(recipeId.isCustomRecipeId())
        val currentUid = awaitUid()
        val batch = firestore.batch()
        batch.delete(customRecipeDocuments(currentUid).document(recipeId))
        batch.delete(recipeNotes(currentUid).document(recipeId))
        batch.delete(favorites(currentUid).document(recipeId))
        batch.commit().await()
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

    private fun <T : Any> observeQuery(
        health: MutableStateFlow<CloudComponentState>,
        type: Class<T>,
        queryFactory: (String) -> Query,
        publish: (List<T>) -> Unit,
    ) {
        scope.launch {
            uid.flatMapLatest { currentUid ->
                if (currentUid == null) {
                    health.value = CloudComponentState.Pending
                    flowOf(currentUid to emptyList<T>())
                } else {
                    health.value = CloudComponentState.Pending
                    resilientObjectsFlow(health, type, queryFactory(currentUid))
                        .onStart { emit(emptyList()) }
                        .map { values -> currentUid to values }
                }
            }.collect { (sourceUid, values) ->
                // Cancellation and UID updates happen on different threads. An already-converting
                // snapshot from the previous account must never repopulate owner state after the
                // synchronous clear in publishAuthenticatedUser().
                if (uid.value == sourceUid) publish(values)
            }
        }
    }

    private fun <T : Any> resilientObjectsFlow(
        health: MutableStateFlow<CloudComponentState>,
        type: Class<T>,
        query: Query,
    ): Flow<List<T>> = query.objectsFlow(type)
        .onEach { health.value = CloudComponentState.Ready }
        .retryWhen { error, attempt -> retryQuery(health, type, error, attempt) }
        .catch { error ->
            if (error is CancellationException) throw error
            health.value = CloudComponentState.Failed(classifyFirebaseFailure(error))
            emit(emptyList())
        }

    private suspend fun retryQuery(
        health: MutableStateFlow<CloudComponentState>,
        type: Class<*>,
        error: Throwable,
        attempt: Long,
    ): Boolean {
        if (error is CancellationException) return false
        val failure = classifyFirebaseFailure(error)
        health.value = CloudComponentState.Failed(failure)
        return finishRetry(health, type, error, failure, attempt)
    }

    private suspend fun finishRetry(
        health: MutableStateFlow<CloudComponentState>,
        type: Class<*>,
        error: Throwable,
        failure: BackendFailure,
        attempt: Long,
    ): Boolean {
        Log.w(FIRESTORE_TAG, type.simpleName, error)
        if (failure.isRetryable == false) return false
        delay(retryDelayMillis(attempt))
        health.value = CloudComponentState.Pending
        return true
    }

    private suspend fun awaitUid(): String {
        ensureReady()
        return requireNotNull(auth.currentUser?.uid ?: uid.value)
    }

    private fun publishAuthenticatedUser(user: FirebaseUser?) {
        val previousUid = uid.value
        val nextUid = user?.uid
        if (previousUid != nextUid) {
            clearOwnerState()
            markOwnerComponentsPending()
        }
        uid.value = nextUid
        _accountState.value = user.toAccountState()
        authHealth.value = if (user == null) CloudComponentState.Pending else CloudComponentState.Ready
    }

    private fun clearOwnerState() {
        _mealPlans.value = emptyList()
        _favoriteRecipeIds.value = emptySet()
        _shoppingItems.value = emptyList()
        _recipeNotes.value = emptyList()
        _customRecipes.value = emptyList()
        _cookedHistory.value = emptyList()
    }

    private fun markOwnerComponentsPending() {
        mealPlansHealth.value = CloudComponentState.Pending
        favoritesHealth.value = CloudComponentState.Pending
        shoppingHealth.value = CloudComponentState.Pending
        notesHealth.value = CloudComponentState.Pending
        customRecipesHealth.value = CloudComponentState.Pending
        cookedHistoryHealth.value = CloudComponentState.Pending
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

    private fun mealPlans(uid: String) =
        firestore.collection(USER_ROOT_COLLECTION).document(uid).collection(MEAL_PLANS_COLLECTION)

    private fun favorites(uid: String) =
        firestore.collection(USER_ROOT_COLLECTION).document(uid).collection(FAVORITES_COLLECTION)

    private fun shoppingItems(uid: String) =
        firestore.collection(USER_ROOT_COLLECTION).document(uid).collection(SHOPPING_ITEMS_COLLECTION)

    private fun recipeNotes(uid: String) =
        firestore.collection(USER_ROOT_COLLECTION).document(uid).collection(RECIPE_NOTES_COLLECTION)

    private fun customRecipeDocuments(uid: String) =
        firestore.collection(USER_ROOT_COLLECTION).document(uid).collection(CUSTOM_RECIPES_COLLECTION)

    private fun cookedHistoryDocuments(uid: String) =
        firestore.collection(USER_ROOT_COLLECTION).document(uid).collection(COOKED_HISTORY_COLLECTION)

    companion object {
        const val RECIPES_COLLECTION = "spoon_recipes"
        const val RECIPE_DETAILS_COLLECTION = "spoon_recipe_details"
        const val USER_ROOT_COLLECTION = "spoon"
        const val MEAL_PLANS_COLLECTION = "mealPlans"
        const val FAVORITES_COLLECTION = "favorites"
        const val SHOPPING_ITEMS_COLLECTION = "shoppingItems"
        const val RECIPE_NOTES_COLLECTION = "recipeNotes"
        const val CUSTOM_RECIPES_COLLECTION = "customRecipes"
        const val COOKED_HISTORY_COLLECTION = "cookedHistory"
        private const val ACTIVE_FIELD = "active"
        private const val LANGUAGE_FIELD = "language"
        private const val DATE_FIELD = "date"
        private const val CREATED_AT_FIELD = "createdAtEpochMillis"
        private const val UPDATED_AT_FIELD = "updatedAtEpochMillis"
        private const val COMPLETED_AT_FIELD = "completedAtEpochMillis"
        private const val COMPLETED_FIELD = "completed"
        private const val CHECKED_FIELD = "checked"
        private const val RECIPE_ID_FIELD = "recipeId"
        private const val RECIPE_TITLE_FIELD = "recipeTitle"
        private const val GREEK_LANGUAGE = "el"
        private const val FIRESTORE_BATCH_LIMIT = 450
        private const val AUTH_ATTEMPT_TIMEOUT_MILLIS = 20_000L
        private const val DETAIL_READ_TIMEOUT_MILLIS = 20_000L
        private const val DETAIL_READ_MAX_RETRIES = 2L
    }
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
    "updatedAtEpochMillis" to updatedAtEpochMillis,
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

private fun <T : Any> Query.objectsFlow(type: Class<T>): Flow<List<T>> =
    callbackFlow<QuerySnapshot> {
        val registration = addSnapshotListener { snapshot, error ->
            when {
                error != null -> close(error)
                snapshot != null -> trySend(snapshot)
            }
        }
        awaitClose { registration.remove() }
    }
        // State consumers only need the newest pending snapshot. The currently converting snapshot
        // always completes first, so emitted results remain chronological without building a queue.
        .buffer(Channel.CONFLATED)
        .map { snapshot ->
            convertFirestoreSnapshotOffMain(
                source = { snapshot.documents },
                convert = { document -> document.toObjectOrLog(type) },
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
