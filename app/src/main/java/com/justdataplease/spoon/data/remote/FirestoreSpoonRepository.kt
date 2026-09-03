package com.justdataplease.spoon.data.remote

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
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
import com.justdataplease.spoon.data.model.DayMealPlan
import com.justdataplease.spoon.data.model.FavoriteRecipe
import com.justdataplease.spoon.data.model.Recipe
import com.justdataplease.spoon.data.requireSafeRecipeDocumentId
import com.justdataplease.spoon.domain.repository.BackendFailure
import com.justdataplease.spoon.domain.repository.BackendFailureKind
import com.justdataplease.spoon.domain.repository.BackendState
import com.justdataplease.spoon.domain.repository.BackendUnavailableException
import com.justdataplease.spoon.domain.repository.SpoonRepository
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
    private val authHealth = MutableStateFlow<CloudComponentState>(
        if (auth.currentUser == null) CloudComponentState.Pending else CloudComponentState.Ready,
    )
    private val recipesHealth = MutableStateFlow<CloudComponentState>(CloudComponentState.Pending)
    private val mealPlansHealth = MutableStateFlow<CloudComponentState>(CloudComponentState.Pending)
    private val favoritesHealth = MutableStateFlow<CloudComponentState>(CloudComponentState.Pending)
    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    private val _mealPlans = MutableStateFlow<List<DayMealPlan>>(emptyList())
    private val _favoriteRecipeIds = MutableStateFlow<Set<String>>(emptySet())

    @Volatile
    private var authJob: Job? = null

    override val backendState = combine(
        authHealth,
        recipesHealth,
        mealPlansHealth,
        favoritesHealth,
    ) { authState, recipesState, plansState, favoritesState ->
        aggregateCloudState(listOf(authState, recipesState, plansState, favoritesState))
    }.stateIn(scope, SharingStarted.Eagerly, BackendState.Connecting)

    override val recipes = _recipes.asStateFlow()
    override val mealPlans = _mealPlans.asStateFlow()
    override val favoriteRecipeIds = _favoriteRecipeIds.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        uid.value = user?.uid
        if (user == null) {
            authHealth.value = CloudComponentState.Pending
            startAuthentication()
        } else {
            authHealth.value = CloudComponentState.Ready
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
        ) { remote -> _recipes.value = eligibleRemoteRecipes(remote).sortedBy(Recipe::title) }
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
    }

    override suspend fun ensureReady() {
        val state = backendState.first { candidate -> candidate is BackendState.Cloud || candidate is BackendState.Error }
        if (state is BackendState.Error) throw BackendUnavailableException(state.failure)
    }

    override suspend fun getRecipeDetails(recipeId: String): Recipe? {
        val safeRecipeId = requireSafeRecipeDocumentId(recipeId)
        awaitUid()

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
        mealPlans(currentUid).document(plan.date).set(plan.toFirestoreDocument()).await()
    }

    override suspend fun setMealCompleted(date: String, completed: Boolean) {
        require(date.isNotBlank())
        val currentUid = awaitUid()
        val updates = mapOf(
            "completed" to completed,
            "updatedAtEpochMillis" to System.currentTimeMillis(),
        )
        mealPlans(currentUid).document(date).update(updates).await()
    }

    override suspend fun toggleFavorite(recipeId: String): Boolean {
        require(recipeId.isNotBlank())
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

    private fun startAuthentication() {
        auth.currentUser?.let { user ->
            uid.value = user.uid
            authHealth.value = CloudComponentState.Ready
            return
        }
        synchronized(authJobLock) {
            if (authJob?.isActive == true) return
            authJob = scope.launch { authenticateWithRetry() }
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
            uid.value = user.uid
            authHealth.value = CloudComponentState.Ready
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
                    flowOf(emptyList())
                } else {
                    health.value = CloudComponentState.Pending
                    resilientObjectsFlow(health, type, queryFactory(currentUid))
                        .onStart { emit(emptyList()) }
                }
            }.collect(publish)
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

    private fun mealPlans(uid: String) =
        firestore.collection(USER_ROOT_COLLECTION).document(uid).collection(MEAL_PLANS_COLLECTION)

    private fun favorites(uid: String) =
        firestore.collection(USER_ROOT_COLLECTION).document(uid).collection(FAVORITES_COLLECTION)

    companion object {
        const val RECIPES_COLLECTION = "spoon_recipes"
        const val RECIPE_DETAILS_COLLECTION = "spoon_recipe_details"
        const val USER_ROOT_COLLECTION = "spoon"
        const val MEAL_PLANS_COLLECTION = "mealPlans"
        const val FAVORITES_COLLECTION = "favorites"
        private const val ACTIVE_FIELD = "active"
        private const val LANGUAGE_FIELD = "language"
        private const val DATE_FIELD = "date"
        private const val GREEK_LANGUAGE = "el"
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
