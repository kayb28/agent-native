# AgentNative

Cliente Android **nativo** (Kotlin + Jetpack Compose) con núcleo de lógica en **Rust**, comunicados por **JNI**, y CI en GitHub Actions que compila todo y publica el APK debug como artifact.

> No es un navegador ni un WebView: la UI es Compose, la lógica pesada vive en Rust
> (`rust-core`) y entre ambos solo cruza un puente JNI con un contrato JSON mínimo.

## Arquitectura

```
┌───────────────────────────── Android ─────────────────────────────┐
│  MainActivity.kt (Compose)                                        │
│      │  TextField + botón «Ejecutar agente»                       │
│      ▼                                                            │
│  AgentViewModel.kt                                                │
│      │  viewModelScope.launch + withContext(Dispatchers.IO)       │
│      ▼                                            (sin bloquear   │
│  RustCore.kt  ── external fun processQuery(String): String    UI) │
└──────┬────────────────────────────────────────────────────────────┘
       │  JNI (System.loadLibrary("agent_core"))
       ▼
┌─────────────────────────── Rust (libagent_core.so) ───────────────┐
│  jni_bridge.rs  Java_com_example_agentnative_RustCore_processQuery│
│         │ input + apiBaseUrl + apiToken                            │
│         ▼                          si URL vacía                   │
│  agent.rs   AgentCore::run(input) ─────────► SimulatedBackend     │
│         │  ── trait AgentBackend ──                               │
│         ▼                                                         │
│  ApiBackend → POST {base}/api/agent/run  (reqwest + rustls)       │
└─────────┬─────────────────────────────────────────────────────────┘
          │ HTTPS (header X-Agent-Token)
          ▼
┌─────────────────── Servidor (Next.js + GLM) ──────────────────────┐
│  /api/agent/run  { query } → modelo GLM → { status, result }      │
└───────────────────────────────────────────────────────────────────┘
```

Respuesta de ejemplo que devuelve Rust:

```json
{
  "status": "ok",
  "engine": "rust",
  "message": "Respuesta generada desde Rust",
  "input": "texto recibido"
}
```

## Estructura del repositorio

```
agent-native/
├── rust-core/                 # crate agent_core (cdylib + rlib)
│   ├── Cargo.toml
│   └── src/
│       ├── lib.rs             # raíz: módulos agent + jni_bridge (cfg android)
│       ├── agent.rs           # lógica pura + PUNTO DE EXTENSIÓN + tests
│       └── jni_bridge.rs      # símbolo JNI (solo compila para Android)
├── android/                   # proyecto Gradle (raíz del proyecto Android)
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   └── app/
│       ├── build.gradle.kts   # minSdk 24, targetSdk 35, Compose
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/com/example/agentnative/
│           │   ├── MainActivity.kt
│           │   ├── AgentViewModel.kt
│           │   └── RustCore.kt
│           └── res/           # tema, strings, iconos
├── scripts/
│   └── build_rust_android.sh  # cargo-ndk → jniLibs
├── .github/workflows/
│   └── android-rust-build.yml # CI: tests Rust + .so + APK + artifact
└── README.md
```

Los `.so` **no se versionan**: los genera `scripts/build_rust_android.sh` (en local) o el workflow de CI.

## Requisitos locales

| Herramienta | Versión | Instalación |
|---|---|---|
| Rust stable | latest | `curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \| sh` |
| Targets Android | — | `rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi` |
| cargo-ndk | latest | `cargo install cargo-ndk --locked` |
| Java | 17 | Temurin 17 recomendado |
| Android SDK | API 35 | Android Studio o `sdkmanager` |
| Android NDK | r26d | `sdkmanager "ndk;26.3.11579264"` |

Exporta el NDK antes de compilar Rust (cualquiera de estas valen):

```bash
export ANDROID_NDK_ROOT=$HOME/Android/Sdk/ndk/26.3.11579264
```

## Compilación local

1. **Rust (tests + librerías para Android):**

   ```bash
   cargo test                            # tests de la lógica pura (escritorio)
   ./scripts/build_rust_android.sh       # genera jniLibs/<abi>/libagent_core.so
   ```

2. **APK debug:**

   ```bash
   cd android
   ./gradlew assembleDebug
   ```

   El APK queda en `android/app/build/outputs/apk/debug/app-debug.apk`
   y contiene `lib/arm64-v8a/libagent_core.so` (+ `armeabi-v7a` y `x86_64`).

## CI (GitHub Actions)

El workflow `.github/workflows/android-rust-build.yml` se ejecuta en **push a `main`**, **push a `develop`** y en **pull requests**. Pasos: checkout → Java 17 (Temurin) → NDK r26d → Rust stable + 3 targets Android → `cargo test` → `cargo install cargo-ndk` → `./scripts/build_rust_android.sh` → `./gradlew assembleDebug` → verificación de los `.so` dentro del APK → subida del artifact.

- **Nombre del artifact:** `agent-native-debug-apk`
- **Dónde descargarlo:** pestaña *Actions* → run correspondiente → sección *Artifacts* → `agent-native-debug-apk`. Dentro viene `app-debug.apk`, instalable directamente (`Desconocidos / Instalar app` en el teléfono).

## Conectar con la API del agente (Fase 5 — HECHA)

La app ya no queda en la respuesta simulada: **Rust llama a una API real desplegada**
(Next.js en el sandbox con el modelo GLM vía `z-ai-web-dev-sdk`, nunca en cliente).

**Configuración en la app** (botón «Ajustes», guardada con `SharedPreferences`, nada va en el código):

- **URL de la API**: la base del servidor, p. ej. `https://preview-xxxx.space-z.ai` (sin `/api` final).
- **Token**: se envía como header `X-Agent-Token`; el servidor lo valida contra su `.env.local`.
- Si la URL está vacía, Rust cae al `SimulatedBackend` (respuesta simulada, sin red).

**Contrato** (`src/app/api/agent/run/route.ts` del proyecto web ↔ `rust-core/src/agent.rs::ApiBackend`):

```http
POST {base_url}/api/agent/run
X-Agent-Token: <token>
{"query": "tu consulta"}          // opcional: "history" (últimos 10 turnos)

→ 200 {"status":"ok","result":"...","engine":"glm"}
→ 400/401/500 {"status":"error","message":"legible"}
```

Los errores de red, timeout (90 s) y respuestas inválidas se traducen en Rust a
mensajes legibles (`AgentError::Api`) que la UI pinta en rojo.

**Fix v2 del APK**: el primer APK con conexión API no traía el permiso
`android.permission.INTERNET` en el manifest, así que toda llamada fallaba con
«no se pudo conectar». Instala la build v2 o posterior (el permiso ya está
declarado en `AndroidManifest.xml`).

**Límites actuales**: el servidor vive en el sandbox de preview (si duerme, la app
muestra «no se pudo conectar» — reabre el preview y reintenta); cada conversación
empieza limpia (sin memoria de esta sesión); la API no tiene acceso a tus archivos.

### Siguiente evolución

1. **Memoria de conversación**: enviar `history` desde la app (la API ya lo acepta).
2. **Ajustes adicionales**: system prompt configurable, modo thinking.
3. **Servidor propio**: migrar la API a un dominio fijo para no depender del sandbox.

## Decisiones técnicas

| Decisión | Elección | Motivo |
|---|---|---|
| AGP / Gradle | 8.7.3 / 8.10.2 | Par estable compatible entre sí y con compileSdk 35 |
| Kotlin | 2.0.21 + plugin compose | Compose Compiler integrado en el lenguaje (sin versiones separadas) |
| Compose BOM | 2024.10.01 | Última BOM estable al fijar el proyecto; material3 vía BOM |
| SDK | minSdk 24 · target/compile 35 | Mínimo pedido por el prompt; 35 = API estable actual |
| NDK en CI | r26d vía `nttld/setup-ndk@v1` | Versión pedida por el prompt; output `ndk-path` |
| `jni` crate | 0.21 | La que pide el prompt; API probada (`get_string`/`new_string`) |
| `crate-type` | `["cdylib", "rlib"]` | Desviación documentada: `cdylib` solo no permite `cargo test`; con `rlib` los tests corren en escritorio |
| Dep `jni` | `cfg(target_os="android")` | El puente JNI no se compila en escritorio; `cargo test` solo prueba lógica pura |
| HTTP desde Rust | `reqwest` blocking + `rustls-tls` | TLS puro-Rust (sin OpenSSL/NDK C); llamada bloqueante porque ya corre en `Dispatchers.IO` |
| `ring` (TLS) | se compila solo en CI | Necesita el clang del NDK; en local sin NDK solo se puede `cargo test` + check lógico |
| Config API | `SharedPreferences` en la app | URL y token editables en «Ajustes»; cero secretos en el código (exigido por el prompt) |
| ViewModel | `AndroidViewModel` | Necesario para leer/guardar la config sin pasarlo por la UI |
| ABIs | arm64-v8a + x86_64 + armeabi-v7a | Las dos priorizadas por el prompt + armv7 «si es fácil» (un flag más de cargo-ndk) |
| `.so` versionados | No | Se generan en CI; evita binarios grandes en el repo |
| Iconos | Adaptive icon (v26) + PNG por densidad | Fallback real para minSdk 24–25 |
| Llamada nativa | `Dispatchers.IO` dentro del ViewModel | Exigido por el prompt: no bloquear el hilo principal |

## Próximos pasos

1. **Memoria de conversación**: mandar `history` desde la app (la API ya lo acepta).
2. **Splits por ABI:** `splits { abi { ... } }` en Gradle para APKs por arquitectura (APK final más pequeño).
3. **Firma release:** keystore en GitHub Secrets + `signingConfig` para APK release instalables sin advertencia.
4. **Tests de UI:** Compose UI test del flujo TextField → botón → respuesta.
5. **CI más rápida:** cachear también la instalación de `cargo-ndk` (hoy se recompila en cada run, ~2 min).
6. **Servidor propio:** dominio fijo + HTTPS real para no depender del sandbox de preview.
