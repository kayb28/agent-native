# Servidor Express del backend del agente para AgentNative.
# Imagen multi-arch (amd64 + arm64) para correr en x86 (Fly.io/Railway) y ARM.

# --- Build stage: instalar dependencias ---
FROM node:20-bookworm-slim AS deps
WORKDIR /app

# Solo package*.json para aprovechar la caché de Docker.
COPY package.json ./
# Si existiera package-lock.json, cópialo también:
COPY package-lock.json* ./

RUN npm install --omit=dev --no-audit --no-fund

# --- Runtime stage: imagen mínima ---
FROM node:20-bookworm-slim AS runtime
WORKDIR /app

ENV NODE_ENV=production
ENV PORT=8080

# Sin stale deps: solo lo necesario.
COPY --from=deps /app/node_modules ./node_modules
COPY package.json ./
COPY src ./src

# Sin shell, sin package manager: ejecuta como usuario no-root.
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
USER app

EXPOSE 8080

# Healthcheck interno: cada 30s, el GET público responde 200 si el proceso vive.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://localhost:'+(process.env.PORT||8080)+'/api/agent/run').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "src/index.js"]
