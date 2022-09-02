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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import io.fusionauth.http.HTTPMethod;
import io.fusionauth.http.HTTPRequest;
import io.fusionauth.http.HTTPValues.TransferEncodings;
import io.fusionauth.http.body.ChunkedBodyState;
import io.fusionauth.http.util.UnlimitedByteBuffer;

/**
 * A processor that handles incoming bytes that form the HTTP request.
 *
 * @author Brian Pontarelli
 */
public class HTTPRequestProcessor {
  private final UnlimitedByteBuffer bodyBuffer = new UnlimitedByteBuffer();

  private final StringBuilder builder = new StringBuilder();

  private final UnlimitedByteBuffer headBuffer = new UnlimitedByteBuffer();

  private final HTTPRequest request;

  private BodyProcessor bodyProcessor;

  private RequestHeadState headState = RequestHeadState.RequestMethod;

  private String headerName;

  private RequestState state = RequestState.Head;

  public HTTPRequestProcessor(HTTPRequest request) {
    this.request = request;
  }

  public ByteBuffer bodyBuffer() {
    return bodyBuffer.currentWriteBuffer();
  }

  public ByteBuffer headBuffer() {
    return headBuffer.currentWriteBuffer();
  }

  public RequestState state() {
    return state;
  }

  public RequestState update() {
    if (state == RequestState.Head) {
      ByteBuffer buffer = headBuffer.currentReadBuffer();
      while (buffer.hasRemaining()) {
        // If there is a state transition, store the value properly and reset the builder (if needed)
        byte ch = buffer.get();
        RequestHeadState nextState = headState.next(ch);
        if (nextState != headState) {
          switch (headState) {
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
        } else if (headState.store()) {
          // If the current state is storing, store the character
          builder.append((char) ch);
        }

        headState = nextState;
        if (headState == RequestHeadState.RequestComplete) {
          // Release the buffer
          headBuffer.release();

          // Determine the next state
          Long contentLength = request.getContentLength();
          boolean chunked = request.getTransferEncoding() != null && request.getTransferEncoding().equals(TransferEncodings.Chunked);
          if ((contentLength != null && contentLength > 0) || chunked) {
            state = RequestState.Body;

            // Set up the body processor
            if (chunked) {
              bodyProcessor = new ChunkedBodyProcessor();
            } else if (contentLength != null) {
              bodyProcessor = new ContentLengthBodyProcessor(contentLength);
            }
          } else if (request.getContentType() != null) {
            state = RequestState.Error411;
          } else {
            state = RequestState.Complete;
          }
        }
      }
    } else if (state == RequestState.Body) {
      ByteBuffer buffer = bodyBuffer.currentReadBuffer();
      if (bodyProcessor.process(buffer)) {
        bodyBuffer.release();
        state = RequestState.Complete;
      }
    }

    return state;
  }

  public interface BodyProcessor {
    boolean process(ByteBuffer buffer);
  }

  public enum RequestState {
    Head,
    Body,
    Error411,
    Complete
  }

  public static class ChunkedBodyProcessor implements BodyProcessor {
    private final byte[] lengthBytes = new byte[32];

    private long bytesRead;

    private long length;

    private int lengthIndex;

    private ChunkedBodyState state = ChunkedBodyState.Chunk;

    @Override
    public boolean process(ByteBuffer buffer) {
      while (buffer.hasRemaining()) {
        byte b = buffer.get();
        ChunkedBodyState nextState = state.next(b, length, bytesRead);

        // Store the length hex digit
        if (nextState == ChunkedBodyState.ChunkSize) {
          lengthBytes[lengthIndex] = b;
          lengthIndex++;

          if (lengthIndex >= 32) {
            throw new ParseException();
          }
        }

        if (state == ChunkedBodyState.ChunkSize && nextState != ChunkedBodyState.ChunkSize) {
          length = Integer.parseInt(new String(lengthBytes, 0, lengthIndex, StandardCharsets.US_ASCII), 16);
          bytesRead = 0;
        }

        if (nextState == ChunkedBodyState.Chunk) {
          bytesRead++;
        }

        if (nextState == ChunkedBodyState.Complete) {
          return true;
        }

        state = nextState;
      }

      return false;
    }
  }

  public static class ContentLengthBodyProcessor implements BodyProcessor {
    private final long contentLength;

    private long bytesRead;

    public ContentLengthBodyProcessor(long contentLength) {
      this.contentLength = contentLength;
    }

    @Override
    public boolean process(ByteBuffer buffer) {
      bytesRead++;

      return bytesRead == contentLength;
    }
  }
}
