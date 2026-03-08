#!/usr/bin/env bash
set -euo pipefail

# Capture a fresh logcat to a timestamped file.
# Usage: [DURATION=30s] [OUTDIR=logcat] [ADB_SERIAL=device-id] ./capture_logcat.sh [package]

PKG="${1:-com.googlevideo.sabr.sample}"
DURATION="${DURATION:-40s}"
OUTDIR="${OUTDIR:-logcat}"
declare -a DEVICE_OPTS=()
if [[ -n "${ADB_SERIAL:-}" ]]; then
  DEVICE_OPTS=(-s "$ADB_SERIAL")
fi

mkdir -p "$OUTDIR"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$OUTDIR/logcat_${TS}.txt"

echo "Clearing logcat..."
adb ${DEVICE_OPTS:+"${DEVICE_OPTS[@]}"} logcat -c || true

echo "Starting logcat for $DURATION to $OUT" 
if command -v timeout >/dev/null 2>&1; then
  timeout "$DURATION" adb ${DEVICE_OPTS:+"${DEVICE_OPTS[@]}"} logcat -v time > "$OUT"
else
  adb ${DEVICE_OPTS:+"${DEVICE_OPTS[@]}"} logcat -v time > "$OUT"
fi

echo "Saved logcat to $OUT"
