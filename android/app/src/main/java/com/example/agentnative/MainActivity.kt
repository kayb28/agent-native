package com.example.agentnative

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AgentScreen()
            }
        }
    }
}

@Composable
fun AgentScreen(viewModel: AgentViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var urlField by remember { mutableStateOf(viewModel.getApiUrl()) }
    var tokenField by remember { mutableStateOf(viewModel.getApiToken()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Agente Rust Android",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Cliente nativo Kotlin + núcleo Rust (JNI) + API GLM",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Consulta para el agente") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        Button(
            onClick = { viewModel.runQuery(query) },
            enabled = query.isNotBlank() && state !is UiState.Running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ejecutar agente")
        }

        Text(
            text = "Estado: ${state.label()}",
            style = MaterialTheme.typography.titleMedium,
            color = when (state) {
                is UiState.Running -> MaterialTheme.colorScheme.tertiary
                is UiState.Success -> MaterialTheme.colorScheme.primary
                is UiState.Error -> MaterialTheme.colorScheme.error
                UiState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        when (val current = state) {
            is UiState.Success -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            text = current.response,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        )
                    }
                }
            }

            is UiState.Error -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = current.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
            }

            else -> {
                Text(
                    text = "Configura la URL y el token en «Ajustes» la primera vez.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ------------------------------------------------------------------
        // Ajustes: URL de la API y token (guardados en el dispositivo).
        // ------------------------------------------------------------------
        OutlinedButton(
            onClick = { showSettings = !showSettings },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (showSettings) "Cerrar ajustes" else "Ajustes")
        }

        if (showSettings) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = urlField,
                        onValueChange = { urlField = it },
                        label = { Text("URL de la API") },
                        placeholder = { Text("https://preview-xxxx.space-z.ai") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tokenField,
                        onValueChange = { tokenField = it },
                        label = { Text("Token (X-Agent-Token)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = {
                            viewModel.saveSettings(urlField, tokenField)
                            showSettings = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Guardar ajustes")
                    }
                }
            }
        }
    }
}
