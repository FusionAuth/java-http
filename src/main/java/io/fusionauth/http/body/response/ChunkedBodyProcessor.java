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
package io.fusionauth.http.body.response;

import java.nio.ByteBuffer;

import io.fusionauth.http.HTTPValues.ControlBytes;
import io.fusionauth.http.io.NonBlockingByteBufferOutputStream;

/**
 * A body processor that handles chunked requests/responses.
 *
 * @author Brian Pontarelli
 */
public class ChunkedBodyProcessor implements BodyProcessor {
  private final ByteBuffer[] Final = new ByteBuffer[]{ByteBuffer.wrap(ControlBytes.ChunkedTerminator)};

  private final ByteBuffer Trailer = ByteBuffer.wrap(ControlBytes.CRLF);

  private final ByteBuffer[] currentBuffers = new ByteBuffer[3];

  private final NonBlockingByteBufferOutputStream outputStream;

  public ChunkedBodyProcessor(NonBlockingByteBufferOutputStream outputStream) {
    this.outputStream = outputStream;
  }

  @Override
  public ByteBuffer[] currentBuffers() {
    // If the current chunk still has data, write it
    if (currentBuffers[1] != null && (currentBuffers[0].hasRemaining() || currentBuffers[1].hasRemaining() || currentBuffers[2].hasRemaining())) {
      return currentBuffers;
    }

    // Otherwise, get the next chunk if any is ready to go
    ByteBuffer buffer = outputStream.readableBuffer();
    if (buffer != null) {
      buildChunk(buffer);
      return currentBuffers;
    }

    if (!outputStream.isClosed()) {
      return null;
    }

    if (Final[0].hasRemaining()) {
      return Final;
    }

    return null;
  }

  @Override
  public boolean isComplete() {
    return outputStream.isClosed() && !Final[0].hasRemaining();
  }

  private void buildChunk(ByteBuffer buffer) {
    String header = Integer.toHexString(buffer.remaining()) + "\r\n";
    currentBuffers[0] = ByteBuffer.wrap(header.getBytes());
    currentBuffers[1] = buffer;
    currentBuffers[2] = Trailer.clear();
  }
}
