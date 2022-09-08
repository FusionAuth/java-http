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

import java.io.IOException;
import java.nio.channels.SelectionKey;

/**
 * Generic interface that is used by all the processors to allow worker threads and other hooks into the processing of the requests and
 * responses.
 *
 * @author Brian Pontarelli
 */
public interface HTTPProcessor {
  /**
   * Signals to the processor that the request handling failed in some way.
   *
   * @param t The exception that caused the failure, it there was one.
   */
  void failure(Throwable t);

  /**
   * Handles a read operation.
   *
   * @param key The selected key for a client that has written bytes to the server.
   * @throws IOException If any I/O operations failed.
   */
  void read(SelectionKey key) throws IOException;

  /**
   * Handles a write operation.
   *
   * @param key The selected key for a client that is attempting to read bytes from the server.
   * @throws IOException If any I/O operations failed.
   */
  void write(SelectionKey key) throws IOException;
}
