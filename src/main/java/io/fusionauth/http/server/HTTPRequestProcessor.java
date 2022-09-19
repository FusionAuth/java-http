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
import io.fusionauth.http.body.request.BodyProcessor;
import io.fusionauth.http.body.request.ChunkedBodyProcessor;
import io.fusionauth.http.body.request.ContentLengthBodyProcessor;
import io.fusionauth.http.io.ReaderBlockingByteBufferInputStream;
import io.fusionauth.http.log.Logger;

/**
 * A processor that handles incoming bytes that form the HTTP request.
 *
 * @author Brian Pontarelli
 */
public class HTTPRequestProcessor {
  private final int bufferSize;

  private final StringBuilder builder = new StringBuilder();

  private final HTTPServerConfiguration configuration;

  private final Logger logger;

  private final HTTPRequest request;

  private BodyProcessor bodyProcessor;

  private String headerName;

  private ReaderBlockingByteBufferInputStream inputStream;

  private RequestPreambleState preambleState = RequestPreambleState.RequestMethod;

  private RequestState state = RequestState.Preamble;

  public HTTPRequestProcessor(HTTPServerConfiguration configuration, HTTPRequest request) {
    this.bufferSize = configuration.getRequestBufferSize();
    this.configuration = configuration;
    this.request = request;
    this.logger = configuration.getLoggerFactory().getLogger(HTTPRequestProcessor.class);
  }

  public ByteBuffer bodyBuffer() {
    return bodyProcessor.currentBuffer();
  }

  public RequestState processBodyBytes() {
    bodyProcessor.processBuffer(inputStream);

    if (bodyProcessor.isComplete()) {
      inputStream.signalDone();
      return RequestState.Complete;
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

        // Determine if there is a body and if we should handle it. Even if we are in an expect request, the body will be coming, so we need
        // to prepare for it here
        Long contentLength = request.getContentLength();
        if ((contentLength != null && contentLength > 0) || request.isChunked()) {
          logger.debug("Client indicated it was sending an entity-body in the request");

          state = RequestState.Body;

          int size = Math.max(buffer.remaining(), bufferSize);
          if (contentLength != null) {
            bodyProcessor = new ContentLengthBodyProcessor(size, contentLength);
          } else {
            bodyProcessor = new ChunkedBodyProcessor(size);
            configuration.getInstrumenter().chunkedRequest();
          }

          // Create the input stream and add any body data that is left over in the buffer
          inputStream = new ReaderBlockingByteBufferInputStream();
          if (buffer.hasRemaining()) {
            bodyProcessor.currentBuffer().put(buffer);
            state = processBodyBytes();
          }

          request.setInputStream(inputStream);
        } else {
          logger.debug("Client indicated it was NOT sending an entity-body in the request");
          state = RequestState.Complete;
        }

        // If we are expecting, set the state and bail
        if (Status.ContinueRequest.equalsIgnoreCase(request.getHeader(Headers.Expect))) {
          logger.debug("Expect request received");
          state = RequestState.Expect;
          return state;
        }
      }
    }

    return state;
  }

  public void resetState(RequestState state) {
    this.state = state;
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
