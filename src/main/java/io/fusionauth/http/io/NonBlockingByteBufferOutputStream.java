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

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import io.fusionauth.http.server.Notifier;

/**
 * This InputStream uses ByteBuffers read by the Server processor and piped into this class. Those ByteBuffers are then fed to the reader of
 * the InputStream. The pushing of ByteBuffers never blocks but the reading will block if there are no more bytes to read.
 * <p>
 * In order to handle chunking and other considerations, this class can be sub-classed to preprocess bytes.
 *
 * @author Brian Pontarelli
 */
public class NonBlockingByteBufferOutputStream extends OutputStream {
  // Shared between writer and reader threads and the writer blocks
  private final BlockingQueue<ByteBuffer> buffers = new LinkedBlockingQueue<>();

  private final Notifier notifier;

  private volatile boolean closed;

  // Only used by the writer thread
  private ByteBuffer currentBuffer;

  public NonBlockingByteBufferOutputStream(Notifier notifier) {
    this.notifier = notifier;
  }

  public void clear() {
    currentBuffer = null;
    buffers.clear();
  }

  @Override
  public void close() {
    if (currentBuffer != null) {
      addBuffer();
      currentBuffer = null;
    } else {
      // Notify whoever is listening for bytes to be written
      notifier.notifyNow();
    }

    closed = true;
  }

  public boolean isClosed() {
    return closed;
  }

  public ByteBuffer writableBuffer() {
    while (buffers.peek() != null) {
      ByteBuffer head = buffers.peek();
      if (head.hasRemaining()) {
        return head;
      }

      // Throw out the head node
      buffers.poll();
    }

    return null;
  }

  @Override
  public void write(byte[] b, int off, int len) {
    if (closed) {
      throw new IllegalStateException("Steam is closed");
    }

    // Set up the buffer to handle the bytes
    setupBuffer(Math.max(1024, len));

    int length = Math.min(currentBuffer.remaining(), len);
    currentBuffer.put(b, off, length);

    if (length < len) {
      addBuffer();

      int newCapacity = Math.max(1024, len - length);
      currentBuffer = ByteBuffer.allocate(newCapacity);
      currentBuffer.put(b, off + length, len - length);

      if (!currentBuffer.hasRemaining()) {
        addBuffer();

        currentBuffer = null;
      }
    }
  }

  @Override
  public void write(int b) {
    if (closed) {
      throw new IllegalStateException("Steam is closed");
    }

    setupBuffer(1024);

    currentBuffer.put((byte) b);
  }

  private void addBuffer() {
    currentBuffer.flip();
    if (!buffers.offer(currentBuffer)) {
      throw new IllegalStateException("The LinkedBlockingQueue is borked. It should never reject an offer() operation.");
    }

    // Notify whoever is listening for bytes to be written
    notifier.notifyNow();
  }

  private void setupBuffer(int length) {
    if (currentBuffer == null) {
      currentBuffer = ByteBuffer.allocate(length);
    } else if (!currentBuffer.hasRemaining()) {
      addBuffer();
      currentBuffer = ByteBuffer.allocate(length);
    }
  }
}
