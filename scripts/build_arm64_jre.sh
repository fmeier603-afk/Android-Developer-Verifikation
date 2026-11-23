#!/usr/bin/env bash
set -euo pipefail

# Baut eine minimale ARM64-OpenJDK-21-Runtime via jlink für JarRunner
# Voraussetzungen: Android-ARM64 JDK-21-Build (z. B. PojavLauncher / Termux / Adoptium cross-jmods)
# Aufruf: ./scripts/build_arm64_jre.sh /pfad/zu/android-arm64-jdk-21/jmods ./jre-arm64-minimal

JMODS_DIR="${1:?Bitte Pfad zum ARM64 jmods-Verzeichnis angeben}"
OUT_DIR="${2:-./jre-arm64-minimal}"

rm -rf "$OUT_DIR"

jlink \
  --module-path "$JMODS_DIR" \
  --add-modules java.base,java.logging,java.net.http,jdk.unsupported \
  --strip-debug \
  --no-man-pages \
  --no-header-files \
  --compress=zip-6 \
  --output "$OUT_DIR"

echo "Minimale ARM64-JRE erstellt unter: $OUT_DIR"
du -sh "$OUT_DIR"
echo "Tipp: Verpacken als ZIP für Import in JarRunner -> Laufzeit-Tab:"
echo "  (cd $OUT_DIR && zip -r ../jre-arm64-minimal.zip .)"
