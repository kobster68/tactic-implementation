#!/usr/bin/env bash
#
# run-tracer.sh -- Bounded four-process tracer.
#
# Builds every module, then launches the four processes in receive-before-send order
# (monitor, receiver, critical-service, then sensor-sim) using these UDP hops:
#   sensor-sim --SensorReading--> critical-service --Heartbeat--> receiver --StatusReport--> monitor
# The critical service sends periodic heartbeats for a bounded demo runtime; the other
# processes still handle one message each. Prints all four logs and exits non-zero
# if any process failed. This checks process exits, not fault detection or UDP delivery.
# Works under bash (via the shebang) on macOS and Linux.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOG_DIR="${ROOT_DIR}/target/tracer-logs"
START_DELAY="${TRACER_START_DELAY:-1}" # seconds to let each listener bind before the next start
RUN_FOR_MS="${TRACER_RUN_FOR_MS:-5000}" # critical-service demo runtime; allow time for sensor startup

cd "${ROOT_DIR}"

echo "== building (mvn -q package) =="
if ! mvn -q package; then
  echo "BUILD FAILED" >&2
  exit 1
fi

mkdir -p "${LOG_DIR}"
rm -f "${LOG_DIR}"/*.log

echo "== starting monitor, receiver, critical-service, then sensor-sim =="
java -jar "${ROOT_DIR}/monitor/target/monitor.jar"                 > "${LOG_DIR}/monitor.log" 2>&1 &
MON_PID=$!
sleep "${START_DELAY}"
java -jar "${ROOT_DIR}/receiver/target/receiver.jar"               > "${LOG_DIR}/receiver.log" 2>&1 &
RCV_PID=$!
sleep "${START_DELAY}"
java -jar "${ROOT_DIR}/critical-service/target/critical-service.jar" \
  --run-for-ms "${RUN_FOR_MS}" > "${LOG_DIR}/critical-service.log" 2>&1 &
SVC_PID=$!
sleep "${START_DELAY}"
java -jar "${ROOT_DIR}/sensor-sim/target/sensor-sim.jar"           > "${LOG_DIR}/sensor-sim.log" 2>&1 &
SIM_PID=$!

status=0
for entry in "sensor-sim:${SIM_PID}" "critical-service:${SVC_PID}" "receiver:${RCV_PID}" "monitor:${MON_PID}"; do
  name="${entry%%:*}"
  pid="${entry##*:}"
  if ! wait "${pid}"; then
    echo "PROCESS FAILED: ${name} (pid ${pid}, non-zero exit)" >&2
    status=1
  fi
done

echo
echo "==================== TRACER LOGS ===================="
for name in sensor-sim critical-service receiver monitor; do
  echo "-------------------- ${name} --------------------"
  cat "${LOG_DIR}/${name}.log" 2>/dev/null || echo "(no log)"
  echo
done

if [ "${status}" -ne 0 ]; then
  echo "TRACER RESULT: FAILED" >&2
else
  echo "TRACER RESULT: OK (all processes exited successfully; inspect logs above for message flow)"
fi
exit "${status}"
