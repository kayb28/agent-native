// ---------------------------------------------------------------------------
// Servidor Express standalone del backend del agente para AgentNative.
//
// Contrato idéntico al de backend/api-agent-run-route.ts (la copia canónica
// del repo, originalmente escrita como Next.js Route Handler). Aquí se
// reutiliza como servidor Express mínimo para desplegar en cualquier host
// (Fly.io, Render, Railway, VPS con Docker) sin depender de Next.js.
//
// Endpoint:
//   GET  /api/agent/run     → health check público (sin token)
//   POST /api/agent/run     → ejecuta el agente (header X-Agent-Token, body JSON)
//
// Variables de entorno:
//   AGENT_TOKEN   (requerido) token compartido con la app Android.
//   ZAI_API_KEY   (requerido) clave para z-ai-web-dev-sdk (la pide el SDK).
//   PORT          (opcional, default 8080) puerto HTTP.
//   AGENT_SYSTEM_PROMPT (opcional) prompt del sistema, override del default.
// ---------------------------------------------------------------------------

import express from "express";
import ZAI from "z-ai-web-dev-sdk";

const PORT = process.env.PORT ? Number(process.env.PORT) : 8080;
const AGENT_TOKEN = process.env.AGENT_TOKEN;
const ZAI_API_KEY = process.env.ZAI_API_KEY;

const DEFAULT_SYSTEM_PROMPT =
  "Eres el agente personal del usuario de la app Android AgentNative, impulsado por el modelo GLM servido en esta API. " +
  "Si preguntan qué eres: eres GLM (modelo de Z.ai); no te inventes otra identidad ni empresa. " +
  "Responde en el idioma en el que te escriban (por defecto, español). " +
  "Estilo: directo, útil y concreto; usa listas cuando ayuden a leer. " +
  "Ayudas con preguntas, tareas, redacción, código, cálculos y análisis. " +
  "Si la consulta no está clara, pide UN dato concreto en vez de especular. " +
  "No inventes datos: si no sabes algo, dilo.";

const SYSTEM_PROMPT = process.env.AGENT_SYSTEM_PROMPT || DEFAULT_SYSTEM_PROMPT;

const app = express();
app.use(express.json({ limit: "256kb" }));

// Logging mínimo: timestamp + método + ruta + status. Sin body (privacidad).
app.use((req, res, next) => {
  const start = Date.now();
  res.on("finish", () => {
    const ms = Date.now() - start;
    console.log(`${new Date().toISOString()} ${req.method} ${req.originalUrl} ${res.statusCode} ${ms}ms`);
  });
  next();
});

// --- Health check público (sin token). Sirve para monitorización. ---
app.get("/api/agent/run", (_req, res) => {
  res.json({
    status: "ok",
    service: "agent-native-api",
    engine: "glm",
  });
});

// --- Endpoint principal: ejecuta el agente con GLM. ---
app.post("/api/agent/run", async (req, res) => {
  // 1) Autenticación por token compartido.
  if (!AGENT_TOKEN) {
    return res
      .status(500)
      .json({ status: "error", message: "Servidor mal configurado: falta AGENT_TOKEN" });
  }
  const provided = req.headers["x-agent-token"] ?? "";
  if (provided !== AGENT_TOKEN) {
    return res
      .status(401)
      .json({ status: "error", message: "Token inválido o ausente" });
  }

  // 2) Validación del cuerpo.
  const query = (typeof req.body?.query === "string" ? req.body.query : "").trim();
  if (!query) {
    return res
      .status(400)
      .json({ status: "error", message: "Falta el campo 'query'" });
  }

  // 3) Historial: filtra roles y se queda con los últimos 10 (igual que Rust).
  const rawHistory = Array.isArray(req.body?.history) ? req.body.history : [];
  const history = rawHistory
    .filter(
      (m) =>
        m &&
        (m.role === "user" || m.role === "assistant") &&
        typeof m.content === "string"
    )
    .slice(-10)
    .map((m) => ({ role: m.role, content: m.content }));

  // 4) Llamada al modelo (SDK solo en el servidor).
  try {
    const zai = await ZAI.create();
    const messages = [
      { role: "assistant", content: SYSTEM_PROMPT },
      ...history,
      { role: "user", content: query },
    ];
    const completion = await zai.chat.completions.create({
      messages,
      thinking: { type: "disabled" },
    });
    const result = completion.choices[0]?.message?.content ?? "";
    if (!result.trim()) {
      throw new Error("El modelo devolvió una respuesta vacía");
    }
    res.json({ status: "ok", result, engine: "glm" });
  } catch (e) {
    const message = e instanceof Error ? e.message : "Error desconocido del servidor";
    console.error("agent/run error:", message);
    res.status(500).json({ status: "error", message });
  }
});

// --- 404 catch-all ---
app.use((_req, res) => {
  res.status(404).json({ status: "error", message: "Not found" });
});

if (!ZAI_API_KEY) {
  console.warn("WARN: ZAI_API_KEY no está definida. La llamada al modelo fallará.");
}
if (!AGENT_TOKEN) {
  console.warn("WARN: AGENT_TOKEN no está definida. El POST /api/agent/run responderá 500.");
}

app.listen(PORT, () => {
  console.log(`agent-native-api escuchando en :${PORT}`);
  console.log(`  GET  /api/agent/run  → health check público`);
  console.log(`  POST /api/agent/run  → agente (header X-Agent-Token)`);
});
