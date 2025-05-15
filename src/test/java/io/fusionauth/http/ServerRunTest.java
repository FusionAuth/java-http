/*
 * Copyright (c) 2025, FusionAuth, All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package io.fusionauth.http;

import java.time.Duration;

import io.fusionauth.http.server.HTTPServer;
import org.testng.annotations.Test;

/**
 * Run a test so we can run things against it externally. Should probably move this to a test target instead?
 *
 * @author Daniel DeGroff
 */
public class ServerRunTest extends BaseTest {
  // Enable to just run a server to test against externally. Could move this to a build target?
  @Test(enabled = false)
  public void server_run() throws Exception {
    Duration runtime = Duration.ofHours(1);
    try (HTTPServer server = makeServer("http", (req, res) -> res.setStatus(200), null).start()) {
      System.out.println("Server started on port: " +
                         server.configuration().getListeners().getFirst().getPort() +
                         "\n - Run for [" + runtime.toMinutes() + "] minutes");
      Thread.sleep(runtime.toMillis());
      System.out.println(" - Exit");
    }
  }
}
