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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HTTPData {
  public static final int BufferSize = 1024;

  public final List<ByteBuffer> buffers = new ArrayList<>();

  public final StringBuilder builder = new StringBuilder();

  public final Map<String, List<String>> headers = new HashMap<>();

  public String headerName;

  public long lastUsed;

  public String method;

  public int offset;

  public String path;

  public String protocl;

  public ByteBuffer response;

  public RequestParserState state = RequestParserState.RequestMethod;

  public ByteBuffer currentBuffer() {
    ByteBuffer last = buffers.isEmpty() ? null : buffers.get(buffers.size() - 1);
    if (last == null || last.position() == last.limit()) {
      last = ByteBuffer.allocate(BufferSize);
      buffers.add(last);
    }

    return last;
  }

  public boolean isRequestComplete() {
    int index = offset / BufferSize;
    ByteBuffer buffer = buffers.get(index);
    byte[] array = buffer.array();
    for (int i = 0; i < buffer.position(); i++) {
      // If there is a state transition, store the value properly and reset the builder (if needed)
      RequestParserState nextState = state.next(array[i]);
      if (nextState != state) {
        switch (state) {
          case RequestMethod -> method = builder.toString();
          case RequestPath -> path = builder.toString();
          case RequestProtocol -> protocl = builder.toString();
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
      if (state == RequestParserState.RequestComplete) {
        return true;
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
    buffers.clear();
    builder.delete(0, builder.length());
    headers.clear();
    headerName = null;
    lastUsed = 0;
    method = null;
    offset = 0;
    path = null;
    protocl = null;
    response = null;
    state = RequestParserState.RequestMethod;
  }
}
