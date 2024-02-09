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
package io.fusionauth.http.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import io.fusionauth.http.ParseException;
import io.fusionauth.http.util.HTTPTools;

/**
 * A filter InputStream that handles the chunked body while passing the body bytes down to the delegate stream.
 *
 * @author Brian Pontarelli
 */
public class ChunkedInputStream extends FilterInputStream {
  private final byte[] buffer;

  private final byte[] chunkBuffer;

  private final StringBuilder headerSizeHex = new StringBuilder();

  private int chunkBufferIndex;

  private int chunkBufferLength;

  private int chunkBytesRead;

  private long chunkTotalSize;

  private ChunkedBodyState state = ChunkedBodyState.ChunkSize;

  private int totalBytesRead;

  public ChunkedInputStream(InputStream delegate, int bufferSize) {
    super(delegate);
    this.buffer = new byte[bufferSize];
    this.chunkBuffer = new byte[bufferSize];
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    if (state != ChunkedBodyState.Chunk || chunkBufferIndex >= chunkBufferLength) {
      if (!processChunk()) {
        return -1;
      }
    }

    int remainingInBuffer = chunkBufferLength - chunkBufferIndex;
    int toRead = Math.min(remainingInBuffer, len);
    System.arraycopy(chunkBuffer, chunkBufferIndex, b, off, toRead);
    chunkBytesRead += toRead;
    totalBytesRead += toRead;
    return toRead;
  }

  @Override
  public int read() throws IOException {
    if (state != ChunkedBodyState.Chunk || chunkBufferIndex >= chunkBufferLength) {
      if (!processChunk()) {
        return -1;
      }
    }

    chunkBytesRead++;
    totalBytesRead++;
    return chunkBuffer[chunkBufferIndex++] & 0xFF;
  }

  private boolean processChunk() throws IOException {
    int read;
    while ((read = super.read(buffer)) > 0) {
      for (int i = 0; i < read; i++) {
        var nextState = state.next(buffer[i], chunkTotalSize, chunkBytesRead);

        // We are DONE!
        if (nextState == ChunkedBodyState.Complete) {
          state = nextState;
          return false;
        }

        if (nextState == ChunkedBodyState.ChunkSize) {
          headerSizeHex.appendCodePoint(buffer[i]);
        } else if (state != ChunkedBodyState.Chunk && nextState == ChunkedBodyState.Chunk) {
          // This means we finished reading the size and are ready to start processing
          if (headerSizeHex.isEmpty()) {
            throw new ChunkException("Chunk size is missing");
          }

          // This is the start of a chunk, so set the size and counter and reset the size hex string
          long chunkSizeLong = Long.parseLong(headerSizeHex, 0, headerSizeHex.length(), 16);
          chunkBytesRead = 0;
          chunkBufferIndex = 0;
          chunkTotalSize = (int) chunkSizeLong;
          headerSizeHex.delete(0, headerSizeHex.length());

          if (chunkTotalSize > 0) {
            System.arraycopy(buffer, i, chunkBuffer, 0, read);
            chunkBufferLength = read;
            return true;
          }
        } else if (nextState == ChunkedBodyState.Chunk) {
          // This means we are in a chunk that was only partially finished last time and needs to be continued
          chunkBufferIndex = 0;
          if (chunkTotalSize > 0) {
            System.arraycopy(buffer, i, chunkBuffer, 0, read);
            chunkBufferLength = read;
            return true;
          }
        }

        state = nextState;
      }
    }

    return false;
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

        throw new ParseException();
      }
    },

    ChunkSizeCR {
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        if (ch == '\n') {
          return ChunkSizeLF;
        }

        throw new ParseException();
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

        throw new ParseException();
      }
    },

    ChunkCR {
      @Override
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        if (ch == '\n') {
          return length == 0 ? Complete : ChunkLF;
        }

        throw new ParseException();
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

        throw new ParseException();
      }
    },

    Complete {
      @Override
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        return null;
      }
    };

    public abstract ChunkedBodyState next(byte ch, long length, long bytesRead);
  }
}
