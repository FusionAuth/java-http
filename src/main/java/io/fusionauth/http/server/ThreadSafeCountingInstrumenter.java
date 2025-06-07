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
package io.fusionauth.http.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread safe counting instrumenter for the HTTPServer, that ensures accurate data but could impact performance.
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("unused")
public class ThreadSafeCountingInstrumenter implements Instrumenter {
  private final AtomicLong acceptedConnections = new AtomicLong();

  private final AtomicLong acceptedRequests = new AtomicLong();

  private final AtomicLong badRequests = new AtomicLong();

  private final AtomicLong bytesRead = new AtomicLong();

  private final AtomicLong bytesWritten = new AtomicLong();

  private final AtomicLong chunkedRequests = new AtomicLong();

  private final AtomicLong chunkedResponses = new AtomicLong();

  private final AtomicLong closedConnections = new AtomicLong();

  private final AtomicLong servers = new AtomicLong();

  private final AtomicLong workers = new AtomicLong();

  @Override
  public void acceptedConnection() {
    acceptedConnections.incrementAndGet();
  }

  @Override
  public void acceptedRequest() {
    acceptedRequests.incrementAndGet();
  }

  @Override
  public void badRequest() {
    badRequests.incrementAndGet();
  }

  @Override
  public void chunkedRequest() {
    chunkedRequests.incrementAndGet();
  }

  @Override
  public void chunkedResponse() {
    chunkedResponses.incrementAndGet();
  }

  @Override
  public void connectionClosed() {
    closedConnections.incrementAndGet();
  }

  public long getAcceptedConnections() {
    return acceptedConnections.get();
  }

  public long getAcceptedRequests() {
    return acceptedRequests.get();
  }

  public long getBadRequests() {
    return badRequests.get();
  }

  public long getBytesRead() {
    return bytesRead.get();
  }

  public long getBytesWritten() {
    return bytesWritten.get();
  }

  public long getChunkedRequests() {
    return chunkedRequests.get();
  }

  public long getChunkedResponses() {
    return chunkedResponses.get();
  }

  public long getClosedConnections() {
    return closedConnections.get();
  }

  public long getServers() {
    return servers.get();
  }

  public long getWorkers() {
    return workers.get();
  }

  @Override
  public void readFromClient(long bytes) {
    bytesRead.addAndGet(bytes);
  }

  @Override
  public void serverStarted() {
    servers.incrementAndGet();
  }

  @Override
  public void workerStarted() {
    workers.incrementAndGet();
  }

  @Override
  public void workerStopped() {
    workers.decrementAndGet();
  }

  @Override
  public void wroteToClient(long bytes) {
    bytesWritten.addAndGet(bytes);
  }
}
