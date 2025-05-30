/*
 * Copyright (c) 2024-2025, FusionAuth, All Rights Reserved
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
package io.fusionauth.http.server.io;

import java.io.IOException;
import java.io.InputStream;

import io.fusionauth.http.TooManyBytesToDrainException;
import io.fusionauth.http.io.ChunkedInputStream;
import io.fusionauth.http.log.Logger;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPServerConfiguration;
import io.fusionauth.http.server.Instrumenter;

/**
 * An InputStream that handles the HTTP body, including body bytes that were read while the preamble was processed. This class also handles
 * chunked bodies by using a delegate InputStream that wraps the original source of the body bytes. The {@link ChunkedInputStream} is the
 * delegate that this class leverages for chunking.
 *
 * @author Brian Pontarelli
 */
public class HTTPInputStream extends InputStream {
  private final Instrumenter instrumenter;

  private final Logger logger;

  private final int maximumBytesToDrain;

  private final HTTPRequest request;

  private byte[] bodyBytes;

  private int bodyBytesIndex;

  private long bytesRemaining;

  private boolean closed;

  private boolean committed;

  private InputStream delegate;

  private boolean drained;

  public HTTPInputStream(HTTPServerConfiguration configuration, HTTPRequest request, InputStream delegate, byte[] bodyBytes) {
    this.logger = configuration.getLoggerFactory().getLogger(HTTPInputStream.class);
    this.instrumenter = configuration.getInstrumenter();
    this.request = request;
    this.delegate = delegate;
    this.bodyBytes = bodyBytes;
    this.maximumBytesToDrain = configuration.getMaxBytesToDrain();

    // Start the countdown
    if (request.getContentLength() != null) {
      this.bytesRemaining = request.getContentLength();
    }
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }

    closed = true;
    if (drained) {
      return;
    }

    drain();
  }

  public int drain() throws IOException {
    if (drained) {
      return 0;
    }

    drained = true;

    int total = 0;
    byte[] skipBuffer = new byte[2048];
    while (true) {
      // Worst case the length of the skipBuffer greater than maximumBytesToDrain.
      int skipped = read(skipBuffer);
      if (skipped < 0) {
        break;
      }

      total += skipped;

      if (total > maximumBytesToDrain) {
        throw new TooManyBytesToDrainException(total, maximumBytesToDrain);
      }
    }

    return total;
  }

  @Override
  public int read() throws IOException {
    // Signal end of the stream
    if (bytesRemaining <= 0) {
      return -1;
    }

    if (!committed) {
      commit();
    }

    int b;
    // If bodyBytes exist, they are left over bytes from parsing the preamble.
    // - Process these bytes first before reading from the delegate InputStream.
    if (bodyBytes != null) {
      b = bodyBytes[bodyBytesIndex++];

      if (bodyBytesIndex >= bodyBytes.length) {
        bodyBytes = null;
      }
    } else {
      b = delegate.read();
    }

    if (instrumenter != null) {
      instrumenter.readFromClient(1);
    }

    bytesRemaining--;
    return b;
  }

  @Override
  public int read(byte[] buffer) throws IOException {
    return read(buffer, 0, buffer.length);
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    // Signal end of the stream if there is no more to read and the request isn't chunked
    if (bytesRemaining <= 0 && !request.isChunked()) {
      return -1;
    }

    if (!committed) {
      commit();
    }

    int read;
    // If bodyBytes exist, they are left over bytes from parsing the preamble.
    // - Process these bytes first before reading from the delegate InputStream.
    if (bodyBytes != null) {
      int remaining = bodyBytes.length - bodyBytesIndex;
      read = Math.min(remaining, length);
      System.arraycopy(bodyBytes, bodyBytesIndex, buffer, offset, read);
      bodyBytesIndex += read;

      if (bodyBytesIndex >= bodyBytes.length) {
        bodyBytes = null;
      }
    } else {
      read = delegate.read(buffer, offset, length);
    }

    if (instrumenter != null) {
      instrumenter.readFromClient(read);
    }

    bytesRemaining -= read;
    return read;
  }

  private void commit() {
    committed = true;

    // Note that isChunked() should take precedence over the fact that we have a Content-Length.
    // - The client should not send both, but in the case they are both present we ignore Content-Length
    Long contentLength = request.getContentLength();
    boolean hasBody = (contentLength != null && contentLength > 0) || request.isChunked();
    if (!hasBody) {
      delegate = InputStream.nullInputStream();
    } else if (request.isChunked()) {
      logger.trace("Client indicated it was sending an entity-body in the request. Handling body using chunked encoding.");
      delegate = new ChunkedInputStream(delegate, 1024, bodyBytes);
      bodyBytes = null;

      if (instrumenter != null) {
        instrumenter.chunkedRequest();
      }
    } else if (contentLength != null) {
      logger.trace("Client indicated it was sending an entity-body in the request. Handling body using Content-Length header {}.", contentLength);
    } else {
      logger.trace("Client indicated it was NOT sending an entity-body in the request");
    }
  }
}
