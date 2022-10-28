/*
 * Copyright (c) 2022, FusionAuth, All Rights Reserved
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

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import io.fusionauth.http.HTTPValues.ContentEncodings;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPResponse;

/**
 * A delegating output stream that can allow for latent configuration changes prior to writing to the underlying output stream.
 *
 * @author Daniel DeGroff
 */
public class DelegatingOutputStream extends OutputStream {
  private final HTTPRequest request;

  private final HTTPResponse response;

  private final OutputStream unCompressingOutputStream;

  private Boolean compress;

  private boolean defaultCompress;

  private String encoding;

  private OutputStream outputStream;

  // https://stackoverflow.com/questions/23228359/is-checking-a-boolean-faster-than-setting-a-boolean-in-java
  // - This seems to indicate that if you only need to write once, and read a bunch, while more byte code is
  //   produced by gating the write operation with a read, it should faster since you'll only write once, and
  //   a read of a volatile is much faster than a write operation.
  // See CompressionTest.volatileCheckPerformance for results.
  private volatile boolean used;

  public DelegatingOutputStream(HTTPRequest request, HTTPResponse response, OutputStream outputStream) {
    this.request = request;
    this.response = response;
    this.outputStream = outputStream;
    this.unCompressingOutputStream = outputStream;
  }

  @Override
  public void close() throws IOException {
    outputStream.close();
  }

  @Override
  public void flush() throws IOException {
    outputStream.flush();
  }

  public boolean isCompress() {
    return (compress != null && compress) || defaultCompress;
  }

  public void setCompress(boolean compress) {
    // Too late, once you write bytes, we can no longer change the OutputStream configuration.
    if (used) {
      throw new IllegalStateException("The HTTPResponse compression configuration cannot be modified once bytes have been written to it.");
    }

    // Short circuit, no work to do.
    if (this.compress != null && this.compress == compress) {
      return;
    }

    if (compress) {
      if (setContentEncodingHeader()) {
        return;
      }
    }

    // Fall through and disable compress, because:
    // 1. Compress was equal to false
    // 2. Compress was equal to true, and the acceptable encoding values did not contain [gzip, deflate]
    this.compress = false;
  }

  public void setDefaultCompress(boolean defaultCompress) {
    this.defaultCompress = defaultCompress;
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    if (!used) {
      init();
    }

    outputStream.write(b, off, len);
  }

  @Override
  public void write(int b) throws IOException {
    if (!used) {
      init();
    }

    outputStream.write(b);
  }

  @Override
  public void write(byte[] b) throws IOException {
    if (!used) {
      init();
    }

    outputStream.write(b);
  }

  private void init() {
    // Initialize the actual OutputStream latent so that we can call setCompress more than once.
    // - The GZIPOutputStream writes bytes to the OutputStream during construction which means we cannot build it
    //   more than once. This is why we must wait until we know for certain we are going to write bytes to construct
    //   the compressing OutputStream.
    used = true;

    // Short circuit if there is nothing to do
    if ((compress != null && !compress) || !defaultCompress) {
      return;
    }

    // If we have not yet set compress, we need to set the header now.
    // - We need to wait this long so that we only write this header when we know there are bytes to write.
    if (compress == null) {
      setContentEncodingHeader();
    }

    if (encoding != null) {
      if (encoding.equalsIgnoreCase(ContentEncodings.Gzip)) {
        try {
          outputStream = new GZIPOutputStream(unCompressingOutputStream);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      } else if (encoding.equalsIgnoreCase(ContentEncodings.Deflate)) {
        outputStream = new DeflaterOutputStream(unCompressingOutputStream);
      }
    }
  }

  private boolean setContentEncodingHeader() {
    // Attempt to honor the requested encoding(s) in order, taking the first match
    for (String encoding : request.getAcceptEncodings()) {
      if (encoding.equalsIgnoreCase(ContentEncodings.Gzip)) {
        response.setHeader(Headers.ContentEncoding, ContentEncodings.Gzip);
        this.compress = true;
        this.encoding = encoding;
        return true;
      } else if (encoding.equalsIgnoreCase(ContentEncodings.Deflate)) {
        response.setHeader(Headers.ContentEncoding, ContentEncodings.Deflate);
        this.compress = true;
        this.encoding = encoding;
        return true;
      }
    }

    return false;
  }
}
