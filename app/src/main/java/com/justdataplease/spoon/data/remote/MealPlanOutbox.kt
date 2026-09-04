package com.justdataplease.spoon.data.remote

import android.content.Context
import android.util.AtomicFile
import com.justdataplease.spoon.data.model.DayMealPlan
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Device-local journal for meal-plan writes that Firestore has not acknowledged yet.
 *
 * The ownerless bucket is used only while Firebase has not established an identity. It is moved
 * atomically to the first authenticated owner, while owner buckets use opaque hashes so plans from
 * one account are never exposed to another account on the same device.
 */
internal interface MealPlanOutbox {
    fun ownerlessPlans(): List<DayMealPlan>
    fun pendingPlans(ownerUid: String): List<DayMealPlan>
    fun claimOwnerless(ownerUid: String): List<DayMealPlan>
    fun upsertOwnerless(plan: DayMealPlan): Boolean
    fun upsertForOwner(ownerUid: String, plan: DayMealPlan): Boolean
    fun removeAcknowledged(ownerUid: String, plan: DayMealPlan): Boolean
}

/** Small I/O boundary that keeps the outbox state machine testable without Android runtime APIs. */
internal interface MealPlanOutboxStorage {
    fun readUtf8(maxBytes: Long): String?
    fun writeUtf8(bytes: ByteArray): Boolean
}

internal class NoBackupMealPlanOutbox(
    private val storage: MealPlanOutboxStorage,
    private val json: Json,
) : MealPlanOutbox {
    constructor(context: Context, json: Json) : this(
        storage = AtomicMealPlanOutboxStorage(File(context.noBackupFilesDir, FILE_NAME)),
        json = json,
    )

    @Synchronized
    override fun ownerlessPlans(): List<DayMealPlan> = read().ownerless.normalizedPlans()

    @Synchronized
    override fun pendingPlans(ownerUid: String): List<DayMealPlan> {
        require(ownerUid.isNotBlank())
        return read().owners[mealPlanOwnerKey(ownerUid)].orEmpty().normalizedPlans()
    }

    @Synchronized
    override fun claimOwnerless(ownerUid: String): List<DayMealPlan> {
        require(ownerUid.isNotBlank())
        val current = read()
        val ownerKey = mealPlanOwnerKey(ownerUid)
        val merged = mergeMealPlanSnapshots(
            current.owners[ownerKey].orEmpty(),
            current.ownerless,
        )
        if (current.ownerless.isNotEmpty()) {
            check(
                write(
                    current.copy(
                        ownerless = emptyList(),
                        owners = current.owners + (ownerKey to merged),
                    ),
                ),
            ) { "Could not claim ownerless meal plans for the authenticated account" }
        }
        return merged
    }

    @Synchronized
    override fun upsertOwnerless(plan: DayMealPlan): Boolean {
        require(plan.date.isNotBlank())
        val current = read()
        return write(current.copy(ownerless = mergeMealPlanSnapshots(current.ownerless, listOf(plan))))
    }

    @Synchronized
    override fun upsertForOwner(ownerUid: String, plan: DayMealPlan): Boolean {
        require(ownerUid.isNotBlank())
        require(plan.date.isNotBlank())
        val current = read()
        val ownerKey = mealPlanOwnerKey(ownerUid)
        val updated = mergeMealPlanSnapshots(current.owners[ownerKey].orEmpty(), listOf(plan))
        return write(current.copy(owners = current.owners + (ownerKey to updated)))
    }

    @Synchronized
    override fun removeAcknowledged(ownerUid: String, plan: DayMealPlan): Boolean {
        require(ownerUid.isNotBlank())
        require(plan.date.isNotBlank())
        val current = read()
        val ownerKey = mealPlanOwnerKey(ownerUid)
        val pending = current.owners[ownerKey].orEmpty()
        val matching = pending.firstOrNull { it.date == plan.date } ?: return true
        if (!mealPlanAcknowledgementSupersedes(matching, plan)) return true
        val remaining = pending.filterNot { it.date == plan.date }
        val owners = if (remaining.isEmpty()) {
            current.owners - ownerKey
        } else {
            current.owners + (ownerKey to remaining)
        }
        return write(current.copy(owners = owners))
    }

    private fun read(): StoredMealPlanOutbox {
        val stored = storage.readUtf8(MAX_FILE_BYTES) ?: return StoredMealPlanOutbox()
        return runCatching {
            json.decodeFromString<StoredMealPlanOutbox>(stored).normalized()
        }.getOrDefault(StoredMealPlanOutbox())
    }

    private fun write(value: StoredMealPlanOutbox): Boolean {
        val bytes = json.encodeToString(value.normalized()).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) return false
        return storage.writeUtf8(bytes)
    }

    private companion object {
        const val FILE_NAME = "meal_plan_outbox_v1.json"
        const val MAX_FILE_BYTES = 4L * 1024L * 1024L
    }
}

private class AtomicMealPlanOutboxStorage(baseFile: File) : MealPlanOutboxStorage {
    private val file = AtomicFile(baseFile)

    override fun readUtf8(maxBytes: Long): String? = runCatching {
        // openRead performs AtomicFile's backup recovery; checking only baseFile first would
        // incorrectly discard a recoverable interrupted write.
        file.openRead().use { stream -> stream.readUtf8WithinLimit(maxBytes) }
    }.getOrNull()

    override fun writeUtf8(bytes: ByteArray): Boolean {
        val stream = runCatching { file.startWrite() }.getOrNull() ?: return false
        return try {
            stream.write(bytes)
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            file.failWrite(stream)
            false
        }
    }
}

/** Reads at most one buffer beyond the limit and never allocates an unbounded String. */
internal fun InputStream.readUtf8WithinLimit(maxBytes: Long): String? {
    if (maxBytes < 0L) return null
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        totalBytes += count
        if (totalBytes > maxBytes) return null
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

@Serializable
private data class StoredMealPlanOutbox(
    val ownerless: List<DayMealPlan> = emptyList(),
    val owners: Map<String, List<DayMealPlan>> = emptyMap(),
) {
    fun normalized(): StoredMealPlanOutbox = copy(
        ownerless = ownerless.normalizedPlans(),
        owners = owners.mapValues { (_, plans) -> plans.normalizedPlans() }
            .filterValues(List<DayMealPlan>::isNotEmpty),
    )
}

/** Later snapshots win equal revisions; a larger revision always wins regardless of source. */
internal fun mergeMealPlanSnapshots(
    vararg snapshots: List<DayMealPlan>,
): List<DayMealPlan> {
    val byDate = linkedMapOf<String, DayMealPlan>()
    snapshots.asSequence().flatten().forEach { candidate ->
        if (candidate.date.isBlank()) return@forEach
        val normalized = candidate.copy(id = candidate.date)
        val current = byDate[candidate.date]
        if (current == null || normalized.updatedAtEpochMillis >= current.updatedAtEpochMillis) {
            byDate[candidate.date] = normalized
        }
    }
    return byDate.values.sortedBy(DayMealPlan::date)
}

/** An equal-revision acknowledgement is valid only for the exact plan that was journaled. */
internal fun mealPlanAcknowledgementSupersedes(
    pending: DayMealPlan,
    acknowledged: DayMealPlan,
): Boolean {
    if (pending.date != acknowledged.date) return false
    if (acknowledged.updatedAtEpochMillis > pending.updatedAtEpochMillis) return true
    if (acknowledged.updatedAtEpochMillis < pending.updatedAtEpochMillis) return false
    return pending.copy(id = pending.date) == acknowledged.copy(id = acknowledged.date)
}

private fun List<DayMealPlan>.normalizedPlans(): List<DayMealPlan> =
    mergeMealPlanSnapshots(this).takeLast(MAX_OUTBOX_PLANS_PER_BUCKET)

internal fun mealPlanOwnerKey(ownerUid: String): String {
    require(ownerUid.isNotBlank())
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(ownerUid.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    return "owner_$digest"
}

private const val MAX_OUTBOX_PLANS_PER_BUCKET = 1_024
