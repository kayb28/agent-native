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
import org.json.JSONObject

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

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** URL y token guardados en el dispositivo (nunca en el código). */
    fun getApiUrl(): String = prefs.getString(KEY_URL, "") ?: ""
    fun getApiToken(): String = prefs.getString(KEY_TOKEN, "") ?: ""

    fun saveSettings(url: String, token: String) {
        prefs.edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    /**
     * Llama al núcleo Rust por JNI SIN bloquear el hilo principal:
     * la llamada nativa (y su HTTP hacia la API) corre en Dispatchers.IO.
     */
    fun runQuery(input: String) {
        if (_state.value is UiState.Running) return

        val url = getApiUrl()
        if (url.isBlank()) {
            _state.value = UiState.Error(
                "Configura primero la URL de la API en «Ajustes»."
            )
            return
        }

        _state.value = UiState.Running
        viewModelScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    RustCore.processQuery(input, url, getApiToken())
                }
                _state.value = UiState.Success(prettyJson(raw))
            } catch (t: Throwable) {
                _state.value = UiState.Error(
                    t.message ?: "Error desconocido al invocar a Rust"
                )
            }
        }
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
