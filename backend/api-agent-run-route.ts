import { NextRequest, NextResponse } from "next/server";
import ZAI from "z-ai-web-dev-sdk";

// API del agente para la app Android AgentNative.
// Contrato (coincide con rust-core/src/agent.rs::ApiBackend):
//   POST /api/agent/run
//   Headers: X-Agent-Token: <token compartido, definido en .env.local>
//   Body:    { "query": "...", "history": [{ "role": "user"|"assistant", "content": "..." }] }
//            (history opcional, últimos 10 turnos)
//   Respuesta ok:     { "status": "ok", "result": "...", "engine": "glm" }
//   Respuesta error:  { "status": "error", "message": "legible" }  (HTTP 400/401/500)

export const runtime = "nodejs";

const AGENT_SYSTEM_PROMPT =
  "Eres el agente personal del usuario de la app Android AgentNative, impulsado por el modelo GLM servido en esta API. " +
  "Si preguntan qué eres: eres GLM (modelo de Z.ai); no te inventes otra identidad ni empresa. " +
  "Responde en el idioma en el que te escriban (por defecto, español). " +
  "Estilo: directo, útil y concreto; usa listas cuando ayuden a leer. " +
  "Ayudas con preguntas, tareas, redacción, código, cálculos y análisis. " +
  "Si la consulta no está clara, pide UN dato concreto en vez de especular. " +
  "No inventes datos: si no sabes algo, dilo.";

export async function GET() {
  // Health check público (sin token): sirve para el botón «Probar conexión».
  return NextResponse.json({
    status: "ok",
    service: "agent-native-api",
    engine: "glm",
  });
}

export async function POST(req: NextRequest) {
  // 1) Autenticación por token compartido (nunca va en el código de la app).
  const expected = process.env.AGENT_TOKEN;
  const provided = req.headers.get("x-agent-token") ?? "";
  if (!expected || provided !== expected) {
    return NextResponse.json(
      { status: "error", message: "Token inválido o ausente" },
      { status: 401 }
    );
  }

  // 2) Validación del cuerpo.
  let body: {
    query?: string;
    history?: { role: "user" | "assistant"; content: string }[];
  };
  try {
    body = await req.json();
  } catch {
    return NextResponse.json(
      { status: "error", message: "El cuerpo debe ser JSON válido" },
      { status: 400 }
    );
  }
  const query = (body.query ?? "").trim();
  if (!query) {
    return NextResponse.json(
      { status: "error", message: "Falta el campo 'query'" },
      { status: 400 }
    );
  }

  // 3) Llamada al modelo (SDK solo en el servidor).
  try {
    const zai = await ZAI.create();
    const history = (body.history ?? []).slice(-10).filter(
      (m) =>
        (m.role === "user" || m.role === "assistant") &&
        typeof m.content === "string"
    );
    const messages = [
      { role: "assistant" as const, content: AGENT_SYSTEM_PROMPT },
      ...history.map((m) => ({ role: m.role, content: m.content })),
      { role: "user" as const, content: query },
    ];
    const completion = await zai.chat.completions.create({
      messages,
      thinking: { type: "disabled" },
    });
    const result = completion.choices[0]?.message?.content ?? "";
    if (!result.trim()) {
      throw new Error("El modelo devolvió una respuesta vacía");
    }
    return NextResponse.json({ status: "ok", result, engine: "glm" });
  } catch (e) {
    const message =
      e instanceof Error ? e.message : "Error desconocido del servidor";
    return NextResponse.json({ status: "error", message }, { status: 500 });
  }
}
