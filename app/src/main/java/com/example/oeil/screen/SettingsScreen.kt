package com.example.oeil.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.example.oeil.ui.theme.BackgroundDark
import com.example.oeil.ui.theme.TextPrimary
import com.example.oeil.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    initialBackendUrl: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier
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
        verticalArrangement = Arrangement.Top
    ) {
        TextButton(onClick = onBack) {
            Text("Back", color = TextSecondary)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Settings",
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Connect Oeil to your Python relay. The relay uses your Nebius model and keeps its API key off this device.",
            color = TextSecondary,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = backendUrl,
            onValueChange = {
                backendUrl = it
                error = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Backend URL") },
            placeholder = { Text("http://10.0.2.2:8000") },
            singleLine = true,
            isError = error != null
        )
        Text(
            text = "Enter the server address, not the /chat path. The app adds that automatically. Leave blank to disconnect.",
            modifier = Modifier.padding(top = 8.dp),
            color = TextSecondary,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )
        error?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 8.dp),
                color = Color(0xFFFF7A7A),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                try {
                    onSave(normalizeBackendUrl(backendUrl))
                } catch (invalidUrl: IllegalArgumentException) {
                    error = invalidUrl.message ?: "Enter a valid backend URL."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save")
        }
    }
}
