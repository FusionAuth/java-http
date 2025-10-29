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
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import io.fusionauth.http.HTTPValues.ContentEncodings;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.HTTPValues.TransferEncodings;
import io.fusionauth.http.io.ChunkedOutputStream;
import io.fusionauth.http.server.HTTPResponse;
import io.fusionauth.http.server.HTTPServerConfiguration;
import io.fusionauth.http.server.Instrumenter;
import io.fusionauth.http.server.internal.HTTPBuffers;
import io.fusionauth.http.util.HTTPTools;

/**
 * The primary output stream for the HTTP server (currently supporting version 1.1). This handles delegating to compression and chunking
 * output streams, depending on the response headers the application set.
 *
 * @author Brian Pontarelli
 */
public class HTTPOutputStream extends OutputStream {
  private final List<String> acceptEncodings;

  private final HTTPBuffers buffers;

  private final Instrumenter instrumenter;

  private final HTTPResponse response;

  private final ServerToSocketOutputStream serverToSocket;

  private boolean committed;

  private boolean compress;

  private OutputStream delegate;

  private boolean wroteOneByteToClient;

  public HTTPOutputStream(HTTPServerConfiguration configuration, List<String> acceptEncodings, HTTPResponse response, OutputStream delegate,
                          HTTPBuffers buffers, Runnable writeObserver) {
    this.acceptEncodings = acceptEncodings;
    this.response = response;
    this.buffers = buffers;
    this.compress = configuration.isCompressByDefault();
    this.instrumenter = configuration.getInstrumenter();
    this.serverToSocket = new ServerToSocketOutputStream(delegate, buffers, writeObserver);
    this.delegate = serverToSocket;
  }

  @Override
  public void close() throws IOException {
    commit(true);
    delegate.close();
  }

  @Override
  public void flush() throws IOException {
    delegate.flush();
  }

  /**
   * Calls the {@link ServerToSocketOutputStream#forceFlush()} method to write all buffered bytes to the socket. This also writes the
   * preamble to the buffer or to the socket if it hasn't been written yet.
   *
   * @throws IOException If the socket throws.
   */
  public void forceFlush() throws IOException {
    commit(false);
    delegate.flush();
    serverToSocket.forceFlush();
  }

  /**
   * @return True if at least one byte was written back to the client. False if the response has not been generated or is sitting in the
   *     response buffer.
   */
  public boolean isCommitted() {
    return wroteOneByteToClient;
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

  public void reset() {
    if (wroteOneByteToClient) {
      throw new IllegalStateException("The HTTPOutputStream can't be reset after it has been committed, meaning at least one byte was written back to the client.");
    }

    serverToSocket.reset();
    committed = false;
    compress = false;
    delegate = serverToSocket;
  }

  /**
   * @return true if compression has been requested, and it appears as though we will compress because the requested content encoding is
   *     supported.
   */
  public boolean willCompress() {
    if (compress) {
      // TODO : Note I could leave this alone, but when we parse the header we can lower case these values and then remove the equalsIgnoreCase here?
      //        Seems like ideally we would normalize them to lowercase earlier.
      //        Hmm.. sems like we have to in theory since someone could call setAcceptEncodings later, or addAcceptEncodings?
      for (String encoding : acceptEncodings) {
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
    commit(false);
    delegate.write(buffer, offset, length);

    if (instrumenter != null) {
      instrumenter.wroteToClient(length);
    }
  }

  @Override
  public void write(int b) throws IOException {
    commit(false);
    delegate.write(b);

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
    if (committed) {
      return;
    }

    committed = true;

    // +++++++++++ Step 1: Determine if there is content and set up the compression, encoding, chunking, and length headers +++++++++++
    boolean twoOhFour = response.getStatus() == 204;
    boolean gzip = false;
    boolean deflate = false;
    boolean chunked = false;

    // If the output stream is closing, but nothing has been written yet, we can safely set the Content-Length header to 0 to let the client
    // know nothing more is coming beyond the preamble
    if (closing && !twoOhFour) { // 204 status is specifically "No Content" so we shouldn't write the content-length header if the status is 204
      response.setContentLength(0L);
    } else {
      // 204 status is specifically "No Content" so we shouldn't write the content-encoding and vary headers if the status is 204
      // TODO : Compress by default is on by default. But it looks like we don't actually compress unless you also send in an Accept-Encoding header?
      if (compress && !twoOhFour) {
        // TODO : Note I could leave this alone, but when we parse the header we can lower case these values and then remove the equalsIgnoreCase here?
        //        Seems like ideally we would normalize them to lowercase earlier.
        //        Hmm.. sems like we have to in theory since someone could call setAcceptEncodings later, or addAcceptEncodings?
        for (String encoding : acceptEncodings) {
          if (encoding.equalsIgnoreCase(ContentEncodings.Gzip)) {
            response.setHeader(Headers.ContentEncoding, ContentEncodings.Gzip);
            response.setHeader(Headers.Vary, Headers.AcceptEncoding);
            response.removeHeader(Headers.ContentLength); // Compression will change the length, so we'll chunk instead
            gzip = true;
            break;
          } else if (encoding.equalsIgnoreCase(ContentEncodings.Deflate)) {
            response.setHeader(Headers.ContentEncoding, ContentEncodings.Deflate);
            response.setHeader(Headers.Vary, Headers.AcceptEncoding);
            response.removeHeader(Headers.ContentLength); // Compression will change the length, so we'll chunk instead
            deflate = true;
            break;
          }
        }
      }

      if (response.getContentLength() == null && !twoOhFour) { // 204 status is specifically "No Content" so we shouldn't write the transfer-encoding header if the status is 204
        response.setHeader(Headers.TransferEncoding, TransferEncodings.Chunked);
        chunked = true;
      }
    }

    // +++++++++++ Step 2: Write the preamble. This must be first without any other output stream interference +++++++++++
    HTTPTools.writeResponsePreamble(response, delegate);

    // +++++++++++ Step 3: Bail if there is no content +++++++++++
    if (closing || twoOhFour) {
      return;
    }

    // +++++++++++ Step 4: Set up the delegates for the body (if there is any) +++++++++++
    if (chunked) {
      delegate = new ChunkedOutputStream(delegate, buffers.chunkBuffer(), buffers.chuckedOutputStream());
      if (instrumenter != null) {
        instrumenter.chunkedResponse();
      }
    }

    if (gzip) {
      try {
        delegate = new GZIPOutputStream(delegate, true);
        response.setHeader(Headers.ContentEncoding, ContentEncodings.Gzip);
        response.setHeader(Headers.Vary, Headers.AcceptEncoding);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    } else if (deflate) {
      delegate = new DeflaterOutputStream(delegate, true);
    }
  }

  /**
   * This OutputStream handles all the complexity of buffering the response, managing calls to {@link #close()}, etc.
   *
   * @author Brian Pontarelli
   */
  private class ServerToSocketOutputStream extends OutputStream {
    private final byte[] buffer;

    private final OutputStream delegate;

    private final byte[] intsAreDumb = new byte[1];

    private final Runnable writeObserver;

    private int bufferIndex;

    public ServerToSocketOutputStream(OutputStream delegate, HTTPBuffers buffers, Runnable writeObserver) {
      this.delegate = delegate;
      this.buffer = buffers.responseBuffer();
      this.bufferIndex = 0;
      this.writeObserver = writeObserver;
    }

    /**
     * Flushes the buffer but does not close the delegate.
     *
     * @throws IOException If the flush fails.
     */
    @Override
    public void close() throws IOException {
      forceFlush();
    }

    /**
     * Only flushes if the buffer is 90+% full.
     *
     * @throws IOException If the write and flush to the delegate stream throws.
     */
    @Override
    public void flush() throws IOException {
      if (buffer == null || bufferIndex >= (buffer.length * 0.90)) {
        forceFlush();
      }
    }

    public void forceFlush() throws IOException {
      if (buffer == null || bufferIndex == 0) {
        return;
      }

      wroteOneByteToClient = true;
      delegate.write(buffer, 0, bufferIndex);
      delegate.flush();
      bufferIndex = 0;
    }

    /**
     * Resets the ServerToSocketOutputStream by resetting the buffer location to 0. This only applies if the response buffer is in use.
     */
    public void reset() {
      bufferIndex = 0;
    }

    @Override
    public void write(byte[] b, int offset, int length) throws IOException {
      writeObserver.run();

      if (buffer == null) {
        delegate.write(b, offset, length);
      } else {
        do {
          int remaining = buffer.length - bufferIndex;
          int toWrite = Math.min(remaining, length);
          System.arraycopy(b, offset, buffer, bufferIndex, toWrite);
          bufferIndex += toWrite;
          offset += toWrite;
          length -= toWrite;

          if (bufferIndex >= buffer.length) {
            forceFlush();
          }
        } while (length > 0);
      }
    }

    @Override
    public void write(int b) throws IOException {
      intsAreDumb[0] = (byte) b;
      write(intsAreDumb, 0, 1);
    }

    @Override
    public void write(byte[] b) throws IOException {
      write(b, 0, b.length);
    }
  }
}
