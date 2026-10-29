#!/usr/bin/env bash
# Prueba de integración: núcleo Kotlin (orquestador + HttpControlClient) → control-bridge.
# No requiere hardware. Evidencia de software para EXP-08 (no sustituye la prueba con el casco).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'kill ${PID:-0} 2>/dev/null || true; rm -rf "$WORK"' EXIT

cd "$ROOT/control-bridge"
export CONTROL_TOKENS_FILE="$WORK/tokens.json" CONTROL_DATA_DIR="$WORK/data" PORT=18787
WRITE=$(node src/tokens.ts e2e-phone control.radar.write control.ideas.write control.tasks.write control.notes.write | tail -1)
READ=$(node src/tokens.ts e2e-reader control.radar.read control.ideas.read | tail -1)
node src/server.ts > "$WORK/server.log" 2>&1 &
PID=$!
for _ in $(seq 1 50); do curl -sf "http://127.0.0.1:$PORT/healthz" >/dev/null && break; sleep 0.1; done

cd "$ROOT/android"
CONTROL_E2E_URL="http://127.0.0.1:$PORT" CONTROL_E2E_TOKEN="$WRITE" CONTROL_E2E_READ_TOKEN="$READ" \
  gradle -q :core:test --tests '*ControlBridgeE2ETest*' --rerun-tasks -i 2>&1 | grep -E "Respuesta hablada|Registro en Control|FAILED|PASSED" || true
echo "--- log del puente ---"
cat "$WORK/server.log"
