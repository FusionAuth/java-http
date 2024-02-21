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
import io.fusionauth.http.HTTPValues.TransferEncodings;
import io.fusionauth.http.io.ChunkedOutputStream;
import io.fusionauth.http.io.FastByteArrayOutputStream;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPResponse;
import io.fusionauth.http.server.HTTPServerConfiguration;
import io.fusionauth.http.server.Instrumenter;
import io.fusionauth.http.util.HTTPTools;

/**
 * The primary output stream for the HTTP server (currently supporting version 1.1). This handles delegating to compression and chunking
 * output streams, depending on the response headers the application set.
 *
 * @author Brian Pontarelli
 */
public class HTTPOutputStream extends OutputStream {
  private final Instrumenter instrumenter;

  private final int maxChunkSize;

  private final FastByteArrayOutputStream premableStream;

  private final HTTPRequest request;

  private final HTTPResponse response;

  private final Throughput throughput;

  private boolean committed;

  private boolean compress;

  private OutputStream delegate;

  public HTTPOutputStream(HTTPServerConfiguration configuration, Throughput throughput, HTTPRequest request, HTTPResponse response,
                          OutputStream delegate, FastByteArrayOutputStream premableStream) {
    this.request = request;
    this.response = response;
    this.throughput = throughput;
    this.premableStream = premableStream;
    this.compress = configuration.isCompressByDefault();
    this.instrumenter = configuration.getInstrumenter();
    this.maxChunkSize = configuration.getRequestBufferSize();

    // Always make the main output stream uncloseable because this is likely the SocketOutputStream. We handle Keep-Alive and Close in the
    // HTTPWorker
    this.delegate = new UncloseableOutputStream(delegate);
  }

  @Override
  public void close() throws IOException {
    if (!committed) {
      commit(true);
    }

    delegate.close();
  }

  @Override
  public void flush() throws IOException {
    delegate.flush();
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
  public void write(byte[] buffer, int offset, int length) throws IOException {
    if (!committed) {
      commit(false);
    }

    delegate.write(buffer, offset, length);
    throughput.writeThroughput(length);

    if (instrumenter != null) {
      instrumenter.wroteToClient(length);
    }
  }

  @Override
  public void write(int b) throws IOException {
    if (!committed) {
      commit(false);
    }

    delegate.write(b);
    throughput.wrote(1);

    if (instrumenter != null) {
      instrumenter.wroteToClient(1);
    }
  }

  @Override
  public void write(byte[] b) throws IOException {
    write(b, 0, b.length);
  }


  /**
   * Initialize the actual OutputStream latent so that we can call setCompress more than once. The GZIPOutputStream writes bytes to the
   * OutputStream during construction which means we cannot build it more than once. This is why we must wait until we know for certain we
   * are going to write bytes to construct the compressing OutputStream.
   */
  private void commit(boolean closing) throws IOException {
    committed = true;

    // Determine the encoding based on the Content-Length header. This should be before the encoding OutputStream so that the chunked parts
    // are the compressed body.
    OutputStream finalDelegate = delegate;
    if (response.getContentLength() == null) {
      finalDelegate = new ChunkedOutputStream(finalDelegate, maxChunkSize);
      response.setHeader(Headers.TransferEncoding, TransferEncodings.Chunked);

      if (instrumenter != null) {
        instrumenter.chunkedResponse();
      }
    }

    // Attempt to honor the requested encoding(s) in order, taking the first match. If a match is not found, we don't compress the response.
    if (compress) {
      for (String encoding : request.getAcceptEncodings()) {
        if (encoding.equalsIgnoreCase(ContentEncodings.Gzip)) {
          try {
            finalDelegate = new GZIPOutputStream(finalDelegate);
            response.setHeader(Headers.ContentEncoding, ContentEncodings.Gzip);
            response.setHeader(Headers.Vary, Headers.AcceptEncoding);
            break;
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        } else if (encoding.equalsIgnoreCase(ContentEncodings.Deflate)) {
          finalDelegate = new DeflaterOutputStream(finalDelegate, true);
          response.setHeader(Headers.ContentEncoding, ContentEncodings.Deflate);
          response.setHeader(Headers.Vary, Headers.AcceptEncoding);
          break;
        }
      }
    }

    // If the output stream is closing, but nothing has been written yet, we can safely set the Content-Length header to 0 to let the client
    // know nothing more is coming beyond the preamble
    if (closing) {
      response.setContentLength(0L);
    }

    // Write the response preamble directly to the Socket OutputStream (the original delegate)
    HTTPTools.writeResponsePreamble(response, premableStream);
    delegate.write(premableStream.bytes(), 0, premableStream.size());
    premableStream.reset();

    // Next, change the delegate to account for compression and/or chunking and wrap it in an uncloseable stream
    delegate = finalDelegate;
  }

  /**
   * Uncloseable stream that overrides all methods to delegate them to another OutputStream, except {@link #close()}.
   *
   * @author Brian Pontarelli
   */
  private static class UncloseableOutputStream extends OutputStream {
    private final OutputStream delegate;

    public UncloseableOutputStream(OutputStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public void close() {
      // No-op (uncloseable)
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void write(byte[] b) throws IOException {
      delegate.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      delegate.write(b, off, len);
    }

    @Override
    public void write(int b) throws IOException {
      delegate.write(b);
    }
  }
}
