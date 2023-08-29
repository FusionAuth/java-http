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
package io.fusionauth.http.body.response;

import java.nio.ByteBuffer;

import io.fusionauth.http.io.BlockingByteBufferOutputStream;

/**
 * A body processor that uses the Content-Length header to determine when the entire body has been read.
 *
 * @author Brian Pontarelli
 */
public class ContentLengthBodyProcessor implements BodyProcessor {
  private final ByteBuffer[] currentBuffers = new ByteBuffer[1];

  private final BlockingByteBufferOutputStream outputStream;

  public ContentLengthBodyProcessor(BlockingByteBufferOutputStream outputStream) {
    this.outputStream = outputStream;
  }

  @Override
  public ByteBuffer[] currentBuffers() {
    if (currentBuffers[0] != null && currentBuffers[0].hasRemaining()) {
      return currentBuffers;
    }

    currentBuffers[0] = outputStream.readableBuffer();
    return currentBuffers[0] != null ? currentBuffers : null;
  }

  @Override
  public boolean isComplete() {
    return currentBuffers[0] == null && outputStream.isClosed();
  }
}
