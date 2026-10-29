package com.example.oeil.screen

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.example.oeil.components.AppDrawer
import com.example.oeil.components.ChatHeader
import com.example.oeil.components.ChatInput
import com.example.oeil.components.EmptyState
import com.example.oeil.components.MessageItem
import com.example.oeil.components.OeilTouch
import com.example.oeil.components.TypingIndicator
import com.example.oeil.data.ConversationReplySource
import com.example.oeil.data.local.OeilDatabase
import com.example.oeil.model.User
import com.example.oeil.state.OeilChatState
import com.example.oeil.ui.theme.BackgroundDark
import com.example.oeil.ui.theme.SurfaceDrawer
import com.example.oeil.ui.theme.SurfaceElevated
import com.example.oeil.ui.theme.SurfaceGlass
import com.example.oeil.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * Chat screen of the Oeil app: navigation drawer, minimal header,
 * message history and composer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OeilScreen(
    user: User,
    replySource: ConversationReplySource,
    store: OeilDatabase,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chat = remember(user.id, replySource, store) {
        OeilChatState(
            replySource = replySource,
            store = store,
            userId = user.id
        )
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    val composerFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)

    val notice: (String) -> Unit = { message ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    LaunchedEffect(chat) { chat.loadHistory() }
    DisposableEffect(chat) {
        onDispose { chat.close() }
    }

    FollowLatest(chat = chat, listState = listState)

    // The composer sits behind the drawer. Drop its focus and the keyboard as
    // soon as the drawer starts moving, otherwise the IME fights the drawer
    // animation and the sheet appears to jump.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(drawerState.targetValue) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = SurfaceDrawer,
                drawerContentColor = TextPrimary,
                drawerShape = RectangleShape,
                drawerTonalElevation = 0.dp,
                // Let the drawer paint behind the status bar; AppDrawer applies
                // the insets to its content instead of to the whole sheet.
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                AppDrawer(
                    user = user,
                    threads = chat.threads,
                    activeThreadId = chat.activeThreadId,
                    onNewChat = chat::startNewChat,
                    onOpenThread = chat::openThread,
                    onOpenSettings = onOpenSettings,
                    onClearAll = {
                        chat.clearAll()
                        notice("History cleared")
                    },
                    onClose = { scope.launch { drawerState.close() } }
                )
            }
        }
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(BackgroundDark)
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = {
                    SnackbarHost(hostState = snackbar) { data ->
                        Snackbar(
                            snackbarData = data,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            containerColor = SurfaceElevated,
                            contentColor = TextPrimary,
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                },
                topBar = {
                    ChatHeader(
                        onMenuClick = {
                            scope.launch {
                                if (drawerState.isOpen) drawerState.close()
                                else drawerState.open()
                            }
                        },
                        onNewChat = chat::startNewChat,
                        mode = chat.mode,
                        onModeClick = chat::cycleMode
                    )
                },
                bottomBar = {
                    // Rises with the keyboard instead of hiding under it: at
                    // target 37 the window no longer resizes for us.
                    ChatInput(
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
                        value = chat.input,
                        onValueChange = chat::updateInput,
                        canSend = chat.canSend,
                        isTyping = chat.isTyping,
                        onSend = { chat.send(scope) },
                        onStop = chat::stopGenerating,
                        attachment = chat.draftAttachment,
                        onAttachmentPicked = { chat.attach(it.uri, it.label) },
                        onAttachmentRemoved = chat::clearAttachment,
                        onVoiceTranscript = chat::appendInput,
                        onNotice = notice,
                        focusRequester = composerFocus
                    )
                }
            ) { padding ->
                Conversation(
                    chat = chat,
                    listState = listState,
                    onCopy = { text ->
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Oeil", text))
                        notice("Copied")
                    },
                    onJumpToLatest = {
                        val anchor = chat.anchorIndex
                        if (anchor >= 0) scope.launch { listState.animateScrollToItem(anchor) }
                    },
                    onPickSuggestion = { prompt ->
                        chat.applySuggestion(prompt)
                        // Focus and keyboard both have to be asked for inside the
                        // tap itself; Android ignores IME requests made later.
                        runCatching { composerFocus.requestFocus() }
                        keyboard?.show()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }
        }
    }
}

/**
 * Keeps the newest turn on screen as messages arrive, as a reply starts and
 * lands, when a thread is reopened, and when the keyboard takes the bottom of
 * the viewport away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FollowLatest(chat: OeilChatState, listState: LazyListState) {
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(
        chat.messages.size,
        chat.isTyping,
        chat.activeThreadId,
        imeVisible
    ) {
        val anchor = chat.anchorIndex
        if (anchor >= 0) {
            listState.animateScrollToItem(anchor)
        }
    }
}

@Composable
private fun Conversation(
    chat: OeilChatState,
    listState: LazyListState,
    onCopy: (String) -> Unit,
    onJumpToLatest: () -> Unit,
    onPickSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(chat.messages, key = { it.id }) { message ->
                MessageItem(
                    message = message,
                    onCopy = onCopy,
                    modifier = Modifier.animateItem()
                )
            }

            // No animateItem() here: this row leaves via an `if`, so its exit
            // animation never finishes and the dots stay burned into the frame.
            if (chat.isTyping) {
                item {
                    TypingIndicator()
                }
            }
        }

        if (chat.messages.isEmpty() && !chat.isTyping) {
            EmptyState(
                modifier = Modifier.fillMaxSize(),
                onPick = onPickSuggestion
            )
        }

        JumpToLatest(
            listState = listState,
            onScroll = onJumpToLatest,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

/** Floats only while the newest message is off screen. */
@Composable
private fun JumpToLatest(
    listState: LazyListState,
    onScroll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrolledUp by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) {
                false
            } else {
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
                lastVisible != info.totalItemsCount - 1 || listState.canScrollForward
            }
        }
    }

    AnimatedVisibility(
        visible = scrolledUp,
        enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.8f),
        exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
        modifier = modifier.padding(end = 16.dp, bottom = 8.dp)
    ) {
        OeilTouch(
            onClick = onScroll,
            modifier = Modifier.size(42.dp),
            color = SurfaceGlass
        ) {
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = "Scroll to the latest message",
                tint = TextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
