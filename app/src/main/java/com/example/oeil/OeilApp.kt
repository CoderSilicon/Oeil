package com.example.oeil

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.oeil.data.BackendReplySource
import com.example.oeil.data.local.OeilDatabase
import com.example.oeil.model.User
import com.example.oeil.screen.OeilScreen
import com.example.oeil.screen.SettingsScreen
import com.example.oeil.ui.theme.BackgroundDark
import com.example.oeil.ui.theme.GreenPrimary
import com.example.oeil.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Composition root for the local profile, backend settings and chat screen. */
@Composable
fun OeilApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val database = remember { OeilDatabase(context) }
    val scope = rememberCoroutineScope()

    var booting by remember { mutableStateOf(true) }
    var user by remember { mutableStateOf<User?>(null) }
    var backendUrl by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val savedState = withContext(Dispatchers.IO) {
            database.localUser() to database.getSetting(BACKEND_URL_SETTING).orEmpty()
        }
        user = savedState.first
        backendUrl = savedState.second
        booting = false
    }

    when {
        booting -> BootScreen(modifier = modifier)
        showSettings -> SettingsScreen(
            initialBackendUrl = backendUrl,
            onBack = { showSettings = false },
            onSave = { updatedUrl ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        database.setSetting(BACKEND_URL_SETTING, updatedUrl)
                    }
                    backendUrl = updatedUrl
                    showSettings = false
                }
            },
            modifier = modifier
        )
        else -> {
            val activeUser = requireNotNull(user)
            val replySource = remember(backendUrl) { BackendReplySource(backendUrl) }
            OeilScreen(
                user = activeUser,
                replySource = replySource,
                store = database,
                onOpenSettings = { showSettings = true },
                modifier = modifier
            )
        }
    }
}

@Composable
private fun BootScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Oeil",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(20.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = GreenPrimary,
                strokeWidth = 2.dp
            )
        }
    }
}

private const val BACKEND_URL_SETTING = "backend_url"
