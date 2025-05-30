#!/usr/bin/env bash

#
# Copyright (c) 2022-2025, FusionAuth, All Rights Reserved
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

SOURCE="${BASH_SOURCE[0]}"
while [[ -h ${SOURCE} ]]; do # resolve $SOURCE until the file is no longer a symlink
  SCRIPT_DIR="$(cd -P "$(dirname "${SOURCE}")" >/dev/null && pwd)"
  SOURCE="$(readlink "${SOURCE}")"
  [[ ${SOURCE} != /* ]] && SOURCE="${SCRIPT_DIR}/${SOURCE}" # if $SOURCE was a relative symlink, we need to resolve it relative to the path where the symlink file was located
done
SCRIPT_DIR="$(cd -P "$(dirname "${SOURCE}")" > /dev/null && pwd)"

# Grab the path
if [[ ! -d lib ]]; then
  echo "Unable to locate library files needed to run the load tests. [lib]. Ensure you run this from build/dist."
  exit 1
fi

CLASSPATH=.
for f in lib/*.jar; do
  CLASSPATH=${CLASSPATH}:${f}
done

suspend=""
if [[ $# -gt 1 && $1 == "--suspend" ]]; then
  suspend="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8000"
  shift
fi

~/dev/java/current21/bin/java ${suspend} -cp "${CLASSPATH}" -Dio.fusionauth.http.server.stats="${SCRIPT_DIR}" io.fusionauth.http.load.Main
