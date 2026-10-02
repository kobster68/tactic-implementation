#!/usr/bin/env bash
# Deterministic local recovery acceptance test.
#
# It sends K-1 right-drift readings, kills the primary, waits for receiver-driven promotion, then
# sends the Kth reading to the backup and requires the lane-departure warning. This proves the
# backup retained the countdown across failover. In active mode phase one is fanned out to both
# replicas; in passive mode the backup obtains that state from checkpoints.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOG_DIR="${ROOT_DIR}/target/recovery-demo-logs"
MODE="${DEMO_MODE:-passive}"
WARNING_THRESHOLD="${DEMO_WARNING_THRESHOLD:-3}"
START_DELAY="${DEMO_START_DELAY:-1}"
TIMEOUT_SECS="${DEMO_TIMEOUT_SECS:-12}"
PROMOTE_PATTERN="${DEMO_PROMOTE_PATTERN:-promot}"
WARNING_PATTERN="${DEMO_WARNING_PATTERN:-lane.*warning|warning.*lane}"

if [ "${MODE}" != "passive" ] && [ "${MODE}" != "active" ]; then
  echo "DEMO_MODE must be passive or active, was: ${MODE}" >&2
  exit 2
fi
if ! [[ "${WARNING_THRESHOLD}" =~ ^[2-9][0-9]*$ ]]; then
  echo "DEMO_WARNING_THRESHOLD must be a whole number of at least 2" >&2
  exit 2
fi

cd "${ROOT_DIR}"
echo "== building and testing =="
if ! mvn -q package; then
  echo "BUILD FAILED" >&2
  exit 1
fi

mkdir -p "${LOG_DIR}"
: > "${LOG_DIR}/monitor.log"
: > "${LOG_DIR}/receiver.log"
: > "${LOG_DIR}/primary.log"
: > "${LOG_DIR}/backup.log"
: > "${LOG_DIR}/sensor-phase-1.log"
: > "${LOG_DIR}/sensor-phase-2.log"

MON_PID=""
RECEIVER_PID=""
PRIMARY_PID=""
BACKUP_PID=""
cleanup() {
  for pid in "${PRIMARY_PID}" "${BACKUP_PID}" "${RECEIVER_PID}" "${MON_PID}"; do
    if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  done
  wait "${PRIMARY_PID}" "${BACKUP_PID}" "${RECEIVER_PID}" "${MON_PID}" 2>/dev/null || true
}
trap cleanup INT TERM EXIT

wait_for_log() {
  local file="$1"
  local pattern="$2"
  local label="$3"
  local elapsed=0
  while [ "${elapsed}" -lt "${TIMEOUT_SECS}" ]; do
    if grep -Eiq "${pattern}" "${file}" 2>/dev/null; then
      return 0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  echo "TIMEOUT: ${label}; expected /${pattern}/ in ${file}" >&2
  return 1
}

java -jar monitor/target/monitor.jar > "${LOG_DIR}/monitor.log" 2>&1 &
MON_PID=$!
sleep "${START_DELAY}"

java \
  -Dredundancy.mode="${MODE}" \
  -Dreplica.role=backup \
  -Dservice.listen.port=5011 \
  -Dservice.backup.host=localhost \
  -Dservice.backup.port=5011 \
  -jar critical-service/target/critical-service.jar \
  > "${LOG_DIR}/backup.log" 2>&1 &
BACKUP_PID=$!
sleep "${START_DELAY}"

java \
  -Dservice.backup.host=localhost \
  -Dservice.backup.port=5011 \
  -jar receiver/target/receiver.jar \
  > "${LOG_DIR}/receiver.log" 2>&1 &
RECEIVER_PID=$!
sleep "${START_DELAY}"

java \
  -Dredundancy.mode="${MODE}" \
  -Dreplica.role=primary \
  -Dservice.listen.port=5001 \
  -Dservice.backup.host=localhost \
  -Dservice.backup.port=5011 \
  -jar critical-service/target/critical-service.jar \
  > "${LOG_DIR}/primary.log" 2>&1 &
PRIMARY_PID=$!
sleep "${START_DELAY}"

PHASE_ONE_COUNT=$((WARNING_THRESHOLD - 1))
java \
  -Dredundancy.mode="${MODE}" \
  -Dsensor.faultProbability=0 \
  -Dsensor.periodMs=300 \
  -Dservice.backup.host=localhost \
  -Dservice.backup.port=5011 \
  -jar sensor-sim/target/sensor-sim.jar \
  --count "${PHASE_ONE_COUNT}" --lane-offset 1.0 \
  > "${LOG_DIR}/sensor-phase-1.log" 2>&1

# Give the passive primary time to publish its latest checkpoint before the crash.
sleep 1
echo "== killing primary after ${PHASE_ONE_COUNT}/${WARNING_THRESHOLD} drift readings =="
kill "${PRIMARY_PID}"
wait "${PRIMARY_PID}" 2>/dev/null || true
PRIMARY_PID=""

wait_for_log "${LOG_DIR}/receiver.log" "${PROMOTE_PATTERN}" "receiver did not promote the backup"
wait_for_log "${LOG_DIR}/backup.log" "${PROMOTE_PATTERN}" "backup did not acknowledge promotion"

# Passive routing moves to the promoted backup. Active routing still fans out, with the dead
# primary harmlessly receiving one UDP datagram and the live backup receiving the identical input.
if [ "${MODE}" = "passive" ]; then
  SENSOR_PRIMARY_PORT=5011
else
  SENSOR_PRIMARY_PORT=5001
fi
java \
  -Dredundancy.mode="${MODE}" \
  -Dsensor.faultProbability=0 \
  -Dsensor.periodMs=300 \
  -Dsensor.target.port="${SENSOR_PRIMARY_PORT}" \
  -Dservice.backup.host=localhost \
  -Dservice.backup.port=5011 \
  -jar sensor-sim/target/sensor-sim.jar \
  --count 1 --lane-offset 1.0 \
  > "${LOG_DIR}/sensor-phase-2.log" 2>&1

wait_for_log "${LOG_DIR}/backup.log" "${WARNING_PATTERN}" \
  "promoted backup did not fire the lane warning on reading ${WARNING_THRESHOLD}"

if ! kill -0 "${BACKUP_PID}" 2>/dev/null; then
  echo "RECOVERY DEMO FAILED: backup exited" >&2
  exit 1
fi

echo "RECOVERY DEMO OK: ${MODE} backup preserved ${PHASE_ONE_COUNT} readings, was promoted,"
echo "and fired the warning on reading ${WARNING_THRESHOLD}."
echo "Logs: ${LOG_DIR}"

