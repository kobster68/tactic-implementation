#!/usr/bin/env bash
#
# run-system.sh -- Continuous four-process heartbeat simulation.
#
# Starts:
#   monitor -> receiver -> critical-service -> sensor-sim
#
# The simulator uses its normal random fault probability. A malformed reading
# may eventually crash critical-service, allowing the receiver and monitor to
# report the failure.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOG_DIR="${ROOT_DIR}/target/system-logs"
START_DELAY="${SYSTEM_START_DELAY:-1}"

cd "${ROOT_DIR}"

echo "== building =="
if ! mvn -q package; then
  echo "BUILD FAILED" >&2
  exit 1
fi

mkdir -p "${LOG_DIR}"
rm -f "${LOG_DIR}"/*.log

echo "== starting monitor, receiver, critical-service, then sensor-sim =="

java -jar "${ROOT_DIR}/monitor/target/monitor.jar" \
  > "${LOG_DIR}/monitor.log" 2>&1 &
MON_PID=$!
sleep "${START_DELAY}"

java -jar "${ROOT_DIR}/receiver/target/receiver.jar" \
  > "${LOG_DIR}/receiver.log" 2>&1 &
RCV_PID=$!
sleep "${START_DELAY}"

java -jar "${ROOT_DIR}/critical-service/target/critical-service.jar" \
  > "${LOG_DIR}/critical-service.log" 2>&1 &
SVC_PID=$!
sleep "${START_DELAY}"

java -jar "${ROOT_DIR}/sensor-sim/target/sensor-sim.jar" \
  > "${LOG_DIR}/sensor-sim.log" 2>&1 &
SIM_PID=$!

cleanup() {
  echo
  echo "== stopping simulation =="

  for pid in "${SIM_PID}" "${SVC_PID}" "${RCV_PID}" "${MON_PID}"; do
    if kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  done

  wait "${SIM_PID}" "${SVC_PID}" "${RCV_PID}" "${MON_PID}" 2>/dev/null || true

  echo "Logs are available in ${LOG_DIR}"
}

trap cleanup INT TERM EXIT

echo "Simulation running. Press Ctrl+C to stop."
echo "Logs: ${LOG_DIR}"

wait "${MON_PID}"