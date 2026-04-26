#!/usr/bin/env bash
# Bootstrap runevault-runelite-plugin on a fresh machine.
# Usage: ./bootstrap.sh
set -euo pipefail

cd "$(dirname "$0")"

# 1. Java sanity check (RuneLite plugins need JDK 11+)
if ! command -v java >/dev/null 2>&1; then
  echo "error: java not found — install JDK 11+ (e.g. brew install openjdk@17)" >&2
  exit 1
fi
java -version

# 2. local.properties for credentials
if [[ ! -f local.properties ]] && [[ -f local.properties.example ]]; then
  cp local.properties.example local.properties
  echo ""
  echo "Created local.properties from example."
  echo "Pull real Supabase/edge secrets from your password manager."
  echo ""
fi

# 3. Build
./gradlew build

echo ""
echo "runevault-runelite-plugin ready."
echo "  ./gradlew run    # launch RuneLite with plugin loaded (preferred dev workflow)"
echo "  ./gradlew build  # produce JAR in build/libs/"
