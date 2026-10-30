package com.example.oeil

import android.app.Activity
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.oeil.data.AuthClient
import com.example.oeil.data.AuthSession
import com.example.oeil.data.BackendReplySource
import com.example.oeil.data.local.OeilDatabase
import com.example.oeil.model.User
import com.example.oeil.screen.AuthScreen
import com.example.oeil.screen.OeilScreen
import com.example.oeil.screen.SettingsScreen
import com.example.oeil.ui.theme.BackgroundDark
import com.example.oeil.ui.theme.GreenPrimary
import com.example.oeil.ui.theme.OeilTheme
import com.example.oeil.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.view.WindowCompat
import java.io.IOException

/** Composition root for local state, account settings and the chat screen. */
@Composable
fun OeilApp(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val database = remember { OeilDatabase(context) }
    val scope = rememberCoroutineScope()

    var booting by remember { mutableStateOf(true) }
    var user by remember { mutableStateOf<User?>(null) }
    var backendUrl by remember { mutableStateOf("") }
    var authToken by remember { mutableStateOf<String?>(null) }
    var themeMode by remember { mutableStateOf("Dark") }
    var showSettings by remember { mutableStateOf(false) }
    var showAuth by remember { mutableStateOf(false) }
    var authBusy by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var accountError by remember { mutableStateOf<String?>(null) }
    val darkTheme = themeMode == "Dark" ||
        (themeMode == "System" && androidx.compose.foundation.isSystemInDarkTheme())
    val view = LocalView.current

    SideEffect {
        val activity = view.context as? Activity
        if (activity != null) {
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    LaunchedEffect(Unit) {
        val savedState = withContext(Dispatchers.IO) {
            Triple(
                database.localUser(),
                database.getSetting(BACKEND_URL_SETTING).orEmpty(),
                database.getSetting(THEME_MODE_SETTING) ?: "Dark",
            ) to database.getSetting(AUTH_TOKEN_SETTING)
        }
        user = savedState.first.first
        backendUrl = savedState.first.second
        themeMode = savedState.first.third
        authToken = savedState.second
        booting = false
    }

    OeilTheme(darkTheme = darkTheme) {
        when {
            booting -> BootScreen(modifier = modifier)
            showAuth -> AuthScreen(
                busy = authBusy,
                externalError = authError,
                onBack = {
                    showAuth = false
                    authError = null
                },
                onSubmit = { name, email, password, signUp ->
                    authError = null
                    authBusy = true
                    scope.launch {
                        try {
                            val session = withContext(Dispatchers.IO) {
                                val client = AuthClient(backendUrl)
                                if (signUp) client.signUp(name, email, password)
                                else client.signIn(email, password)
                            }
                            acceptSession(session, database) { account, token ->
                                user = account
                                authToken = token
                                showAuth = false
                                showSettings = true
                            }
                        } catch (requestError: IOException) {
                            authError = requestError.message ?: "Could not sign in."
                        } finally {
                            authBusy = false
                        }
                    }
                },
                modifier = modifier,
            )
            showSettings -> SettingsScreen(
                initialBackendUrl = backendUrl,
                user = requireNotNull(user),
                themeMode = themeMode,
                onBack = { showSettings = false },
                onSaveBackendUrl = { updatedUrl ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            database.setSetting(BACKEND_URL_SETTING, updatedUrl)
                        }
                        backendUrl = updatedUrl
                        accountError = null
                    }
                },
                onThemeModeChange = { selectedMode ->
                    themeMode = selectedMode
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            database.setSetting(THEME_MODE_SETTING, selectedMode)
                        }
                    }
                },
                onSignIn = {
                    authError = null
                    showAuth = true
                },
                onSignOut = {
                    val token = authToken
                    scope.launch {
                        val revokeError = try {
                            if (token != null) {
                                withContext(Dispatchers.IO) { AuthClient(backendUrl).signOut(token) }
                            }
                            null
                        } catch (requestError: IOException) {
                            requestError
                        }
                        val localUser = withContext(Dispatchers.IO) {
                            database.setSetting(AUTH_TOKEN_SETTING, "")
                            database.clearSession()
                            database.localUser()
                        }
                        user = localUser
                        authToken = null
                        accountError = revokeError?.let {
                            "Signed out on this device, but the server could not revoke the session: " +
                                (it.message ?: "network error")
                        }
                    }
                },
                accountError = accountError,
                modifier = modifier,
            )
            else -> {
                val activeUser = requireNotNull(user)
                val replySource = remember(backendUrl) { BackendReplySource(backendUrl) }
                OeilScreen(
                    user = activeUser,
                    replySource = replySource,
                    store = database,
                    onOpenSettings = { showSettings = true },
                    modifier = modifier,
                )
            }
        }
    }
}

private suspend fun acceptSession(
    session: AuthSession,
    database: OeilDatabase,
    onAccepted: (User, String) -> Unit,
) {
    withContext(Dispatchers.IO) {
        database.saveUser(session.user)
        database.setActiveUser(session.user.id)
        database.setSetting(AUTH_TOKEN_SETTING, session.token)
    }
    onAccepted(session.user, session.token)
}

@Composable
private fun BootScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Oeil",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(20.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = GreenPrimary,
                strokeWidth = 2.dp,
            )
        }
    }
}

private const val BACKEND_URL_SETTING = "backend_url"
private const val THEME_MODE_SETTING = "theme_mode"
private const val AUTH_TOKEN_SETTING = "auth_token"
