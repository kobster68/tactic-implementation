#!/usr/bin/env bash
#
# run-tracer.sh -- Bounded four-process tracer.
#
# Builds every module, then launches the four processes in receive-before-send order
# (monitor, receiver, critical-service, then sensor-sim) using these UDP hops:
#   sensor-sim --SensorReading--> critical-service --Heartbeat--> receiver --StatusReport--> monitor
# The critical service beats for a bounded runtime and then stops; a grace period lets the
# receiver observe the silence and report the service FAILED, after which the receiver and
# monitor daemons are stopped. Prints all four logs and exits non-zero only if a bounded
# producer failed or a daemon exited early.
# Works under bash (via the shebang) on macOS and Linux.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOG_DIR="${ROOT_DIR}/target/tracer-logs"
START_DELAY="${TRACER_START_DELAY:-1}" # seconds to let each listener bind before the next start
RUN_FOR_MS="${TRACER_RUN_FOR_MS:-5000}" # critical-service demo runtime; allow time for sensor startup
GRACE_SECS="${TRACER_GRACE_SECS:-4}"    # after producers stop, let the watchdog reach FAILED

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
java -Dsensor.faultProbability=0 -jar "${ROOT_DIR}/sensor-sim/target/sensor-sim.jar" --count 3 > "${LOG_DIR}/sensor-sim.log" 2>&1 &
SIM_PID=$!

status=0
# The bounded producers exit on their own; their exit code is the pass/fail signal.
for entry in "sensor-sim:${SIM_PID}" "critical-service:${SVC_PID}"; do
  name="${entry%%:*}"
  pid="${entry##*:}"
  if ! wait "${pid}"; then
    echo "PROCESS FAILED: ${name} (pid ${pid}, non-zero exit)" >&2
    status=1
  fi
done

# Let the watchdog observe the now-silent critical service (HEALTHY -> SUSPECT -> FAILED).
sleep "${GRACE_SECS}"

# The receiver and monitor are long-running daemons; they never exit on their own, so stop them.
# A signal-driven shutdown is expected here and is not counted as a failure; exiting early is.
for entry in "receiver:${RCV_PID}" "monitor:${MON_PID}"; do
  name="${entry%%:*}"
  pid="${entry##*:}"
  if kill -0 "${pid}" 2>/dev/null; then
    kill "${pid}" 2>/dev/null
    wait "${pid}" 2>/dev/null || true # shutdown hook runs; the signal exit code is expected
  else
    echo "PROCESS FAILED: ${name} (pid ${pid}, exited early)" >&2
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
  echo "TRACER RESULT: OK (producers exited cleanly; watchdog reached FAILED; daemons stopped -- see logs)"
fi
exit "${status}"
