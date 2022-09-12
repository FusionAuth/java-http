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
package io.fusionauth.http.body;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * A body processor that handles chunked requests/responses.
 *
 * @author Brian Pontarelli
 */
public class ChunkedBodyProcessor implements BodyProcessor {
  public static final int MaxChunkSize = Integer.MAX_VALUE - 2;

  private final ByteBuffer buffer;

  private final StringBuilder headerSizeHex = new StringBuilder();

  private int chunkBytes;

  private int chunkSize;

  private ChunkedBodyState state = ChunkedBodyState.ChunkSize;

  private int totalBytes;

  public ChunkedBodyProcessor(int bufferSize) {
    this.buffer = ByteBuffer.allocate(bufferSize);
  }

  @Override
  public ByteBuffer currentBuffer() {
    return buffer;
  }

  @Override
  public boolean isComplete() {
    return state == ChunkedBodyState.Complete;
  }

  @Override
  public void processBuffer(Consumer<ByteBuffer> consumer) {
    buffer.flip();
    while (buffer.hasRemaining()) {
      byte ch = buffer.get();
      var nextState = state.next(ch, chunkSize, chunkBytes);

      // We are DONE!
      if (nextState == ChunkedBodyState.Complete) {
        state = nextState;
        return;
      }

      if (nextState == ChunkedBodyState.ChunkSize) {
        headerSizeHex.appendCodePoint(ch);
      } else if (state != ChunkedBodyState.Chunk && nextState == ChunkedBodyState.Chunk) {
        // This means we finished reading the size and are reading to start processing
        if (headerSizeHex.isEmpty()) {
          throw new ChunkException("Chunk size is missing");
        }

        long chunkSizeLong = Long.parseLong(headerSizeHex, 0, headerSizeHex.length(), 16);
        if (chunkSizeLong > MaxChunkSize) {
          throw new ChunkException("Chunk size too large [" + chunkSize + "]. Max chunk size is [" + MaxChunkSize + "]");
        }

        // This is the start of a chunk, so set the size and counter and reset the size hex string
        chunkBytes = 0;
        chunkSize = (int) chunkSizeLong;
        headerSizeHex.delete(0, headerSizeHex.length());
        buffer.position(buffer.position() - 1); // Go back one character to the start of the chunk
        if (chunkSize > 0) {
          offerChunk(consumer);
        }
      } else if (nextState == ChunkedBodyState.Chunk) {
        // This means we are in a chunk that was only partially finished last time and needs to be continued
        buffer.position(buffer.position() - 1); // Go back one character to the start of the chunk
        if (chunkSize > 0) {
          offerChunk(consumer);
        }
      }

      state = nextState;
    }

    // Always reset the buffer for the next read
    buffer.clear();
  }

  @Override
  public long totalBytesProcessed() {
    return totalBytes;
  }

  private void offerChunk(Consumer<ByteBuffer> consumer) {
    // The number of bytes to send is the chunk size minus the bytes already sent, or the bytes remaining in the buffer
    int bytes = Math.min(chunkSize - chunkBytes, buffer.remaining());
    var toSend = ByteBuffer.allocate(bytes);
    toSend.put(0, buffer, buffer.position(), bytes);
    consumer.accept(toSend);
    chunkBytes += bytes;
    totalBytes += bytes;

    // Move the position since the put above doesn't
    buffer.position(buffer.position() + bytes);
  }
}
