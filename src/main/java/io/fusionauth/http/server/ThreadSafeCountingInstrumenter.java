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
package io.fusionauth.http.server;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread safe counting instrumenter for the HTTPServer, that ensures accurate data but could impact performance.
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("unused")
public class ThreadSafeCountingInstrumenter implements Instrumenter {
  private final AtomicLong badRequests = new AtomicLong();

  private final AtomicLong bytesRead = new AtomicLong();

  private final AtomicLong bytesWritten = new AtomicLong();

  private final AtomicLong chunkedRequests = new AtomicLong();

  private final AtomicLong chunkedResponses = new AtomicLong();

  private final AtomicLong closedConnections = new AtomicLong();

  private final AtomicLong connections = new AtomicLong();

  private final AtomicLong acceptedRequests = new AtomicLong();

  private final AtomicLong startedCount = new AtomicLong();

  private final AtomicLong threadCount = new AtomicLong();

  @Override
  public void acceptedConnection() {
    connections.incrementAndGet();
  }

  @Override
  public void acceptedRequests() {
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

  public long getConnections() {
    return connections.get();
  }

  public long getAcceptedRequests() {
    return acceptedRequests.get();
  }

  public long getStartedCount() {
    return startedCount.get();
  }

  public long getThreadCount() {
    return threadCount.get();
  }

  @Override
  public void readFromClient(long bytes) {
    bytesRead.addAndGet(bytes);
  }

  @Override
  public void serverStarted() {
    startedCount.incrementAndGet();
  }

  @Override
  public void threadExited() {
    threadCount.decrementAndGet();
  }

  @Override
  public void threadStarted() {
    threadCount.incrementAndGet();
  }

  @Override
  public void wroteToClient(long bytes) {
    bytesWritten.addAndGet(bytes);
  }
}
