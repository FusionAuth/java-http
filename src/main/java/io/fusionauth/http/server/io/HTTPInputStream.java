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

public class HTTPInputStream extends InputStream {
  private final Logger logger;

  private final HTTPRequest request;

  private byte[] bodyBytes;

  private int bodyBytesIndex;

  private long bytesRead;

  private boolean committed;

  private InputStream delegate;

  private long firstByteReadInstant;

  private long lastByteReadInstant;

  public HTTPInputStream(HTTPRequest request, Logger logger, InputStream delegate, byte[] bodyBytes) {
    this.request = request;
    this.logger = logger;
    this.delegate = delegate;
    this.bodyBytes = bodyBytes;
  }

  public long getBytesRead() {
    return bytesRead;
  }

  public long getFirstByteReadInstant() {
    return firstByteReadInstant;
  }

  public long getLastByteReadInstant() {
    return lastByteReadInstant;
  }

  @Override
  public void close() throws IOException {
    // Ignore because we don't know if we should close the socket's InputStream
  }

  @Override
  public int read() throws IOException {
    long now = System.currentTimeMillis();
    if (firstByteReadInstant == 0) {
      firstByteReadInstant = now;
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

    bytesRead++;
    lastByteReadInstant = now;
    return b;
  }

  @Override
  public int read(byte[] buffer) throws IOException {
    long now = System.currentTimeMillis();
    if (firstByteReadInstant == 0) {
      firstByteReadInstant = now;
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

    bytesRead += read;
    lastByteReadInstant = now;
    return read;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    long now = System.currentTimeMillis();
    if (firstByteReadInstant == 0) {
      firstByteReadInstant = now;
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

    bytesRead += read;
    lastByteReadInstant = now;
    return read;
  }

  public long readThroughput(boolean noBytesWritten, long readThroughputCalculationDelay) {
    // Haven't read anything yet, or we read everything in the first read (instants are equal)
    if (firstByteReadInstant == -1 || bytesRead == 0 || lastByteReadInstant == firstByteReadInstant) {
      return Long.MAX_VALUE;
    }

    if (noBytesWritten) {
      long millis = System.currentTimeMillis() - firstByteReadInstant;
      if (millis < readThroughputCalculationDelay) {
        return Long.MAX_VALUE;
      }

      double result = ((double) bytesRead / (double) millis) * 1_000;
      return Math.round(result);
    }

    double result = ((double) bytesRead / (double) (lastByteReadInstant - firstByteReadInstant)) * 1_000;
    return Math.round(result);
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
    } else {
      logger.debug("Client indicated it was NOT sending an entity-body in the request");
    }
  }
}
