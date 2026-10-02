#!/usr/bin/env bash
# Run on machine 1: primary replica plus sensor simulator.
# Usage: bash scripts/run-primary-machine.sh <backup-host> <receiver-host> [passive|active]
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BACKUP_HOST="${1:-}"
RECEIVER_HOST="${2:-}"
MODE="${3:-passive}"
LOG_DIR="${ROOT_DIR}/target/recovery-primary-logs"

if [ -z "${BACKUP_HOST}" ] || [ -z "${RECEIVER_HOST}" ]; then
  echo "Usage: $0 <backup-host> <receiver-host> [passive|active]" >&2
  exit 2
fi
if [ "${MODE}" != "passive" ] && [ "${MODE}" != "active" ]; then
  echo "Mode must be passive or active, was: ${MODE}" >&2
  exit 2
fi
if [ ! -f "${ROOT_DIR}/critical-service/target/critical-service.jar" ] \
    || [ ! -f "${ROOT_DIR}/sensor-sim/target/sensor-sim.jar" ]; then
  echo "Build the jars first with: mvn -q package" >&2
  exit 1
fi

mkdir -p "${LOG_DIR}"
PRIMARY_PID=""
cleanup() {
  if [ -n "${PRIMARY_PID}" ] && kill -0 "${PRIMARY_PID}" 2>/dev/null; then
    kill "${PRIMARY_PID}" 2>/dev/null || true
    wait "${PRIMARY_PID}" 2>/dev/null || true
  fi
}
trap cleanup INT TERM EXIT

java \
  -Dredundancy.mode="${MODE}" \
  -Dreplica.role=primary \
  -Dservice.listen.port=5001 \
  -Dservice.backup.host="${BACKUP_HOST}" \
  -Dservice.backup.port=5011 \
  -Dservice.heartbeat.target.host="${RECEIVER_HOST}" \
  -Dservice.heartbeat.target.port=5002 \
  -jar "${ROOT_DIR}/critical-service/target/critical-service.jar" \
  > "${LOG_DIR}/primary.log" 2>&1 &
PRIMARY_PID=$!

echo "Primary started as pid ${PRIMARY_PID}; log: ${LOG_DIR}/primary.log"
echo "Starting sensor in ${MODE} mode. Press Ctrl+C to stop."
java \
  -Dredundancy.mode="${MODE}" \
  -Dsensor.target.host=localhost \
  -Dsensor.target.port=5001 \
  -Dservice.backup.host="${BACKUP_HOST}" \
  -Dservice.backup.port=5011 \
  -jar "${ROOT_DIR}/sensor-sim/target/sensor-sim.jar" \
  2>&1 | tee "${LOG_DIR}/sensor-sim.log"

