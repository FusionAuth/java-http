/*
 * Copyright (c) 2024, FusionAuth, All Rights Reserved
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

public class HTTPInputStream extends InputStream {
  private final Instrumenter instrumenter;

  private final Logger logger;

  private final HTTPRequest request;

  private byte[] bodyBytes;

  private int bodyBytesIndex;

  private boolean committed;

  private InputStream delegate;

  private long bytesRemaining;

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
  }

  @Override
  public int read() throws IOException {
    // Signal end of the stream
    if (bytesRemaining <= 0) {
      return -1;
    }

    if (!committed) {
      commit();
      committed = true;
    }

    int b;
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
    // Signal end of the stream
    if (bytesRemaining <= 0) {
      return -1;
    }

    if (!committed) {
      commit();
      committed = true;
    }

    int read;
    if (bodyBytes != null) {
      read = Math.min(bodyBytes.length, buffer.length);
      System.arraycopy(bodyBytes, bodyBytesIndex, buffer, 0, read);
      bodyBytesIndex += read;

      if (bodyBytesIndex >= bodyBytes.length) {
        bodyBytes = null;
      }
    } else {
      read = delegate.read(buffer);
    }

    if (instrumenter != null) {
      instrumenter.readFromClient(read);
    }

    bytesRemaining -= read;
    return read;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    // Signal end of the stream
    if (bytesRemaining <= 0) {
      return -1;
    }

    if (!committed) {
      commit();
      committed = true;
    }

    int read;
    if (bodyBytes != null) {
      read = Math.min(bodyBytes.length, length);
      System.arraycopy(bodyBytes, bodyBytesIndex, buffer, offset, read);
      bodyBytesIndex += read;

      if (bodyBytesIndex >= bodyBytes.length) {
        bodyBytes = null;
      }
    } else {
      read = delegate.read(buffer);
    }

    if (instrumenter != null) {
      instrumenter.readFromClient(read);
    }

    bytesRemaining -= read;
    return read;
  }

  private void commit() {
    Long contentLength = request.getContentLength();
    boolean hasBody = (contentLength != null && contentLength > 0) || request.isChunked();
    if (!hasBody) {
      delegate = InputStream.nullInputStream();
    } else if (contentLength != null) {
      logger.debug("Client indicated it was sending an entity-body in the request. Handling body using Content-Length header {}.", contentLength);
    } else if (request.isChunked()) {
      logger.debug("Client indicated it was sending an entity-body in the request. Handling body using chunked encoding.");
      delegate = new ChunkedInputStream(delegate, 1024);

      if (instrumenter != null) {
        instrumenter.chunkedRequest();
      }
    } else {
      logger.debug("Client indicated it was NOT sending an entity-body in the request");
    }
  }
}
