package com.example.oeil.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.oeil.data.normalizeBackendUrl
import com.example.oeil.model.User
import com.example.oeil.ui.theme.BackgroundDark
import com.example.oeil.ui.theme.TextPrimary
import com.example.oeil.ui.theme.TextSecondary

private val themeOptions = listOf("System", "Light", "Dark")

@Composable
fun SettingsScreen(
    initialBackendUrl: String,
    user: User,
    themeMode: String,
    onBack: () -> Unit,
    onSaveBackendUrl: (String) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    accountError: String?,
    modifier: Modifier = Modifier,
) {
    var backendUrl by remember(initialBackendUrl) { mutableStateOf(initialBackendUrl) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back", color = TextSecondary) }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Settings",
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Make Oeil feel like yours and manage your account.",
            modifier = Modifier.padding(top = 6.dp),
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )

        SettingsSection(title = "Appearance", subtitle = "Choose how Oeil looks on this device.") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                themeOptions.forEach { option ->
                    if (themeMode == option) {
                        Button(
                            onClick = { onThemeModeChange(option) },
                            modifier = Modifier.weight(1f),
                        ) { Text(option) }
                    } else {
                        OutlinedButton(
                            onClick = { onThemeModeChange(option) },
                            modifier = Modifier.weight(1f),
                        ) { Text(option) }
                    }
                }
            }
        }

        SettingsSection(title = "Account", subtitle = "Your account keeps your profile ready across sessions.") {
            Text(
                text = user.email ?: "Using Oeil on this device",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (user.email == null) {
                    "Sign in to connect an account."
                } else {
                    "Signed in as ${user.name}"
                },
                modifier = Modifier.padding(top = 4.dp),
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            accountError?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            if (user.email == null) {
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign in or create account")
                }
            } else {
                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out")
                }
            }
        }

        SettingsSection(title = "AI connection", subtitle = "Connect Oeil to your Python relay.") {
            OutlinedTextField(
                value = backendUrl,
                onValueChange = {
                    backendUrl = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Backend URL") },
                placeholder = { Text("https://api.example.com") },
                singleLine = true,
                isError = error != null,
            )
            Text(
                text = "Enter the server address, not the /chat path. Leave blank to disconnect.",
                modifier = Modifier.padding(top = 8.dp),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            error?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 8.dp),
                    color = Color(0xFFFF7A7A),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    try {
                        onSaveBackendUrl(normalizeBackendUrl(backendUrl))
                    } catch (invalidUrl: IllegalArgumentException) {
                        error = invalidUrl.message ?: "Enter a valid backend URL."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save backend")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(
            text = title,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = subtitle,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp)) { content() }
        }
    }
}
