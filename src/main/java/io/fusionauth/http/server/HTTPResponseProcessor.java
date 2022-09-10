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

import io.fusionauth.http.HTTPValues.Connections;
import io.fusionauth.http.HTTPValues.Headers;
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

  private final HTTPRequest request;

  private final HTTPResponse response;

  private ByteBuffer preambleBuffer;

  private volatile ResponseState state = ResponseState.Preamble;

  public HTTPResponseProcessor(HTTPRequest request, HTTPResponse response, NonBlockingByteBufferOutputStream outputStream,
                               int maxHeadLength, LoggerFactory loggerFactory) {
    this.request = request;
    this.response = response;
    this.outputStream = outputStream;
    this.maxHeadLength = maxHeadLength;
    this.logger = loggerFactory.getLogger(HTTPRequestProcessor.class);
  }

  public synchronized ByteBuffer currentBuffer() {
    ByteBuffer buffer = outputStream.readableBuffer();
    boolean closed = outputStream.isClosed();

    if (state == ResponseState.Preamble || state == ResponseState.Expect) {
      // We can't write the preamble under normal conditions if the worker thread is still working. Expect handling is different and the
      // client is waiting for a pre-canned response
      if (state != ResponseState.Expect && buffer == null && !closed) {
        return null;
      }

      // Construct the preamble if needed and return it if there is any bytes left
      if (preambleBuffer == null) {
        logger.debug("The worker thread has bytes to write or has closed the stream, but the preamble hasn't been sent yet. Generating preamble");
        if (state == ResponseState.Preamble) {
          fillInHeaders();
          preambleBuffer = HTTPTools.buildResponsePreamble(response, maxHeadLength);
        } else if (state == ResponseState.Expect) {
          preambleBuffer = HTTPTools.buildExpectResponsePreamble(response, maxHeadLength);
        }

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

      // Reset the buffer in case we need to write another preamble (i.e. for expect)
      preambleBuffer = null;

      // If expect and preamble done, figure out stage to be Continue or Close
      if (state == ResponseState.Expect) {
        logger.debug("Expect response written");
        if (response.getStatus() == 100) {
          logger.debug("Continuing");
          state = ResponseState.Continue;
        } else {
          logger.debug("Closing");
          state = ResponseState.Close;
        }
      } else {
        // If not expect, next stage is Body
        state = ResponseState.Body;
      }
    } else if (state == ResponseState.Body) {
      if (buffer != null) {
        logger.debug("Writing back bytes");
        logger.trace("(WB)");
        return buffer;
      }

      if (closed) {
        // No more bytes and the stream is closed, figure out if we should Keep-Alive or Close
        state = response.isKeepAlive() ? ResponseState.KeepAlive : ResponseState.Close;
        logger.debug("No more bytes from worker thread. Changing state to [{}]", state);
        logger.trace("(WL)");
      } else {
        // Just some debugging
        logger.debug("Nothing to write from the worker thread but the OutputStream isn't closed");
      }
    }

    return null;
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
    // If the client wants the connection closed, force that in the response. This will force the code above to close the connection.
    // Otherwise, if the client asked for Keep-Alive and the server agrees, keep it. If the request asked for Keep-Alive, and the server
    // doesn't care, keep it. Otherwise, if the client and server both don't care, set to Keep-Alive.
    String requestConnection = request.getHeader(Headers.Connection);
    boolean requestKeepAlive = Connections.KeepAlive.equalsIgnoreCase(requestConnection);
    String responseConnection = response.getHeader(Headers.Connection);
    boolean responseKeepAlive = Connections.KeepAlive.equalsIgnoreCase(responseConnection);
    if (Connections.Close.equalsIgnoreCase(requestConnection)) {
      response.setHeader(Headers.Connection, Connections.Close);
    } else if ((requestKeepAlive && responseKeepAlive) || (requestKeepAlive && responseConnection == null) || (requestConnection == null && responseConnection == null)) {
      response.setHeader(Headers.Connection, Connections.KeepAlive);
    }

    if (outputStream.isEmpty()) {
      response.setContentLength(0L);
    }
  }

  public enum ResponseState {
    Preamble,
    Body,
    KeepAlive,
    Close,
    Expect,
    Continue,
    Failure
  }
}
