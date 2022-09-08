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

import io.fusionauth.http.HTTPValues;
import io.fusionauth.http.io.NonBlockingByteBufferOutputStream;
import io.fusionauth.http.log.Logger;
import io.fusionauth.http.log.LoggerFactory;
import io.fusionauth.http.util.HTTPTools;

/**
 * A processor that handles incoming bytes that form the HTTP request.
 *
 * @author Brian Pontarelli
 */
public class HTTPResponseProcessor {
  private final Logger logger;

  private final int maxHeadLength;

  private final NonBlockingByteBufferOutputStream outputStream;

  private final HTTPResponse response;

  private ByteBuffer preambleBuffer;

  private volatile ResponseState state = ResponseState.Preamble;

  public HTTPResponseProcessor(HTTPResponse response, NonBlockingByteBufferOutputStream outputStream, int maxHeadLength,
                               LoggerFactory loggerFactory) {
    this.response = response;
    this.outputStream = outputStream;
    this.maxHeadLength = maxHeadLength;
    this.logger = loggerFactory.getLogger(HTTPRequestProcessor.class);
  }

  public synchronized ByteBuffer currentBuffer() {
    if (state != ResponseState.Preamble && state != ResponseState.Body) {
      return null;
    }

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
      if (logger.isDebuggable()) {
        logger.debug("Preamble is [\n{}\n]", new String(preambleBuffer.array(), 0, preambleBuffer.remaining()));
      }
    }

    if (preambleBuffer.hasRemaining()) {
      logger.debug("Still writing preamble");
      logger.trace("(WP)");
      return preambleBuffer;
    }

    if (buffer == null) {
      state = response.isKeepAlive() ? ResponseState.KeepAlive : ResponseState.Close;
      logger.debug("No more bytes from worker thread. Changing state to [{}]", state);
      logger.trace("(WL)");
      return null;
    }

    logger.debug("Writing back bytes");
    logger.trace("(WB)");
    return buffer;
  }

  public synchronized void failure() {
    // Go nuclear and wipe the response and stream, even if the response has already been committed (meaning one or more bytes have been
    // written)
    response.setStatus(500);
    response.setStatusMessage("Failure");
    response.clearHeaders();
    response.setContentLength(0L);

    preambleBuffer = null;

    outputStream.clear();
    outputStream.close();
    state = ResponseState.Failure;
  }

  public void resetState(ResponseState state) {
    this.state = state;
  }

  public ResponseState state() {
    return state;
  }

  private void fillInHeaders() {
    if (!response.containsHeader(HTTPValues.Headers.Connection)) {
      response.setHeader(HTTPValues.Headers.Connection, HTTPValues.Connections.KeepAlive);
    }
  }

  public enum ResponseState {
    Preamble,
    Body,
    KeepAlive,
    Close,
    Failure
  }
}
