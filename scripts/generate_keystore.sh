#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Genera un keystore de firma para los APK release de AgentNative y prepara
# los valores base64 / strings que hay que pegar como GitHub Secrets.
#
# NO sube nada a GitHub: solo crea el keystore local e imprime los secretos
# listos para copiar/pegar en:
#   https://github.com/kayb28/agent-native/settings/secrets/actions
#
# Uso:   ./scripts/generate_keystore.sh
#        ./scripts/generate_keystore.sh /ruta/personal.keystore  # keystore ya existente
# ---------------------------------------------------------------------------
set -euo pipefail

KEYSTORE="${1:-$HOME/.gradle/agent-native.keystore}"
ALIAS="agent-native"

# Contraseñas: si no existen en env, se generan aleatorias de 32 chars y se
# imprimen al final (cópialas en sus secrets correspondientes).
KS_PASS="${KEYSTORE_PASSWORD:-$(openssl rand -base64 24 | tr -d '/=+' | head -c 32)}"
KEY_PASS="${KEY_PASSWORD:-$KS_PASS}"

if [ -f "$KEYSTORE" ]; then
    echo "== Usando keystore existente: $KEYSTORE =="
else
    mkdir -p "$(dirname "$KEYSTORE")"
    echo "== Generando keystore nuevo en: $KEYSTORE =="
    echo "   (responde a las preguntas de DN. Para desarrollo local puedes dar"
    echo "    valores ficticios; para Play Console usa datos reales)"
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias "$ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" \
        -keypass "$KEY_PASS"
fi

# Verifica el keystore
echo "== Verificación =="
keytool -list -v -keystore "$KEYSTORE" -storepass "$KS_PASS" -alias "$ALIAS" \
    | grep -E 'Alias name|Owner|Valid from|Signature algorithm name|Version' || true

# Genera el base64 del keystore para el secret ANDROID_KEYSTORE_BASE64
B64=$(base64 -w 0 "$KEYSTORE" 2>/dev/null || base64 "$KEYSTORE" | tr -d '\n')

cat <<EOF

========================================================================
  SECRETS PARA GITHUB (Settings → Secrets and variables → Actions)
========================================================================

1) ANDROID_KEYSTORE_BASE64
   (valor base64 del keystore — lo decodea el workflow a ~/.gradle/agent-native.keystore)

   $B64

2) KEYSTORE_PASSWORD

   $KS_PASS

3) KEY_ALIAS

   $ALIAS

4) KEY_PASSWORD

   $KEY_PASS

========================================================================
  KEYSOTRE LOCAL (no se sube al repo)
========================================================================
  Ruta:   $KEYSTORE
  Guarda una copia segura. Si la pierdes, no podrás actualizar la app en
  Play Console con el mismo packageId.

========================================================================
  Cómo publicar un release firmado
========================================================================
  git tag v0.1.0
  git push origin v0.1.0

  → dispara el job "Signed release APK" del workflow.
  → sube los APKs firmados como artifacts y crea un GitHub Release con los
    binarios adjuntos.

========================================================================
EOF
