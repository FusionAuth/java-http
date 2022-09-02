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
package io.fusionauth.http.util;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Implements an OutputStream that stores all the bytes in a growing buffer and then can return a ByteBuffer that wraps the buffer. This is
 * more efficient than using the toByteArray method of ByteArrayOutputStream, which makes a copy.
 *
 * @author Brian Pontarelli
 */
public class ByteBufferOutputStream extends OutputStream {
  private final int initialSize;

  private final int maxCapacity;

  private byte[] buf;

  private int index;

  public ByteBufferOutputStream() {
    this(64, 1024 * 1024);
  }

  public ByteBufferOutputStream(int size, int maxCapacity) {
    this.initialSize = size;
    this.buf = new byte[size];
    this.maxCapacity = maxCapacity;
  }

  @Override
  public void flush() {
  }

  public ByteBuffer toByteBuffer() {
    return ByteBuffer.wrap(buf, 0, index);
  }

  @Override
  public void write(byte[] b) {
    write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) {
    ensureSize(index + len);
    System.arraycopy(b, 0, buf, index, len);
  }

  @Override
  public void write(int b) {
    ensureSize(index + 1);
    buf[index++] = (byte) b;
  }

  private void ensureSize(int minCapacity) {
    int oldCapacity = buf.length;
    int minGrowth = minCapacity - oldCapacity;
    if (minGrowth > 0) {
      if (minCapacity > maxCapacity) {
        throw new IllegalArgumentException("Unable to increase the buffer for ByteBufferOutputStream beyond max of [" + maxCapacity + "]");
      }

      buf = Arrays.copyOf(buf, Math.max(minGrowth, initialSize));
    }
  }
}
