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
import io.fusionauth.http.io.PushbackInputStream;
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
  private final int chunkedBufferSize;

  private final Instrumenter instrumenter;

  private final Logger logger;

  private final int maximumBytesToDrain;

  private final HTTPRequest request;

  private long bytesRemaining;

  private boolean closed;

  private boolean committed;

  private InputStream delegate;

  private boolean drained;

  public HTTPInputStream(HTTPServerConfiguration configuration, HTTPRequest request, InputStream delegate) {
    this.logger = configuration.getLoggerFactory().getLogger(HTTPInputStream.class);
    this.instrumenter = configuration.getInstrumenter();
    this.request = request;
    this.delegate = delegate;
    this.chunkedBufferSize = configuration.getChunkedBufferSize();
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
    // TODO : Daniel : Review : Does this work for chunked requests? bytesRemaining will be 0 if this is not a fixed length request.
    // Signal end of the stream
    if (bytesRemaining <= 0) {
      return -1;
    }

    if (!committed) {
      commit();
    }

    int b = delegate.read();
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
    // If this is a fixed length request, and we have less than or equal to 0 bytes remaining, return -1
    if (!request.isChunked() && bytesRemaining <= 0) {
      return -1;
    }

    if (!committed) {
      commit();
    }

    // When we have a fixed length request, read beyond the remainingBytes if possible.
    // - Under heavy load we may be able to start reading the next request. Just push those bytes
    //   back onto the InputStream and we will read them later.
    // TODO : Daniel : Use a push back, and read the full 2k for performance, but push back (length - bodyBytesRemaining).
    // TODO : Daniel : If I push back here, that means I need to keep the reference to the PushBackInputStream between requests correct?
    //                 I wonder if there is any risk in having pre-read the entire pre-amble, and then the preamble code trying to
    //                 push back extra bytes.
    //                 Or we could just use push back for the preamble stuff, and then just always try to not over-read to avoid
    //                 trying to manage these buffers across requests.
    int read = delegate.read(buffer, offset, length);
    if (!request.isChunked()) {
      int extraBytes = (int) (read - bytesRemaining);
      if (extraBytes > 0) {
        // TODO : Daniel : I could set the ref type to PushBackInputStream so I don't have to cast.
        ((PushbackInputStream) delegate).push(buffer, (int) bytesRemaining, extraBytes);
      }
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
      delegate = new ChunkedInputStream(delegate, chunkedBufferSize);

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
