#!/usr/bin/env bash
#
# test-critical-service.sh -- Critical-service integration checks (in progress).
#
# Currently builds the critical service and its dependencies, runs protocol tests,
# and verifies the runnable jar exists. Runtime heartbeat and crash checks are pending.
# Exits non-zero if prerequisites, the build, or the jar check fail.
# Works under bash on macOS, Linux, and Windows (Git Bash).
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)" || exit 1
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SERVICE_JAR="${ROOT_DIR}/critical-service/target/critical-service.jar"

cd "${ROOT_DIR}" || exit 1

echo "== checking prerequisites =="
for command_name in java javac mvn; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "PREREQUISITE FAILED: ${command_name} is missing from PATH (requires JDK 21 and Maven 3.9+)." >&2
    exit 1
  fi
done

echo "== building (mvn -q -pl critical-service -am package) =="
if ! mvn -q -pl critical-service -am package; then
  echo "BUILD FAILED" >&2
  exit 1
fi

echo "== checking critical-service jar =="
if [ ! -f "${SERVICE_JAR}" ]; then
  echo "JAR CHECK FAILED: ${SERVICE_JAR} not found" >&2
  exit 1
fi

echo
echo "BUILD RESULT: OK (${SERVICE_JAR})"
echo "RUNTIME CHECKS: NOT IMPLEMENTED (heartbeat and crash checks are pending)"
exit 0
