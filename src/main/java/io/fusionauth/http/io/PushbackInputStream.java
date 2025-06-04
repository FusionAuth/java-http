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

/**
 * An input stream that allows a portion of bytes read into a buffer to be pushed back to be read again.
 *
 * @author Daniel DeGroff
 */
public class PushbackInputStream extends InputStream {
  private final byte[] b1 = new byte[1];

  private byte[] buffer;

  private int bufferEndPosition;

  private int bufferPosition;

  private InputStream delegate;

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

      // Ideally we would just continue to read from the delegate if we have not yet filled the buffer.
      // - However, in non-fixed length request such as a chunked transfer encoding, if the end of the request
      //   is in the bytes we read from the buffer, and we call read() we will block because no bytes are available.
      // - So I think we have to return, and allow the caller to decide if they want to read more bytes based upon
      //   the contents of the bytes we return.
      // TODO : Daniel : Review the above statement.
      return read;
    }

    return delegate.read(b, off, len);
  }

  @Override
  public int read() throws IOException {
    var read = read(b1);
    if (read <= 0) {
      return read;
    }

    return b1[0] & 0xFF;
  }

  public void setDelegate(InputStream delegate) {
    this.delegate = delegate;
  }
}
