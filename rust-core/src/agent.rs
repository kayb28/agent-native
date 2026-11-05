//! Lógica del agente y PUNTO DE EXTENSIÓN para conectar el backend real.

use serde::{Deserialize, Serialize};

/// Respuesta estándar del núcleo hacia Android (se serializa a JSON).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AgentResponse {
    /// "ok" | "error"
    pub status: String,
    /// Motor que generó la respuesta ("rust" simulado, "rust+api" con backend real).
    pub engine: String,
    /// Mensaje legible para mostrar en la UI.
    pub message: String,
    /// Eco del texto recibido.
    pub input: String,
    /// Texto del agente real cuando el backend es la API (None en el simulado).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<String>,
}

/// Errores conocidos del núcleo.
#[derive(Debug, Clone, PartialEq)]
pub enum AgentError {
    /// La consulta llegó vacía o solo con espacios.
    EmptyInput,
    /// La API del agente falló (red, token, respuesta inválida...).
    Api(String),
}

impl AgentError {
    /// Mensaje legible que viaja dentro del JSON de error.
    pub fn message(&self) -> String {
        match self {
            AgentError::EmptyInput => "La consulta está vacía".to_string(),
            AgentError::Api(detail) => format!("Error de la API: {detail}"),
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
            result: None,
        })
    }
}

/// Backend REAL: llama a la API del agente desplegada (Next.js + GLM).
///
/// Contrato (ver src/app/api/agent/run/route.ts del proyecto web):
///   POST {base_url}/api/agent/run   header X-Agent-Token: <token>
///   body   { "query": "..." }
///   ok     { "status": "ok", "result": "...", "engine": "glm" }
///   error  { "status": "error", "message": "legible" }  (HTTP 400/401/500)
///
/// La llamada es bloqueante a propósito: Kotlin ya la invoca desde Dispatchers.IO.
#[cfg(target_os = "android")]
pub struct ApiBackend {
    base_url: String,
    token: String,
}

#[cfg(target_os = "android")]
impl ApiBackend {
    pub fn new(base_url: &str, token: &str) -> Self {
        Self {
            base_url: base_url.trim().trim_end_matches('/').to_string(),
            token: token.trim().to_string(),
        }
    }

    fn describe_error(e: &reqwest::Error) -> AgentError {
        if e.is_timeout() {
            AgentError::Api("la API tardó demasiado en responder".to_string())
        } else if e.is_connect() {
            AgentError::Api(format!(
                "no se pudo conectar (¿URL correcta?, ¿servidor activo?): {e}"
            ))
        } else {
            AgentError::Api(format!("error de red: {e}"))
        }
    }
}

#[cfg(target_os = "android")]
impl AgentBackend for ApiBackend {
    fn process(&self, input: &str) -> Result<AgentResponse, AgentError> {
        let trimmed = input.trim();
        if trimmed.is_empty() {
            return Err(AgentError::EmptyInput);
        }

        let client = reqwest::blocking::Client::builder()
            .timeout(std::time::Duration::from_secs(90))
            .build()
            .map_err(|e| AgentError::Api(format!("no se pudo crear el cliente HTTP: {e}")))?;

        let response = client
            .post(format!("{}/api/agent/run", self.base_url))
            .header("X-Agent-Token", &self.token)
            .json(&serde_json::json!({ "query": trimmed }))
            .send()
            .map_err(|e| Self::describe_error(&e))?;

        let http_status = response.status().as_u16();
        let body: serde_json::Value = response
            .json()
            .map_err(|_| AgentError::Api(format!("la API respondió {http_status} con un cuerpo no JSON")))?;

        parse_api_response(http_status, &body, trimmed)
    }
}

/// Traduce el cuerpo JSON de la API a la respuesta del núcleo.
/// Función PURA (sin red): así `cargo test` en escritorio la cubre.
pub fn parse_api_response(
    http_status: u16,
    body: &serde_json::Value,
    input: &str,
) -> Result<AgentResponse, AgentError> {
    let message_of = |fallback: &str| -> String {
        body.get("message")
            .and_then(|m| m.as_str())
            .unwrap_or(fallback)
            .to_string()
    };

    if !(200..300).contains(&http_status) {
        return Err(AgentError::Api(format!(
            "la API respondió {}: {}",
            http_status,
            message_of("sin detalle")
        )));
    }
    if body.get("status").and_then(|s| s.as_str()) != Some("ok") {
        return Err(AgentError::Api(message_of(
            "respuesta sin status=ok",
        )));
    }
    let result = body
        .get("result")
        .and_then(|r| r.as_str())
        .unwrap_or("")
        .to_string();
    if result.is_empty() {
        return Err(AgentError::Api(
            "la API respondió ok pero sin texto en 'result'".to_string(),
        ));
    }
    Ok(AgentResponse {
        status: "ok".to_string(),
        engine: "rust+api".to_string(),
        message: "Respuesta del agente".to_string(),
        input: input.to_string(),
        result: Some(result),
    })
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
                result: None,
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

    #[test]
    fn la_respuesta_simulada_no_incluye_la_clave_result() {
        let json = run("test");
        assert!(json.get("result").is_none());
    }

    #[test]
    fn parse_api_ok_extrae_result_y_eco() {
        let body = serde_json::json!({"status": "ok", "result": "Hola, soy GLM", "engine": "glm"});
        let resp = parse_api_response(200, &body, "¿quién eres?").expect("debe ser ok");
        assert_eq!(resp.status, "ok");
        assert_eq!(resp.engine, "rust+api");
        assert_eq!(resp.result.as_deref(), Some("Hola, soy GLM"));
        assert_eq!(resp.input, "¿quién eres?");
    }

    #[test]
    fn parse_api_401_da_error_legible() {
        let body = serde_json::json!({"status": "error", "message": "Token inválido o ausente"});
        let err = parse_api_response(401, &body, "x").expect_err("debe ser error");
        assert!(err.message().contains("401"));
        assert!(err.message().contains("Token inválido"));
    }

    #[test]
    fn parse_api_ok_sin_result_da_error_claro() {
        let body = serde_json::json!({"status": "ok"});
        let err = parse_api_response(200, &body, "x").expect_err("debe ser error");
        assert!(err.message().contains("result"));
    }

    #[test]
    fn parse_api_status_no_ok_da_error_con_mensaje() {
        let body = serde_json::json!({"status": "error", "message": "El modelo devolvió una respuesta vacía"});
        let err = parse_api_response(500, &body, "x").expect_err("debe ser error");
        assert!(err.message().contains("500"));
        assert!(err.message().contains("vacía"));
    }
}
