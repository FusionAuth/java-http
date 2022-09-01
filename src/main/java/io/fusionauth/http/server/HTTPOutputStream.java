/*
 * Copyright (c) 2021-2022, FusionAuth, All Rights Reserved
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

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Defines an OutputStream that can handle writing an HTTP response back to the client without having to maintain the entire body in memory.
 * This manages the HTTP headers and flushing the response as needed.
 *
 * @author Brian Pontarelli
 */
public class HTTPOutputStream extends OutputStream {
  private Queue<ByteBuffer> buffers;

  private ByteBuffer current;

  @Override
  public void close() {
    // No-op
  }

  @Override
  public void flush() {
    // No-op
  }

  @Override
  public void write(byte[] b) {
    write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) {
    ByteBuffer buffer = currentBuffer();
    int min = Math.min(len, current.remaining());
    buffer.put(b, 0, min);

    // If there are any left over, write them to a new buffer
    if (min < len) {
      buffer = currentBuffer();
      buffer.put(b, min, len);
    }
  }

  @Override
  public void write(int b) {
    ByteBuffer buffer = currentBuffer();
    buffer.put((byte) b);
  }

  private ByteBuffer currentBuffer() {
    if (buffers == null) {
      buffers = new LinkedList<>();
    }

    if (current == null || current.position() == current.limit()) {
      current = ByteBuffer.allocate(1024);
      buffers.offer(current);
    }

    return current;
  }
}