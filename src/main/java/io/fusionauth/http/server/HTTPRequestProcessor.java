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

import io.fusionauth.http.HTTPMethod;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.HTTPValues.Status;
import io.fusionauth.http.HTTPValues.TransferEncodings;
import io.fusionauth.http.io.ReaderBlockingByteBufferInputStream;
import io.fusionauth.http.log.Logger;
import io.fusionauth.http.log.LoggerFactory;

/**
 * A processor that handles incoming bytes that form the HTTP request.
 *
 * @author Brian Pontarelli
 */
public class HTTPRequestProcessor {
  private final StringBuilder builder = new StringBuilder();

  private final Logger logger;

  private final HTTPRequest request;

  private long bytesRead;

  private long contentLength;

  private ByteBuffer currentBodyBuffer;

  private String headerName;

  private ReaderBlockingByteBufferInputStream inputStream;

  private RequestPreambleState preambleState = RequestPreambleState.RequestMethod;

  private RequestState state = RequestState.Preamble;

  public HTTPRequestProcessor(HTTPRequest request, LoggerFactory loggerFactory) {
    this.request = request;
    this.logger = loggerFactory.getLogger(HTTPRequestProcessor.class);
  }

  public ByteBuffer bodyBuffer() {
    // If there is still space left, return the current buffer
    if (currentBodyBuffer != null && currentBodyBuffer.hasRemaining()) {
      return currentBodyBuffer;
    }

    // There isn't any space left, so let's feed the current buffer to the InputStream
    if (currentBodyBuffer != null) {
      inputStream.addByteBuffer(currentBodyBuffer);
    }

    // Create a new buffer and return it. This will also be hit if currentBodyBuffer is null
    currentBodyBuffer = ByteBuffer.allocate(1024);
    return currentBodyBuffer;
  }

  public RequestState processBodyBytes() {
    bytesRead += currentBodyBuffer.position();

    if (bytesRead >= contentLength && currentBodyBuffer.position() > 0) {
      logger.debug("Pushing last buffer");
      currentBodyBuffer.flip();
      inputStream.addByteBuffer(currentBodyBuffer);
      inputStream.signalDone();
      return RequestState.Complete;
    }

    if (!currentBodyBuffer.hasRemaining()) {
      logger.debug("Pushing not last buffer");
      currentBodyBuffer.flip();
      inputStream.addByteBuffer(currentBodyBuffer);
      bodyBuffer();
    }

    return RequestState.Body;
  }

  public RequestState processPreambleBytes(ByteBuffer buffer) {
    while (buffer.hasRemaining()) {
      // If there is a state transition, store the value properly and reset the builder (if needed)
      byte ch = buffer.get();
      RequestPreambleState nextState = preambleState.next(ch);
      if (nextState != preambleState) {
        switch (preambleState) {
          case RequestMethod -> request.setMethod(HTTPMethod.of(builder.toString()));
          case RequestPath -> request.setPath(builder.toString());
          case RequestProtocol -> request.setProtocol(builder.toString());
          case HeaderName -> headerName = builder.toString();
          case HeaderValue -> request.addHeader(headerName, builder.toString());
        }

        // If the next state is storing, reset the builder
        if (nextState.store()) {
          builder.delete(0, builder.length());
          builder.appendCodePoint(ch);
        }
      } else if (preambleState.store()) {
        // If the current state is storing, store the character
        builder.appendCodePoint(ch);
      }

      preambleState = nextState;
      if (preambleState == RequestPreambleState.Complete) {
        logger.debug("Preamble successfully parsed");

        if (Status.ContinueRequest.equalsIgnoreCase(request.getHeader(Headers.Expect))) {
          logger.debug("Expect request received");
          state = RequestState.Expect;
          return state;
        }

        // Determine the next state
        Long contentLength = request.getContentLength();
        if (contentLength != null) {
          this.contentLength = contentLength;
        }

        boolean chunked = request.getTransferEncoding() != null && request.getTransferEncoding().equalsIgnoreCase(TransferEncodings.Chunked);
        if ((contentLength != null && contentLength > 0) || chunked) {
          logger.debug("Client indicated it was sending an entity-body in the request");

          state = RequestState.Body;

          if (chunked) {
            throw new IllegalStateException("Chunking not supported yet");
          }

          // Create the input stream and add any body data that is left over in the buffer
          inputStream = new ReaderBlockingByteBufferInputStream();
          if (buffer.hasRemaining()) {
            currentBodyBuffer = ByteBuffer.allocate(buffer.remaining());
            currentBodyBuffer.put(buffer);
            state = processBodyBytes();
          }

          request.setInputStream(inputStream);
        } else {
          logger.debug("Client indicated it was NOT sending an entity-body in the request");
          state = RequestState.Complete;
        }
      }
    }

    return state;
  }

  public void resetState(RequestState state) {
    this.state = state;

    // If we are resetting to the Body state, we need to set up a new InputStream
    if (this.state == RequestState.Body) {
      inputStream = new ReaderBlockingByteBufferInputStream();
      request.setInputStream(inputStream);
    }
  }

  public RequestState state() {
    return state;
  }

  public enum RequestState {
    Preamble,
    Body,
    Expect,
    Complete
  }
}
