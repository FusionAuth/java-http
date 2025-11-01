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

import io.fusionauth.http.ContentTooLargeException;
import io.fusionauth.http.HTTPValues.ContentEncodings;
import io.fusionauth.http.io.ChunkedInputStream;
import io.fusionauth.http.io.FixedLengthInputStream;
import io.fusionauth.http.io.PushbackInputStream;
import io.fusionauth.http.log.Logger;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPServerConfiguration;
import io.fusionauth.http.server.Instrumenter;

/**
 * An InputStream intended to be read the HTTP request body.
 * <p>
 * This will handle fixed length requests, chunked requests as well as decompression if necessary.
 *
 * @author Brian Pontarelli
 */
public class HTTPInputStream extends InputStream {
  private final byte[] b1 = new byte[1];

  private final int chunkedBufferSize;

  private final Instrumenter instrumenter;

  private final Logger logger;

  private final int maximumBytesToDrain;

  private final int maximumContentLength;

  private final PushbackInputStream pushbackInputStream;

  private final HTTPRequest request;

  private int bytesRead;

  private boolean closed;

  private InputStream delegate;

  private boolean drained;

  private boolean initialized;

  public HTTPInputStream(HTTPServerConfiguration configuration, HTTPRequest request, PushbackInputStream pushbackInputStream,
                         int maximumContentLength) {
    this.logger = configuration.getLoggerFactory().getLogger(HTTPInputStream.class);
    this.instrumenter = configuration.getInstrumenter();
    this.request = request;
    this.delegate = pushbackInputStream;
    this.pushbackInputStream = pushbackInputStream;
    this.chunkedBufferSize = configuration.getChunkedBufferSize();
    this.maximumBytesToDrain = configuration.getMaxBytesToDrain();
    this.maximumContentLength = maximumContentLength;
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

    if (!initialized) {
      initialize();
    }

    // When a maximum content length has been specified, read at most one byte past the maximum.
    int maxReadLen = maximumContentLength == -1 ? len : Math.min(len, maximumContentLength - bytesRead + 1);
    int read = delegate.read(b, off, maxReadLen);
    if (read > 0) {
      bytesRead += read;
    }

    // Throw an exception once we have read past the maximum configured content length,
    if (maximumContentLength != -1 && bytesRead > maximumContentLength) {
      String detailedMessage = "The maximum request size has been exceeded. The maximum request size is [" + maximumContentLength + "] bytes.";
      throw new ContentTooLargeException(maximumContentLength, detailedMessage);
    }

    return read;
  }

  private void initialize() throws IOException {
    initialized = true;

    // hasBody means we are either using chunked transfer encoding or we have a non-zero Content-Length.
    boolean hasBody = request.hasBody();
    if (hasBody) {
      Long contentLength = request.getContentLength();
      // Transfer-Encoding always takes precedence over Content-Length. In practice if they were to both be present on
      // the request we would have removed Content-Length during validation to remove ambiguity. See HTTPWorker.validatePreamble.
      if (request.isChunked()) {
        logger.trace("Client indicated it was sending an entity-body in the request. Handling body using chunked encoding.");
        delegate = new ChunkedInputStream(pushbackInputStream, chunkedBufferSize);
        if (instrumenter != null) {
          instrumenter.chunkedRequest();
        }
      } else {
        logger.trace("Client indicated it was sending an entity-body in the request. Handling body using Content-Length header {}.", contentLength);
        delegate = new FixedLengthInputStream(pushbackInputStream, contentLength);
      }

      // Now that we have the InputStream set up to read the body, handle decompression.
      // The request may contain more than one value, apply in reverse order.
      // - These are both using the default 512 buffer size.
      for (String contentEncoding : request.getContentEncodings().reversed()) {
        if (contentEncoding.equalsIgnoreCase(ContentEncodings.Deflate)) {
          delegate = new InflaterInputStream(delegate);
        } else if (contentEncoding.equalsIgnoreCase(ContentEncodings.Gzip)) {
          delegate = new GZIPInputStream(delegate);
        }
      }

      // If we have a fixed length request that is reporting a contentLength larger than the configured maximum, fail early.
      // - Do this last so if anyone downstream wants to read from the InputStream it would work.
      // - Note that it is possible that the body is compressed which would mean the contentLength represents the compressed value.
      //   But when we decompress the bytes the result will be larger than the reported contentLength, so we can safely throw this exception.
      if (contentLength != null && maximumContentLength != -1 && contentLength > maximumContentLength) {
        String detailedMessage = "The maximum request size has been exceeded. The reported Content-Length is [" + contentLength + "] and the maximum request size is [" + maximumContentLength + "] bytes.";
        throw new ContentTooLargeException(maximumContentLength, detailedMessage);
      }
    } else {
      // This means that we did not find Content-Length or Transfer-Encoding on the request. Do not attempt to read from the InputStream.
      // - Note that the spec indicates it is plausible for a client to send an entity body and omit these two headers and the server can optionally
      //   read bytes until the end of the InputStream is reached. This would assume Connection: close was also sent because if we do not know
      //   how to delimit the request we cannot use a persistent connection.
      // - We aren't doing any of that - if the client wants to send bytes, it needs to send a Content-Length header, or specify Transfer-Encoding: chunked.
      logger.trace("Client indicated it was NOT sending an entity-body in the request");
      delegate = InputStream.nullInputStream();
    }
  }
}
