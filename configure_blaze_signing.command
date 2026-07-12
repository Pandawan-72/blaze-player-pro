#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

LOCAL_PROPERTIES="$SCRIPT_DIR/local.properties"

if [[ ! -f "$LOCAL_PROPERTIES" && -f "$SCRIPT_DIR/local.properties.generated" ]]; then
  cp "$SCRIPT_DIR/local.properties.generated" "$LOCAL_PROPERTIES"
fi

echo "Configuration de la signature release de Blaze Player"
echo "Utilisez impérativement le keystore ayant servi aux anciens builds Google Play."
echo

read -r -p "Chemin absolu du keystore (.jks ou .keystore) : " KEYSTORE_PATH
KEYSTORE_PATH="${KEYSTORE_PATH/#\~/$HOME}"

if [[ ! -f "$KEYSTORE_PATH" ]]; then
  echo "Erreur : fichier introuvable : $KEYSTORE_PATH" >&2
  exit 1
fi

read -r -p "Alias de la clé : " KEY_ALIAS
if [[ -z "$KEY_ALIAS" ]]; then
  echo "Erreur : l'alias ne peut pas être vide." >&2
  exit 1
fi

read -r -s -p "Mot de passe du keystore : " STORE_PASSWORD
echo
if [[ -z "$STORE_PASSWORD" ]]; then
  echo "Erreur : le mot de passe du keystore ne peut pas être vide." >&2
  exit 1
fi

read -r -s -p "Mot de passe de la clé (Entrée = même mot de passe) : " KEY_PASSWORD
echo
if [[ -z "$KEY_PASSWORD" ]]; then
  KEY_PASSWORD="$STORE_PASSWORD"
fi

echo
echo "Vérification du keystore et de l'alias…"
if ! keytool -list \
    -keystore "$KEYSTORE_PATH" \
    -storepass "$STORE_PASSWORD" \
    -alias "$KEY_ALIAS" >/dev/null 2>&1; then
  echo "Erreur : keystore, mot de passe ou alias incorrect." >&2
  exit 1
fi

export BLAZE_KEYSTORE_FILE_VALUE="$KEYSTORE_PATH"
export BLAZE_KEYSTORE_PASSWORD_VALUE="$STORE_PASSWORD"
export BLAZE_KEY_ALIAS_VALUE="$KEY_ALIAS"
export BLAZE_KEY_PASSWORD_VALUE="$KEY_PASSWORD"
export LOCAL_PROPERTIES_PATH="$LOCAL_PROPERTIES"

python3 <<'PY'
from pathlib import Path
import os

path = Path(os.environ["LOCAL_PROPERTIES_PATH"])
values = {
    "BLAZE_KEYSTORE_FILE": os.environ["BLAZE_KEYSTORE_FILE_VALUE"],
    "BLAZE_KEYSTORE_PASSWORD": os.environ["BLAZE_KEYSTORE_PASSWORD_VALUE"],
    "BLAZE_KEY_ALIAS": os.environ["BLAZE_KEY_ALIAS_VALUE"],
    "BLAZE_KEY_PASSWORD": os.environ["BLAZE_KEY_PASSWORD_VALUE"],
}

def escape_value(value: str) -> str:
    value = value.replace("\\", "\\\\")
    value = value.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    if value.startswith(" "):
        value = "\\" + value
    return value

existing_lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
keys = set(values)
output = []
seen = set()

for line in existing_lines:
    stripped = line.lstrip()
    matched = None
    if stripped and not stripped.startswith(("#", "!")):
        for key in keys:
            if stripped.startswith(key + "=") or stripped.startswith(key + ":"):
                matched = key
                break
    if matched:
        if matched not in seen:
            output.append(f"{matched}={escape_value(values[matched])}")
            seen.add(matched)
    else:
        placeholder = stripped.lstrip("#").strip()
        if any(placeholder.startswith(key + "=") for key in keys):
            continue
        output.append(line)

if output and output[-1] != "":
    output.append("")

for key in (
    "BLAZE_KEYSTORE_FILE",
    "BLAZE_KEYSTORE_PASSWORD",
    "BLAZE_KEY_ALIAS",
    "BLAZE_KEY_PASSWORD",
):
    if key not in seen:
        output.append(f"{key}={escape_value(values[key])}")

path.write_text("\n".join(output).rstrip() + "\n", encoding="utf-8")
PY

unset STORE_PASSWORD KEY_PASSWORD
unset BLAZE_KEYSTORE_FILE_VALUE BLAZE_KEYSTORE_PASSWORD_VALUE
unset BLAZE_KEY_ALIAS_VALUE BLAZE_KEY_PASSWORD_VALUE LOCAL_PROPERTIES_PATH

echo
echo "local.properties configuré avec succès :"
echo "$LOCAL_PROPERTIES"
echo
echo "Vous pouvez maintenant lancer :"
echo "./gradlew clean bundleRelease"
