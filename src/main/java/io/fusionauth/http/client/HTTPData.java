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
package io.fusionauth.http.client;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class HTTPData {
  public static final int BufferSize = 1024;

  public final List<ByteBuffer> buffers = new ArrayList<>();

  public final StringBuilder builder = new StringBuilder();

  public final Map<String, List<String>> headers = new HashMap<>();

  public int bodyBytes;

  public int bodyLength;

  public int bodyOffset;

  public int code;

  public CompletableFuture<Integer> future;

  public boolean hasBody;

  public String headerName;

  public String host;

  public long lastUsed;

  public String message;

  public int offset;

  public String protocl;

  public ByteBuffer request;

  public ResponseParserState state = ResponseParserState.ResponseProtocol;

  public ByteBuffer currentBuffer() {
    ByteBuffer last = buffers.isEmpty() ? null : buffers.get(buffers.size() - 1);
    if (last == null || last.position() == last.limit()) {
      last = ByteBuffer.allocate(BufferSize);
      buffers.add(last);
    }

    return last;
  }

  public boolean isResponseComplete() {
    int index = offset / BufferSize;
    ByteBuffer buffer = buffers.get(index);
    byte[] array = buffer.array();
    for (int i = 0; i < buffer.position(); i++) {
      if (hasBody) {
        bodyBytes++;
        offset++;
        if (bodyBytes == bodyLength) {
          return true;
        }

        continue;
      }

      // If there is a state transition, store the value properly and reset the builder (if needed)
      ResponseParserState nextState = state.next(array[i], headers);
      if (nextState != state) {
        switch (state) {
          case ResponseStatusCode -> code = Integer.parseInt(builder.toString());
          case ResponseStatusMessage -> message = builder.toString();
          case ResponseProtocol -> protocl = builder.toString();
          case HeaderName -> headerName = builder.toString();
          case HeaderValue -> headers.computeIfAbsent(headerName.toLowerCase(), key -> new ArrayList<>()).add(builder.toString());
        }

        // If the next state is storing, reset the builder
        if (nextState.store()) {
          builder.delete(0, builder.length());
          builder.append((char) array[i]);
        }
      } else if (state.store()) {
        // If the current state is storing, store the character
        builder.append((char) array[i]);
      }

      state = nextState;
      if (state == ResponseParserState.ResponseComplete) {
        hasBody = headers.containsKey("content-length");
        bodyOffset = offset;
        bodyLength = Integer.parseInt(headers.get("content-length").get(0));

        // If there is a body, we continue in this loop, and we'll keep parsing
        if (!hasBody) {
          return true;
        }
      }

      // Increment the offset across all the buffers
      offset++;
    }

    return false;
  }

  public void markUsed() {
    lastUsed = System.currentTimeMillis();
  }

  public void reset() {
    bodyBytes = 0;
    bodyLength = 0;
    bodyOffset = 0;
    hasBody = false;
    buffers.clear();
    builder.delete(0, builder.length());
    headers.clear();
    headerName = null;
    lastUsed = 0;
    code = 0;
    offset = 0;
    message = null;
    protocl = null;
    state = ResponseParserState.ResponseStatusCode;
  }
}
