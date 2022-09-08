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
package io.fusionauth.http.io;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * This InputStream uses ByteBuffers read by the Server processor and piped into this class. Those ByteBuffers are then fed to the reader of
 * the InputStream. The pushing of ByteBuffers never blocks but the reading will block if there are no more bytes to read.
 * <p>
 * In order to handle chunking and other considerations, this class can be sub-classed to preprocess bytes.
 *
 * @author Brian Pontarelli
 */
public class ReaderBlockingByteBufferInputStream extends InputStream {
  private static final ByteBuffer Last = ByteBuffer.allocate(0);

  // Shared between writer and reader threads and the reader blocks
  private final BlockingQueue<ByteBuffer> buffers = new LinkedBlockingQueue<>();

  // Only used by the reader thread
  private ByteBuffer currentBuffer;

  public void addByteBuffer(ByteBuffer buffer) {
    if (!buffers.offer(buffer)) {
      throw new IllegalStateException("The LinkedBlockingQueue is borked. It should never reject an offer() operation.");
    }
  }

  @Override
  public int read() {
    poll();

    if (currentBuffer == Last) {
      return -1;
    }

    return currentBuffer.get();
  }

  @Override
  public int read(byte[] b, int off, int len) {
    poll();

    if (currentBuffer == Last) {
      return -1;
    }

    int length = Math.min(len, currentBuffer.remaining());
    currentBuffer.get(b, off, length);
    return length;
  }

  public void signalDone() {
    addByteBuffer(Last);
  }

  private void poll() {
    while (currentBuffer != Last && (currentBuffer == null || !currentBuffer.hasRemaining())) {
      try {
        System.out.println("Taking non-last");
        currentBuffer = buffers.take();
      } catch (InterruptedException e) {
        // Ignore and try again
      }
    }
  }
}
