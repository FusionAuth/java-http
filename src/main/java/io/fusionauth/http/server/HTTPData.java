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
import java.util.List;

import io.fusionauth.http.HTTPRequest;

public class HTTPData {
  public static final int BufferSize = 1024;

  public final List<ByteBuffer> buffers = new ArrayList<>();

  public final StringBuilder builder = new StringBuilder();

  public final HTTPRequest request = new HTTPRequest();

  public int offset;

  public RequestHeadState state = RequestHeadState.RequestMethod;

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
      RequestHeadState nextState = state.next(array[i]);
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
      if (state == RequestHeadState.RequestComplete) {
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
    offset = 0;
    request.reset();
    state = RequestHeadState.RequestMethod;
  }
}
