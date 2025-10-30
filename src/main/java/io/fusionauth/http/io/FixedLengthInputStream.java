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
 * A filter InputStream that reads a fixed length body.
 *
 * @author Daniel DeGroff
 */
public class FixedLengthInputStream extends InputStream {
  private final byte[] b1 = new byte[1];

  private final PushbackInputStream delegate;

  private long bytesRemaining;

  public FixedLengthInputStream(PushbackInputStream delegate, long contentLength) {
    this.delegate = delegate;
    this.bytesRemaining = contentLength;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    if (bytesRemaining <= 0) {
      return -1;
    }

    int read = delegate.read(b, off, len);
    int reportBytesRead = read;
    if (read > 0) {
      int extraBytes = (int) (read - bytesRemaining);
      if (extraBytes > 0) {
        reportBytesRead -= extraBytes;
        delegate.push(b, (int) bytesRemaining, extraBytes);
      }

      bytesRemaining -= reportBytesRead;
    }

    return reportBytesRead;
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
