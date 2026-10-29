package com.example.oeil.state

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.oeil.data.ConversationReplySource
import com.example.oeil.data.MockConversationReplySource
import com.example.oeil.data.local.OeilDatabase
import com.example.oeil.model.Attachment
import com.example.oeil.model.ChatMessage
import com.example.oeil.model.ChatThread
import com.example.oeil.model.ReplyMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the conversation state for [com.example.oeil.screen.OeilScreen].
 *
 * Kept as a plain class so the screen only deals with layout, while message
 * history, typing flags and reply timing live in one testable place.
 *
 * Replies come from a [ConversationReplySource] (the Python relay in the app, canned text
 * in tests). When a [store] and [userId] are supplied, threads and messages are
 * mirrored into SQLite on a single background lane so writes stay ordered.
 */
class OeilChatState(
    private val replySource: ConversationReplySource = MockConversationReplySource(),
    private val replyDelayMs: Long = REPLY_DELAY_MS,
    private val store: OeilDatabase? = null,
    private val userId: String? = null
) {

    val messages = mutableStateListOf<ChatMessage>()

    /** Conversations shown in the drawer, newest first. Includes the one on screen. */
    val threads = mutableStateListOf<ChatThread>()

    var input by mutableStateOf("")
        private set

    var isTyping by mutableStateOf(false)
        private set

    var mode by mutableStateOf(ReplyMode.Auto)
        private set

    var draftAttachment by mutableStateOf<Attachment?>(null)
        private set

    /** Thread the on-screen draft belongs to, or null for a brand new chat. */
    var activeThreadId by mutableStateOf<String?>(null)
        private set

    @OptIn(ExperimentalCoroutinesApi::class)
    private val persistenceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    val canSend: Boolean
        get() = !isTyping && (input.isNotBlank() || draftAttachment != null)

    /** Row that should sit at the bottom: the typing placeholder, else the last message. */
    val anchorIndex: Int
        get() = if (isTyping) messages.size else messages.size - 1

    /** Restores saved conversations for the signed-in account. */
    suspend fun loadHistory() {
        val database = store ?: return
        val owner = userId ?: return
        val saved = withContext(Dispatchers.IO) { database.threads(owner) }
        val merged = (saved + threads)
            .distinctBy { it.id }
            .sortedByDescending { it.updatedAt }
        threads.clear()
        threads.addAll(merged)
    }

    fun updateInput(value: String) {
        input = value
    }

    /** Adds dictated text without discarding what was already typed. */
    fun appendInput(text: String) {
        input = if (input.isBlank()) text else "${input.trimEnd()} $text"
    }

    /** Pre-fills the composer; the screen raises the keyboard as part of the same tap. */
    fun applySuggestion(suggestion: String) {
        input = suggestion
    }

    fun attach(uri: Uri, label: String) {
        draftAttachment = Attachment(uri, label)
    }

    fun clearAttachment() {
        draftAttachment = null
    }

    fun cycleMode() {
        mode = mode.next()
    }

    fun send(scope: CoroutineScope) {
        val text = input.trim()
        val attachment = draftAttachment
        if (isTyping || (text.isEmpty() && attachment == null)) return

        messages.add(
            ChatMessage(
                content = text.ifEmpty { attachment?.label.orEmpty() },
                isUser = true,
                attachmentLabel = attachment?.label
            )
        )
        input = ""
        draftAttachment = null
        isTyping = true
        syncThread()

        val history = messages.toList()
        val activeMode = mode

        replyJob = scope.launch {
            if (replyDelayMs > 0) delay(replyDelayMs)
            try {
                // Network and blocking clients stay off the main thread.
                val reply = withContext(Dispatchers.IO) {
                    replySource.reply(history, activeMode)
                }
                messages.add(ChatMessage(content = reply, isUser = false))
                isTyping = false
                syncThread()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                messages.add(ChatMessage(content = failureMessage(error), isUser = false))
                isTyping = false
                syncThread()
            }
        }
    }

    /** Abandons the reply in flight; the composer stops offering the stop control. */
    fun stopGenerating() {
        cancelPendingReply()
        isTyping = false
    }

    /** Shows an archived conversation. Nothing is lost: each send already saves the draft. */
    fun openThread(id: String) {
        if (id == activeThreadId) return
        val thread = threads.firstOrNull { it.id == id } ?: return
        cancelPendingReply()
        messages.clear()
        messages.addAll(thread.messages)
        activeThreadId = thread.id
        input = ""
        draftAttachment = null
        isTyping = false
    }

    /** Starts a blank draft; the conversation just on screen stays in [threads]. */
    fun startNewChat() {
        cancelPendingReply()
        messages.clear()
        activeThreadId = null
        input = ""
        draftAttachment = null
        isTyping = false
    }

    /** Drops the whole history and the open draft. */
    fun clearAll() {
        cancelPendingReply()
        threads.clear()
        messages.clear()
        activeThreadId = null
        input = ""
        draftAttachment = null
        isTyping = false
        persistClear()
    }

    /** Releases background persistence when the screen leaves composition. */
    fun close() {
        cancelPendingReply()
        persistenceScope.cancel()
    }

    private fun cancelPendingReply() {
        replyJob?.cancel()
        replyJob = null
    }

    private fun syncThread() {
        if (messages.isEmpty()) return
        val snapshot = messages.toList()
        val now = System.currentTimeMillis()
        val existing = activeThreadId?.let { id -> threads.indexOfFirst { it.id == id } } ?: -1
        if (existing >= 0) {
            val refreshed = threads.removeAt(existing)
                .copy(title = titleOf(snapshot), messages = snapshot, updatedAt = now)
            threads.add(0, refreshed)
            persistThread(refreshed)
        } else {
            val created = ChatThread(title = titleOf(snapshot), messages = snapshot, updatedAt = now)
            activeThreadId = created.id
            threads.add(0, created)
            persistThread(created)
        }
    }

    private fun persistThread(thread: ChatThread) {
        val database = store ?: return
        val owner = userId ?: return
        persistenceScope.launch { database.saveThread(owner, thread) }
    }

    private fun persistClear() {
        val database = store ?: return
        val owner = userId ?: return
        persistenceScope.launch { database.clearThreads(owner) }
    }

    private fun failureMessage(error: Throwable): String {
        val detail = error.message?.trim().orEmpty()
        return if (detail.isBlank()) {
            "I couldn't get a reply from the AI service. Please try again."
        } else {
            "I couldn't get a reply: $detail"
        }
    }

    private fun titleOf(items: List<ChatMessage>): String {
        val first = items.firstOrNull { it.isUser }?.content ?: items.first().content
        val flattened = first.replace('\n', ' ').trim()
        return if (flattened.length <= TITLE_MAX) {
            flattened
        } else {
            flattened.take(TITLE_MAX - 1).trimEnd() + "…"
        }
    }

    private var replyJob: Job? = null

    private companion object {
        const val REPLY_DELAY_MS = 0L
        const val TITLE_MAX = 42
    }
}
