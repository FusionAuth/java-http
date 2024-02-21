/*
 * Copyright (c) 2024, FusionAuth, All Rights Reserved
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
import java.util.Arrays;

/**
 * A simple fast byte array output stream. This does no checks and has no synchronization. It simply jams data in an array and resizes in
 * increments (set in the constructor).
 *
 * @author Brian Pontarelli
 */
public class FastByteArrayOutputStream extends OutputStream {
  protected byte[] buffer;

  protected int count;

  protected int growthRate;

  /**
   * Creates a new {@code FastByteArrayOutputStream} with the given size.
   *
   * @param size The initial size of the buffer.
   */
  public FastByteArrayOutputStream(int size, int growthRate) {
    this.buffer = new byte[size];
    this.growthRate = growthRate;
  }

  @Override
  public void close() {
  }

  public void reset() {
    count = 0;
  }

  public int size() {
    return count;
  }

  public byte[] bytes() {
    return buffer;
  }

  @Override
  public void write(int b) {
    if (count + 1 >= buffer.length) {
      resize(growthRate);
    }

    buffer[count] = (byte) b;
    count++;
  }

  @Override
  public void write(byte[] source, int offset, int length) {
    if (count + length >= buffer.length) {
      resize(Math.max(growthRate, length));
    }

    System.arraycopy(source, offset, buffer, count, length);
    count += length;
  }

  @Override
  public void write(byte[] source) throws IOException {
    write(source, 0, source.length);
  }

  private void resize(int growth) {
    buffer = Arrays.copyOf(buffer, buffer.length + growth);
  }
}
