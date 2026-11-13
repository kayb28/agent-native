# Servidor propio para AgentNative

Esta carpeta contiene todo lo necesario para desplegar el backend del agente
(`POST /api/agent/run`) en un dominio fijo, en vez del sandbox de preview.

## Qué se despliega

Un único endpoint `POST /api/agent/run` que:

1. Valida el header `X-Agent-Token` contra `process.env.AGENT_TOKEN`.
2. Recibe `{ "query": "...", "history": [...] }` (history opcional, hasta 10 turnos).
3. Llama al modelo GLM (vía `z-ai-web-dev-sdk`) **solo en el servidor**.
4. Devuelve `{ "status": "ok", "result": "...", "engine": "glm" }` o
   `{ "status": "error", "message": "..." }` (HTTP 400/401/500).

Contrato idéntico al de [`backend/api-agent-run-route.ts`](../backend/api-agent-run-route.ts):
es la copia canónica que ya viven en el repo; basta con montarla en un Next.js
mínimo o como app standalone.

## Por qué migrar del sandbox de preview

El sandbox de preview duerme entre usos y tiene URL efímera
(`https://preview-XXXX.space-z.ai`). Para producción necesitas:

- URL fija que los usuarios guardan una sola vez en la app.
- HTTPS real (no self-signed) para que `reqwest+rustls` no rechace el certificado.
- El servidor despierto (al menos warm) para que la app no muestre
  «no se pudo conectar» en el primer uso del día.

## Opciones soportadas

| Plataforma | Coste | Complejidad | TLS |
|---|---|---|---|
| **Fly.io** | Free tier suficiente | Baja (un `fly.toml`) | Automático (Let's Encrypt) |
| **Render** | Free tier limitado | Baja | Automático |
| **Railway** | Pago desde $5/mes | Baja | Automático |
| **VPS (Hetzner/DigitalOcean)** | $4–6/mes | Media (nginx + certbot) | Manual |
| **Cloudflare Workers + Pages** | Free tier | Media (edge) | Automático |

Recomendado: **Fly.io** para empezar (gratis, con TLS automático y dominio propio
`*.fly.dev` o dominio custom).

## Requisitos

- Cuenta en una de las plataformas anteriores.
- `AGENT_TOKEN`: string aleatorio largo que usarás como header `X-Agent-Token`
  (el mismo valor se guarda en la app Android en «Ajustes» → «Token»).
- Variable de entorno `ZAI_API_KEY` (o la que pida `z-ai-web-dev-sdk` en su
  entorno de despliegue — revisa la docs del SDK).

## Despliegue en Fly.io (paso a paso)

1. **Instala flyctl** y haz login:

   ```bash
   curl -L https://fly.io/install.sh | sh
   fly auth login
   ```

2. **Lanza la app** desde esta carpeta:

   ```bash
   cd server
   fly launch --no-deploy
   ```

   Responde:
   - App name: `agent-native-api` (o el que quieras).
   - Region: la más cercana a tus usuarios (p. ej. `gru` para São Paulo).
   - No Postgres, no Redis.

3. **Configura los secrets**:

   ```bash
   fly secrets set AGENT_TOKEN=$(openssl rand -base64 32 | tr -d '/=+')
   fly secrets set ZAI_API_KEY=tu-key-del-sdk
   ```

   Anota el `AGENT_TOKEN`: es el que pondrás en la app Android.

4. **Despliega**:

   ```bash
   fly deploy
   ```

5. **Tu URL fija** será: `https://agent-native-api.fly.dev`.

   Pruébala (sin auth):

   ```bash
   curl https://agent-native-api.fly.dev/api/agent/run
   # → { "status":"ok","service":"agent-native-api","engine":"glm" }
   ```

6. **En la app Android**:

   - Ajustes → URL de la API: `https://agent-native-api.fly.dev`
   - Ajustes → Token: el `AGENT_TOKEN` que anotaste.

7. (Opcional) **Dominio propio**:

   ```bash
   fly certs add api.tudominio.com
   ```

   Sigue las instrucciones para crear el registro CNAME en tu DNS.

## Despliegue en Render / Railway

1. Conecta el repo.
2. Root directory: `server`.
3. Build: `npm install && npm run build`.
4. Start: `npm start`.
5. Environment:
   - `AGENT_TOKEN=<generado>`
   - `ZAI_API_KEY=<tu key>`
6. Tu URL será `https://agent-native-api.onrender.com` (o similar).
   Render duerme el free tier tras 15 min de inactividad — el primer request
   tarda ~30 s en despertar. Para evitarlo, contrata el plan mínimo ($7/mes)
   o usa un cron externo que haga ping al health check cada 10 min.

## Health check (sin auth)

`GET /api/agent/run` devuelve `{ "status":"ok","service":"agent-native-api","engine":"glm" }`
sin requerir token. Útil para monitorización (UptimeRobot, BetterStack, etc.).

## Endurecimiento para producción

- **Rate limiting**: añade `@upstash/ratelimit` o similar para limitar por IP
  (p. ej. 60 req/min) si la app crece.
- **Logging**: registra consultas con IP + timestamp (sin el contenido del
  prompt, por privacidad) para detectar abuso.
- **CORS**: este endpoint NO necesita CORS (lo llama Rust desde la app, no un
  navegador). No añadas `Access-Control-Allow-Origin: *` sin motivo.
- **Secret rotation**: rota `AGENT_TOKEN` cada 90 días; la app permite
  editarlo en Ajustes sin reinstalar.
- **Observabilidad**: integra Sentry / Logflare en el SDK del servidor para
  capturar errores 500.

## Migración desde el sandbox de preview

1. Despliega en tu dominio fijo siguiendo los pasos anteriores.
2. Verifica que `curl https://TU-DOMINIO/api/agent/run` devuelve `status: ok`.
3. Verifica con auth:

   ```bash
   curl -X POST https://TU-DOMINIO/api/agent/run \
        -H "X-Agent-Token: TU_TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"query":"hola"}'
   ```

   Debe devolver `{ "status":"ok","result":"...","engine":"glm" }`.

4. Abre la app → Ajustes → pega la URL nueva y el token → Guardar.
5. Pulsa «Ejecutar agente» con cualquier consulta. La primera respuesta
   tardará < 2 s si el server está warm.

## Rollback

Si el servidor propio falla, vuelve al sandbox de preview cambiando la URL
en la app (Ajustes). No se pierde nada: el historial vive en memoria del
dispositivo hasta que se cierra la app o se pulsa «Limpiar».
