package com.example.agentnative

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Un mensaje del historial de conversación (rol + contenido). */
data class ChatMessage(val role: String, val content: String) {
    companion object {
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
    }
}

/** Estado de la última ejecución del agente. */
sealed interface UiState {
    data object Idle : UiState
    data object Running : UiState
    data class Success(val response: String) : UiState
    data class Error(val message: String) : UiState
}

/** Etiqueta de estado que se pinta en la UI (Idle / Running / Success / Error). */
fun UiState.label(): String = when (this) {
    UiState.Idle -> "Idle"
    UiState.Running -> "Running"
    is UiState.Success -> "Success"
    is UiState.Error -> "Error"
}

/**
 * Contrato que consume la UI. [AgentViewModel] real lo implementa usando Rust
 * vía JNI; los tests pueden inyectar un fake que implemente la misma interfaz
 * (ver `androidTest/.../AgentScreenUiTest.kt`).
 */
interface AgentViewModelContract {
    val state: StateFlow<UiState>
    val history: StateFlow<List<ChatMessage>>
    fun getApiUrl(): String
    fun getApiToken(): String
    fun saveSettings(url: String, token: String)
    fun clearHistory()
    fun runQuery(input: String)
}

class AgentViewModel(application: Application) : AndroidViewModel(application), AgentViewModelContract {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    override val state: StateFlow<UiState> = _state.asStateFlow()

    /** Historial de la conversación actual (vivo en memoria; se borra al cerrar la app). */
    private val _history = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val history: StateFlow<List<ChatMessage>> = _history.asStateFlow()

    /** URL y token guardados en el dispositivo (nunca en el código). */
    override fun getApiUrl(): String = prefs.getString(KEY_URL, "") ?: ""
    override fun getApiToken(): String = prefs.getString(KEY_TOKEN, "") ?: ""

    override fun saveSettings(url: String, token: String) {
        prefs.edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    /** Borra el historial en memoria (la API no guarda estado entre sesiones). */
    override fun clearHistory() {
        _history.value = emptyList()
        _state.value = UiState.Idle
    }

    /**
     * Llama al núcleo Rust por JNI SIN bloquear el hilo principal:
     * la llamada nativa (y su HTTP hacia la API) corre en Dispatchers.IO.
     *
     * Tras una respuesta exitosa, añade el par (user, assistant) al historial
     * para que la siguiente llamada lleve memoria de conversación.
     */
    override fun runQuery(input: String) {
        if (_state.value is UiState.Running) return

        val url = getApiUrl()
        if (url.isBlank()) {
            _state.value = UiState.Error(
                "Configura primero la URL de la API en «Ajustes»."
            )
            return
        }

        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            _state.value = UiState.Error("La consulta está vacía")
            return
        }

        // Snapshot del historial actual para enviar a Rust (inmutable).
        val historyJson = serializeHistory(_history.value)

        _state.value = UiState.Running
        viewModelScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    RustCore.processQuery(trimmed, historyJson, url, getApiToken())
                }
                val parsed = JSONObject(raw)
                when (parsed.optString("status")) {
                    "ok" -> {
                        val result = parsed.optString("result")
                            .ifBlank { parsed.optString("message") }
                        // Añade el turno al historial de memoria.
                        _history.value = _history.value + listOf(
                            ChatMessage.user(trimmed),
                            ChatMessage.assistant(result),
                        )
                        _state.value = UiState.Success(prettyJson(raw))
                    }
                    else -> {
                        val msg = parsed.optString("message", "Error desconocido")
                        _state.value = UiState.Error(msg)
                    }
                }
            } catch (t: Throwable) {
                _state.value = UiState.Error(
                    t.message ?: "Error desconocido al invocar a Rust"
                )
            }
        }
    }

    /**
     * Serializa el historial a un JSON array string aceptado por Rust.
     * Roles válidos: "user" | "assistant". Los demás se filtran.
     */
    private fun serializeHistory(list: List<ChatMessage>): String {
        val arr = JSONArray()
        for (m in list) {
            if (m.role != "user" && m.role != "assistant") continue
            val o = JSONObject()
            o.put("role", m.role)
            o.put("content", m.content)
            arr.put(o)
        }
        return arr.toString()
    }

    /**
     * Formatea el JSON devuelto por Rust para mostrarlo legible (indentado);
     * si no fuera JSON válido, lo muestra tal cual.
     */
    private fun prettyJson(raw: String): String = try {
        JSONObject(raw).toString(2)
    } catch (_: Exception) {
        raw
    }

    companion object {
        private const val PREFS_NAME = "agent_settings"
        private const val KEY_URL = "api_url"
        private const val KEY_TOKEN = "api_token"
    }
}
