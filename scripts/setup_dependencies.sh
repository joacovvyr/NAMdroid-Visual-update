#!/usr/bin/env bash
# Descarga Eigen (libreria de algebra lineal, solo headers) que necesita
# el motor NAM para compilar. No la incluimos en el zip porque pesa ~20MB+
# y GitHub Actions (o tu PC) la baja fresca en segundos.
#
# Se puede correr:
#   - Automaticamente en GitHub Actions (ya esta en el workflow)
#   - A mano en tu PC si algun dia instalas Android Studio: bash scripts/setup_dependencies.sh

set -euo pipefail

DEST="app/src/main/cpp/nam/third_party/eigen"

if [ -d "$DEST/Eigen" ]; then
  echo "Eigen ya esta presente en $DEST, no hace falta descargarlo de nuevo."
  exit 0
fi

echo "Descargando Eigen en $DEST ..."
rm -rf "$DEST"
mkdir -p "$DEST"

# Repo oficial (GitLab). Si tu red bloquea gitlab.com, usa el mirror de GitHub
# comentado abajo como alternativa.
if git clone --depth 1 https://gitlab.com/libeigen/eigen "$DEST"; then
  echo "Eigen descargado desde GitLab (oficial)."
else
  echo "GitLab no accesible, probando mirror de GitHub..."
  git clone --depth 1 https://github.com/eigenteam/eigen-git-mirror.git "$DEST"
fi

rm -rf "$DEST/.git"
echo "Listo."
