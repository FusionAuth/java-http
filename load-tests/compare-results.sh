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

usage() {
  echo "Usage: $0 <baseline.json> <comparison.json>"
  echo ""
  echo "Compares two benchmark result files and shows normalized ratios."
  echo "Useful for comparing results across runs to detect regressions or improvements."
  exit 1
}

if [[ $# -ne 2 ]]; then
  usage
fi

BASELINE="$1"
COMPARISON="$2"

if [[ ! -f "${BASELINE}" ]]; then
  echo "ERROR: Baseline file not found: ${BASELINE}"
  exit 1
fi

if [[ ! -f "${COMPARISON}" ]]; then
  echo "ERROR: Comparison file not found: ${COMPARISON}"
  exit 1
fi

echo "=== Benchmark Comparison ==="
echo ""
echo "Baseline:   ${BASELINE}"
echo "  System:   $(jq -r '.system.description // "unknown"' "${BASELINE}")"
echo "  Date:     $(jq -r '.timestamp' "${BASELINE}")"
echo ""
echo "Comparison: ${COMPARISON}"
echo "  System:   $(jq -r '.system.description // "unknown"' "${COMPARISON}")"
echo "  Date:     $(jq -r '.timestamp' "${COMPARISON}")"
echo ""

# Get all unique scenario names across both files
SCENARIOS="$(jq -r '.results[].scenario' "${BASELINE}" "${COMPARISON}" | sort -u)"

for scenario in ${SCENARIOS}; do
  echo "--- Scenario: ${scenario} ---"
  echo ""
  printf "%-20s %12s %12s %12s %12s\n" "Server" "Base RPS" "Comp RPS" "Ratio" "Status"
  printf "%-20s %12s %12s %12s %12s\n" "--------------------" "------------" "------------" "------------" "------------"

  # Get all servers that appear in this scenario in either file
  SERVERS="$(jq -r --arg s "${scenario}" '.results[] | select(.scenario == $s) | .server' "${BASELINE}" "${COMPARISON}" | sort -u)"

  # Get self (java-http) RPS from both files for normalization
  base_self_rps="$(jq -r --arg s "${scenario}" '.results[] | select(.server == "self" and .scenario == $s) | .metrics.rps' "${BASELINE}" 2>/dev/null || echo "0")"
  comp_self_rps="$(jq -r --arg s "${scenario}" '.results[] | select(.server == "self" and .scenario == $s) | .metrics.rps' "${COMPARISON}" 2>/dev/null || echo "0")"

  for server in ${SERVERS}; do
    base_rps="$(jq -r --arg srv "${server}" --arg s "${scenario}" '.results[] | select(.server == $srv and .scenario == $s) | .metrics.rps' "${BASELINE}" 2>/dev/null || echo "0")"
    comp_rps="$(jq -r --arg srv "${server}" --arg s "${scenario}" '.results[] | select(.server == $srv and .scenario == $s) | .metrics.rps' "${COMPARISON}" 2>/dev/null || echo "0")"

    if [[ "${base_rps}" == "0" || "${base_rps}" == "null" || -z "${base_rps}" ]]; then
      printf "%-20s %12s %12.0f %12s %12s\n" "${server}" "N/A" "${comp_rps}" "N/A" "---"
      continue
    fi

    if [[ "${comp_rps}" == "0" || "${comp_rps}" == "null" || -z "${comp_rps}" ]]; then
      printf "%-20s %12.0f %12s %12s %12s\n" "${server}" "${base_rps}" "N/A" "N/A" "---"
      continue
    fi

    # Compute normalized ratios (relative to self in each environment)
    if [[ "${base_self_rps}" != "0" && "${base_self_rps}" != "null" && -n "${base_self_rps}" && \
          "${comp_self_rps}" != "0" && "${comp_self_rps}" != "null" && -n "${comp_self_rps}" ]]; then
      base_norm="$(echo "scale=4; ${base_rps} / ${base_self_rps}" | bc)"
      comp_norm="$(echo "scale=4; ${comp_rps} / ${comp_self_rps}" | bc)"

      # Difference in normalized ratios
      diff="$(echo "scale=4; (${comp_norm} - ${base_norm}) / ${base_norm} * 100" | bc)"
      abs_diff="$(echo "${diff}" | tr -d '-')"

      if (( $(echo "${abs_diff} < 10" | bc -l) )); then
        status="CONSISTENT"
      elif (( $(echo "${abs_diff} < 20" | bc -l) )); then
        status="MINOR DIFF"
      else
        status="DIVERGENT"
      fi

      ratio="$(echo "scale=2; ${comp_rps} / ${base_rps}" | bc)x"
    else
      ratio="$(echo "scale=2; ${comp_rps} / ${base_rps}" | bc)x"
      status="NO SELF"
    fi

    printf "%-20s %12.0f %12.0f %12s %12s\n" "${server}" "${base_rps}" "${comp_rps}" "${ratio}" "${status}"
  done

  echo ""
done

echo "=== Legend ==="
echo "  Ratio:      Comparison RPS / Baseline RPS (absolute throughput ratio)"
echo "  Status:     Compares normalized rankings (relative to java-http in each environment)"
echo "  CONSISTENT: Normalized ratio difference < 10% (rankings are stable)"
echo "  MINOR DIFF: Normalized ratio difference 10-20%"
echo "  DIVERGENT:  Normalized ratio difference > 20% (environment may be skewing results)"
