/*
 * Copyright (c) 2022, FusionAuth, All Rights Reserved
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
                                             .withInstrumenter(instrumenter)
                                             .withListener(new HTTPListenerConfiguration(8080))
                                             .withLoggerFactory(SystemOutLoggerFactory.FACTORY)
                                             .start()) {

      for (int i = 0; i < 1_000; i++) {
        Thread.sleep(10_000);
        System.out.printf("Current stats. Bad requests [%s]. Bytes read [%s]. Bytes written [%s]. Chunked requests [%s]. Chunked responses [%s]. Closed connections [%s]. Connections [%s]. Started [%s]. Virtual threads [%s].\n",
            instrumenter.getBadRequests(), instrumenter.getBytesRead(), instrumenter.getBytesWritten(), instrumenter.getChunkedRequests(),
            instrumenter.getChunkedResponses(), instrumenter.getClosedConnections(), instrumenter.getConnections(), instrumenter.getStartedCount(),
            instrumenter.getThreadCount());
      }

      System.out.println("Shutting down java-http server");
    }
  }
}
