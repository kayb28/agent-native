package com.example.agentnative

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
fun AgentScreen(viewModel: AgentViewModelContract = viewModel<AgentViewModel>()) {
    val state by viewModel.state.collectAsState()
    val history by viewModel.history.collectAsState()
    var query by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var urlField by remember { mutableStateOf(viewModel.getApiUrl()) }
    var tokenField by remember { mutableStateOf(viewModel.getApiToken()) }
    val listState = rememberLazyListState()

    // Auto-scroll al final cuando llega un mensaje nuevo o cambia el estado.
    LaunchedEffect(history.size, state) {
        if (history.isNotEmpty()) {
            listState.animateScrollToItem(history.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
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

        // --- Lista de conversación (memoria visible) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (history.isEmpty()) {
                Text(
                    text = "Aún no hay mensajes. Escribe una consulta y pulsa «Ejecutar agente».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(history) { msg ->
                        MessageBubble(msg)
                    }
                    if (state is UiState.Running) {
                        item {
                            Text(
                                text = "· · ·  pensando",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Consulta para el agente") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    viewModel.runQuery(query)
                    query = ""
                },
                enabled = query.isNotBlank() && state !is UiState.Running,
                modifier = Modifier.weight(1f)
            ) {
                Text("Ejecutar agente")
            }
            OutlinedButton(
                onClick = { viewModel.clearHistory() },
                enabled = history.isNotEmpty() && state !is UiState.Running,
                modifier = Modifier.weight(1f)
            ) {
                Text("Limpiar")
            }
        }

        when (val current = state) {
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
            else -> Unit
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
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
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

/** Burbuja de mensaje: alineación + color según rol. */
@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    val bg = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val fg = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        SelectionContainer {
            Text(
                text = msg.content,
                color = fg,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (isUser) FontFamily.Default else FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
