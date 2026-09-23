#!/usr/bin/env bash
# Baixa o core mGBA (GB/GBC/GBA) do buildbot oficial do libretro
# e coloca em app/src/main/jniLibs com o prefixo "lib" que o Android exige.
# Uso (Linux/macOS/Git Bash, na raiz do projeto):  ./scripts/baixar-cores.sh
set -euo pipefail

CORES=(mgba)
ABIS=(arm64-v8a)   # adicione armeabi-v7a para aparelhos 32 bits (e no abiFilters do Gradle)
BASE="https://buildbot.libretro.com/nightly/android/latest"
TMP="$(mktemp -d)"

for abi in "${ABIS[@]}"; do
  dest="app/src/main/jniLibs/$abi"
  mkdir -p "$dest"
  for core in "${CORES[@]}"; do
    file="${core}_libretro_android.so"
    echo "Baixando $core ($abi)..."
    curl -fL "$BASE/$abi/$file.zip" -o "$TMP/$file.zip"
    unzip -o -q "$TMP/$file.zip" -d "$TMP"
    mv -f "$TMP/$file" "$dest/lib$file"
    echo "  -> $dest/lib$file"
  done
done
rm -rf "$TMP"
echo "Pronto."
