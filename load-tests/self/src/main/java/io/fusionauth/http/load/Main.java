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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import io.fusionauth.http.log.Level;
import io.fusionauth.http.log.SystemOutLoggerFactory;
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServer;
import io.fusionauth.http.server.ThreadSafeCountingInstrumenter;

public class Main {
  public static void main(String[] args) throws Exception {
    SystemOutLoggerFactory.FACTORY.getLogger(Object.class).setLevel(Level.Debug);

    var outputFile = setupOutput();
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

      // End the server after 2 hours, or you an CTRL+C as well.
      var runtime = Instant.now().plus(Duration.ofHours(2));

      var lastSnapshot = new Snapshot(instrumenter);

      // Try to report every 15 seconds. If nothing has changed, skip.
      do {
        //noinspection BusyWait
        Thread.sleep(Duration.ofSeconds(2).toMillis());

        // Cut down on noise if we are not running any requests.
        var snapshot = new Snapshot(instrumenter);
        if (!snapshot.equals(lastSnapshot)) {
          writeStatus(outputFile, snapshot);
          lastSnapshot = snapshot;
        }

      } while (runtime.isAfter(Instant.now()));

      System.out.println("Shutting down java-http server");
    }
  }

  private static Path setupOutput() throws IOException {
    var outputDir = System.getProperty("io.fusionauth.http.server.stats");
    if (outputDir != null) {
      var path = Paths.get(outputDir).toAbsolutePath();
      if (!Files.exists(path)) {
        Files.createDirectory(path);
      }
    } else {
      outputDir = System.getProperty("java.io.tmpdir");
    }

    var outputFile = Paths.get(outputDir).resolve("load-test-instrumenter");
    if (!Files.exists(outputFile)) {
      Files.createFile(outputFile);
    }

    System.out.println("""
        Server instrumentation output file created. Watch this file for server statistics during your load test.
        > watch cat {outputFile}
        """.replace("{outputFile}", outputFile.toString()));
    return outputFile;
  }

  private static void writeStatus(Path outputFile, Snapshot snapshot) throws IOException {
    String message = String.format("""
            Last updated %s
            -------------------------------------------
             - Servers started:    [%,d]
             - Active workers:     [%,d]
             - Total connections:  [%,d]
             - Accepted requests:  [%,d]
             - Bad requests:       [%,d]
             - Closed connections: [%,d]
             - Chunked requests:   [%,d]
             - Chunked responses:  [%,d]
             - Bytes read:         [%,d]
             - Bytes written:      [%,d]
            """,
        snapshot.now,
        snapshot.servers,
        snapshot.workers,
        snapshot.acceptedConnections,
        snapshot.acceptedRequests,
        snapshot.badRequests,
        snapshot.closedConnections,
        snapshot.chunkedRequests,
        snapshot.chunkedResponses,
        snapshot.bytesRead,
        snapshot.bytesWritten);

    try (OutputStream outputStream = Files.newOutputStream(outputFile, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
      outputStream.write(message.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static class Snapshot {
    public long acceptedConnections;

    public long acceptedRequests;

    public long badRequests;

    public long bytesRead;

    public long bytesWritten;

    public long chunkedRequests;

    public long chunkedResponses;

    public long closedConnections;

    public long now;

    public long servers;

    public long workers;

    public Snapshot(ThreadSafeCountingInstrumenter instrumenter) {
      now = System.currentTimeMillis();
      servers = instrumenter.getServers();
      workers = instrumenter.getWorkers();
      acceptedConnections = instrumenter.getAcceptedConnections();
      acceptedRequests = instrumenter.getAcceptedRequests();
      badRequests = instrumenter.getBadRequests();
      chunkedRequests = instrumenter.getChunkedRequests();
      chunkedResponses = instrumenter.getChunkedResponses();
      closedConnections = instrumenter.getClosedConnections();
      bytesRead = instrumenter.getBytesRead();
      bytesWritten = instrumenter.getBytesWritten();
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Snapshot snapshot = (Snapshot) o;
      return now == snapshot.now && servers == snapshot.servers && workers == snapshot.workers && acceptedRequests == snapshot.acceptedRequests && badRequests == snapshot.badRequests && chunkedRequests == snapshot.chunkedRequests && chunkedResponses == snapshot.chunkedResponses && closedConnections == snapshot.closedConnections && acceptedConnections == snapshot.acceptedConnections && bytesRead == snapshot.bytesRead && bytesWritten == snapshot.bytesWritten;
    }

    @Override
    public int hashCode() {
      return Objects.hash(now, servers, workers, acceptedRequests, badRequests, chunkedRequests, chunkedResponses, closedConnections, acceptedConnections, bytesRead, bytesWritten);
    }
  }
}
