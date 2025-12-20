#!/usr/bin/env bash
set -euo pipefail

# -----------------------------
# Config (override via env vars)
# -----------------------------
HOST="${ELARA_HOST:-127.0.0.1}"
PORT="${ELARA_PORT:-7777}"
METHOD="${ELARA_METHOD:-dispatchEvent}"
EVENT_TYPE="${ELARA_EVENT_TYPE:-system_ready}"
FOLLOW="${ELARA_FOLLOW:-true}"
FOLLOW_MS="${ELARA_FOLLOW_MS:-200}"

# -----------------------------
# Args
# -----------------------------
if [ $# -lt 1 ]; then
  echo "Usage: $0 <app-name.es> [--no-follow]"
  echo
  echo "Example:"
  echo "  $0 my_app.es"
  echo "  $0 demo.es --no-follow"
  exit 1
fi

APP_NAME="$1"
SCRIPT_PATH="scripts/${APP_NAME}"

if [ ! -f "$SCRIPT_PATH" ]; then
  echo "❌ Script not found: $SCRIPT_PATH"
  exit 1
fi

if [[ "${2:-}" == "--no-follow" ]]; then
  FOLLOW="false"
fi

# -----------------------------
# Build CLI args
# -----------------------------
CLI_ARGS="--host=${HOST} --port=${PORT} --method=${METHOD} --eventType=${EVENT_TYPE} --script=$(realpath "$SCRIPT_PATH")"

if [[ "$FOLLOW" == "true" ]]; then
  CLI_ARGS="${CLI_ARGS} --follow --followMs=${FOLLOW_MS}"
fi

# -----------------------------
# Launch
# -----------------------------
echo "▶ Launching Elara RPC client"
echo "  Host:        $HOST"
echo "  Port:        $PORT"
echo "  Script:      $SCRIPT_PATH"
echo "  Follow:      $FOLLOW"
echo

mvn -q -DskipTests exec:java \
  -Dexec.mainClass=com.elara.script.rpc.ElaraRpcCli \
  -Dexec.args="$CLI_ARGS"

