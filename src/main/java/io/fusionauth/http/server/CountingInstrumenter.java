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

/**
 * A simple counting instrumenter for the HTTPServer. This is not thread safe, so if you need accurate data, you'll want to use the
 * {@link ThreadSafeCountingInstrumenter}.
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("unused")
public class CountingInstrumenter implements Instrumenter {
  private long acceptedRequests;

  private long badRequests;

  private long bytesRead;

  private long bytesWritten;

  private long chunkedRequests;

  private long chunkedResponses;

  private long closedConnections;

  private long connections;

  private long servers;

  private long workers;

  @Override
  public void acceptedConnection() {
    connections++;
  }

  @Override
  public void acceptedRequest() {
    acceptedRequests++;
  }

  @Override
  public void badRequest() {
    badRequests++;
  }

  @Override
  public void chunkedRequest() {
    chunkedRequests++;
  }

  @Override
  public void chunkedResponse() {
    chunkedResponses++;
  }

  @Override
  public void connectionClosed() {
    closedConnections++;
  }

  public long getAcceptedRequests() {
    return acceptedRequests;
  }

  public long getBadRequests() {
    return badRequests;
  }

  public long getBytesRead() {
    return bytesRead;
  }

  public long getBytesWritten() {
    return bytesWritten;
  }

  public long getChunkedRequests() {
    return chunkedRequests;
  }

  public long getChunkedResponses() {
    return chunkedResponses;
  }

  public long getClosedConnections() {
    return closedConnections;
  }

  public long getConnections() {
    return connections;
  }

  public long getServers() {
    return servers;
  }

  public long getWorkers() {
    return workers;
  }

  @Override
  public void readFromClient(long bytes) {
    bytesRead += bytes;
  }

  @Override
  public void serverStarted() {
    servers++;
  }

  @Override
  public void workerStarted() {
    workers++;
  }

  @Override
  public void workerStopped() {
    workers--;
  }

  @Override
  public void wroteToClient(long bytes) {
    bytesWritten += bytes;
  }
}
