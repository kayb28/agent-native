//! Puente JNI — solo se compila para Android (`cfg(target_os = "android")`).
//!
//! Resolución de símbolos: JNI convierte la función nativa declarada en Kotlin
//! `RustCore.processQuery(input, apiBaseUrl, apiToken)` (paquete
//! `com.example.agentnative`) en el símbolo Rust
//! `Java_com_example_agentnative_RustCore_processQuery`.

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

use crate::agent::{AgentCore, ApiBackend, SimulatedBackend};

#[no_mangle]
pub extern "system" fn Java_com_example_agentnative_RustCore_processQuery<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
    api_base_url: JString<'local>,
    api_token: JString<'local>,
) -> jstring {
    // 1) Strings de Kotlin -> Strings de Rust.
    let query: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let base_url: String = env
        .get_string(&api_base_url)
        .map(|s| s.into())
        .unwrap_or_default();
    let token: String = env
        .get_string(&api_token)
        .map(|s| s.into())
        .unwrap_or_default();

    // 2) Elección de backend:
    //    - URL configurada -> ApiBackend (agente real en el servidor).
    //    - URL vacía       -> SimulatedBackend (fallback sin red del MVP).
    let response = if base_url.trim().is_empty() {
        AgentCore::new(SimulatedBackend).run(&query)
    } else {
        AgentCore::new(ApiBackend::new(&base_url, &token)).run(&query)
    };

    // 3) String de Rust -> String de Kotlin.
    //    Si falla, se devuelve null y Kotlin lo trata como error legible.
    match env.new_string(response) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
