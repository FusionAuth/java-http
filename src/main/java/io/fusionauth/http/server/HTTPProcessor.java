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
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

/**
 * Generic interface that is used by all the processors to allow worker threads and other hooks into the processing of the requests and
 * responses.
 *
 * @author Brian Pontarelli
 */
public interface HTTPProcessor {
  /**
   * Closes the processing. In some cases, the process of closing a connection might require additional work, like shutting down TLS.
   * Therefore, this method can interrupt a normal shutdown.
   *
   * @return The new state of the processor.
   */
  ProcessorState close();

  /**
   * Signals to the processor that the request handling failed in some way.
   *
   * @param t The exception that caused the failure, it there was one.
   */
  void failure(Throwable t);

  /**
   * Allows the HTTPProcessor to determine what the initial key operations are for the SelectionKey. For TLS, this is usually READ and WRITE
   * in order to handle the handshake. For non-TLS, this is usually just READ.
   *
   * @return The initial interested ops.
   * @see SelectionKey#OP_READ
   * @see SelectionKey#OP_WRITE
   */
  int initialKeyOps();

  /**
   * @return The instant that this processor was last used.
   */
  long lastUsed();

  /**
   * Allows the HTTPProcessor to handle bytes that were read.
   *
   * @param buffer The bytes read from the client.
   * @return The new state of the HTTPProcessor.
   * @throws IOException If any I/O operations failed.
   */
  ProcessorState read(ByteBuffer buffer) throws IOException;

  /**
   * @return The current read buffer or null if the state has changed or there we aren't expecting to read.
   */
  ByteBuffer readBuffer();

  /**
   * @return The current state of the HTTPProcessor.
   */
  ProcessorState state();

  /**
   * @return The current write buffer(s) and never null.
   */
  ByteBuffer[] writeBuffers();

  /**
   * Called by the selector to tell the HTTPProcessor that bytes were written back to the client.
   *
   * @param num The number of bytes written.
   * @return The new state of the HTTPProcessor.
   */
  ProcessorState wrote(long num);
}
