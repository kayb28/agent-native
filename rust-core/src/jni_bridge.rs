//! Puente JNI — solo se compila para Android (`cfg(target_os = "android")`).
//!
//! Resolución de símbolos: JNI convierte la función nativa declarada en Kotlin
//! `RustCore.processQuery` (paquete `com.example.agentnative`) en el símbolo Rust
//! `Java_com_example_agentnative_RustCore_processQuery`.

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

use crate::agent::{AgentCore, SimulatedBackend};

#[no_mangle]
pub extern "system" fn Java_com_example_agentnative_RustCore_processQuery<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> jstring {
    // 1) String de Kotlin -> String de Rust.
    let query: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    // 2) Lógica del agente.
    //    Hoy: SimulatedBackend. Para el agente real solo se cambia esta línea
    //    por el backend nuevo (p. ej. ApiBackend) — el contrato no cambia.
    let core = AgentCore::new(SimulatedBackend);
    let response = core.run(&query);

    // 3) String de Rust -> String de Kotlin.
    //    Si falla, se devuelve null y Kotlin lo trata como error legible.
    match env.new_string(response) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
