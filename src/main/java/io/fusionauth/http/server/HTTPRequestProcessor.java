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
import java.util.ArrayList;

import io.fusionauth.http.HTTPMethod;
import io.fusionauth.http.HTTPRequest;
import io.fusionauth.http.HTTPValues.TransferEncodings;
import io.fusionauth.http.io.ReaderBlockingByteBufferInputStream;

/**
 * A processor that handles incoming bytes that form the HTTP request.
 *
 * @author Brian Pontarelli
 */
public class HTTPRequestProcessor {
  private final StringBuilder builder = new StringBuilder();

  private final HTTPRequest request;

  private long bytesRead;

  private long contentLength;

  private ByteBuffer currentBodyBuffer;

  private String headerName;

  private ReaderBlockingByteBufferInputStream inputStream;

  private RequestPreambleState preambleState = RequestPreambleState.RequestMethod;

  private RequestState state = RequestState.Preamble;

  public HTTPRequestProcessor(HTTPRequest request) {
    this.request = request;
  }

  public ByteBuffer bodyBuffer() {
    // If there is still a decent amount of space left, return the current buffer
    if (currentBodyBuffer != null && currentBodyBuffer.remaining() > 128) {
      return currentBodyBuffer;
    }

    // There isn't enough space to warrant reuse, so let's feed the current buffer to the InputStream
    if (currentBodyBuffer != null) {
      inputStream.addByteBuffer(currentBodyBuffer);
    }

    // Create a new buffer and return it. This will also be hit if currentBodyBuffer is null
    currentBodyBuffer = ByteBuffer.allocate(1024);
    return currentBodyBuffer;
  }

  public RequestState processBodyBytes() {
    bytesRead += currentBodyBuffer.position();

    if (currentBodyBuffer.remaining() == 0) {
      currentBodyBuffer.flip();
      inputStream.addByteBuffer(currentBodyBuffer);
      bodyBuffer();
    }

    if (bytesRead >= contentLength) {
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
          case RequestMethod -> request.method = HTTPMethod.of(builder.toString());
          case RequestPath -> request.path = builder.toString();
          case RequestProtocol -> request.protocl = builder.toString();
          case HeaderName -> headerName = builder.toString();
          case HeaderValue -> request.headers.computeIfAbsent(headerName.toLowerCase(), key -> new ArrayList<>()).add(builder.toString());
        }

        // If the next state is storing, reset the builder
        if (nextState.store()) {
          builder.delete(0, builder.length());
          builder.append(ch);
        }
      } else if (preambleState.store()) {
        // If the current state is storing, store the character
        builder.append((char) ch);
      }

      preambleState = nextState;
      if (preambleState == RequestPreambleState.Complete) {
        // Determine the next state
        Long contentLength = request.getContentLength();
        if (contentLength != null) {
          this.contentLength = contentLength;
        }

        boolean chunked = request.getTransferEncoding() != null && request.getTransferEncoding().equalsIgnoreCase(TransferEncodings.Chunked);
        if ((contentLength != null && contentLength > 0) || chunked) {
          state = RequestState.Body;

          if (chunked) {
            throw new IllegalStateException("Chunking not supported yet");
          }

          // Create the input stream and add any body data that is left over in the buffer
          inputStream = new ReaderBlockingByteBufferInputStream();
          if (buffer.hasRemaining()) {
            int remaining = buffer.remaining();
            byte[] bodyStart = new byte[remaining];
            System.arraycopy(buffer.array(), buffer.position(), bodyStart, 0, remaining);
            inputStream.addByteBuffer(ByteBuffer.wrap(bodyStart));
          }

          request.setInputStream(inputStream);
        } else {
          state = RequestState.Complete;
        }
      }
    }

    return state;
  }

  public RequestState state() {
    return state;
  }

  public enum RequestState {
    Preamble,
    Body,
    Complete
  }
}
