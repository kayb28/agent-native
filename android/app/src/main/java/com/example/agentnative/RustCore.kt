package com.example.agentnative

/**
 * Puente Kotlin -> Rust.
 *
 * El símbolo nativo lo implementa el crate `agent_core`
 * (ver rust-core/src/jni_bridge.rs, símbolo
 * Java_com_example_agentnative_RustCore_processQuery) y Gradle lo empaqueta
 * desde android/app/src/main/jniLibs/<abi>/libagent_core.so
 * (los .so los genera scripts/build_rust_android.sh durante el build de CI).
 */
object RustCore {
    init {
        // Carga libagent_core.so desde la APK.
        System.loadLibrary("agent_core")
    }

    external fun processQuery(input: String): String
}
