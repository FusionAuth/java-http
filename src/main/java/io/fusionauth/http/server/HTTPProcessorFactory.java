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

import java.nio.ByteBuffer;

import io.fusionauth.http.util.ThreadPool;

/**
 * Provides a way for processors to be built when needed.
 *
 * @author Brian Pontarelli
 */
public class HTTPProcessorFactory {
  private final HTTPServerConfiguration configuration;

  private final HTTPListenerConfiguration listener;

  private final ThreadPool threadPool;

  public HTTPProcessorFactory(HTTPServerConfiguration configuration, HTTPListenerConfiguration listener, ThreadPool threadPool) {
    this.configuration = configuration;
    this.listener = listener;
    this.threadPool = threadPool;
  }

  /**
   * Builds an HTTPProcessor to handle the client (socket).
   *
   * @param notifier       A notifier that is used by the processors to notify the main selector thread when new bytes are ready for
   *                       processing.
   * @param preambleBuffer A ByteBuffer that is always used for the HTTP preamble. Since the preamble is always fully parsed each read
   *                       operation, this buffer can be shared to reduce the memory footprint of the server.
   * @param ipAddress      The IP address of the client.
   * @return The HTTPProcessor.
   */
  public HTTPProcessor build(Notifier notifier, ByteBuffer preambleBuffer, String ipAddress) {
    return new HTTP11Processor(configuration, listener, notifier, preambleBuffer, threadPool, ipAddress);
  }
}
