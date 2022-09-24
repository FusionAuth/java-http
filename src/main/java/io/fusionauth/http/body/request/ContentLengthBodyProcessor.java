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
package io.fusionauth.http.body.request;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * A body processor that uses the Content-Length header to determine when the entire body has been read.
 *
 * @author Brian Pontarelli
 */
public class ContentLengthBodyProcessor implements BodyProcessor {
  private final int bufferSize;

  private final long contentLength;

  private ByteBuffer buffer;

  private long bytesRead = 0;

  public ContentLengthBodyProcessor(int bufferSize, long contentLength) {
    this.bufferSize = bufferSize;
    this.contentLength = contentLength;
    this.buffer = ByteBuffer.allocate(bufferSize);
  }

  @Override
  public ByteBuffer currentBuffer() {
    return buffer;
  }

  @Override
  public boolean isComplete() {
    return bytesRead == contentLength;
  }

  @Override
  public void processBuffer(Consumer<ByteBuffer> consumer) {
    // If the bytes read is Content-Length or the buffer is full
    if (buffer.remaining() + bytesRead >= contentLength || buffer.remaining() == buffer.capacity()) {
      bytesRead += buffer.remaining();
      consumer.accept(buffer);

      // Create a new buffer if we have more bytes to read
      if (bytesRead < contentLength) {
        buffer = ByteBuffer.allocate(bufferSize);
      }
    } else {
      // Reset the position and limit such that we can read more into the buffer
      buffer.position(buffer.limit());
      buffer.limit(buffer.capacity());
    }
  }

  @Override
  public long totalBytesProcessed() {
    return bytesRead;
  }
}
