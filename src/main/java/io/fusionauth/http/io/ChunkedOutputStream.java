/*
 * Copyright (c) 2022-2023, FusionAuth, All Rights Reserved
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
import java.io.OutputStream;

import io.fusionauth.http.HTTPValues.ControlBytes;

/**
 * An OutputStream that writes back a chunked response.
 *
 * @author Brian Pontarelli
 */
public class ChunkedOutputStream extends OutputStream {
  private final byte[] buffer;

  private final OutputStream delegate;

  private int bufferIndex;

  private boolean closed;

  public ChunkedOutputStream(OutputStream delegate, int maxChunkSize) {
    this.delegate = delegate;
    this.buffer = new byte[maxChunkSize];
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      flush();
      delegate.write(ControlBytes.ChunkedTerminator);
      delegate.flush();
      delegate.close();
    }

    closed = true;
  }

  @Override
  public void flush() throws IOException {
    if (closed) {
      return;
    }

    if (bufferIndex > 0) {
      String header = Integer.toHexString(bufferIndex) + "\r\n";
      delegate.write(header.getBytes());
      delegate.write(buffer, 0, bufferIndex);
      delegate.write(ControlBytes.CRLF);
      bufferIndex = 0;
    }

    delegate.flush();
  }

  @Override
  public void write(byte[] b) throws IOException {
    write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int offset, int length) throws IOException {
    int index = offset;
    while (index < length) {
      int wrote = Math.min(buffer.length - bufferIndex, length);
      System.arraycopy(b, 0, buffer, bufferIndex, wrote);
      bufferIndex += wrote;
      index += wrote;

      if (bufferIndex >= buffer.length) {
        flush();
      }
    }
  }

  @Override
  public void write(int b) throws IOException {
    if (bufferIndex < buffer.length) {
      buffer[bufferIndex++] = (byte) b;
    }
  }
}
