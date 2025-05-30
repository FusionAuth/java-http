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
package io.fusionauth.http.util;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class BetterByteBuffer {
  private final ByteBuffer buffer;

  private final int length;

  private final int offset;

  public BetterByteBuffer(byte[] buffer, int offset, int length) {
    this.buffer = ByteBuffer.wrap(buffer);
    this.offset = offset;
    this.length = length;
  }

  public byte[] array() {
    return Arrays.copyOfRange(buffer.array(), offset, offset + length);
  }

  public int get(int i) {
    return buffer.get(offset + i);
  }

  public int length() {
    return length;
  }
}
