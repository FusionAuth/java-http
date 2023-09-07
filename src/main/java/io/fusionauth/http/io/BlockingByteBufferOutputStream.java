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

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import io.fusionauth.http.ConnectionClosedException;
import io.fusionauth.http.server.Notifier;

/**
 * This InputStream uses ByteBuffers read by the Server processor and piped into this class. Those ByteBuffers are then fed to the reader of
 * the InputStream. The pushing of ByteBuffers blocks based on the configuration and the reading will block if there are no more bytes to
 * read.
 * <p>
 * In order to handle chunking and other considerations, this class can be sub-classed to preprocess bytes.
 *
 * @author Brian Pontarelli
 */
public class BlockingByteBufferOutputStream extends OutputStream {
  private final int bufferSize;

  // Shared between writer and reader threads.
  private final BlockingQueue<ByteBuffer> buffers;

  private final Notifier notifier;

  private volatile boolean closed;

  // Only used by the writer thread
  private ByteBuffer currentBuffer;

  private volatile boolean used;

  public BlockingByteBufferOutputStream(Notifier notifier, int bufferSize, int maximumQueueSize) {
    this.buffers = new LinkedBlockingQueue<>(maximumQueueSize);
    this.bufferSize = bufferSize;
    this.notifier = notifier;
  }

  public void clear() {
    currentBuffer = null;
    buffers.clear();
  }

  /**
   * Flushes and then marks the stream closed. The flush must occur first so that the readers have access to the buffers before they are
   * aware of the stream's closure.
   */
  @Override
  public void close() {
    // DO NOT CHANGE THIS ORDER!
    if (currentBuffer != null) {
      addBuffer(false);
    }

    closed = true;

    // Notify whoever is listening for bytes to be written even though there aren't any ready
    notifier.notifyNow();
  }

  /**
   * Flushes the current stream contents if the current ByteBuffer has less than 10% remaining space. It flushes by putting the current
   * ByteBuffer into the Queue that the reader thread is reading from. Then it sets the current ByteBuffer to null so that a new one is
   * created. And finally, this notifies the selector to wake up.
   */
  public void flush() {
    if (currentBuffer != null && currentBuffer.remaining() < (currentBuffer.capacity() / 10)) {
      addBuffer(true);
    }
  }

  public boolean hasReadableBuffer() {
    return buffers.peek() != null;
  }

  public boolean isClosed() {
    return buffers.isEmpty() && closed;
  }

  public boolean isEmpty() {
    return !used;
  }

  /**
   * Used by the reader side (the selector/processor) so that bytes can be read from the worker thread and written back to the client. This
   * method should only be called once per buffer. It pops the buffer off the queue.
   *
   * @return A ByteBuffer that is used to read bytes that will be written back to the client or null if there aren't any buffers ready yet.
   */
  public ByteBuffer readableBuffer() {
    return buffers.poll();
  }

  @Override
  public void write(int b) {
    if (closed) {
      throw new IllegalStateException("Steam is closed");
    }

    // Mark this stream used so that the processor knows if it should write back a content-length header of zero or not
    used = true;

    setupBuffer(bufferSize);

    currentBuffer.put((byte) b);
  }

  @Override
  public void write(byte[] b, int off, int len) {
    if (closed) {
      throw new IllegalStateException("Steam is closed");
    }

    // Mark this stream used so that the processor knows if it should write back a content-length header of zero or not
    used = true;

    // Set up the buffer to handle the bytes
    setupBuffer(bufferSize);

    int length = Math.min(currentBuffer.remaining(), len);
    currentBuffer.put(b, off, length);

    if (length < len) {
      addBuffer(true);

      int newCapacity = Math.max(bufferSize, len - length);
      currentBuffer = ByteBuffer.allocate(newCapacity);
      currentBuffer.put(b, off + length, len - length);

      if (!currentBuffer.hasRemaining()) {
        addBuffer(true);
      }
    }
  }

  private void addBuffer(boolean notify) {
    currentBuffer.flip();

    try {
      // This will block until we have capacity. We looked, and it seems as though there aren't any ways that a Worker thread can be in a
      // state where it was interrupted by the HTTPServerThread and this line of code doesn't throw an InterruptedException.
      buffers.put(currentBuffer);
    } catch (InterruptedException e) {
      currentBuffer = null;
      buffers.clear();
      throw new ConnectionClosedException(e);
    }

    currentBuffer = null;

    // Optionally notify whoever is listening for bytes to be written
    if (notify) {
      notifier.notifyNow();
    }
  }

  private void setupBuffer(int length) {
    if (currentBuffer == null) {
      currentBuffer = ByteBuffer.allocate(length);
    } else if (!currentBuffer.hasRemaining()) {
      addBuffer(true);
      currentBuffer = ByteBuffer.allocate(length);
    }
  }
}
