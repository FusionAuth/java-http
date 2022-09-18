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

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.fusionauth.http.FileInfo;
import io.fusionauth.http.HTTPValues.ContentTypes;
import io.fusionauth.http.HTTPValues.ControlBytes;
import io.fusionauth.http.HTTPValues.DispositionParameters;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.ParseException;
import io.fusionauth.http.server.RequestPreambleState;
import io.fusionauth.http.util.HTTPTools;
import io.fusionauth.http.util.HTTPTools.HeaderValue;

/**
 * Handles the multipart body encoding and file uploads.
 *
 * @author Brian Pontarelli
 */
public class MultipartStream {
  private final byte[] boundary;

  private final byte[] buffer;

  private final InputStream input;

  private int boundaryLength;

  private int boundaryStart;

  private int current;

  private int end;

  private int partialBoundary;

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
      throw new IllegalArgumentException("Boundary cannot be null.");
    }

    // We prepend CR/LF to the boundary to chop trailing CRLF from body-data tokens.
    if (bufSize < boundary.length * 2) {
      throw new IllegalArgumentException("The buffer size specified for the MultipartStream is too small. Must be double the boundary length.");
    }

    this.input = input;
    this.buffer = new byte[bufSize];
    this.boundary = new byte[boundary.length + 4]; // CRLF--<boundary> (then we manually handle CRLF or --)
    this.boundaryStart = 2; // Initially we start after the CRLF
    this.boundaryLength = boundary.length + 2; // Initially we only analyze the boundary plus the dashes

    // Set up the full boundary
    System.arraycopy(ControlBytes.MultipartBoundaryPrefix, 0, this.boundary, 0, 4);
    System.arraycopy(boundary, 0, this.boundary, 4, boundary.length);

    current = 0;
    end = 0;
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
    // Initialize the buffer with enough bytes to analyze one boundary
    if (!reload(boundaryLength + 2)) {
      throw new ParseException("Invalid multipart body. The body is empty.");
    }

    current = findBoundary();
    if (current == -1) {
      throw new ParseException("Invalid multipart body. The body doesn't contain any boundaries.");
    }

    // Close this boundary
    boolean hasMore = closeBoundary();

    // Reset the boundary and the search table
    boundaryStart = 0;
    boundaryLength = boundary.length;

    // Loop
    Map<String, HeaderValue> headers = new HashMap<>();
    while (hasMore) {
      readHeaders(headers);
      readPart(headers, parameters, files);
      hasMore = closeBoundary();

      if (hasMore) {
        headers.clear();
      }
    }
  }

  /**
   * Closes a boundary by reading 2 more bytes. If those bytes are CRLF, then there are more parts. If they are --, then the body is
   * complete.
   *
   * @return True if there are more parts.
   * @throws IOException    If any I/O operation failed.
   * @throws ParseException If the input is not a proper multipart body and could not be processed.
   */
  private boolean closeBoundary() throws IOException {
    current += boundaryLength;

    // Read the next two bytes
    byte one = readByte();
    byte two = readByte();

    // Determine if there are more parts
    boolean hasMore;
    if (one == '-' && two == '-') {
      hasMore = false;
    } else if (one == '\r' && two == '\n') {
      hasMore = true;
    } else {
      throw new ParseException("Unexpected characters [" + new String(new byte[]{one, two}) + "] follow a boundary.");
    }

    return hasMore;
  }

  /**
   * Searches for the {@code boundary} in the current {@code buffer} region. At the same time, if a partial boundary is found, this sets the
   * {@code partialBoundary} field.
   *
   * @return The position of the boundary found, counting from the beginning of the {@code buffer}, or {@code -1} if not found.
   */
  private int findBoundary() {
    int bufferIndex = current;
    int boundaryIndex = boundaryStart;
    int checkIndex;

    while (bufferIndex < end) {
      // Run ahead and find the start
      while (bufferIndex < end && buffer[bufferIndex] != boundary[boundaryIndex]) {
        bufferIndex++;
      }

      // Found the start
      if (bufferIndex < end) {
        partialBoundary = bufferIndex;
      } else {
        partialBoundary = -1;
      }

      // Compare until we complete the boundary, find a miss, or exhaust the buffer
      checkIndex = bufferIndex;
      while (checkIndex < end && boundaryIndex < boundaryLength && buffer[checkIndex] == boundary[boundaryIndex]) {
        checkIndex++;
        boundaryIndex++;
      }

      // Found a match
      if (boundaryIndex == boundaryLength) {
        partialBoundary = -1;
        return bufferIndex;
      }

      // Hit the end of the input but the match was valid
      if (checkIndex == end) {
        return -1;
      }

      // Restart because we fell through due to a mismatch
      boundaryIndex = 0;
      bufferIndex++;
      partialBoundary = -1;
    }

    return -1;
  }

  /**
   * Reads a byte from the {@code buffer}, and reloads it as necessary.
   *
   * @return The next byte from the input stream.
   * @throws IOException    If any I/O operation failed.
   * @throws ParseException If the input is not a proper multipart body and could not be processed.
   */
  private byte readByte() throws IOException {
    // Buffer depleted ? - reload at will
    if (current == end) {
      if (!reload(1)) {
        throw new ParseException("Invalid multipart body. Ran out of data while processing.");
      }
    }

    return buffer[current++];
  }

  /**
   * Processes any headers in the stream using the same finite state machine as the HTTP request preamble uses.
   *
   * @throws IOException    If any I/O operation failed.
   * @throws ParseException If the input is not a proper multipart body and could not be processed.
   */
  private void readHeaders(Map<String, HeaderValue> headers) throws IOException, ParseException {
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
          case HeaderValue -> headers.put(headerName, HTTPTools.parseHeaderValue(build.toString()));
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
   * Reads a single body and based on the headers, determines if it is a parameter or a file.
   *
   * @throws IOException    If any I/O operation failed.
   * @throws ParseException If the input is not a proper multipart body and could not be processed.
   */
  private void readPart(Map<String, HeaderValue> headers, Map<String, List<String>> parameters, List<FileInfo> files)
      throws IOException, ParseException {
    HeaderValue disposition = headers.get(Headers.ContentDispositionLower);
    if (disposition == null) {
      throw new ParseException("Invalid multipart body. A part is missing a [Content-Disposition] header.");
    }

    String name = disposition.parameters().get(DispositionParameters.name);
    if (name == null) {
      throw new ParseException("Invalid multipart body. A part is missing a name parameter in the [Content-Disposition] header.");
    }

    String filename = disposition.parameters().get(DispositionParameters.filename);
    boolean isFile = filename != null;
    HeaderValue contentType = headers.get(Headers.ContentTypeLower);
    String contentTypeString = contentType != null ? contentType.value() : "application/octet-stream";
    String encodingString = contentType != null ? contentType.parameters().get(ContentTypes.CharsetParameter) : null;
    Charset encoding = encodingString != null ? Charset.forName(encodingString) : StandardCharsets.UTF_8;

    PartProcessor processor;
    if (isFile) {
      processor = new FilePartProcessor(contentTypeString, encoding, filename, name);
    } else {
      processor = new ParameterPartProcessor(encoding);
    }

    try (processor) {
      int boundaryIndex;
      do {
        boundaryIndex = findBoundary();
        if (boundaryIndex == -1) {
          // Process up to the partial boundary match or the rest of the buffer
          if (partialBoundary == -1) {
            processor.process(current, end);
          } else {
            processor.process(current, partialBoundary);
          }

          reload(boundary.length + 2); // Minimum is at least a full boundary
        } else {
          processor.process(current, boundaryIndex);
          current = boundaryIndex;
        }
      } while (boundaryIndex == -1);

      if (isFile) {
        files.add(processor.toFileInfo());
      } else {
        parameters.computeIfAbsent(name, key -> new LinkedList<>()).add(processor.toValue());
      }
    }
  }

  private boolean reload(int minimumToLoad) throws IOException {
    // Move data that needs to be retained
    int start = 0;
    if (partialBoundary > 0) {
      System.arraycopy(buffer, partialBoundary, buffer, 0, end - partialBoundary);
      start = end - partialBoundary;
      end -= partialBoundary;
      partialBoundary = -1;
      minimumToLoad = boundaryLength + 2; // If we have a partial, we need at least enough for the rest of the boundary
    } else {
      end = 0;
    }

    current = 0;

    // Load until we have enough
    while (end - current < minimumToLoad) {
      end += input.read(buffer, start, buffer.length - start);
      if (end == -1) {
        return false;
      }

      start += end;
    }

    return true;
  }

  /**
   * An interface to assist with processing body bytes from a part into a String or a FileInfo.
   */
  private interface PartProcessor extends Closeable {
    void process(int start, int end) throws IOException;

    FileInfo toFileInfo() throws IOException;

    String toValue();
  }

  private class FilePartProcessor implements PartProcessor {
    private final String contentType;

    private final Charset encoding;

    private final String filename;

    private final String name;

    private final OutputStream output;

    private final Path path;

    private FilePartProcessor(String contentType, Charset encoding, String filename, String name) throws IOException {
      this.contentType = contentType;
      this.encoding = encoding;
      this.filename = filename;
      this.name = name;
      this.path = Files.createTempFile("java-http", "file-upload");
      this.output = Files.newOutputStream(this.path);
    }

    @Override
    public void close() throws IOException {
      output.close();
    }

    @Override
    public void process(int start, int end) throws IOException {
      output.write(buffer, start, end - start);
    }

    @Override
    public FileInfo toFileInfo() throws IOException {
      output.close();
      return new FileInfo(path, filename, name, contentType, encoding);
    }

    @Override
    public String toValue() {
      throw new UnsupportedOperationException();
    }
  }

  private class ParameterPartProcessor implements PartProcessor {
    private final Charset encoding;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private ParameterPartProcessor(Charset encoding) {
      this.encoding = encoding;
    }

    @Override
    public void close() {
    }

    @Override
    public void process(int start, int end) {
      if (start < end) {
        output.write(buffer, start, end - start);
      }
    }

    @Override
    public FileInfo toFileInfo() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String toValue() {
      return output.toString(encoding);
    }
  }
}
