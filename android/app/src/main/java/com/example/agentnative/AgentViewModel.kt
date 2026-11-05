package com.example.agentnative

import androidx.lifecycle.ViewModel
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

class AgentViewModel : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Llama al núcleo Rust por JNI SIN bloquear el hilo principal:
     * la llamada nativa corre en Dispatchers.IO dentro del ViewModel.
     */
    fun runQuery(input: String) {
        if (_state.value is UiState.Running) return
        _state.value = UiState.Running
        viewModelScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    RustCore.processQuery(input)
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
}
