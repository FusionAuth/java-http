#!/usr/bin/env bash

#
# Copyright (c) 2025, FusionAuth, All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied. See the License for the specific
# language governing permissions and limitations under the License.
#

set -euo pipefail

# Resolve script directory
SOURCE="${BASH_SOURCE[0]}"
while [[ -h ${SOURCE} ]]; do
  SCRIPT_DIR="$(cd -P "$(dirname "${SOURCE}")" >/dev/null && pwd)"
  SOURCE="$(readlink "${SOURCE}")"
  [[ ${SOURCE} != /* ]] && SOURCE="${SCRIPT_DIR}/${SOURCE}"
done
SCRIPT_DIR="$(cd -P "$(dirname "${SOURCE}")" >/dev/null && pwd)"

# Defaults
ALL_SERVERS="self jdk-httpserver jetty netty tomcat"
ALL_SCENARIOS="baseline hello post-load large-file high-concurrency mixed"
SERVERS="${ALL_SERVERS}"
SCENARIOS="${ALL_SCENARIOS}"
LABEL=""
OUTPUT_DIR="${SCRIPT_DIR}/results"
DURATION="30s"
TOOL="wrk"
FUSIONAUTH_LOAD_TESTS_DIR="${FUSIONAUTH_LOAD_TESTS_DIR:-${HOME}/dev/fusionauth/fusionauth-load-tests}"

usage() {
  echo "Usage: $0 [OPTIONS]"
  echo ""
  echo "Options:"
  echo "  --servers <list>     Comma-separated server list (default: all)"
  echo "                       Available: ${ALL_SERVERS// /, }"
  echo "  --scenarios <list>   Comma-separated scenario list (default: all)"
  echo "                       Available: ${ALL_SCENARIOS// /, }"
  echo "  --tool <name>        Benchmark tool: wrk, fusionauth, or both (default: wrk)"
  echo "  --label <name>       Label for the results file"
  echo "  --output <dir>       Output directory (default: load-tests/results/)"
  echo "  --duration <time>    Duration per scenario (default: 30s)"
  echo "  -h, --help           Show this help"
  echo ""
  echo "Environment:"
  echo "  FUSIONAUTH_LOAD_TESTS_DIR   Path to fusionauth-load-tests checkout"
  echo "                               (default: ~/dev/fusionauth/fusionauth-load-tests)"
  exit 0
}

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --servers)   SERVERS="${2//,/ }"; shift 2 ;;
    --scenarios) SCENARIOS="${2//,/ }"; shift 2 ;;
    --tool)      TOOL="$2"; shift 2 ;;
    --label)     LABEL="$2"; shift 2 ;;
    --output)    OUTPUT_DIR="$2"; shift 2 ;;
    --duration)  DURATION="$2"; shift 2 ;;
    -h|--help)   usage ;;
    *)           echo "Unknown option: $1"; usage ;;
  esac
done

if [[ "${TOOL}" != "wrk" && "${TOOL}" != "fusionauth" && "${TOOL}" != "both" ]]; then
  echo "ERROR: --tool must be wrk, fusionauth, or both"
  exit 1
fi

# --- Prerequisites ---

check_command() {
  if ! command -v "$1" &>/dev/null; then
    echo "ERROR: '$1' is not installed or not in PATH."
    exit 1
  fi
}

if [[ "${TOOL}" == "wrk" || "${TOOL}" == "both" ]]; then
  check_command wrk
fi
check_command sb
check_command java
check_command curl
check_command jq

# --- Parse duration to seconds ---

duration_to_seconds() {
  local dur="$1"
  local num="${dur%[smhSMH]}"
  local unit="${dur#"${num}"}"
  case "${unit}" in
    s|S|"") echo "${num}" ;;
    m|M)    echo $(( num * 60 )) ;;
    h|H)    echo $(( num * 3600 )) ;;
    *)      echo "${num}" ;;
  esac
}

DURATION_SECS="$(duration_to_seconds "${DURATION}")"

# --- fusionauth-load-tests setup ---

FUSIONAUTH_LT_BUILT=false
FUSIONAUTH_LT_DIST=""

build_fusionauth_load_tests() {
  if [[ "${FUSIONAUTH_LT_BUILT}" == "true" ]]; then
    return 0
  fi

  if [[ ! -d "${FUSIONAUTH_LOAD_TESTS_DIR}" ]]; then
    echo "ERROR: fusionauth-load-tests not found at ${FUSIONAUTH_LOAD_TESTS_DIR}"
    echo "       Set FUSIONAUTH_LOAD_TESTS_DIR or clone the repo there."
    return 1
  fi

  echo "--- Building fusionauth-load-tests ---"
  (cd "${FUSIONAUTH_LOAD_TESTS_DIR}" && sb clean int) || {
    echo "ERROR: Failed to build fusionauth-load-tests"
    return 1
  }

  FUSIONAUTH_LT_DIST="${FUSIONAUTH_LOAD_TESTS_DIR}/build/dist"
  FUSIONAUTH_LT_BUILT=true
}

# Generate a JSON config file for fusionauth-load-tests
# Args: $1=endpoint, $2=workerCount, $3=loopCount, $4=output_file
generate_fusionauth_config() {
  local endpoint="$1"
  local workers="$2"
  local loops="$3"
  local output_file="$4"

  cat > "${output_file}" <<JSONEOF
{
  "loopCount": ${loops},
  "workerCount": ${workers},
  "workerFactory": {
    "className": "io.fusionauth.load.HTTPWorkerFactory",
    "attributes": {
      "directive": "java-http-load-test",
      "url": "http://localhost:8080${endpoint}",
      "restClient": "java",
      "chunked": false
    }
  },
  "listeners": [
    {
      "className": "io.fusionauth.load.listeners.ThroughputListener"
    }
  ],
  "reporter": {
    "className": "io.fusionauth.load.reporters.DefaultReporter",
    "attributes": {
      "interval": ${DURATION_SECS},
      "csvOutput": false
    }
  }
}
JSONEOF
}

# Run fusionauth-load-tests and parse the output into JSON metrics
# Args: $1=config_file
# Output: JSON string with metrics, or empty on failure
run_fusionauth_load_tests() {
  local config_file="$1"
  local work_dir="${FUSIONAUTH_LT_DIST}"

  # Build classpath
  local classpath="."
  for f in "${work_dir}"/lib/*.jar; do
    classpath="${classpath}:${f}"
  done

  # Run to completion — loopCount is sized to approximate the target duration
  local output
  output="$("${JAVA_HOME}/bin/java" -cp "${classpath}" io.fusionauth.load.LoadRunner "${config_file}" 2>&1)" || true

  # Parse the last Throughput Report block from the output
  # Format:
  #   Throughput Report
  #    Total duration:    30052 ms (~30 s)
  #    Total count:       3245678
  #    Avg. latency:      0.923 ms
  #    Avg. success:      107987.123 per second
  #    Avg. failure:      - per second

  local duration_ms total_count avg_latency_ms avg_rps avg_failures

  # Get the last occurrence of each metric line
  duration_ms="$(echo "${output}" | grep 'Total duration:' | tail -1 | sed 's/.*Total duration:[[:space:]]*//' | sed 's/ ms.*//')" || true
  total_count="$(echo "${output}" | grep 'Total count:' | tail -1 | sed 's/.*Total count:[[:space:]]*//')" || true
  avg_latency_ms="$(echo "${output}" | grep 'Avg. latency:' | tail -1 | sed 's/.*Avg. latency:[[:space:]]*//' | sed 's/ ms//')" || true
  avg_rps="$(echo "${output}" | grep 'Avg. success:' | tail -1 | sed 's/.*Avg. success:[[:space:]]*//' | sed 's/ per second//')" || true
  avg_failures="$(echo "${output}" | grep 'Avg. failure:' | tail -1 | sed 's/.*Avg. failure:[[:space:]]*//' | sed 's/ per second//')" || true

  if [[ -z "${avg_rps}" || "${avg_rps}" == "-" ]]; then
    echo ""
    return 1
  fi

  # Convert avg latency from ms to us for consistency with wrk output
  local avg_latency_us
  avg_latency_us="$(echo "${avg_latency_ms} * 1000" | bc 2>/dev/null || echo "0")"

  local error_count=0
  if [[ -n "${avg_failures}" && "${avg_failures}" != "-" ]]; then
    # failures is per-second, multiply by duration to estimate total
    local duration_s
    duration_s="$(echo "${duration_ms} / 1000" | bc 2>/dev/null || echo "0")"
    error_count="$(echo "${avg_failures} * ${duration_s}" | bc 2>/dev/null | sed 's/\..*//' || echo "0")"
  fi

  # Output JSON metrics (matching wrk format where possible)
  jq -n \
    --argjson requests "${total_count}" \
    --argjson duration_us "$(echo "${duration_ms} * 1000" | bc 2>/dev/null || echo "0")" \
    --argjson rps "${avg_rps}" \
    --argjson avg_latency_us "${avg_latency_us}" \
    --argjson errors "${error_count}" \
    '{
      requests: $requests,
      duration_us: $duration_us,
      rps: $rps,
      avg_latency_us: $avg_latency_us,
      p50_us: 0,
      p90_us: 0,
      p99_us: 0,
      max_us: 0,
      errors_connect: 0,
      errors_read: 0,
      errors_write: 0,
      errors_timeout: $errors
    }'
}

# --- Elapsed timer helpers ---
# Starts a background process that prints elapsed time on the current line.
# Usage: start_timer "prefix message"
#        ... long-running command ...
#        stop_timer
TIMER_PID=""
start_timer() {
  local msg="$1"
  (
    local start="${SECONDS}"
    while true; do
      local elapsed=$(( SECONDS - start ))
      printf "\r    %s ... %ds" "${msg}" "${elapsed}"
      sleep 1
    done
  ) &
  TIMER_PID=$!
}

stop_timer() {
  if [[ -n "${TIMER_PID}" ]]; then
    kill "${TIMER_PID}" 2>/dev/null || true
    wait "${TIMER_PID}" 2>/dev/null || true
    TIMER_PID=""
    printf "\r\033[K"  # Clear the timer line
  fi
}
trap stop_timer EXIT

# --- Banner ---

SUITE_START="${SECONDS}"
SUITE_START_TIME="$(date '+%H:%M:%S')"
echo "=== java-http Benchmark Suite (started ${SUITE_START_TIME}) ==="
echo ""

# --- System metadata ---

OS="$(uname -s)"
ARCH="$(uname -m)"
CPU_CORES="$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo unknown)"
if [[ "${OS}" == "Darwin" ]]; then
  CPU_MODEL="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)"
  RAM_GB="$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 ))"
else
  CPU_MODEL="$(lscpu 2>/dev/null | grep 'Model name' | sed 's/.*: *//' || echo unknown)"
  RAM_GB="$(( $(grep MemTotal /proc/meminfo 2>/dev/null | awk '{print $2}' || echo 0) / 1048576 ))"
fi
JAVA_VERSION="$(java -version 2>&1 | head -1 || echo unknown)"

WRK_VERSION="N/A"
if [[ "${TOOL}" == "wrk" || "${TOOL}" == "both" ]]; then
  WRK_VERSION="$(set +o pipefail; wrk -v 2>&1 | head -1)"
fi

echo "System:   ${OS} ${ARCH}, ${CPU_CORES} cores, ${RAM_GB}GB RAM"
echo "CPU:      ${CPU_MODEL}"
echo "Java:     ${JAVA_VERSION}"
echo "Tool:     ${TOOL}"
if [[ "${TOOL}" == "wrk" || "${TOOL}" == "both" ]]; then
  echo "wrk:      ${WRK_VERSION}"
fi
echo "Duration: ${DURATION} (${DURATION_SECS}s)"
echo ""

# --- Build fusionauth-load-tests if needed ---

if [[ "${TOOL}" == "fusionauth" || "${TOOL}" == "both" ]]; then
  build_fusionauth_load_tests
  echo ""
fi

# --- Scenario configuration ---
# Maps scenario name -> "threads connections endpoint"

scenario_config() {
  case "$1" in
    baseline)         echo "12 100 /" ;;
    hello)            echo "12 100 /hello" ;;
    post-load)        echo "12 100 /load" ;;
    large-file)       echo "4 10 /file?size=1048576" ;;
    high-concurrency) echo "12 1000 /" ;;
    mixed)            echo "12 100 /" ;;
    *)                echo ""; return 1 ;;
  esac
}

# --- Server build and start configuration ---

server_build_target() {
  case "$1" in
    tomcat) echo "clean tomcat" ;;
    *)      echo "clean app" ;;
  esac
}

start_server() {
  local server="$1"
  local server_dir="${SCRIPT_DIR}/${server}"
  local log_file="${server_dir}/build/server.log"

  case "${server}" in
    tomcat)
      (cd "${server_dir}/build/dist/tomcat/apache-tomcat/bin" && ./catalina.sh run) >"${log_file}" 2>&1 &
      ;;
    *)
      (cd "${server_dir}/build/dist" && ./start.sh) >"${log_file}" 2>&1 &
      ;;
  esac

  SERVER_PID=$!
}

wait_for_server() {
  local timeout=30
  local elapsed=0
  while [[ ${elapsed} -lt ${timeout} ]]; do
    if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ 2>/dev/null | grep -q "200"; then
      return 0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  echo "ERROR: Server did not start within ${timeout}s"
  return 1
}

stop_server() {
  if [[ -n "${SERVER_PID:-}" ]]; then
    # Kill the process group to ensure child processes are also terminated
    kill -- -"${SERVER_PID}" 2>/dev/null || kill "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
    SERVER_PID=""
  fi

  # Also check for anything still on port 8080
  local pids
  pids="$(lsof -ti :8080 2>/dev/null || true)"
  if [[ -n "${pids}" ]]; then
    echo "${pids}" | xargs kill 2>/dev/null || true
    sleep 1
  fi
}

# --- Run a single wrk benchmark ---
# Args: $1=server, $2=scenario, $3=threads, $4=connections, $5=endpoint
# Appends result to RESULTS_JSON
run_wrk_benchmark() {
  local server="$1" scenario="$2" threads="$3" connections="$4" endpoint="$5"

  echo "  [wrk] Running: ${scenario} (${threads}t, ${connections}c, ${DURATION}) -> ${endpoint}"

  # Run wrk and capture JSON output from the Lua done() callback
  start_timer "[wrk] ${server}/${scenario}"
  local wrk_output
  wrk_output="$(wrk -t"${threads}" -c"${connections}" -d"${DURATION}" \
    -s "${SCENARIO_DIR}/${scenario}.lua" \
    "http://localhost:8080${endpoint}" 2>&1)"
  stop_timer

  # The JSON line is the last line of output (from the done() callback)
  local json_line
  json_line="$(echo "${wrk_output}" | grep '^{' | tail -1)"

  if [[ -z "${json_line}" ]]; then
    echo "    WARNING: No JSON output from wrk for ${server}/${scenario}"
    echo "    wrk output: ${wrk_output}"
    return
  fi

  # Build the result entry
  local result_entry
  result_entry="$(jq -n \
    --arg server "${server}" \
    --arg tool "wrk" \
    --arg protocol "http/1.1" \
    --arg scenario "${scenario}" \
    --argjson threads "${threads}" \
    --argjson connections "${connections}" \
    --arg duration "${DURATION}" \
    --arg endpoint "${endpoint}" \
    --argjson metrics "${json_line}" \
    '{
      server: $server,
      tool: $tool,
      protocol: $protocol,
      scenario: $scenario,
      config: { threads: $threads, connections: $connections, duration: $duration, endpoint: $endpoint },
      metrics: $metrics
    }'
  )"

  RESULTS_JSON="$(echo "${RESULTS_JSON}" | jq --argjson entry "${result_entry}" '. + [$entry]')"

  # Print summary
  local rps avg_lat p99_lat errors
  rps="$(echo "${json_line}" | jq -r '.rps')"
  avg_lat="$(echo "${json_line}" | jq -r '.avg_latency_us')"
  p99_lat="$(echo "${json_line}" | jq -r '.p99_us')"
  errors="$(echo "${json_line}" | jq -r '.errors_connect + .errors_read + .errors_write + .errors_timeout')"
  printf "    RPS: %'.0f | Avg Latency: %'.0f us | P99: %'.0f us | Errors: %d\n" \
    "${rps}" "${avg_lat}" "${p99_lat}" "${errors}"
}

# --- Run a single fusionauth-load-tests benchmark ---
# Args: $1=server, $2=scenario, $3=connections, $4=endpoint
# Appends result to RESULTS_JSON
run_fusionauth_benchmark() {
  local server="$1" scenario="$2" connections="$3" endpoint="$4"

  # fusionauth-load-tests only supports a single URL per config; skip 'mixed'
  if [[ "${scenario}" == "mixed" ]]; then
    echo "  [fusionauth] Skipping: ${scenario} (single-URL tool, not supported)"
    return
  fi

  echo "  [fusionauth] Running: ${scenario} (${connections} workers, ~${DURATION_SECS}s) -> ${endpoint}"

  # Calculate loopCount to approximate target duration.
  # Estimate ~1000 requests/sec per worker (conservative for ~100K total RPS with 100 workers).
  # The test runs to completion, so actual duration may differ slightly.
  local loops_per_worker=$(( 1000 * DURATION_SECS ))
  if [[ ${loops_per_worker} -lt 1000 ]]; then
    loops_per_worker=1000
  fi

  # Generate a temp config file
  local config_file="${SCRIPT_DIR}/.fusionauth-config-tmp.json"
  generate_fusionauth_config "${endpoint}" "${connections}" "${loops_per_worker}" "${config_file}"

  # Run and parse
  start_timer "[fusionauth] ${server}/${scenario}"
  local json_line
  json_line="$(run_fusionauth_load_tests "${config_file}")"
  stop_timer

  # Clean up config
  rm -f "${config_file}"

  if [[ -z "${json_line}" ]]; then
    echo "    WARNING: No output from fusionauth-load-tests for ${server}/${scenario}"
    return
  fi

  # Build the result entry
  local result_entry
  result_entry="$(jq -n \
    --arg server "${server}" \
    --arg tool "fusionauth" \
    --arg protocol "http/1.1" \
    --arg scenario "${scenario}" \
    --argjson connections "${connections}" \
    --arg duration "${DURATION}" \
    --arg endpoint "${endpoint}" \
    --argjson metrics "${json_line}" \
    '{
      server: $server,
      tool: $tool,
      protocol: $protocol,
      scenario: $scenario,
      config: { threads: 0, connections: $connections, duration: $duration, endpoint: $endpoint },
      metrics: $metrics
    }'
  )"

  RESULTS_JSON="$(echo "${RESULTS_JSON}" | jq --argjson entry "${result_entry}" '. + [$entry]')"

  # Print summary
  local rps avg_lat errors
  rps="$(echo "${json_line}" | jq -r '.rps')"
  avg_lat="$(echo "${json_line}" | jq -r '.avg_latency_us')"
  errors="$(echo "${json_line}" | jq -r '.errors_timeout')"
  printf "    RPS: %'.0f | Avg Latency: %'.0f us | Errors: %d\n" \
    "${rps}" "${avg_lat}" "${errors}"
}

# --- Run benchmarks ---

SCENARIO_DIR="${SCRIPT_DIR}/scenarios"
export SCENARIO_DIR

mkdir -p "${OUTPUT_DIR}"

TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# Use ISO timestamp for filename (colons replaced with dashes for filesystem safety)
DATE_LABEL="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
if [[ -n "${LABEL}" ]]; then
  RESULT_FILE="${OUTPUT_DIR}/${DATE_LABEL}-${LABEL}.json"
else
  RESULT_FILE="${OUTPUT_DIR}/${DATE_LABEL}.json"
fi

# Collect all results as JSON array elements
RESULTS_JSON="[]"

for server in ${SERVERS}; do
  server_dir="${SCRIPT_DIR}/${server}"

  if [[ ! -d "${server_dir}" ]]; then
    echo "WARNING: Server directory not found: ${server_dir}, skipping."
    continue
  fi

  echo "--- Building ${server} ---"
  build_target="$(server_build_target "${server}")"
  (cd "${server_dir}" && sb ${build_target}) || {
    echo "ERROR: Failed to build ${server}, skipping."
    continue
  }

  echo "--- Starting ${server} ---"
  stop_server
  start_server "${server}"

  if ! wait_for_server; then
    echo "ERROR: ${server} failed to start, skipping."
    stop_server
    continue
  fi

  echo "--- ${server} is ready on port 8080 ---"
  echo ""

  for scenario in ${SCENARIOS}; do
    config="$(scenario_config "${scenario}")" || {
      echo "WARNING: Unknown scenario: ${scenario}, skipping."
      continue
    }

    read -r threads connections endpoint <<< "${config}"

    if [[ "${TOOL}" == "wrk" || "${TOOL}" == "both" ]]; then
      run_wrk_benchmark "${server}" "${scenario}" "${threads}" "${connections}" "${endpoint}"
    fi

    if [[ "${TOOL}" == "fusionauth" || "${TOOL}" == "both" ]]; then
      run_fusionauth_benchmark "${server}" "${scenario}" "${connections}" "${endpoint}"
    fi
  done

  echo ""
  echo "--- Stopping ${server} ---"
  stop_server
  echo ""
done

# --- Write results file ---

FULL_RESULT="$(jq -n \
  --argjson version 1 \
  --arg timestamp "${TIMESTAMP}" \
  --arg os "${OS}" \
  --arg arch "${ARCH}" \
  --arg cpuModel "${CPU_MODEL}" \
  --argjson cpuCores "${CPU_CORES}" \
  --argjson ramGB "${RAM_GB}" \
  --arg javaVersion "${JAVA_VERSION}" \
  --arg description "Local benchmark" \
  --arg tool "${TOOL}" \
  --arg wrkVersion "${WRK_VERSION}" \
  --argjson results "${RESULTS_JSON}" \
  '{
    version: $version,
    timestamp: $timestamp,
    system: {
      os: $os,
      arch: $arch,
      cpuModel: $cpuModel,
      cpuCores: $cpuCores,
      ramGB: $ramGB,
      javaVersion: $javaVersion,
      description: $description
    },
    tools: {
      selected: $tool,
      wrkVersion: $wrkVersion
    },
    results: $results
  }'
)"

echo "${FULL_RESULT}" > "${RESULT_FILE}"
echo "=== Results written to ${RESULT_FILE} ==="
echo ""

# --- Print summary table ---

echo "=== Summary ==="
echo ""
printf "%-15s %-10s %-18s %12s %12s %12s %8s\n" "Server" "Tool" "Scenario" "RPS" "Avg Lat(us)" "P99(us)" "Errors"
printf "%-15s %-10s %-18s %12s %12s %12s %8s\n" "---------------" "----------" "------------------" "------------" "------------" "------------" "--------"

echo "${RESULTS_JSON}" | jq -r '.[] | [.server, .tool, .scenario, (.metrics.rps | tostring), (.metrics.avg_latency_us | tostring), (.metrics.p99_us | tostring), ((.metrics.errors_connect + .metrics.errors_read + .metrics.errors_write + .metrics.errors_timeout) | tostring)] | @tsv' | \
  while IFS=$'\t' read -r srv tool scn rps avg p99 errs; do
    printf "%-15s %-10s %-18s %12.0f %12.0f %12d %8d\n" "${srv}" "${tool}" "${scn}" "${rps}" "${avg}" "${p99}" "${errs}"
  done

SUITE_ELAPSED=$(( SECONDS - SUITE_START ))
SUITE_MINS=$(( SUITE_ELAPSED / 60 ))
SUITE_SECS=$(( SUITE_ELAPSED % 60 ))
echo ""
echo "=== Done (${SUITE_MINS}m ${SUITE_SECS}s) ==="
