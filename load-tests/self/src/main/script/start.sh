#!/usr/bin/env bash

#
# Copyright (c) 2022, FusionAuth, All Rights Reserved
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

# Grab the path
if [[ ! -d lib ]]; then
  echo "Unable to locate library files needed to run the load tests. [lib]"
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

~/dev/java/current21/bin/java ${suspend} -agentpath:/Applications/YourKit-Java-Profiler-2023.9.app/Contents/Resources/bin/mac/libyjpagent.dylib=disablestacktelemetry,exceptions=disable,delay=10000,listen=all -cp "${CLASSPATH}" io.fusionauth.http.load.Main
