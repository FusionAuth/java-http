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

import io.fusionauth.http.ContentTooLargeException;
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

  private final int maximumContentLength;

  private final PushbackInputStream pushbackInputStream;

  private final HTTPRequest request;

  private int bytesRead;

  private long bytesRemaining;

  private boolean closed;

  private boolean committed;

  private InputStream delegate;

  private boolean drained;

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

    if (!committed) {
      commit();
    }

    // When we have a fixed length request, read beyond the remainingBytes if possible.
    // - If we have read past the end of the current request, push those bytes back onto the InputStream.
    int maxLen = maximumContentLength == -1 ? len : Math.min(len, maximumContentLength - bytesRead + 1);
    int read = delegate.read(b, off, maxLen);

    int reportBytesRead = read;
    if (fixedLength && read > 0) {
      int extraBytes = (int) (read - bytesRemaining);
      if (extraBytes > 0) {
        reportBytesRead -= extraBytes;
        pushbackInputStream.push(b, (int) bytesRemaining, extraBytes);
      }
    }

    if (read > 0) {
      if (fixedLength) {
        bytesRemaining -= read;
      }
    }

    // TODO : Daniel : Review : If we push back n bytes, don't we need to return read - n? This was previously read which ignored bytes pushed back.
    // TODO : Daniel : Write a test to prove this, send a content-length of 100, buffer size 80 (as an example), ensure this returns 80, and then
    //                 the next call returns 20?


    bytesRead += reportBytesRead;

    // This won't cause us to fail as fast as we could, but it keeps the code a bit simpler.
    // - This means we will have read past the maximum by n where n is > 0 && < len. This seems like an acceptable over-read, in practice the buffers will be
    if (maximumContentLength != -1) {
      if (bytesRead > maximumContentLength) {
        String detailedMessage = "The maximum request size has been exceeded.The maximum request size is [" + maximumContentLength + "] bytes.";
        throw new ContentTooLargeException(maximumContentLength, detailedMessage);
      }
    }

    return reportBytesRead;
  }

  private void commit() {
    committed = true;

    // TODO : Handle : Content-Encoding

    // Note that isChunked() should take precedence over the fact that we have a Content-Length.
    // - The client should not send both, but in the case they are both present we ignore Content-Length
    //   In practice, we will remove the Content-Length header when sent in addition to Transfer-Encoding. See HTTPWorker.validatePreamble.
    Long contentLength = request.getContentLength();
    boolean hasBody = (contentLength != null && contentLength > 0) || request.isChunked();
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

    // If we have a maximumContentLength, and this is a fixed content length request, before we read any bytes, fail early.
    // For good measure do this last so if anyone downstream wants to read from the InputStream they could in theory because
    // we will have set up the InputStream.
    if (contentLength != null && maximumContentLength != -1) {
      if (contentLength > maximumContentLength) {
        String detailedMessage = "The maximum request size has been exceeded. The reported Content-Length is [" + contentLength + "] and the maximum request size is [" + maximumContentLength + "] bytes.";
        throw new ContentTooLargeException(maximumContentLength, detailedMessage);
      }
    }
  }
}
