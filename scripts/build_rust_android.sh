#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Compila el crate `agent_core` para Android con cargo-ndk y copia los .so
# a android/app/src/main/jniLibs/, listos para que Gradle los empaquete.
#
# Uso (desde la raíz del repositorio):   ./scripts/build_rust_android.sh
#
# Requisitos previos:
#   - Rust stable + targets Android:
#       rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi
#   - cargo-ndk:      cargo install cargo-ndk --locked
#   - Android NDK apuntado por ANDROID_NDK_ROOT, NDK_HOME o ANDROID_NDK_HOME
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR/rust-core"

# Localizar el NDK: acepta cualquiera de las tres variables habituales.
NDK_PATH="${ANDROID_NDK_ROOT:-${NDK_HOME:-${ANDROID_NDK_HOME:-}}}"
if [ -z "$NDK_PATH" ]; then
    echo "ERROR: no se encontró el Android NDK." >&2
    echo "       Define ANDROID_NDK_ROOT (o NDK_HOME / ANDROID_NDK_HOME) y reintenta." >&2
    echo "       Ejemplo:  export ANDROID_NDK_ROOT=\$HOME/Android/Sdk/ndk/26.3.11579264" >&2
    exit 1
fi
export ANDROID_NDK_ROOT="$NDK_PATH"
export NDK_HOME="$NDK_PATH"
export ANDROID_NDK_HOME="$NDK_PATH"
echo "== NDK: $NDK_PATH =="

# Verificar que los targets Rust están instalados (falla pronto con mensaje claro).
MISSING=$(rustup target list --installed | grep -cE 'aarch64-linux-android|x86_64-linux-android|armv7-linux-androideabi' || true)
if [ "$MISSING" -lt 3 ]; then
    echo "== Instalando targets Rust para Android =="
    rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi
fi

# Compilar (release) los tres ABIs y volcar los .so directamente a jniLibs.
cargo ndk \
    -t arm64-v8a \
    -t x86_64 \
    -t arm \
    -o ../android/app/src/main/jniLibs \
    build --release

echo "== OK: librerías generadas =="
find "$ROOT_DIR/android/app/src/main/jniLibs" -name '*.so' -type f
