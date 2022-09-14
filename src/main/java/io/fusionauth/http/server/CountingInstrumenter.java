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

/**
 * A simple counting instrumenter for the HTTPServer.
 *
 * @author Brian Pontarelli
 */
public class CountingInstrumenter implements Instrumenter {
  private long bytesRead;

  private long bytesWritten;

  private long chunkedRequests;

  private long chunkedResponses;

  private int connections;

  private int startedCount;

  @Override
  public void acceptedConnection() {
    connections++;
  }

  @Override
  public void chunkedRequest() {
    chunkedRequests++;
  }

  @Override
  public void chunkedResponse() {
    chunkedResponses++;
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

  public int getConnections() {
    return connections;
  }

  public int getStartedCount() {
    return startedCount;
  }

  @Override
  public void readFromClient(long bytes) {
    bytesRead += bytes;
  }

  @Override
  public void serverStarted() {
    startedCount++;
  }

  @Override
  public void wroteToClient(long bytes) {
    bytesWritten += bytes;
  }
}
