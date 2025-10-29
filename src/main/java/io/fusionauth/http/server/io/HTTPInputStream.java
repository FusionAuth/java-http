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
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import io.fusionauth.http.HTTPValues.ContentEncodings;
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
  private final byte[] b1 = new byte[1];

  private final int chunkedBufferSize;

  private final Instrumenter instrumenter;

  private final Logger logger;

  private final int maximumBytesToDrain;

  private final PushbackInputStream pushbackInputStream;

  private final HTTPRequest request;

  private long bytesRemaining;

  private boolean closed;

  private boolean initialized;

  private InputStream delegate;

  private boolean drained;

  public HTTPInputStream(HTTPServerConfiguration configuration, HTTPRequest request, PushbackInputStream pushbackInputStream) {
    this.logger = configuration.getLoggerFactory().getLogger(HTTPInputStream.class);
    this.instrumenter = configuration.getInstrumenter();
    this.request = request;
    this.delegate = pushbackInputStream;
    this.pushbackInputStream = pushbackInputStream;
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
    var read = read(b1);
    if (read <= 0) {
      return read;
    }

    return b1[0] & 0xFF;
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    if (len == 0) {
      return 0;
    }

    // If this is a fixed length request, and we have less than or equal to 0 bytes remaining, return -1
    boolean fixedLength = !request.isChunked();
    if (fixedLength && bytesRemaining <= 0) {
      return -1;
    }

    if (!initialized) {
      initialize();
    }

    // When we have a fixed length request, read beyond the remainingBytes if possible.
    // - If we have read past the end of the current request, push those bytes back onto the InputStream.
    int read = delegate.read(b, off, len);
    if (fixedLength && read > 0) {
      int extraBytes = (int) (read - bytesRemaining);
      if (extraBytes > 0) {
        pushbackInputStream.push(b, (int) bytesRemaining, extraBytes);
      }
    }

    if (read > 0) {
      if (fixedLength) {
        bytesRemaining -= read;
      }
    }

    return read;
  }

  private void initialize() throws IOException {
    initialized = true;

    Long contentLength = request.getContentLength();
    boolean hasBody = (contentLength != null && contentLength > 0) || request.isChunked();

    // Request order of operations:
    //  - "hello world" -> compress -> chunked.

    // Current:
    //   Chunked:
    //     HTTPInputStream > Chunked > Pushback > Throughput > Socket
    //   Fixed:
    //     HTTPInputStream > Pushback > Throughput > Socket

    // New:
    //   Chunked
    //     HTTPInputStream > Chunked > Pushback > Decompress > Throughput > Socket
    //   Fixed
    //     HTTPInputStream > Pushback > Decompress > Throughput > Socket

    // Note that isChunked() should take precedence over the fact that we have a Content-Length.
    // - The client should not send both, but in the case they are both present we ignore Content-Length
    if (!hasBody) {
      delegate = InputStream.nullInputStream();
    } else if (request.isChunked()) {
      logger.trace("Client indicated it was sending an entity-body in the request. Handling body using chunked encoding.");
      delegate = new ChunkedInputStream(pushbackInputStream, chunkedBufferSize);
      if (instrumenter != null) {
        instrumenter.chunkedRequest();
      }
    } else if (contentLength != null) {
      logger.trace("Client indicated it was sending an entity-body in the request. Handling body using Content-Length header {}.", contentLength);
    } else {
      logger.trace("Client indicated it was NOT sending an entity-body in the request");
    }

    // Those who push back:
    // HTTPInputStream when fixed
    // ChunkedInputStream when chunked
    // Preamble parser

    // HTTPInputStream (this) > Pushback (delegate) > Throughput > Socket
    // HTTPInputStream (this) > Chunked (delegate) > Pushback > Throughput > Socket

    // HTTPInputStream (this) > Pushback > Decompress > Throughput > Socket
    // HTTPInputStream (this) > Pushback > Decompress > Chunked > Throughput > Socket

    // TODO : Note I could leave this alone, but when we parse the header we can lower case these values and then remove the equalsIgnoreCase here?
    //        Seems like ideally we would normalize them to lowercase earlier.
    if (hasBody) {
      // The request may contain more than one value, apply in reverse order.
      // - These are both using the default 512 buffer size.
      for (String contentEncoding : request.getContentEncodings().reversed()) {
        if (contentEncoding.equalsIgnoreCase(ContentEncodings.Deflate)) {
          delegate = new InflaterInputStream(delegate);
        } else if (contentEncoding.equalsIgnoreCase(ContentEncodings.Gzip)) {
          delegate = new GZIPInputStream(delegate);
        }
      }
    }
  }
}
