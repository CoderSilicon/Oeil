package com.example.oeil.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.oeil.model.ChatMessage
import com.example.oeil.model.ChatThread
import com.example.oeil.model.User
import java.util.UUID

/**
 * Small SQLite store for the local profile, conversations and settings.
 * Kept dependency-free (plain [SQLiteOpenHelper]) while the data model is moving.
 */
class OeilDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE users (
                id TEXT PRIMARY KEY NOT NULL,
                email TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                token TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE session (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                user_id TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE threads (
                id TEXT PRIMARY KEY NOT NULL,
                user_id TEXT NOT NULL,
                title TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                thread_id TEXT NOT NULL,
                content TEXT NOT NULL,
                is_user INTEGER NOT NULL,
                attachment_label TEXT,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_threads_user ON threads(user_id, updated_at DESC)"
        )
        db.execSQL(
            "CREATE INDEX idx_messages_thread ON messages(thread_id, timestamp ASC)"
        )
        db.execSQL(
            """
            CREATE TABLE settings (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Prototype schema: rebuild rather than carry migration code while the
        // model is still being shaped.
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS threads")
        db.execSQL("DROP TABLE IF EXISTS session")
        db.execSQL("DROP TABLE IF EXISTS users")
        db.execSQL("DROP TABLE IF EXISTS settings")
        onCreate(db)
    }

    fun activeUser(): User? =
        readableDatabase.rawQuery(
            """
            SELECT u.id, u.name, u.email
            FROM users u
            INNER JOIN session s ON s.user_id = u.id
            WHERE s.id = 1
            """.trimIndent(),
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toUser() else null }

    fun localUser(): User {
        val existing = activeUser()
        if (existing != null) return existing
        val id = getSetting(LOCAL_USER_ID_KEY)
            ?: UUID.randomUUID().toString().also { setSetting(LOCAL_USER_ID_KEY, it) }
        val user = User(id = id, name = "On this device")
        saveUser(user)
        setActiveUser(user.id)
        return user
    }

    fun saveUser(user: User) {
        val values = ContentValues().apply {
            put("id", user.id)
            put("email", user.email ?: LOCAL_USER_EMAIL)
            put("name", user.name)
            putNull("token")
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "users",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun setActiveUser(userId: String) {
        val values = ContentValues().apply {
            put("id", 1)
            put("user_id", userId)
        }
        writableDatabase.insertWithOnConflict(
            "session",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun clearSession() {
        writableDatabase.delete("session", null, null)
    }

    fun threads(userId: String): List<ChatThread> {
        val result = mutableListOf<ChatThread>()
        readableDatabase.rawQuery(
            """
            SELECT id, title, updated_at
            FROM threads
            WHERE user_id = ?
            ORDER BY updated_at DESC
            """.trimIndent(),
            arrayOf(userId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                result += ChatThread(
                    id = id,
                    title = cursor.getString(1),
                    messages = messages(id),
                    updatedAt = cursor.getLong(2)
                )
            }
        }
        return result
    }

    fun saveThread(userId: String, thread: ChatThread) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val threadValues = ContentValues().apply {
                put("id", thread.id)
                put("user_id", userId)
                put("title", thread.title)
                put("updated_at", thread.updatedAt)
            }
            db.insertWithOnConflict(
                "threads",
                null,
                threadValues,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.delete("messages", "thread_id = ?", arrayOf(thread.id))
            thread.messages.forEach { message ->
                val messageValues = ContentValues().apply {
                    put("id", message.id)
                    put("thread_id", thread.id)
                    put("content", message.content)
                    put("is_user", if (message.isUser) 1 else 0)
                    put("attachment_label", message.attachmentLabel)
                    put("timestamp", message.timestamp)
                }
                db.insertWithOnConflict(
                    "messages",
                    null,
                    messageValues,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearThreads(userId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                "messages",
                "thread_id IN (SELECT id FROM threads WHERE user_id = ?)",
                arrayOf(userId)
            )
            db.delete("threads", "user_id = ?", arrayOf(userId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getSetting(key: String): String? =
        readableDatabase.rawQuery(
            "SELECT value FROM settings WHERE key = ?",
            arrayOf(key)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun setSetting(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict(
            "settings",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun messages(threadId: String): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        readableDatabase.rawQuery(
            """
            SELECT id, content, is_user, attachment_label, timestamp
            FROM messages
            WHERE thread_id = ?
            ORDER BY timestamp ASC, rowid ASC
            """.trimIndent(),
            arrayOf(threadId)
        ).use { cursor ->
            val attachmentIndex = cursor.getColumnIndexOrThrow("attachment_label")
            while (cursor.moveToNext()) {
                result += ChatMessage(
                    id = cursor.getString(0),
                    content = cursor.getString(1),
                    isUser = cursor.getInt(2) == 1,
                    attachmentLabel = if (cursor.isNull(attachmentIndex)) {
                        null
                    } else {
                        cursor.getString(attachmentIndex)
                    },
                    timestamp = cursor.getLong(4)
                )
            }
        }
        return result
    }

    private fun Cursor.toUser(): User {
        return User(
            id = getString(getColumnIndexOrThrow("id")),
            name = getString(getColumnIndexOrThrow("name")),
            email = getString(getColumnIndexOrThrow("email"))
                .takeUnless { it == LOCAL_USER_EMAIL }
        )
    }

    private companion object {
        const val DATABASE_NAME = "oeil.db"
        const val DATABASE_VERSION = 1
        const val LOCAL_USER_ID_KEY = "local_user_id"
        const val LOCAL_USER_EMAIL = "on-device@oeil.local"
    }
}
