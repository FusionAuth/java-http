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

RESULTS_DIR="${SCRIPT_DIR}/results"
README="${SCRIPT_DIR}/../README.md"

# Find the latest results file
LATEST="$(ls -t "${RESULTS_DIR}"/*.json 2>/dev/null | head -1)"

if [[ -z "${LATEST}" ]]; then
  echo "ERROR: No results files found in ${RESULTS_DIR}/"
  exit 1
fi

echo "Using results from: ${LATEST}"

# Extract system info and timestamp
TIMESTAMP="$(jq -r '.timestamp' "${LATEST}")"
SYSTEM_DESC="$(jq -r '[.system.os, .system.arch, (.system.cpuCores | tostring) + " cores", .system.cpuModel] | join(", ")' "${LATEST}")"
RAM_GB="$(jq -r '.system.ramGB' "${LATEST}")"
JAVA_VERSION="$(jq -r '.system.javaVersion' "${LATEST}")"
TOOL_NAME="$(jq -r '.tools.selected // .tool.name // "wrk"' "${LATEST}")"
MACHINE_MODEL="$(jq -r '.system.machineModel // "unknown"' "${LATEST}")"
OS_VERSION="$(jq -r '.system.osVersion // ""' "${LATEST}")"

# Server display name mapping
server_display_name() {
  case "$1" in
    self)            echo "java-http" ;;
    jdk-httpserver)  echo "JDK HttpServer" ;;
    jetty)           echo "Jetty" ;;
    netty)           echo "Netty" ;;
    tomcat)          echo "Apache Tomcat" ;;
    *)               echo "$1" ;;
  esac
}

# Pick scenario — prefer hello, fall back to baseline
SCENARIO="hello"
if ! jq -e ".results[] | select(.scenario == \"${SCENARIO}\")" "${LATEST}" &>/dev/null; then
  SCENARIO="baseline"
fi

# Check if high-concurrency data is available
HAS_HIGH_CONCURRENCY=false
if jq -e '.results[] | select(.scenario == "high-concurrency")' "${LATEST}" &>/dev/null; then
  HAS_HIGH_CONCURRENCY=true
fi

# When both tools were used, prefer wrk results for the table (has percentiles)
TOOL_FILTER="wrk"
if ! jq -e ".results[] | select(.tool == \"wrk\")" "${LATEST}" &>/dev/null; then
  TOOL_FILTER="fusionauth"
fi

# Get java-http RPS as the normalization baseline
SELF_RPS="$(jq -r ".results[] | select(.server == \"self\" and .scenario == \"${SCENARIO}\" and .tool == \"${TOOL_FILTER}\") | .metrics.rps" "${LATEST}" 2>/dev/null || echo "0")"
if [[ -z "${SELF_RPS}" || "${SELF_RPS}" == "null" ]]; then
  SELF_RPS="$(jq -r ".results[] | select(.server == \"self\" and .scenario == \"${SCENARIO}\") | .metrics.rps" "${LATEST}" 2>/dev/null | head -1 || echo "0")"
fi

# Extract scenario config for reproducibility
SCENARIO_CONFIG="$(jq -r --arg scenario "${SCENARIO}" --arg tool "${TOOL_FILTER}" \
  '.results[] | select(.scenario == $scenario and .tool == $tool) | .config | "\(.threads)t, \(.connections)c, \(.duration)"' \
  "${LATEST}" 2>/dev/null | head -1)"

# Build the performance section into a temp file
PERF_FILE="$(mktemp)"
trap 'rm -f "${PERF_FILE}"' EXIT

DATE_FORMATTED="$(echo "${TIMESTAMP}" | cut -d'T' -f1)"

# Helper function: generate a results table for a given scenario
# Args: $1=scenario, $2=tool_filter, $3=self_rps
generate_table() {
  local scenario="$1"
  local tool="$2"
  local self_rps="$3"

  echo "| Server | Requests/sec | Failures/sec | Avg latency (ms) | P99 latency (ms) | vs java-http |"
  echo "|--------|-------------:|-------------:|------------------:|------------------:|-------------:|"

  jq -r --arg scenario "${scenario}" --arg tool "${tool}" \
    '[.results[] | select(.scenario == $scenario and .tool == $tool)] | sort_by(if .server == "self" then "" else .server end) | .[] | [.server, (.metrics.rps | tostring), ((.metrics.errors_connect + .metrics.errors_read + .metrics.errors_write + .metrics.errors_timeout) | tostring), (.metrics.avg_latency_us | tostring), (.metrics.p99_us | tostring)] | @tsv' \
    "${LATEST}" | while IFS=$'\t' read -r server rps errors avg_lat p99_lat; do

    display_name="$(server_display_name "${server}")"

    # Convert microseconds to milliseconds (printf ensures leading zero)
    avg_lat_ms="$(printf "%.2f" "$(echo "scale=4; ${avg_lat} / 1000" | bc)")"
    p99_lat_ms="$(printf "%.2f" "$(echo "scale=4; ${p99_lat} / 1000" | bc)")"

    # Calculate failures per second from total errors / duration
    duration_s="$(jq -r --arg server "${server}" --arg scenario "${scenario}" --arg tool "${tool}" \
      '.results[] | select(.server == $server and .scenario == $scenario and .tool == $tool) | .metrics.duration_us / 1e6' "${LATEST}")"
    if [[ -n "${duration_s}" && "${duration_s}" != "0" && "${duration_s}" != "null" ]]; then
      fps="$(echo "scale=1; ${errors} / ${duration_s}" | bc)"
    else
      fps="0"
    fi

    # Normalized performance vs java-http
    if [[ -n "${self_rps}" && "${self_rps}" != "0" && "${self_rps}" != "null" ]]; then
      normalized="$(echo "scale=1; ${rps} * 100 / ${self_rps}" | bc)"
    else
      normalized="?"
    fi

    rps_formatted="$(printf "%'.0f" "${rps}")"
    printf "| %-14s | %12s | %12s | %17s | %17s | %11s%% |\n" \
      "${display_name}" "${rps_formatted}" "${fps}" "${avg_lat_ms}" "${p99_lat_ms}" "${normalized}"
  done
}

cat > "${PERF_FILE}" << 'HEADER'
## Performance

A key purpose for this project is to obtain screaming performance. Here are benchmark results comparing `java-http` against other Java HTTP servers.

These benchmarks ensure `java-http` stays near the top in raw throughput, and we'll be working on claiming the top position -- even if only for bragging rights, since in practice your database and application code will be the bottleneck long before the HTTP server.

All servers implement the same request handler that reads the request body and returns a `200`. All servers were tested over HTTP (no TLS) to isolate server performance.

HEADER

# Add the primary table (hello or baseline)
generate_table "${SCENARIO}" "${TOOL_FILTER}" "${SELF_RPS}" >> "${PERF_FILE}"

# Add high-concurrency table if available
if [[ "${HAS_HIGH_CONCURRENCY}" == "true" ]]; then
  HC_SELF_RPS="$(jq -r ".results[] | select(.server == \"self\" and .scenario == \"high-concurrency\" and .tool == \"${TOOL_FILTER}\") | .metrics.rps" "${LATEST}" 2>/dev/null || echo "0")"

  cat >> "${PERF_FILE}" << 'HC_HEADER'

#### Under stress (1,000 concurrent connections)

HC_HEADER
  generate_table "high-concurrency" "${TOOL_FILTER}" "${HC_SELF_RPS}" >> "${PERF_FILE}"

  cat >> "${PERF_FILE}" << 'HC_NOTE'

_JDK HttpServer (`com.sun.net.httpserver`) is included as a baseline since it ships with the JDK and requires no dependencies. However, as the stress test shows, it is not suitable for production workloads — it suffers significant failures under high concurrency._
HC_NOTE
fi

# Add footer with machine specs and reproducibility info
MACHINE_LINE=""
if [[ "${MACHINE_MODEL}" != "unknown" && -n "${MACHINE_MODEL}" ]]; then
  MACHINE_LINE=" (${MACHINE_MODEL})"
fi
OS_LINE=""
if [[ -n "${OS_VERSION}" && "${OS_VERSION}" != "null" ]]; then
  OS_LINE=$'\n'"_OS: ${OS_VERSION}._"
fi

cat >> "${PERF_FILE}" << EOF

_Benchmark performed ${DATE_FORMATTED} on ${SYSTEM_DESC}, ${RAM_GB}GB RAM${MACHINE_LINE}._${OS_LINE}
_Java: ${JAVA_VERSION}._

To reproduce:
\`\`\`bash
cd load-tests
./run-benchmarks.sh --tool ${TOOL_NAME} --scenarios ${SCENARIO}$(if [[ "${HAS_HIGH_CONCURRENCY}" == "true" ]]; then echo ",high-concurrency"; fi)
./update-readme.sh
\`\`\`

See [load-tests/README.md](load-tests/README.md) for full usage and options.
EOF

# Verify README has the Performance section
if ! grep -q "^## Performance" "${README}"; then
  echo "ERROR: Could not find '## Performance' section in README.md"
  echo "       Add a '## Performance' heading to README.md first."
  exit 1
fi

# Replace the Performance section using line-based processing
# Strategy: print everything before "## Performance", then our new content,
# then skip until the next "## " heading and print the rest.
{
  # Print lines before ## Performance
  sed -n '1,/^## Performance/{ /^## Performance/!p; }' "${README}"

  # Print our new performance section
  cat "${PERF_FILE}"

  # Print lines after the next ## heading (after Performance)
  awk '
    BEGIN { in_perf = 0; past_perf = 0 }
    /^## Performance/ { in_perf = 1; next }
    in_perf && /^## [^#]/ { in_perf = 0; past_perf = 1; print "" }
    past_perf { print }
  ' "${README}"
} > "${README}.tmp"

mv "${README}.tmp" "${README}"

echo "README.md updated with latest benchmark results."
