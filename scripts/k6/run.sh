#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
K6_BIN="${K6_BIN:-k6}"
if ! command -v "$K6_BIN" >/dev/null 2>&1; then
  echo 'k6 not found. Set K6_BIN to a binary compatible with this OS/CPU.' >&2
  exit 1
fi
"$K6_BIN" version
PROFILE="${PROFILE:-smoke}"
export PROFILE
RESULT_DIR="${RESULT_DIR:-build/k6/$(date +%Y%m%d-%H%M%S)-$PROFILE}"
mkdir -p "$RESULT_DIR"
echo "Profile: $PROFILE; results: $RESULT_DIR"
"$K6_BIN" run --summary-export "$RESULT_DIR/summary.json" scripts/k6/serving.js 2>&1 | tee "$RESULT_DIR/console.log"
