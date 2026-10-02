#!/usr/bin/env bash
# Run on machine 2: backup replica, heartbeat receiver, and monitor.
# Usage: bash scripts/run-backup-machine.sh [passive|active]
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
MODE="${1:-passive}"
LOG_DIR="${ROOT_DIR}/target/recovery-backup-logs"

if [ "${MODE}" != "passive" ] && [ "${MODE}" != "active" ]; then
  echo "Mode must be passive or active, was: ${MODE}" >&2
  exit 2
fi
for jar in monitor receiver critical-service; do
  if [ ! -f "${ROOT_DIR}/${jar}/target/${jar}.jar" ]; then
    echo "Build the jars first with: mvn -q package" >&2
    exit 1
  fi
done

mkdir -p "${LOG_DIR}"
MON_PID=""
BACKUP_PID=""
RECEIVER_PID=""
cleanup() {
  for pid in "${RECEIVER_PID}" "${BACKUP_PID}" "${MON_PID}"; do
    if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  done
  wait "${RECEIVER_PID}" "${BACKUP_PID}" "${MON_PID}" 2>/dev/null || true
}
trap cleanup INT TERM EXIT

java -jar "${ROOT_DIR}/monitor/target/monitor.jar" \
  > "${LOG_DIR}/monitor.log" 2>&1 &
MON_PID=$!

java \
  -Dredundancy.mode="${MODE}" \
  -Dreplica.role=backup \
  -Dservice.listen.port=5011 \
  -Dservice.backup.host=localhost \
  -Dservice.backup.port=5011 \
  -Dservice.heartbeat.target.host=localhost \
  -jar "${ROOT_DIR}/critical-service/target/critical-service.jar" \
  > "${LOG_DIR}/backup.log" 2>&1 &
BACKUP_PID=$!

java \
  -Dservice.backup.host=localhost \
  -Dservice.backup.port=5011 \
  -jar "${ROOT_DIR}/receiver/target/receiver.jar" \
  > "${LOG_DIR}/receiver.log" 2>&1 &
RECEIVER_PID=$!

echo "Backup-side processes started in ${MODE} mode:"
echo "  monitor=${MON_PID}, backup=${BACKUP_PID}, receiver=${RECEIVER_PID}"
echo "Logs: ${LOG_DIR}"
echo "Press Ctrl+C to stop."
wait "${RECEIVER_PID}"

