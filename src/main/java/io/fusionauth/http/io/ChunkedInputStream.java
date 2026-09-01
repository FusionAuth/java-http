/*
 * Copyright (c) 2022-2026, FusionAuth, All Rights Reserved
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

import io.fusionauth.http.ParseException;
import io.fusionauth.http.util.HTTPTools;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static io.fusionauth.http.util.HTTPTools.makeParseException;

/**
 * A filter InputStream that handles the chunked body while passing the body bytes down to the delegate stream.
 *
 * @author Brian Pontarelli
 */
public class ChunkedInputStream extends InputStream {
    private final byte[] b1 = new byte[1];

    private final byte[] delegateBuffer;

    private final PushbackInputStream delegate;

    private final StringBuilder chunkSizeBuilder = new StringBuilder();

    private int delegateBufferIndex;

    private int delegateBufferLength;

    private int chunkBytesRead;

    private int chunkBytesRemaining;

    private int chunkSize;

    private ChunkedBodyState state = ChunkedBodyState.ChunkSize;

    public ChunkedInputStream(PushbackInputStream delegate, int bufferSize) {
        this.delegate = delegate;
        this.delegateBuffer = new byte[bufferSize];
    }

    @Override
    public int read(byte[] destination, int dOff, int dLen) throws IOException {
        if (dLen == 0) {
            return 0;
        }
        Objects.checkFromIndexSize(dOff, dLen, destination.length);

        int dEndIndex = dLen + dOff;
        int dCurrentIndex = dOff;
        while (dCurrentIndex < dEndIndex) {
            if (state == ChunkedInputStream.ChunkedBodyState.Complete) {
                pushBackOverReadBytes();
                break;
            }

            // Read some more if we are out of bytes
            if (delegateBufferIndex >= delegateBufferLength) {
                delegateBufferIndex = 0;
                delegateBufferLength = delegate.read(delegateBuffer);
                // nothing left to read from the delegate stream. This is either an incomplete chunk or
                // the client failed to send a terminating/terminal chunk of 0\r\n\r\n
                // Per RFC 9112 section 8, this is an 'incomplete' message body
                if (delegateBufferLength == -1) {
                    return -1;
                }
            }

            // Process the buffer
            while (delegateBufferIndex < delegateBufferLength && dCurrentIndex < dEndIndex) {
                ChunkedBodyState nextState;
                try {
                    nextState = state.next(delegateBuffer[delegateBufferIndex], chunkSize, chunkBytesRead);
                } catch (ParseException e) {
                    // This allows us to add the index to the exception. Useful for debugging.
                    e.setIndex(delegateBufferIndex);
                    throw e;
                }

                // We have reached the end of the encoded payload. Push back any additional bytes read.
                if (state == ChunkedBodyState.Complete) {
                    state = nextState;
                    delegateBufferIndex++;
                    pushBackOverReadBytes();
                    break;
                }

                // Capture the character to calculate the next chunk size
                if (nextState == ChunkedBodyState.ChunkSize) {
                    chunkSizeBuilder.appendCodePoint(delegateBuffer[delegateBufferIndex]);
                    state = nextState;
                    delegateBufferIndex++;
                    continue;
                }

                // We have found the chunk, this means we can now convert the captured chunk size bytes and then try and read the chunk.
                if (state != ChunkedBodyState.Chunk && nextState == ChunkedBodyState.Chunk) {
                    if (chunkSizeBuilder.isEmpty()) {
                        throw new ChunkException("Chunk size is missing");
                    }

                    // This is the start of a chunk, so set the size and counter and reset the size hex string
                    long chunkSizeLong = Long.parseLong(chunkSizeBuilder, 0, chunkSizeBuilder.length(), 16);
                    if (chunkSizeLong > Integer.MAX_VALUE) {
                        throw new ChunkException("Chunk size is too large");
                    }

                    chunkSize = (int) chunkSizeLong;
                    chunkBytesRead = 0;
                    chunkBytesRemaining = chunkSize;
                    chunkSizeBuilder.delete(0, chunkSizeBuilder.length());

                    // A chunk size of 0 indicates this is the terminating chunk. Continue and we will expect the state machine
                    // to process the final CRLF and hit the Complete state.
                    if (chunkSize == 0) {
                        state = nextState;
                        continue;
                    }
                }

                int lengthToCopy;
                if (chunkBytesRemaining > 0) {
                    int remainingDelegateBufferBytes = delegateBufferLength - delegateBufferIndex;
                    // we've got:
                    // 1) chunkBytesRemaining - what's left in the chunk
                    // 2) remainingInBuffer - what's left in the buffer the chunk is in
                    // 3) dEndIndex - dCurrentIndex - the what's left of the total we've been asked to copy
                    // we have to take the minimum of all of that
                    lengthToCopy = Math.min(Math.min(chunkBytesRemaining, remainingDelegateBufferBytes), dEndIndex - dCurrentIndex);
                } else {
                    // Nothing to do, continue to the next state.
                    state = nextState;
                    delegateBufferIndex++;
                    continue;
                }

                // Copy 'lengthToCopy' to the destination buffer
                System.arraycopy(delegateBuffer, delegateBufferIndex, destination, dCurrentIndex, lengthToCopy);
                delegateBufferIndex += lengthToCopy;
                chunkBytesRead += lengthToCopy;
                chunkBytesRemaining -= lengthToCopy;
                dCurrentIndex += lengthToCopy;
                state = nextState;
            }
        }

        int total = dCurrentIndex - dOff;
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

    // I'm not sure what the need for this is as of now. None of the existing code paths use it. could be
    // useful for HTTP/2 in the future
    private void pushBackOverReadBytes() {
        int leftOver = delegateBufferLength - delegateBufferIndex;
        if (leftOver > 0) {
            delegate.push(delegateBuffer, delegateBufferIndex, leftOver);

            // Move the pointer to the end of the buffer, We have used up the bytes by pushing them back.
            delegateBufferIndex = delegateBufferLength;
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
                    // Following the final 0 length chunk, trailers are optional.
                    if (HTTPTools.isURICharacter(ch)) {
                        return Trailer;
                    } else {
                        return Complete;
                    }

                } else if (bytesRead == length && ch == '\r') {
                    return ChunkCR;
                } else if (bytesRead < length) {
                    return Chunk;
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
        },
        Trailer {
            @Override
            public ChunkedBodyState next(byte ch, long length, long bytesRead) {
                if (ch == '\r') {
                    return TrailerCR;
                } else {
                    return Trailer;
                }
            }
        },
        TrailerCR {
            @Override
            public ChunkedBodyState next(byte ch, long length, long bytesRead) {
                if (ch == '\n') {
                    return TrailerLF;
                }

                throw makeParseException(ch, this);
            }
        },
        TrailerLF {
            @Override
            public ChunkedBodyState next(byte ch, long length, long bytesRead) {
                if (HTTPTools.isURICharacter(ch)) {
                    return Trailer;
                } else {
                    return Complete;
                }
            }
        };

        public abstract ChunkedInputStream.ChunkedBodyState next(byte ch, long length, long bytesRead);
    }
}
