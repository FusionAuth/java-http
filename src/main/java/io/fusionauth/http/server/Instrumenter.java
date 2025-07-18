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
 * A generic interface that allows the HTTP Server to be instrumented.
 *
 * @author Brian Pontarelli
 */
public interface Instrumenter {
  /**
   * Called when a new client connection is accepted by the HTTP server.
   */
  void acceptedConnection();

  /**
   * Called when a new request is accepted by an HTTP worker.
   */
  void acceptedRequest();

  /**
   * Called when a client sends in a bad HTTP request. A bad request is one that cannot be handled by the HTTP server.
   */
  void badRequest();

  /**
   * Called when a client sends in chunked request data.
   */
  void chunkedRequest();

  /**
   * Called when a client sends in chunked request data.
   */
  void chunkedResponse();

  /**
   * Called when a connection is closed due to an issue or a timeout. This should not count connections closed in expected conditions such
   * as a keep-alive timeout.
   */
  void connectionClosed();

  /**
   * Called when bytes are read from a client.
   *
   * @param bytes The number of bytes read.
   */
  void readFromClient(long bytes);

  /**
   * Called when the server listener is started. If you have a single listener, you will have one server started. If you have two listeners,
   * one for HTTP and one for HTTPS, you will have two servers started.
   */
  void serverStarted();

  /**
   * Signals that an HTTP worker has been started.
   */
  void workerStarted();

  /**
   * Signals that an HTTP worker has been stopped.
   */
  void workerStopped();

  /**
   * Called when bytes are written to a client.
   *
   * @param bytes The number of bytes written.
   */
  void wroteToClient(long bytes);
}
