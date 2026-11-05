//! Lógica del agente y PUNTO DE EXTENSIÓN para conectar el backend real.

use serde::{Deserialize, Serialize};

/// Respuesta estándar del núcleo hacia Android (se serializa a JSON).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AgentResponse {
    /// "ok" | "error"
    pub status: String,
    /// Motor que generó la respuesta ("rust" en este MVP).
    pub engine: String,
    /// Mensaje legible para mostrar en la UI.
    pub message: String,
    /// Eco del texto recibido.
    pub input: String,
}

/// Errores conocidos del núcleo.
#[derive(Debug, Clone, PartialEq)]
pub enum AgentError {
    /// La consulta llegó vacía o solo con espacios.
    EmptyInput,
}

impl AgentError {
    /// Mensaje legible que viaja dentro del JSON de error.
    pub fn message(&self) -> String {
        match self {
            AgentError::EmptyInput => "La consulta está vacía".to_string(),
        }
    }
}

/// ------------------------------------------------------------------
/// PUNTO DE EXTENSIÓN del agente.
///
/// Todo el comportamiento futuro se implementa como una nueva
/// implementación de este trait, sin tocar JNI ni la app Android:
///
/// - `SimulatedBackend` (MVP actual): respuesta simulada, sin red.
/// - `ApiBackend` (futuro): llamada HTTP a `POST {base_url}/api/agent/run`
///   con cuerpo `{ "query": "..." }` y respuesta `{ "status": "ok", "result": "..." }`.
///   (Se implementaría con `reqwest` + `rustls`; pedirlo en la siguiente fase.)
/// - También podría envolver: ejecución de tareas, procesamiento de datos,
///   inferencia local, o comunicación con el backend actual.
/// ------------------------------------------------------------------
pub trait AgentBackend {
    fn process(&self, input: &str) -> Result<AgentResponse, AgentError>;
}

/// Backend simulado del MVP: sin red, sin estado.
pub struct SimulatedBackend;

impl AgentBackend for SimulatedBackend {
    fn process(&self, input: &str) -> Result<AgentResponse, AgentError> {
        let trimmed = input.trim();
        if trimmed.is_empty() {
            return Err(AgentError::EmptyInput);
        }
        Ok(AgentResponse {
            status: "ok".to_string(),
            engine: "rust".to_string(),
            message: "Respuesta generada desde Rust".to_string(),
            input: trimmed.to_string(),
        })
    }
}

/// Fachada del núcleo, genérica sobre el backend.
pub struct AgentCore<B: AgentBackend> {
    backend: B,
}

impl<B: AgentBackend> AgentCore<B> {
    pub fn new(backend: B) -> Self {
        Self { backend }
    }

    /// Ejecuta la consulta y devuelve SIEMPRE un JSON en string
    /// (de éxito o de error), listo para cruzar el puente JNI hacia Kotlin.
    pub fn run(&self, input: &str) -> String {
        let response = match self.backend.process(input) {
            Ok(resp) => resp,
            Err(err) => AgentResponse {
                status: "error".to_string(),
                engine: "rust".to_string(),
                message: err.message(),
                input: input.to_string(),
            },
        };
        serde_json::to_string(&response).unwrap_or_else(|_| {
            // Camino de emergencia: nunca debe fallar el cruce del puente sin JSON.
            format!(
                "{{\"status\":\"error\",\"engine\":\"rust\",\"message\":\"fallo de serializacion\",\"input\":{}}}",
                serde_json::to_string(input).unwrap_or_else(|_| "\"\"".to_string())
            )
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn run(input: &str) -> Value {
        let core = AgentCore::new(SimulatedBackend);
        serde_json::from_str(&core.run(input)).expect("run() debe devolver JSON válido")
    }

    #[test]
    fn consulta_normal_devuelve_ok_con_eco() {
        let json = run("hola agente");
        assert_eq!(json["status"], "ok");
        assert_eq!(json["engine"], "rust");
        assert_eq!(json["message"], "Respuesta generada desde Rust");
        assert_eq!(json["input"], "hola agente");
    }

    #[test]
    fn entrada_vacia_devuelve_error_legible() {
        let json = run("   ");
        assert_eq!(json["status"], "error");
        assert_eq!(json["message"], "La consulta está vacía");
    }

    #[test]
    fn el_input_con_unicode_y_emojis_no_se_corrompe() {
        let json = run("probar ñ á ☕ 🙂 fin");
        assert_eq!(json["status"], "ok");
        assert_eq!(json["input"], "probar ñ á ☕ 🙂 fin");
    }

    #[test]
    fn el_json_tiene_las_claves_esperadas() {
        let json = run("test");
        let obj = json.as_object().expect("debe ser un objeto JSON");
        let mut keys: Vec<&str> = obj.keys().map(String::as_str).collect();
        keys.sort();
        assert_eq!(keys, vec!["engine", "input", "message", "status"]);
    }

    #[test]
    fn el_eco_recorta_los_espacios_de_los_extremos() {
        let json = run("  hola mundo  ");
        assert_eq!(json["status"], "ok");
        assert_eq!(json["input"], "hola mundo");
    }
}
