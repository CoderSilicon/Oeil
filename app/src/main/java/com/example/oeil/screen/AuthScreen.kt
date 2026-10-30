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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.oeil.ui.theme.BackgroundDark
import com.example.oeil.ui.theme.GreenPrimary
import com.example.oeil.ui.theme.TextPrimary
import com.example.oeil.ui.theme.TextSecondary

@Composable
fun AuthScreen(
    busy: Boolean,
    externalError: String?,
    onBack: () -> Unit,
    onSubmit: (name: String, email: String, password: String, signUp: Boolean) -> Unit,
    onOAuth: (provider: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var signUp by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        TextButton(onClick = onBack, enabled = !busy) {
            Text("Back to settings", color = TextSecondary)
        }
        Spacer(modifier = Modifier.height(30.dp))
        Text(
            text = if (signUp) "Create your account" else "Welcome back",
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = if (signUp) {
                "One account for your Oeil experience."
            } else {
                "Sign in to your Oeil account."
            },
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )

        if (signUp) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name") },
                singleLine = true,
                enabled = !busy,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Email") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(modifier = Modifier.height(16.dp))
        externalError?.let {
            Text(
                text = it,
                modifier = Modifier.padding(bottom = 12.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = { onSubmit(name.trim(), email.trim(), password, signUp) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && email.isNotBlank() && password.length >= 8 &&
                (!signUp || name.isNotBlank()),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = GreenPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text(if (signUp) "Create account" else "Sign in")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (signUp) "Already have an account?" else "New to Oeil?",
                color = TextSecondary,
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = { signUp = !signUp },
                enabled = !busy,
            ) {
                Text(if (signUp) "Sign in" else "Create account")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "OR CONTINUE WITH",
            modifier = Modifier.padding(bottom = 12.dp),
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
        listOf("Google" to "google", "Microsoft" to "microsoft", "GitHub" to "github")
            .forEach { (label, provider) ->
                OutlinedButton(
                    onClick = { onOAuth(provider) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    enabled = !busy,
                ) {
                    Text("Continue with $label")
                }
            }
    }
}
