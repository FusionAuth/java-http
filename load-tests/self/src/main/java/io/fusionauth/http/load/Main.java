/*
 * Copyright (c) 2022-2025, FusionAuth, All Rights Reserved
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
package io.fusionauth.http.load;

import java.time.Duration;

import io.fusionauth.http.log.Level;
import io.fusionauth.http.log.SystemOutLoggerFactory;
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServer;
import io.fusionauth.http.server.ThreadSafeCountingInstrumenter;

public class Main {
  public static void main(String[] args) throws Exception {
    SystemOutLoggerFactory.FACTORY.getLogger(Object.class).setLevel(Level.Debug);

    System.out.println("Starting java-http server");
    var instrumenter = new ThreadSafeCountingInstrumenter();
    try (HTTPServer ignore = new HTTPServer().withHandler(new LoadHandler())
                                             .withCompressByDefault(false)
                                             .withInitialReadTimeout(Duration.ofSeconds(10))
                                             .withMinimumReadThroughput(4 * 1024)
                                             .withMinimumWriteThroughput(4 * 1024)
                                             .withInstrumenter(instrumenter)
                                             .withListener(new HTTPListenerConfiguration(8080))
                                             .withLoggerFactory(SystemOutLoggerFactory.FACTORY)
                                             .start()) {

      long lastMeasuredAcceptedRequests = instrumenter.getAcceptedRequests();
      long lastMeasuredConnectionsClosed = instrumenter.getClosedConnections();

      for (int i = 0; i < 1_000; i++) {
        Thread.sleep(10_000);
        long acceptedRequests = instrumenter.getAcceptedRequests();
        long connectionsClosed = instrumenter.getClosedConnections();
        // Cut down on noise if we are not running any requests.
        if (acceptedRequests > lastMeasuredAcceptedRequests || connectionsClosed > lastMeasuredConnectionsClosed) {
          System.out.printf("""
                  %s Current stats
                   - Servers started:    [%,d]
                   - Active workers:     [%,d]
                   - Accepted requests:  [%,d]
                   - Bad requests:       [%,d]
                   - Chunked requests:   [%,d]
                   - Chunked responses:  [%,d]
                   - Closed connections: [%,d]
                   - Total connections:  [%,d]
                   - Bytes read:         [%,d]
                   - Bytes written:      [%,d]
                  """,
              System.currentTimeMillis(),
              instrumenter.getStartedCount(),
              instrumenter.getThreadCount(),
              acceptedRequests,
              instrumenter.getBadRequests(),
              instrumenter.getChunkedRequests(),
              instrumenter.getChunkedResponses(),
              instrumenter.getClosedConnections(),
              instrumenter.getConnections(),
              instrumenter.getBytesRead(),
              instrumenter.getBytesWritten());
        }

        lastMeasuredAcceptedRequests = acceptedRequests;
        lastMeasuredConnectionsClosed = connectionsClosed;
      }

      System.out.println("Shutting down java-http server");
    }
  }
}
