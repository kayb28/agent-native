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
│      ▼                                                            │
│  agent.rs   AgentCore::run(input) -> JSON String                  │
│      │  SimulatedBackend (MVP)                                    │
│      │  ── PUNTO DE EXTENSIÓN: trait AgentBackend ──              │
│      │  ApiBackend (futuro) → POST /api/agent/run                 │
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

## Conectar el agente real (Fase 5)

El único sitio que cambia es **`rust-core/src/agent.rs`**:

1. Añade un backend nuevo que implemente el trait `AgentBackend`, por ejemplo
   `ApiBackend { base_url: String }`, con `reqwest` (bloqueante o con `tokio`) + `rustls`:
   - Petición: `POST {base_url}/api/agent/run` con cuerpo `{"query": "..."}`.
   - Respuesta esperada: `{"status": "ok", "result": "..."}`.
2. En `rust-core/src/jni_bridge.rs` cambia `SimulatedBackend` por `ApiBackend`
   (una sola línea; el contrato JSON hacia Kotlin no cambia).
3. La URL base **no va hardcodeada**: léela de una variable de entorno en CI o de
   `local.properties`/`BuildConfig` en la app; nunca de secretos en el código.
4. Maneja errores de red devolviendo el JSON de error estándar
   (`{"status":"error","engine":"rust","message":"...","input":"..."}`), que la UI ya sabe pintar.

Mientras no exista una API del agente, el MVP queda con `SimulatedBackend`.

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
| ABIs | arm64-v8a + x86_64 + armeabi-v7a | Las dos priorizadas por el prompt + armv7 «si es fácil» (un flag más de cargo-ndk) |
| `.so` versionados | No | Se generan en CI; evita binarios grandes en el repo |
| Iconos | Adaptive icon (v26) + PNG por densidad | Fallback real para minSdk 24–25 |
| Llamada nativa | `Dispatchers.IO` dentro del ViewModel | Exigido por el prompt: no bloquear el hilo principal |

## Próximos pasos

1. **Fase 5 — Agente real:** implementar `ApiBackend` (contrato en la sección anterior) y decidir dónde vive la URL/secreto (`local.properties` o GitHub Secrets).
2. **Splits por ABI:** `splits { abi { ... } }` en Gradle para APKs por arquitectura (APK final más pequeño).
3. **Firma release:** keystore en GitHub Secrets + `signingConfig` para APK release instalables sin advertencia.
4. **Tests de UI:** Compose UI test del flujo TextField → botón → respuesta.
5. **CI más rápida:** cachear también la instalación de `cargo-ndk` (hoy se recompila en cada run, ~2 min).
6. **Telemetría ligera:** log de latencia y errores de `processQuery` para medir el agente real.
