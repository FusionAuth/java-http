/*
 * Copyright (c) 2022-2025, FusionAuth, All Rights Reserved
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
package io.fusionauth.http.io;

import java.io.IOException;
import java.io.InputStream;

import io.fusionauth.http.util.HTTPTools;
import static io.fusionauth.http.util.HTTPTools.makeParseException;

/**
 * A filter InputStream that handles the chunked body while passing the body bytes down to the delegate stream.
 * <p>
 * TODO: This can be much more efficient by not handling each chunk separately.
 *
 * @author Brian Pontarelli
 */
public class ChunkedInputStream extends InputStream {
  private final byte[] buffer;

  private final InputStream delegate;

  private final StringBuilder headerSizeHex = new StringBuilder();

  private int bufferIndex;

  private int bufferLength;

  private int chunkBytesRemaining;

  private int chunkSize;

  private ChunkedBodyState state = ChunkedBodyState.ChunkSize;

  // TODO : Why is this so different from HTTP InputStream, can't I just pass through the ByteBuffer that wraps the Request Buffer?
  public ChunkedInputStream(InputStream delegate, int bufferSize, byte[] bodyBytes) {
    this.delegate = delegate;

    if (bodyBytes == null) {
      this.buffer = new byte[bufferSize];
    } else {
      this.bufferLength = bodyBytes.length;
      this.buffer = new byte[Math.max(bufferSize, bufferLength)];
      System.arraycopy(bodyBytes, 0, buffer, 0, bufferLength);
    }
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    return processChunk(b, off, len);
  }

  @Override
  public int read() throws IOException {
    return read(new byte[1]); // Slow but this method should never be called
  }

  private int processChunk(byte[] destination, int offset, int length) throws IOException {
    // Bail early if the state machine is done
    if (state == ChunkedBodyState.Complete) {
      return -1;
    }

    // Read some more if we are out of bytes
    if (bufferIndex >= bufferLength) {
      bufferIndex = 0;
      bufferLength = delegate.read(buffer);
    }

    for (; bufferIndex < bufferLength; bufferIndex++) {
      var nextState = state.next(buffer[bufferIndex], chunkSize, chunkSize - chunkBytesRemaining);

      // We are DONE!
      if (nextState == ChunkedBodyState.Complete) {
        state = nextState;
        bufferIndex++;
        return -1;
      }

      // Record the size hex digit
      if (nextState == ChunkedBodyState.ChunkSize) {
        headerSizeHex.appendCodePoint(buffer[bufferIndex]);
        state = nextState;
        continue;
      }

      // No chunk processing, just continue to the next character
      if (nextState == ChunkedBodyState.ChunkSizeCR || nextState == ChunkedBodyState.ChunkSizeLF ||
          nextState == ChunkedBodyState.ChunkCR || nextState == ChunkedBodyState.ChunkLF) {
        state = nextState;
        continue;
      }

      if (state != ChunkedBodyState.Chunk && nextState == ChunkedBodyState.Chunk) {
        // This means we finished reading the size and are ready to start processing
        if (headerSizeHex.isEmpty()) {
          throw new ChunkException("Chunk size is missing");
        }

        // This is the start of a chunk, so set the size and counter and reset the size hex string
        chunkSize = (int) Long.parseLong(headerSizeHex, 0, headerSizeHex.length(), 16);
        chunkBytesRemaining = chunkSize;
        headerSizeHex.delete(0, headerSizeHex.length());

        // AF\r1234 i=3 length=42 read=7 chunkSize=175(AF)
        if (chunkSize == 0) {
          state = nextState;
          return 0;
        }
      }

      int remainingInBuffer = bufferLength - bufferIndex;
      int copied = Math.min(Math.min(chunkBytesRemaining, remainingInBuffer), length); // That's an ugly baby!
      System.arraycopy(buffer, bufferIndex, destination, offset, copied);
      bufferIndex += copied;
      chunkBytesRemaining -= copied;
      state = nextState;
      return copied;
    }

    return 0;
  }

  public enum ChunkedBodyState {
    ChunkSize {
      @Override
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        if (ch == '\r') {
          return ChunkSizeCR;
        } else if (HTTPTools.isHexadecimalCharacter(ch)) {
          return ChunkSize;
        }

        throw makeParseException(ch, this);
      }
    },

    ChunkSizeCR {
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        if (ch == '\n') {
          return ChunkSizeLF;
        }

        throw makeParseException(ch, this);
      }
    },

    ChunkSizeLF {
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        return Chunk;
      }
    },

    Chunk {
      @Override
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        if (bytesRead < length) {
          return Chunk;
        } else if (bytesRead == length && ch == '\r') {
          return ChunkCR;
        }

        throw makeParseException(ch, this);
      }
    },

    ChunkCR {
      @Override
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        if (ch == '\n') {
          return length == 0 ? Complete : ChunkLF;
        }

        throw makeParseException(ch, this);
      }
    },

    ChunkLF {
      @Override
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        if (length == 0) {
          return Complete;
        } else if (HTTPTools.isHexadecimalCharacter(ch)) {
          return ChunkSize;
        }

        throw makeParseException(ch, this);
      }
    },

    Complete {
      @Override
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        return Complete;
      }
    };

    public abstract ChunkedBodyState next(byte ch, long length, long bytesRead);
  }
}
