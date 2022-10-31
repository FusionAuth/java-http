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

  private boolean compress;

  private OutputStream outputStream;

  private boolean used;

  public DelegatingOutputStream(HTTPRequest request, HTTPResponse response, OutputStream outputStream, boolean compressByDefault) {
    this.request = request;
    this.response = response;
    this.outputStream = outputStream;
    this.compress = compressByDefault;
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
    return compress;
  }

  public void setCompress(boolean compress) {
    // Too late, once you write bytes, we can no longer change the OutputStream configuration.
    if (used) {
      throw new IllegalStateException("The HTTPResponse compression configuration cannot be modified once bytes have been written to it.");
    }

    this.compress = compress;
  }

  /**
   * @return true if compression has been requested and thn will compress because we support the requested content encoding.
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
    if (!compress) {
      return;
    }

    // Attempt to honor the requested encoding(s) in order, taking the first match
    // - If a match is not found, we will not compress the response.
    for (String encoding : request.getAcceptEncodings()) {
      if (encoding.equalsIgnoreCase(ContentEncodings.Gzip)) {
        try {
          outputStream = new GZIPOutputStream(unCompressingOutputStream);
          response.setHeader(Headers.ContentEncoding, ContentEncodings.Gzip);
          return;
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      } else if (encoding.equalsIgnoreCase(ContentEncodings.Deflate)) {
        outputStream = new DeflaterOutputStream(unCompressingOutputStream);
        response.setHeader(Headers.ContentEncoding, ContentEncodings.Deflate);
        return;
      }
    }

    compress = false;
  }
}
