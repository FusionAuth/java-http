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
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.fusionauth.http.HTTPValues.ControlBytes;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.ParseException;
import io.fusionauth.http.server.FileInfo;
import io.fusionauth.http.server.RequestPreambleState;
import io.fusionauth.http.util.HTTPTools;
import io.fusionauth.http.util.HTTPTools.HeaderValue;

/**
 * Copied from Apache Commons (see the license above) but modified heavily to simplify and work better with Java HTTP.
 */
@SuppressWarnings("resource")
public class MultipartStream {
  /**
   * The byte sequence that partitions the stream.
   */
  private final byte[] boundary;

  /**
   * The table for Knuth-Morris-Pratt search algorithm.
   */
  private final int[] boundaryTable;

  /**
   * The length of the buffer used for processing the request.
   */
  private final int bufSize;

  /**
   * The buffer used for processing the request.
   */
  private final byte[] buffer;

  /**
   * The input stream from which data is read.
   */
  private final InputStream input;

  /**
   * The amount of data, in bytes, that must be kept in the buffer in order to detect delimiters reliably.
   */
  private final int keepRegion;

  /**
   * The length of the boundary token plus the leading {@code CRLF--}.
   */
  private int boundaryLength;

  /**
   * The index of first valid character in the buffer.
   * <p>
   * 0 <= head < bufSize
   */
  private int head;

  /**
   * The index of last valid character in the buffer + 1.
   * <br>
   * 0 <= tail <= bufSize
   */
  private int tail;

  /**
   * Constructs a {@code MultipartStream} with a custom size buffer.
   * <p>
   * Note that the buffer must be at least big enough to contain the boundary string, plus 4 characters for CR/LF and double dash, plus at
   * least one byte of data.  Too small a buffer size setting will degrade performance.
   *
   * @param input    The {@code InputStream} to serve as a data source.
   * @param boundary The token used for dividing the stream into {@code encapsulations}.
   * @param bufSize  The size of the buffer to be used, in bytes.
   * @throws IllegalArgumentException If the buffer size is too small
   */
  public MultipartStream(final InputStream input, final byte[] boundary, final int bufSize) {
    if (boundary == null) {
      throw new IllegalArgumentException("boundary may not be null");
    }

    // We prepend CR/LF to the boundary to chop trailing CR/LF from body-data tokens.
    this.boundaryLength = boundary.length + ControlBytes.MultipartBoundaryPrefix.length;
    if (bufSize < this.boundaryLength + 1) {
      throw new IllegalArgumentException("The buffer size specified for the MultipartStream is too small");
    }

    this.input = input;
    this.bufSize = Math.max(bufSize, boundaryLength * 2);
    this.buffer = new byte[this.bufSize];

    this.boundary = new byte[this.boundaryLength];
    this.boundaryTable = new int[this.boundaryLength + 1];
    this.keepRegion = this.boundary.length;

    System.arraycopy(ControlBytes.MultipartBoundaryPrefix, 0, this.boundary, 0, ControlBytes.MultipartBoundaryPrefix.length);
    System.arraycopy(boundary, 0, this.boundary, ControlBytes.MultipartBoundaryPrefix.length, boundary.length);
    computeBoundaryTable();

    head = 0;
    tail = 0;
  }

  /**
   * Completely processes the multipart body and puts the parameters and files into the given collections.
   *
   * @param parameters The parameters.
   * @param files      The files.
   * @throws IOException    If any I/O operation failed.
   * @throws ParseException If the input is not a proper multipart body and could not be processed.
   */
  public void process(Map<String, List<String>> parameters, List<FileInfo> files) throws IOException, ParseException {
    boolean hasMore = initialize();

    Map<String, List<HeaderValue>> headers = new HashMap<>();
    while (hasMore) {
      readHeaders(headers);
      readBodyData(headers, parameters, files);
      hasMore = readBoundary();

      if (hasMore) {
        headers.clear();
      }
    }
  }

  /**
   * Compute the table used for Knuth-Morris-Pratt search algorithm.
   */
  private void computeBoundaryTable() {
    int position = 2;
    int candidate = 0;

    boundaryTable[0] = -1;
    boundaryTable[1] = 0;

    while (position <= boundaryLength) {
      if (boundary[position - 1] == boundary[candidate]) {
        boundaryTable[position] = candidate + 1;
        candidate++;
        position++;
      } else if (candidate > 0) {
        candidate = boundaryTable[candidate];
      } else {
        boundaryTable[position] = 0;
        position++;
      }
    }
  }

  /**
   * Searches for the {@code boundary} in the {@code buffer} region delimited by {@code head} and {@code tail}.
   *
   * @return The position of the boundary found, counting from the beginning of the {@code buffer}, or {@code -1} if not found.
   */
  private int findSeparator() {
    int bufferPos = this.head;
    int tablePos = 0;

    while (bufferPos < this.tail) {
      while (tablePos >= 0 && buffer[bufferPos] != boundary[tablePos]) {
        tablePos = boundaryTable[tablePos];
      }

      bufferPos++;
      tablePos++;

      if (tablePos == boundaryLength) {
        return bufferPos - boundaryLength;
      }
    }

    return -1;
  }

  /**
   * Finds the beginning of the first part.
   *
   * @return True if the input has at least one part, false if there are no parts.
   * @throws IOException    If any I/O operation failed.
   * @throws ParseException If the input is not a proper multipart body and could not be processed.
   */
  private boolean initialize() throws IOException, ParseException {
    // First boundary may be not preceded with a CRLF.
    System.arraycopy(boundary, 2, boundary, 0, boundary.length - 2);
    boundaryLength = boundary.length - 2;
    computeBoundaryTable();

    boolean hasParts = readBoundary();

    // Restore boundary
    System.arraycopy(boundary, 0, boundary, 2, boundary.length - 2);
    boundaryLength = boundary.length;
    boundary[0] = ControlBytes.CR;
    boundary[1] = ControlBytes.LF;
    computeBoundaryTable();

    return hasParts;
  }

  /**
   * Reads a single body and based on the headers, determines if it is a parameter or a file.
   *
   * @throws IOException    If any I/O operation failed.
   * @throws ParseException If the input is not a proper multipart body and could not be processed.
   */
  private void readBodyData(Map<String, List<HeaderValue>> headers, Map<String, List<String>> parameters, List<FileInfo> files)
      throws IOException, ParseException {
    String name;
    String filename;
    if (headers.containsKey(Headers.ContentDispositionLower)) {
//      HTTPTools.parseHeaderValue()
    }
//    return inputStream.transferTo(output);
  }

  /**
   * Skips a {@code boundary} token, and checks whether more {@code encapsulations} are contained in the stream.
   *
   * @return {@code true} if there are more encapsulations in this stream; {@code false} otherwise.
   * @throws IOException If the stream couldn't be read or was not a valid multipart body.
   */
  private boolean readBoundary() throws IOException {
    final byte[] marker = new byte[2];
    final boolean nextChunk;

    head += boundaryLength;
    marker[0] = readByte();
    if (marker[0] == ControlBytes.LF) {
      // Work around IE5 Mac bug with input type=image.
      // Because the boundary delimiter, not including the trailing
      // CRLF, must not appear within any file (RFC 2046, section
      // 5.1.1), we know the missing CR is due to a buggy browser
      // rather than a file containing something similar to a
      // boundary.
      return true;
    }

    marker[1] = readByte();
    if (HTTPTools.arraysEquals(marker, ControlBytes.MultipartTerminator, 2)) {
      nextChunk = false;
    } else if (HTTPTools.arraysEquals(marker, ControlBytes.CRLF, 2)) {
      nextChunk = true;
    } else {
      throw new ParseException("Unexpected characters follow a boundary");
    }

    return nextChunk;
  }

  /**
   * Reads a byte from the {@code buffer}, and refills it as necessary.
   *
   * @return The next byte from the input stream.
   * @throws IOException If any I/O operation failed.
   */
  private byte readByte() throws IOException {
    // Buffer depleted ?
    if (head == tail) {
      head = 0;
      // Refill.
      tail = input.read(buffer, head, bufSize);
      if (tail == -1) {
        // No more data available.
        throw new IOException("No more data is available");
      }
    }

    return buffer[head++];
  }

  /**
   * Processes any headers in the stream using the same finite state machine as the HTTP request preamble uses.
   *
   * @throws IOException    If any I/O operation failed.
   * @throws ParseException If the input is not a proper multipart body and could not be processed.
   */
  private void readHeaders(Map<String, List<HeaderValue>> headers) throws IOException, ParseException {
    var state = RequestPreambleState.HeaderName;
    var build = new StringBuilder();
    String headerName = null;
    byte b;
    while (state != RequestPreambleState.Complete) {
      b = readByte();

      var nextState = state.next(b);
      if (nextState != state) {
        switch (state) {
          case HeaderName -> headerName = build.toString().toLowerCase();
          case HeaderValue ->
              headers.computeIfAbsent(headerName, key -> new LinkedList<>()).add(HTTPTools.parseHeaderValue(build.toString()));
        }

        // If the next state is storing, reset the builder
        if (nextState.store()) {
          build.delete(0, build.length());
          build.appendCodePoint(b);
        }
      } else if (state.store()) {
        // If the current state is storing, store the character
        build.appendCodePoint(b);
      }

      state = nextState;
    }
  }

  /**
   * An {@link InputStream} for reading an items contents.
   */
  public class ItemInputStream extends InputStream {
    /**
     * Offset when converting negative bytes to integers.
     */
    private static final int BYTE_POSITIVE_OFFSET = 256;

    /**
     * Whether the stream is already closed.
     */
    private boolean closed;

    /**
     * The number of bytes, which must be hold, because they might be a part of the boundary.
     */
    private int pad;

    /**
     * The current offset in the buffer.
     */
    private int pos;

    /**
     * Creates a new instance.
     */
    ItemInputStream() {
      findSeparator();
    }

    /**
     * Returns the number of bytes, which are currently available, without blocking.
     *
     * @return Number of bytes in the buffer.
     */
    @Override
    public int available() {
      if (pos == -1) {
        return tail - head - pad;
      }
      return pos - head;
    }

    /**
     * Closes the input stream.
     *
     * @throws IOException An I/O error occurred.
     */
    @Override
    public void close() throws IOException {
      close(false);
    }

    /**
     * Closes the input stream.
     *
     * @param closeUnderlying Whether to close the underlying stream (hard close)
     * @throws IOException An I/O error occurred.
     */
    public void close(boolean closeUnderlying) throws IOException {
      if (closed) {
        return;
      }

      if (closeUnderlying) {
        closed = true;
        input.close();
      } else {
        for (; ; ) {
          int available = available();
          if (available == 0) {
            available = makeAvailable();
            if (available == 0) {
              break;
            }
          }

          skip(available);
        }
      }

      closed = true;
    }

    /**
     * Reads bytes into the given buffer.
     *
     * @param b   The destination buffer, where to write to.
     * @param off Offset of the first byte in the buffer.
     * @param len Maximum number of bytes to read.
     * @return Number of bytes, which have been actually read, or -1 for EOF.
     * @throws IOException An I/O error occurred.
     */
    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
      if (closed) {
        throw new IOException();
      }

      if (len == 0) {
        return 0;
      }

      int res = available();
      if (res == 0) {
        res = makeAvailable();
        if (res == 0) {
          return -1;
        }
      }

      res = Math.min(res, len);
      System.arraycopy(buffer, head, b, off, res);
      head += res;
      return res;
    }

    /**
     * Returns the next byte in the stream.
     *
     * @return The next byte in the stream, as a non-negative integer, or -1 for EOF.
     * @throws IOException An I/O error occurred.
     */
    @Override
    public int read() throws IOException {
      if (closed) {
        throw new IOException();
      }

      if (available() == 0 && makeAvailable() == 0) {
        return -1;
      }

      final int b = buffer[head++];
      return (b >= 0) ? b : b + BYTE_POSITIVE_OFFSET;
    }

    /**
     * Skips the given number of bytes.
     *
     * @param bytes Number of bytes to skip.
     * @return The number of bytes, which have actually been skipped.
     * @throws IOException An I/O error occurred.
     */
    @Override
    public long skip(final long bytes) throws IOException {
      if (closed) {
        throw new IOException();
      }

      int av = available();
      if (av == 0) {
        av = makeAvailable();
        if (av == 0) {
          return 0;
        }
      }
      final long res = Math.min(av, bytes);
      head += res;
      return res;
    }

    /**
     * Called for finding the separator.
     */
    private void findSeparator() {
      pos = MultipartStream.this.findSeparator();
      if (pos == -1) {
        pad = Math.min(tail - head, keepRegion);
      }
    }

    /**
     * Attempts to read more data.
     *
     * @return Number of available bytes
     * @throws IOException An I/O error occurred.
     */
    private int makeAvailable() throws IOException {
      if (pos != -1) {
        return 0;
      }

      // Move the data to the beginning of the buffer.
      System.arraycopy(buffer, tail - pad, buffer, 0, pad);

      // Refill buffer with new data.
      head = 0;
      tail = pad;

      for (; ; ) {
        final int bytesRead = input.read(buffer, tail, bufSize - tail);
        if (bytesRead == -1) {
          throw new ParseException("Unable to parse multipart body because it isn't valid");
        }

        tail += bytesRead;

        findSeparator();
        final int av = available();

        if (av > 0 || pos != -1) {
          return av;
        }
      }
    }
  }
}
