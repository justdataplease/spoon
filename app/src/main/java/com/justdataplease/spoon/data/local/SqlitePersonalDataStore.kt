package com.justdataplease.spoon.data.local

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** One owner-scoped database outside Android backup, with atomic document and outbox updates. */
internal class SqlitePersonalDataStore private constructor(
    private val storage: SqlitePersonalRowStorage,
) : PersonalDataStore by TransactionalPersonalDataStore(storage), AutoCloseable {
    constructor(
        context: Context,
        databaseFile: File = File(context.noBackupFilesDir, "personal_data.db"),
    ) : this(SqlitePersonalRowStorage(databaseFile, context.applicationContext))

    override fun close() = storage.close()
}

private class SqlitePersonalRowStorage(file: File, context: Context) : PersonalRowStorage, AutoCloseable {
    private val helper = object : SQLiteOpenHelper(
        context, file.absolutePath, null, 1,
        DatabaseErrorHandler {
            // The framework's default handler deletes corrupt databases. Personal history must
            // remain recoverable: surface the error and leave the original files intact.
            throw SQLiteDatabaseCorruptException("The personal data database could not be read")
        },
    ) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.execSQL("PRAGMA synchronous = FULL")
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE personal_documents (
                    owner TEXT NOT NULL, collection TEXT NOT NULL, document_id TEXT NOT NULL,
                    payload TEXT, updated_at INTEGER NOT NULL, revision TEXT,
                    acknowledged INTEGER NOT NULL DEFAULT 0, observed INTEGER NOT NULL DEFAULT 0,
                    imported INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (owner, collection, document_id)
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE personal_imports (
                    owner TEXT NOT NULL, token TEXT NOT NULL, PRIMARY KEY (owner, token)
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("No personal database migration from $oldVersion to $newVersion")
        }
    }.apply { setWriteAheadLoggingEnabled(true) }

    @Synchronized
    override fun <T> transaction(block: PersonalRowTransaction.() -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(SqliteTransaction(db))
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun close() = helper.close()
}

private class SqliteTransaction(private val db: SQLiteDatabase) : PersonalRowTransaction {
    override fun rows(owner: String): List<PersonalRow> = query("owner = ?", arrayOf(owner))

    override fun row(owner: String, key: PersonalRowKey): PersonalRow? = query(
        "owner = ? AND collection = ? AND document_id = ?",
        arrayOf(owner, key.collection.name, key.documentId),
    ).singleOrNull()

    override fun pendingRows(owner: String): List<PersonalRow> = query(
        "owner = ? AND revision IS NOT NULL", arrayOf(owner),
    )

    private fun query(selection: String, arguments: Array<String>): List<PersonalRow> = db.query(
        "personal_documents",
        arrayOf("collection", "document_id", "payload", "updated_at", "revision", "acknowledged", "observed", "imported"),
        selection, arguments, null, null, "collection, document_id",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(PersonalRow(
                collection = PersonalCollection.valueOf(cursor.getString(0)),
                documentId = cursor.getString(1),
                payload = if (cursor.isNull(2)) null else cursor.getString(2),
                timestamp = cursor.getLong(3),
                revision = if (cursor.isNull(4)) null else cursor.getString(4),
                acknowledged = cursor.getInt(5) != 0,
                observed = cursor.getInt(6) != 0,
                imported = cursor.getInt(7) != 0,
            ))
        }
    }

    override fun put(owner: String, row: PersonalRow) {
        val values = ContentValues().apply {
            put("owner", owner)
            put("collection", row.collection.name)
            put("document_id", row.documentId)
            put("payload", row.payload)
            put("updated_at", row.timestamp)
            put("revision", row.revision)
            put("acknowledged", if (row.acknowledged) 1 else 0)
            put("observed", if (row.observed) 1 else 0)
            put("imported", if (row.imported) 1 else 0)
        }
        check(db.insertWithOnConflict("personal_documents", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    override fun delete(owner: String, key: PersonalRowKey) {
        db.delete("personal_documents", "owner = ? AND collection = ? AND document_id = ?",
            arrayOf(owner, key.collection.name, key.documentId))
    }

    override fun wasImported(owner: String, token: String): Boolean = db.rawQuery(
        "SELECT 1 FROM personal_imports WHERE owner = ? AND token = ?", arrayOf(owner, token),
    ).use { it.moveToFirst() }

    override fun markImported(owner: String, token: String) {
        val values = ContentValues().apply { put("owner", owner); put("token", token) }
        check(db.insertOrThrow("personal_imports", null, values) != -1L)
    }
}
