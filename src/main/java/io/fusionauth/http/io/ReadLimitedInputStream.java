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
import java.util.Objects;
import java.util.function.Function;

import io.fusionauth.http.HTTPProcessingException;

/**
 * An input stream that limits the number of bytes that can be read.
 *
 * @author Daniel DeGroff
 */
public class ReadLimitedInputStream extends InputStream {
  private final byte[] b1 = new byte[1];

  private final InputStream delegate;

  private int bytesRead;

  private Function<Integer, HTTPProcessingException> exceptionFunction;

  private int maximumBytesToRead = -1;

  public ReadLimitedInputStream(InputStream inputStream) {
    this.delegate = inputStream;
  }

  @Override
  public int read(byte[] b) throws IOException {
    return delegate.read(b);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    // If disabled, just use the delegate
    if (maximumBytesToRead == -1) {
      return delegate.read(b, off, len);
    }

    // Add one to the right hand argument to ensure that if that is the smaller of the two values it will still allow us to read at least
    // one byte past the maximumBytesToRead so we can know when we have exceeded limit.
    int maxLen = Math.min(len, maximumBytesToRead - bytesRead + 1);
    int read = delegate.read(b, off, maxLen);

    bytesRead += read;

    if (bytesRead > maximumBytesToRead) {
      throw exceptionFunction.apply(maximumBytesToRead);
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

  public void setMaximumBytesToRead(Long expectedLength, int maximumBytesToRead,
                                    Function<Integer, HTTPProcessingException> exceptionFunction) {
    Objects.requireNonNull(exceptionFunction);
    this.bytesRead = 0;
    this.maximumBytesToRead = maximumBytesToRead;
    this.exceptionFunction = exceptionFunction;

    if (expectedLength != null && expectedLength > maximumBytesToRead) {
      throw exceptionFunction.apply(maximumBytesToRead);
    }
  }
}
