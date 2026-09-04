package com.justdataplease.spoon.data.remote

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

internal interface OwnerBootstrapStore {
    fun isComplete(ownerUid: String): Boolean
    fun markComplete(ownerUid: String): Boolean
}

/**
 * A per-device marker stored under noBackupFilesDir. It must never be restored onto a new phone
 * without Firestore's device-local cache, otherwise an empty restored cache could be trusted.
 */
internal class NoBackupOwnerBootstrapStore(
    context: Context,
) : OwnerBootstrapStore {
    private val directory = context.noBackupFilesDir

    override fun isComplete(ownerUid: String): Boolean =
        markerFile(directory, ownerUid).isFile

    @Synchronized
    override fun markComplete(ownerUid: String): Boolean {
        val marker = AtomicFile(markerFile(directory, ownerUid))
        val stream = runCatching { marker.startWrite() }.getOrNull() ?: return false
        return try {
            stream.write(1)
            marker.finishWrite(stream)
            true
        } catch (_: Exception) {
            marker.failWrite(stream)
            false
        }
    }
}

internal fun ownerBootstrapMarkerName(ownerUid: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(ownerUid.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    return "owner_bootstrap_$digest"
}

private fun markerFile(directory: File, ownerUid: String): File =
    File(directory, ownerBootstrapMarkerName(ownerUid))
