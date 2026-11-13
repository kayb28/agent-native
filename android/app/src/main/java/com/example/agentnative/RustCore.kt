package com.example.agentnative

/**
 * Puente Kotlin -> Rust.
 *
 * El símbolo nativo lo implementa el crate `agent_core`
 * (ver rust-core/src/jni_bridge.rs, símbolo
 * Java_com_example_agentnative_RustCore_processQuery) y Gradle lo empaqueta
 * desde android/app/src/main/jniLibs/<abi>/libagent_core.so
 * (los .so los genera scripts/build_rust_android.sh durante el build de CI).
 *
 * @param input      consulta del usuario.
 * @param historyJson  historial de conversación como JSON array string:
 *                   `[{"role":"user","content":"..."},{"role":"assistant","content":"..."}]`.
 *                   Vacío o "[]" si es el primer turno.
 * @param apiBaseUrl URL base de la API del agente (p. ej. "https://preview-xxx.space-z.ai").
 *                   Si llega vacía, Rust usa el backend simulado del MVP.
 * @param apiToken   token compartido (X-Agent-Token); NO se guarda en el código,
 *                   solo en las preferencias del dispositivo.
 */
object RustCore {
    init {
        // Carga libagent_core.so desde la APK.
        System.loadLibrary("agent_core")
    }

    external fun processQuery(
        input: String,
        historyJson: String,
        apiBaseUrl: String,
        apiToken: String,
    ): String
}
