package com.example.agentnative

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.AndroidViewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests de UI en Compose del flujo principal.
 *
 * Para no depender del `.so` de Rust (que solo existe si se compiló antes
 * con cargo-ndk y se cargó vía JNI en un emulador real), estos tests usan
 * un ViewModel falso ([FakeAgentViewModel]) con la misma forma pública que
 * [AgentViewModel]. Así se prueba la UI sin acoplamiento al nativo.
 *
 * Qué cubre:
 *  - estado inicial (Idle) y mensaje de ayuda cuando no hay mensajes.
 *  - botón «Ejecutar agente» deshabilitado cuando la consulta está vacía.
 *  - sin URL configurada, al pulsar aparece el error correspondiente.
 *  - tras una respuesta exitosa, el historial se pinta (memoria visible).
 *  - el botón «Limpiar» vacía la conversación.
 */
@RunWith(AndroidJUnit4::class)
class AgentScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun estado_inicial_muestra_titulo_y_ayuda_y_boton_deshabilitado() {
        composeRule.setContent {
            MaterialTheme {
                AgentScreen(viewModel = FakeAgentViewModel(ApplicationProvider.getApplicationContext()))
            }
        }
        composeRule.onNodeWithText("Agente Rust Android").assertIsDisplayed()
        composeRule.onNodeWithText("Ejecutar agente").assertIsNotEnabled()
        composeRule.onNodeWithText(
            "Aún no hay mensajes. Escribe una consulta y pulsa «Ejecutar agente»."
        ).assertIsDisplayed()
    }

    @Test
    fun sin_url_configurada_el_click_muestra_error() {
        val vm = FakeAgentViewModel(ApplicationProvider.getApplicationContext())
        composeRule.setContent {
            MaterialTheme {
                AgentScreen(viewModel = vm)
            }
        }
        composeRule.onNodeWithText("Consulta para el agente").performTextInput("¿quién eres?")
        composeRule.onNodeWithText("Ejecutar agente").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(
            "Configura primero la URL de la API en «Ajustes»."
        ).assertIsDisplayed()
    }

    @Test
    fun tras_respuesta_ok_el_historial_se_pinta() {
        val vm = FakeAgentViewModel(ApplicationProvider.getApplicationContext()).apply {
            // Configura URL (sin invocar Rust real) para que runQuery avance.
            saveSettings("https://fake.example.com", "tok")
            // Modo "éxito inmediato": el fake devuelve sin llamar al nativo.
            fakeMode = FakeMode.Ok
        }
        composeRule.setContent {
            MaterialTheme {
                AgentScreen(viewModel = vm)
            }
        }
        composeRule.onNodeWithText("Consulta para el agente").performTextInput("hola")
        composeRule.onNodeWithText("Ejecutar agente").performClick()
        composeRule.waitForIdle()
        // El mensaje del usuario y la respuesta del agente aparecen.
        composeRule.onNodeWithText("hola").assertIsDisplayed()
        composeRule.onNodeWithText("Hola desde el fake").assertIsDisplayed()
    }

    @Test
    fun el_boton_limpiar_vacia_la_conversacion() {
        val vm = FakeAgentViewModel(ApplicationProvider.getApplicationContext()).apply {
            saveSettings("https://fake.example.com", "tok")
            fakeMode = FakeMode.Ok
        }
        composeRule.setContent {
            MaterialTheme {
                AgentScreen(viewModel = vm)
            }
        }
        composeRule.onNodeWithText("Consulta para el agente").performTextInput("hola")
        composeRule.onNodeWithText("Ejecutar agente").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Limpiar").performClick()
        composeRule.waitForIdle()
        // Vuelve a aparecer el mensaje de "aún no hay mensajes".
        composeRule.onNodeWithText(
            "Aún no hay mensajes. Escribe una consulta y pulsa «Ejecutar agente»."
        ).assertIsDisplayed()
    }
}

/** Modos del fake: controlan qué devuelve runQuery sin tocar Rust. */
private enum class FakeMode { NotConfigured, Ok }

/**
 * ViewModel de test que implementa [AgentViewModelContract] pero SIN llamar a
 * RustCore por JNI. Necesario porque el `.so` no está disponible en el
 * entorno de test instrumentado sin haberlo compilado con cargo-ndk y
 * empaquetado en el APK de test antes.
 */
private class FakeAgentViewModel(app: Application) : AndroidViewModel(app), AgentViewModelContract {
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    override val state: StateFlow<UiState> = _state.asStateFlow()

    private val _history = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val history: StateFlow<List<ChatMessage>> = _history.asStateFlow()

    var fakeMode: FakeMode = FakeMode.NotConfigured

    private var url: String = ""
    private var token: String = ""

    override fun getApiUrl(): String = url
    override fun getApiToken(): String = token

    override fun saveSettings(url: String, token: String) {
        this.url = url.trim()
        this.token = token.trim()
    }

    override fun clearHistory() {
        _history.value = emptyList()
        _state.value = UiState.Idle
    }

    override fun runQuery(input: String) {
        if (_state.value is UiState.Running) return
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            _state.value = UiState.Error("La consulta está vacía")
            return
        }
        if (url.isBlank()) {
            _state.value = UiState.Error(
                "Configura primero la URL de la API en «Ajustes»."
            )
            return
        }
        _state.value = UiState.Running
        when (fakeMode) {
            FakeMode.Ok -> {
                val reply = "Hola desde el fake"
                _history.value = _history.value + listOf(
                    ChatMessage.user(trimmed),
                    ChatMessage.assistant(reply),
                )
                _state.value = UiState.Success("{\"status\":\"ok\",\"result\":\"$reply\"}")
            }
            FakeMode.NotConfigured -> {
                _state.value = UiState.Error("Configura primero la URL de la API en «Ajustes».")
            }
        }
    }
}
