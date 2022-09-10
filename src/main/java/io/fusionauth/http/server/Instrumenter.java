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
 * A generic interface that allows the HTTP Server to be instrumented.
 *
 * @author Brian Pontarelli
 */
public interface Instrumenter {
  /**
   * Called when a new client connection is accepted.
   */
  void acceptedConnection();

  /**
   * Called when bytes are read from a client.
   *
   * @param bytes The number of bytes read.
   */
  void readFromClient(long bytes);

  /**
   * Called when the server is started.
   */
  void serverStarted();

  /**
   * Called when bytes are written to a client.
   *
   * @param bytes The number of bytes written.
   */
  void wroteToClient(long bytes);
}
