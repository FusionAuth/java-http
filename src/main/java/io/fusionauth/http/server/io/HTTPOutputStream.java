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
import java.io.OutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import io.fusionauth.http.HTTPValues.ContentEncodings;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPResponse;
import io.fusionauth.http.util.HTTPTools;

public class HTTPOutputStream extends OutputStream {
  private final HTTPRequest request;

  private final HTTPResponse response;

  private long bytesWritten;

  private boolean committed;

  private boolean compress;

  private OutputStream delegate;

  private long firstByteWroteInstant;

  public HTTPOutputStream(HTTPRequest request, HTTPResponse response, OutputStream delegate, boolean compressByDefault) {
    this.request = request;
    this.response = response;
    this.delegate = delegate;
    this.compress = compressByDefault;
  }

  @Override
  public void close() throws IOException {
    // Ignore because we don't know if we should be closing the socket's OutputStream
  }

  @Override
  public void flush() throws IOException {
    delegate.flush();
  }

  public long getFirstByteWroteInstant() {
    return firstByteWroteInstant;
  }

  public boolean isCommitted() {
    return committed;
  }

  public boolean isCompress() {
    return compress;
  }

  public void setCompress(boolean compress) {
    // Too late, once you write bytes, we can no longer change the OutputStream configuration.
    if (committed) {
      throw new IllegalStateException("The HTTPResponse compression configuration cannot be modified once bytes have been written to it.");
    }

    this.compress = compress;
  }

  /**
   * @return true if compression has been requested, and it appears as though we will compress because the requested content encoding is
   *     supported.
   */
  public boolean willCompress() {
    if (compress) {
      for (String encoding : request.getAcceptEncodings()) {
        if (encoding.equalsIgnoreCase(ContentEncodings.Gzip)) {
          return true;
        } else if (encoding.equalsIgnoreCase(ContentEncodings.Deflate)) {
          return true;
        }
      }

      return false;
    }

    return false;
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    if (firstByteWroteInstant == 0) {
      firstByteWroteInstant = System.currentTimeMillis();
    }

    if (!committed) {
      commit();
    }

    delegate.write(b, off, len);
    bytesWritten++;
  }

  @Override
  public void write(int b) throws IOException {
    if (firstByteWroteInstant == 0) {
      firstByteWroteInstant = System.currentTimeMillis();
    }

    if (!committed) {
      commit();
    }

    delegate.write(b);
    bytesWritten++;
  }

  @Override
  public void write(byte[] b) throws IOException {
    write(b, 0, b.length);
  }

  public long writeThroughput(long writeThroughputCalculationDelay) {
    // Haven't written anything yet or not enough time has passed to calculated throughput (2s)
    if (firstByteWroteInstant == -1 || bytesWritten == 0) {
      return Long.MAX_VALUE;
    }

    // Always use currentTime since this calculation is ongoing until the client reads all the bytes
    long millis = System.currentTimeMillis() - firstByteWroteInstant;
    if (millis < writeThroughputCalculationDelay) {
      return Long.MAX_VALUE;
    }

    double result = ((double) bytesWritten / (double) millis) * 1_000;
    return Math.round(result);
  }

  /**
   * Initialize the actual OutputStream latent so that we can call setCompress more than once. The GZIPOutputStream writes bytes to the
   * OutputStream during construction which means we cannot build it more than once. This is why we must wait until we know for certain we
   * are going to write bytes to construct the compressing OutputStream.
   */
  private void commit() throws IOException {
    committed = true;

    // Short circuit if there is nothing to do
    if (!compress) {
      return;
    }

    // Attempt to honor the requested encoding(s) in order, taking the first match
    // - If a match is not found, we will not compress the response.
    for (String encoding : request.getAcceptEncodings()) {
      if (encoding.equalsIgnoreCase(ContentEncodings.Gzip)) {
        try {
          delegate = new GZIPOutputStream(delegate);
          response.setHeader(Headers.ContentEncoding, ContentEncodings.Gzip);
          return;
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      } else if (encoding.equalsIgnoreCase(ContentEncodings.Deflate)) {
        delegate = new DeflaterOutputStream(delegate);
        response.setHeader(Headers.ContentEncoding, ContentEncodings.Deflate);
        return;
      }
    }

    compress = false;

    HTTPTools.writeResponsePreamble(response, delegate);
  }
}
