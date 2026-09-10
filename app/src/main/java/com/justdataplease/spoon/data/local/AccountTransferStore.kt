package com.justdataplease.spoon.data.local

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A durable intent permits recovery if the process dies after email sign-in but before local claim. */
@Serializable
internal data class PendingAccountTransfer(val anonymousUid: String, val email: String) {
    /** Call only with the actual Firebase user's email and anonymous flag after successful sign-in. */
    fun matchesAuthenticatedAccount(email: String?, isAnonymous: Boolean): Boolean =
        !isAnonymous && email != null && this.email == normalizedTransferEmail(email)
}

internal interface AccountTransferStore {
    fun read(): PendingAccountTransfer?
    fun prepare(anonymousUid: String, email: String)
    fun clear()
}

/** Contains UID and normalized email only; passwords never enter this API or its file. */
internal class NoBackupAccountTransferStore(
    context: Context,
    file: File = File(context.noBackupFilesDir, "pending_account_transfer_v1.json"),
) : AccountTransferStore by PersistedAccountTransferStore(AtomicAccountTransferStorage(file))

internal class InMemoryAccountTransferStore : AccountTransferStore by PersistedAccountTransferStore(
    object : AccountTransferStorage {
        private var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    },
)

/** No cached shortcut: each read sees the persisted intent, including after another instance restarts. */
internal class PersistedAccountTransferStore(private val storage: AccountTransferStorage) : AccountTransferStore {
    @Synchronized
    override fun read(): PendingAccountTransfer? = storage.read()?.let { payload ->
        Json.decodeFromString<PendingAccountTransfer>(payload).also { pending ->
            require(pending.anonymousUid.isNotBlank()) { "The pending account transfer has no source owner" }
            require(pending.email.isNotBlank() && pending.email == normalizedTransferEmail(pending.email)) {
                "The pending account transfer has an invalid destination email"
            }
        }
    }

    @Synchronized
    override fun prepare(anonymousUid: String, email: String) {
        require(anonymousUid.isNotBlank())
        val normalizedEmail = normalizedTransferEmail(email)
        require(normalizedEmail.isNotBlank())
        storage.write(Json.encodeToString(PendingAccountTransfer(anonymousUid, normalizedEmail)))
    }

    @Synchronized
    override fun clear() = storage.clear()
}

internal interface AccountTransferStorage {
    /** null means that no intent exists; read and corruption failures must throw. */
    fun read(): String?
    fun write(value: String)
    fun clear()
}

private fun normalizedTransferEmail(email: String): String = email.trim().lowercase(Locale.ROOT)

private class AtomicAccountTransferStorage(private val baseFile: File) : AccountTransferStorage {
    private val file = AtomicFile(baseFile)

    override fun read(): String? {
        val stream = try {
            file.openRead()
        } catch (failure: FileNotFoundException) {
            if (!baseFile.exists() && !File(baseFile.path + ".bak").exists()) return null
            throw failure
        }
        return stream.use {
            if (it.channel.size() > 16_384L) throw IOException("The pending account transfer file is unexpectedly large")
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(it.readBytes())).toString()
        }
    }

    override fun write(value: String) {
        val stream = file.startWrite()
        try {
            stream.write(value.toByteArray(Charsets.UTF_8))
            // AtomicFile logs some sync/rename failures internally. Explicit fsync and readback
            // ensure prepare cannot report success while the transfer intent is only in memory.
            stream.fd.sync()
            file.finishWrite(stream)
            if (read() != value) throw IOException("The pending account transfer was not committed")
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    override fun clear() {
        file.delete()
        if (baseFile.exists() || File(baseFile.path + ".bak").exists() || File(baseFile.path + ".new").exists()) {
            throw IOException("The completed account transfer could not be cleared")
        }
    }
}
