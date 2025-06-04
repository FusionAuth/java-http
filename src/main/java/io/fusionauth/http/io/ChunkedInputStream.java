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

import io.fusionauth.http.ChunkException;
import io.fusionauth.http.ParseException;
import io.fusionauth.http.util.HTTPTools;
import static io.fusionauth.http.util.HTTPTools.makeParseException;

/**
 * A filter InputStream that handles the chunked body while passing the body bytes down to the delegate stream.
 *
 * @author Brian Pontarelli
 */
public class ChunkedInputStream extends InputStream {
  private final byte[] b1 = new byte[1];

  private final byte[] buffer;

  private final InputStream delegate;

  private final StringBuilder headerSizeHex = new StringBuilder();

  private int bufferIndex;

  private int bufferLength;

  private int chunkBytesRemaining;

  private int chunkSize;

  private ChunkedBodyState state = ChunkedBodyState.ChunkSize;

  public ChunkedInputStream(InputStream delegate, int bufferSize) {
    this.delegate = delegate;
    this.buffer = new byte[bufferSize];
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    return processChunk(b, off, len);
  }

  @Override
  public int read() throws IOException {
    var read = read(b1);
    if (read <= 0) {
      return read;
    }

    return b1[0] & 0xFF;
  }

  private int processChunk(byte[] destination, int offset, int length) throws IOException {
    int totalRead = 0;
    while (totalRead < length) {
      // Bail early if the state machine is done
      if (state == ChunkedBodyState.Complete) {
        // We need to push back any remaining bytes to the InputStream since we may have read more bytes than we needed.
        int leftOver = bufferLength - bufferIndex;
        if (leftOver > 0) {
          // TODO : Daniel : Review : This doesn't seem like a good idea. It will fail silently, but this is required.
          //        Discuss with Brian.
          //        .
          //        Options:
          //         - Leave as is
          //         - Throw a OverReadException that is caught by the HTTPInputStream which has a typed ref to PushbackInputStream and handled.
          //         - Keep a typed ref for PushbackInputStream, but this messes up the 'commit' path in HTTPInputStream that swaps the pointer.
          //                So we could re-work how we handle a chunked request body - instead of swapping out pointers we do something else?
          if (delegate instanceof PushbackInputStream pis) {
            pis.push(buffer, bufferIndex, leftOver);
          }
        }

        return -1;
      }

      // Read some more if we are out of bytes
      if (bufferIndex >= bufferLength) {
        bufferIndex = 0;
        bufferLength = delegate.read(buffer);
        if (bufferLength > 0) {
          totalRead += bufferLength;
        }
      }

      for (; bufferIndex < bufferLength; bufferIndex++) {
        ChunkedBodyState nextState;
        try {
          nextState = state.next(buffer[bufferIndex], chunkSize, chunkSize - chunkBytesRemaining);
        } catch (ParseException e) {
          // This allows us to add the index to the exception. Useful for debugging.
          e.setIndex(bufferIndex);
          throw e;
        }

        // We are DONE!
        if (nextState == ChunkedBodyState.Complete) {
          state = nextState;
          bufferIndex++;
          // We need to push back any remaining bytes to the InputStream since we may have read more bytes than we needed.
          int leftOver = bufferLength - bufferIndex;
          if (leftOver > 0) {
            // TODO : Daniel : Review : This doesn't seem like a good idea. It will fail silently, but this is required.
            //        Discuss with Brian.
            if (delegate instanceof PushbackInputStream pis) {
              pis.push(buffer, bufferIndex, leftOver);
            }
          }
          return -1;
        }

        // Record the size hex digit
        if (nextState == ChunkedBodyState.ChunkSize) {
          headerSizeHex.appendCodePoint(buffer[bufferIndex]);
          state = nextState;
          continue;
        }

        // Capture the chunk size
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
        int lengthToCopy = Math.min(Math.min(chunkBytesRemaining, remainingInBuffer), length); // That's an ugly baby!

        // If we don't have anything to copy, continue.
        if (lengthToCopy == 0) {
          state = nextState;
          continue;
        }

        // TODO : If we wanted to handle more than one chunk at a time, I think we would potentially continue here
        //        and see if we can get more than one chunk completed before copying back to the destination.
        //        Discuss with Brian.
        //        Should we try this right now, or is this ok? Load testing with a small body,
        //        Chunked is slower than fixed length, I suppose this makes sense. In practice I don't think browsers
        //        and such use chunked for small requests.

        System.arraycopy(buffer, bufferIndex, destination, offset, lengthToCopy);
        bufferIndex += lengthToCopy;
        chunkBytesRemaining -= lengthToCopy;
        state = nextState;
        return lengthToCopy;
      }
    }

    return 0;
  }

  public enum ChunkedBodyState {
    ChunkExtensionStart {
      @Override
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        if (ch == '\r') {
          return ChunkExtensionCR;
        } else if (HTTPTools.isTokenCharacter(ch)) {
          return ChunkExtensionName;
        }

        throw makeParseException(ch, this);
      }
    },
    ChunkExtensionName {
      @Override
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        if (ch == '\r') {
          return ChunkExtensionCR;
        } else if (ch == '=') {
          return ChunkExtensionValueSep;
        } else if (ch == ';') {
          return ChunkExtensionStart;
        } else if (HTTPTools.isTokenCharacter(ch)) {
          return ChunkExtensionName;
        }

        throw makeParseException(ch, this);
      }
    },
    ChunkExtensionValueSep {
      @Override
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        if (ch == '\r') {
          return ChunkExtensionCR;
        } else if (ch == ';') {
          return ChunkExtensionStart;
        } else if (HTTPTools.isTokenCharacter(ch)) {
          return ChunkExtensionValue;
        }

        throw makeParseException(ch, this);
      }
    },
    ChunkExtensionValue {
      @Override
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        if (ch == '\r') {
          return ChunkExtensionCR;
        } else if (ch == ';') {
          return ChunkExtensionStart;
        } else if (HTTPTools.isTokenCharacter(ch)) {
          return ChunkExtensionValue;
        }

        throw makeParseException(ch, this);
      }
    },
    ChunkExtensionCR {
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        if (ch == '\n') {
          return ChunkExtensionLF;
        }

        throw makeParseException(ch, this);
      }
    },
    ChunkExtensionLF {
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        return Chunk;
      }
    },
    ChunkSize {
      @Override
      public ChunkedBodyState next(byte ch, long length, long bytesRead) {
        if (ch == '\r') {
          return ChunkSizeCR;
        } else if (ch == ';') {
          return ChunkExtensionStart;
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
