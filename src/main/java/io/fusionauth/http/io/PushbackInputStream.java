/*
 * Copyright (c) 2025, FusionAuth, All Rights Reserved
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

import io.fusionauth.http.server.Instrumenter;

/**
 * An input stream that allows a portion of bytes read into a buffer to be pushed back to be read again.
 *
 * @author Daniel DeGroff
 */
public class PushbackInputStream extends InputStream {
  private final byte[] b1 = new byte[1];

  private final InputStream delegate;

  private final Instrumenter instrumenter;

  private byte[] buffer;

  private int bufferEndPosition;

  private int bufferPosition;

  public PushbackInputStream(InputStream delegate, Instrumenter instrumenter) {
    this.delegate = delegate;
    this.instrumenter = instrumenter;
  }

  public int getAvailableBufferedBytesRemaining() {
    return buffer != null ? (bufferEndPosition - bufferPosition) : 0;
  }

  /**
   * Push back the bytes found in the provided buffer.
   *
   * @param buffer the buffer that contains bytes that should be pushed back into the stream.
   * @param offset the offset into the buffer where a read should start
   * @param length the length of bytes starting from the offset that are available to be read
   */
  public void push(byte[] buffer, int offset, int length) {
    if (this.buffer != null) {
      throw new IllegalStateException("You are not allowed to push more bytes back on to the InputStream until you have read the previously pushed bytes.");
    }

    this.buffer = buffer;
    this.bufferPosition = offset;
    this.bufferEndPosition = offset + length;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    if (buffer != null) {
      int read = Math.min(len, (bufferEndPosition - bufferPosition));
      System.arraycopy(buffer, bufferPosition, b, off, read);
      bufferPosition += read;

      // Buffer is all used up
      if (bufferPosition >= bufferEndPosition) {
        this.buffer = null;
        this.bufferPosition = -1;
        this.bufferEndPosition = -1;
      }

      // Note that we must return after reading from the buffer.
      // - We don't know if we are at the end of the InputStream. Calling read again will block causing us not to be able to
      //   complete processing of the bytes we just read from the buffer in order to send the HTTP response.
      //   The end result is the client will block while waiting for us to send a response until we take an exception waiting
      //   for the read timeout.
      // - Do not count this as a read from the client, that would double count.
      //   We should only be counting bytes read from the delegate.
      return read;
    }

    var read = delegate.read(b, off, len);
    if (read > 0 && instrumenter != null) {
      instrumenter.readFromClient(read);
    }

    return read;
  }

  @Override
  public int read() throws IOException {
    var read = read(b1);
    if (read <= 0) {
      return read;
    }

    return b1[0] & 0xFF;
  }
}
