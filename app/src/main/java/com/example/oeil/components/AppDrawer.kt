package com.example.oeil.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.oeil.model.ChatThread
import com.example.oeil.model.User
import com.example.oeil.ui.theme.OutlineDark
import com.example.oeil.ui.theme.SurfaceDrawer
import com.example.oeil.ui.theme.SurfaceDrawerItem
import com.example.oeil.ui.theme.SurfaceElevated
import com.example.oeil.ui.theme.TextPrimary
import com.example.oeil.ui.theme.TextSecondary

/**
 * Navigation drawer over the app's own conversations: new chat, search that
 * really filters, the real recent list, and a destructive clear.
 *
 * The header and footer stay put while the conversation list scrolls, so the
 * drawer never jumps when it opens or when the search field appears.
 */
@Composable
fun AppDrawer(
    user: User,
    threads: List<ChatThread>,
    activeThreadId: String?,
    onNewChat: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val matches = if (query.isBlank()) {
        threads
    } else {
        threads.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .background(SurfaceDrawer)
            .windowInsetsPadding(
                WindowInsets.systemBars.only(
                    WindowInsetsSides.Vertical + WindowInsetsSides.Start
                )
            )
    ) {
        DrawerHeader(
            searching = searching,
            onToggleSearch = {
                searching = !searching
                if (!searching) query = ""
            },
            onClose = onClose
        )

        if (searching) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                onCancel = {
                    query = ""
                    searching = false
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        DrawerRow(
            icon = Icons.Outlined.Edit,
            label = "New chat",
            highlighted = true,
            onClick = {
                onNewChat()
                onClose()
            }
        )

        DrawerRow(
            icon = Icons.Outlined.Settings,
            label = "Settings",
            onClick = {
                onOpenSettings()
                onClose()
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        DrawerRow(
            icon = Icons.Outlined.DeleteSweep,
            label = "Clear conversations",
            onClick = {
                onClearAll()
                query = ""
                onClose()
            }
        )

        Spacer(modifier = Modifier.height(12.dp))
        SectionDivider()
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = if (query.isBlank()) "Recent" else "Results",
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            when {
                threads.isEmpty() -> item(key = "empty") {
                    EmptyNote("No conversations yet")
                }

                matches.isEmpty() -> item(key = "no-match") {
                    EmptyNote("Nothing matches “${query.trim()}”")
                }

                else -> items(matches, key = { it.id }) { thread ->
                    HistoryRow(
                        thread = thread,
                        active = thread.id == activeThreadId,
                        onClick = { onOpenThread(thread.id) }
                    )
                }
            }
        }

        SectionDivider()
        DrawerFooter(user = user, conversationCount = threads.size)
    }
}

@Composable
private fun DrawerHeader(
    searching: Boolean,
    onToggleSearch: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Oeil",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            fontSize = 18.sp
        )

        Spacer(modifier = Modifier.weight(1f))

        HeaderIcon(
            icon = Icons.Default.Search,
            contentDescription = if (searching) "Hide search" else "Search conversations",
            onClick = onToggleSearch
        )

        Spacer(modifier = Modifier.width(4.dp))

        HeaderIcon(
            icon = Icons.Default.Close,
            contentDescription = "Close menu",
            onClick = onClose
        )
    }
}

@Composable
private fun HeaderIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    OeilTouch(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val focus = remember { FocusRequester() }

    // Without this the composer behind the drawer keeps focus and swallows
    // every keystroke meant for the search.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(shape)
            .background(SurfaceElevated)
            .border(1.dp, OutlineDark, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
            cursorBrush = SolidColor(TextPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = "Search conversations",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                    inner()
                }
            }
        )

        if (value.isNotEmpty()) {
            OeilTouch(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear search",
                    tint = TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun DrawerRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false
) {
    val shape = RoundedCornerShape(24.dp)
    OeilTouch(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        shape = shape,
        color = if (highlighted) SurfaceDrawerItem else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (highlighted) TextPrimary else TextSecondary,
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (highlighted) FontWeight.Medium else FontWeight.Normal,
                color = if (highlighted) TextPrimary else TextPrimary.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
        modifier = Modifier.padding(horizontal = 26.dp, vertical = 10.dp)
    )
}

@Composable
private fun HistoryRow(
    thread: ChatThread,
    active: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    OeilTouch(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        shape = shape,
        color = if (active) SurfaceDrawerItem else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (active) TextPrimary else Color.Transparent)
            )

            Spacer(modifier = Modifier.width(11.dp))

            Text(
                text = thread.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                color = TextPrimary.copy(alpha = if (active) 1f else 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.06f))
    )
}

@Composable
private fun DrawerFooter(
    user: User,
    conversationCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(SurfaceDrawerItem),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials(user.name),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (conversationCount == 1) {
                    "1 conversation"
                } else {
                    "$conversationCount conversations"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

    }
}

private fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "O"
        parts.size == 1 -> parts.first().take(1).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}
