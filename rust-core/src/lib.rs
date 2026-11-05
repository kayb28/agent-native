//! `agent_core` — núcleo Rust de AgentNative.
//!
//! Contrato del MVP:
//! - Recibe un `String` (la consulta) desde Kotlin por JNI.
//! - Devuelve un `String` con JSON:
//!   `{ "status": "ok", "engine": "rust", "message": "...", "input": "..." }`
//!
//! Punto de extensión para el agente real: el trait [`agent::AgentBackend`].
//! Hoy existe [`agent::SimulatedBackend`] (respuesta simulada, sin red).
//! Mañana se añade, por ejemplo, un `ApiBackend` que llame a
//! `POST https://TU-DOMINIO/api/agent/run` — sin tocar el puente JNI ni la app.

pub mod agent;

/// Puente JNI. Solo se compila cuando el destino es Android:
/// en escritorio `cargo test` compila únicamente la lógica pura de [`agent`].
#[cfg(target_os = "android")]
mod jni_bridge;
