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

  private final HTTPRequest request;

  private byte[] bodyBytes;

  private int bodyBytesIndex;

  private long bytesRemaining;

  private boolean committed;

  private InputStream delegate;

  public HTTPInputStream(HTTPServerConfiguration configuration, HTTPRequest request, InputStream delegate, byte[] bodyBytes) {
    this.logger = configuration.getLoggerFactory().getLogger(HTTPInputStream.class);
    this.instrumenter = configuration.getInstrumenter();
    this.request = request;
    this.delegate = delegate;
    this.bodyBytes = bodyBytes;

    // Start the countdown
    if (request.getContentLength() != null) {
      this.bytesRemaining = request.getContentLength();
    }
  }

  @Override
  public void close() throws IOException {
    // Ignore because we don't know if we should close the socket's InputStream

    // TODO : Daniel : Review : Should this be revisited? This could be a good location to call drain?
    //        See LeftOverInputStream.drain()
    //        See FixedLengthInputStream
    //        In some similar code, it seems that close calls drain - so in theory by closing the InputStream you can then
    //        safely write to the output stream. Maybe this is the same difference that we are doing by directly calling drain().
    //        Seems like there should be a better way, but maybe not - maybe it is only solved in HTTP2.
  }

  public long purge() throws IOException {
    // Would this work?
    // - Note: Please don't call delegate.skipNBytes(Long.MAX_VALUE)
    // TODO : Daniel : Review : Try the above and see what happens - we may swap out the pointer on delegate during commit()
    try {
      System.out.println("   > skip [" + Long.MAX_VALUE + "] bytes...");
      var result = skip(Long.MAX_VALUE);
      System.out.println("   > purged [" + result + "] bytes");
      return result;
    } finally {
      // Note that skip calls read, read honors bytesRemaining. Set this to 0 last.
      bytesRemaining = 0;
    }
  }

  @Override
  public int read() throws IOException {
    // Signal end of the stream
    // TODO : Daniel : Review : What about chunked encoding, we will not have a Content-Length.
    //        Should this also check if chunked, or does chunked never call this method?
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
    // TODO : Daniel : Review : When we drain to be able to write a response --- how does Transfer-Encoding work into this?
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
      read = Math.min(bodyBytes.length - bodyBytesIndex, length);
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

    Long contentLength = request.getContentLength();
    boolean hasBody = (contentLength != null && contentLength > 0) || request.isChunked();
    if (!hasBody) {
      delegate = InputStream.nullInputStream();
    } else if (contentLength != null) {
      logger.trace("Client indicated it was sending an entity-body in the request. Handling body using Content-Length header {}.", contentLength);
    } else if (request.isChunked()) {
      logger.trace("Client indicated it was sending an entity-body in the request. Handling body using chunked encoding.");
      delegate = new ChunkedInputStream(delegate, 1024, bodyBytes);
      bodyBytes = null;

      if (instrumenter != null) {
        instrumenter.chunkedRequest();
      }
    } else {
      logger.trace("Client indicated it was NOT sending an entity-body in the request");
    }
  }
}
