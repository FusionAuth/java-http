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

  private final PushbackInputStream delegate;

  private final StringBuilder headerSizeHex = new StringBuilder();

  private int bufferIndex;

  private int bufferLength;

  private int chunkBytesRead;

  private int chunkBytesRemaining;

  private int chunkSize;

  private ChunkedBodyState state = ChunkedBodyState.ChunkSize;

  public ChunkedInputStream(PushbackInputStream delegate, int bufferSize) {
    this.delegate = delegate;
    this.buffer = new byte[bufferSize];
  }

  @Override
  public int read(byte[] destination, int dOff, int dLen) throws IOException {
    int dIndex = dOff;
    while (dIndex < dLen) {
      if (state == ChunkedInputStream.ChunkedBodyState.Complete) {
        pushBackOverReadBytes();
        break;
      }

      // Read some more if we are out of bytes
      if (bufferIndex >= bufferLength) {
        bufferIndex = 0;
        bufferLength = delegate.read(buffer);
      }

      // Process the buffer
      while (bufferIndex < bufferLength) {
        ChunkedBodyState nextState;
        try {
          nextState = state.next(buffer[bufferIndex], chunkSize, chunkBytesRead);
        } catch (ParseException e) {
          // This allows us to add the index to the exception. Useful for debugging.
          e.setIndex(bufferIndex);
          throw e;
        }

        // We have reached the end of the encoded payload. Push back any additional bytes read.
        if (state == ChunkedBodyState.Complete) {
          state = nextState;
          pushBackOverReadBytes();
          break;
        }

        // Capture the character to calculate the next chunk size
        if (nextState == ChunkedBodyState.ChunkSize) {
          headerSizeHex.appendCodePoint(buffer[bufferIndex]);
          state = nextState;
          bufferIndex++;
          continue;
        }

        // We have found the chunk, this means we can now convert the captured chunk size bytes and then try and read the chunk.
        if (state != ChunkedBodyState.Chunk && nextState == ChunkedBodyState.Chunk) {
          if (headerSizeHex.isEmpty()) {
            throw new ChunkException("Chunk size is missing");
          }

          // This is the start of a chunk, so set the size and counter and reset the size hex string
          chunkSize = (int) Long.parseLong(headerSizeHex, 0, headerSizeHex.length(), 16);

          chunkBytesRead = 0;
          chunkBytesRemaining = chunkSize;
          headerSizeHex.delete(0, headerSizeHex.length());

          // A chunk size of 0 indicates this is the terminating chunk. Continue and we will expect the state machine
          // to process the final CRLF and hit the Complete state.
          if (chunkSize == 0) {
            state = nextState;
            bufferIndex++;
            continue;
          }
        }

        int lengthToCopy;
        if (chunkBytesRemaining > 0) {
          int remainingInBuffer = bufferLength - bufferIndex;
          lengthToCopy = Math.min(Math.min(chunkBytesRemaining, remainingInBuffer), dLen - dIndex); // That's an ugly baby!

          // This means we don't have room in the destination buffer
          if (lengthToCopy == 0) {
            state = nextState;
            bufferIndex++;
            return dIndex - dOff;
          }
        } else {
          // Nothing to do, continue to the next state.
          state = nextState;
          bufferIndex++;
          continue;
        }

        // Copy 'lengthToCopy' to the destination buffer
        System.arraycopy(buffer, bufferIndex, destination, dIndex, lengthToCopy);
        bufferIndex += lengthToCopy;
        chunkBytesRead += lengthToCopy;
        chunkBytesRemaining -= lengthToCopy;
        dIndex += lengthToCopy;
        state = nextState;

        // If we have bytes to copy, we are at the correct location in the state machine, If we don't have room, break.
        // - This will break the while loop, and return at the end of this method the total bytes we have written to the destination buffer.
        if (dIndex == dLen) {
          break;
        }
      }
    }

    int total = dIndex - dOff;
    return total == 0 ? -1 : total;
  }

  @Override
  public int read() throws IOException {
    var read = read(b1);
    if (read <= 0) {
      return read;
    }

    return b1[0] & 0xFF;
  }

  private void pushBackOverReadBytes() {
    int leftOver = bufferLength - bufferIndex;
    if (leftOver > 0) {
      delegate.push(buffer, bufferIndex, leftOver);

      // Move the pointer to the end of the buffer, We have used up the bytes by pushing them back.
      bufferIndex = bufferLength;
    }
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
        if (length == 0) {
          return Complete;
        } else if (bytesRead == length && ch == '\r') {
          return ChunkCR;
        } else if (bytesRead < length) {
          return Chunk;
        } else {
          throw makeParseException(ch, this);
        }
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

    public abstract ChunkedInputStream.ChunkedBodyState next(byte ch, long length, long bytesRead);
  }
}
