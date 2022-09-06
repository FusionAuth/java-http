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

import io.fusionauth.http.HTTPResponse;
import io.fusionauth.http.HTTPValues;
import io.fusionauth.http.io.NonBlockingByteBufferOutputStream;
import io.fusionauth.http.util.HTTPTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A processor that handles incoming bytes that form the HTTP request.
 *
 * @author Brian Pontarelli
 */
public class HTTPResponseProcessor {
  public static final ByteBuffer Last = ByteBuffer.allocate(0);

  private static final Logger logger = LoggerFactory.getLogger(HTTPResponseProcessor.class);

  private final int maxHeadLength;

  private final NonBlockingByteBufferOutputStream outputStream;

  private final HTTPResponse response;

  private ByteBuffer preambleBuffer;

  public HTTPResponseProcessor(HTTPResponse response, NonBlockingByteBufferOutputStream outputStream, int maxHeadLength) {
    this.response = response;
    this.outputStream = outputStream;
    this.maxHeadLength = maxHeadLength;
  }

  public ByteBuffer currentBuffer() {
    boolean closed = outputStream.isClosed();
    ByteBuffer buffer = outputStream.writableBuffer();
    if (buffer == null && !closed) {
      logger.debug("Nothing to write from the worker thread");
      return null;
    }

    if (preambleBuffer == null) {
      logger.debug("The worker thread has bytes to write or has closed the stream, but the preamble hasn't been sent yet. Generating preamble");
      fillInHeaders();
      preambleBuffer = HTTPTools.buildResponsePreamble(response, maxHeadLength);
      logger.debug("Preamble is [{}] bytes long", preambleBuffer.remaining());
    }

    if (preambleBuffer.hasRemaining()) {
      logger.debug("Still writing preamble");
      logger.trace("(WP)");
      return preambleBuffer;
    }

    if (buffer == null) {
      logger.debug("No more bytes from worker thread. Sending the `Last` signal");
      logger.trace("(WL)");
      return Last;
    }

    logger.debug("Writing back bytes");
    logger.trace("(WB)");
    return buffer;
  }

  public NonBlockingByteBufferOutputStream outputStream() {
    return outputStream;
  }

  private void fillInHeaders() {
    if (!response.containsHeader(HTTPValues.Headers.Connection)) {
      response.setHeader(HTTPValues.Headers.Connection, HTTPValues.Connections.KeepAlive);
    }
  }
}
