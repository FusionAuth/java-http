/*
 * Copyright (c) 2021-2022, FusionAuth, All Rights Reserved
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
package io.fusionauth.http.server;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.fusionauth.http.Cookie;
import io.fusionauth.http.HTTPValues.Connections;
import io.fusionauth.http.HTTPValues.ContentTypes;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.HTTPValues.Status;
import io.fusionauth.http.io.DelegatingOutputStream;
import io.fusionauth.http.util.HTTPTools;
import io.fusionauth.http.util.HTTPTools.HeaderValue;

/**
 * An HTTP response that the server sends back to a client. The handler that processes the HTTP request can fill out this object and the
 * HTTP server will send it back to the client.
 *
 * @author Brian Pontarelli
 */
public class HTTPResponse {
  private final Map<String, Map<String, Cookie>> cookies = new HashMap<>(); // <Path, <Name, Cookie>>

  private final Map<String, List<String>> headers = new HashMap<>();

  private final DelegatingOutputStream outputStream;

  private volatile boolean committed;

  private Throwable exception;

  private int status = 200;

  private String statusMessage;

  private Writer writer;

  public HTTPResponse(OutputStream outputStream, HTTPRequest request) {
    this.outputStream = new DelegatingOutputStream(request, this, outputStream);
  }

  public void addCookie(Cookie cookie) {
    String path = cookie.path != null ? cookie.path : "/";
    cookies.computeIfAbsent(path, key -> new HashMap<>()).put(cookie.name, cookie);
  }

  /**
   * Add a response header. Calling this method multiple times with the same name will result in multiple headers being written to the HTTP
   * response.
   * <p>
   * Optionally call {@link #setHeader(String, String)} if you wish to only write a single header by name, overwriting any previously
   * written headers.
   * <p>
   * If either parameter are null, the header will not be added to the response.
   *
   * @param name  the header name
   * @param value the header value
   */
  public void addHeader(String name, String value) {
    if (name == null || value == null) {
      return;
    }

    headers.computeIfAbsent(name.toLowerCase(), key -> new ArrayList<>()).add(value);
  }

  public void clearHeaders() {
    headers.clear();
  }

  /**
   * Closes the HTTP response to ensure that the client is notified that the server is finished responding. This closes the Writer or the
   * OutputStream if they are available. The Writer is preferred if it exists so that it is properly flushed.
   */
  public void close() throws IOException {
    if (writer != null) {
      writer.close();
    } else {
      outputStream.close();
    }
  }

  public boolean containsHeader(String name) {
    String key = name.toLowerCase();
    return headers.containsKey(key) && headers.get(key).size() > 0;
  }

  public boolean failure() {
    return status < 200 || status > 299;
  }

  /**
   * Determines the character set by parsing the {@code Content-Type} header (if it exists) to pull out the {@code charset} parameter.
   *
   * @return The Charset or UTF-8 if it wasn't specified in the {@code Content-Type} header.
   */
  public Charset getCharset() {
    Charset charset = StandardCharsets.UTF_8;
    String contentType = getContentType();
    if (contentType != null) {
      HeaderValue headerValue = HTTPTools.parseHeaderValue(contentType);
      String charsetName = headerValue.parameters().get(ContentTypes.CharsetParameter);
      if (charsetName != null) {
        charset = Charset.forName(charsetName);
      }
    }

    return charset;
  }

  public Long getContentLength() {
    if (containsHeader(Headers.ContentLength)) {
      return Long.parseLong(getHeader(Headers.ContentLength));
    }

    return null;
  }

  public void setContentLength(long length) {
    setHeader(Headers.ContentLength, Long.toString(length));
  }

  public String getContentType() {
    return getHeader(Headers.ContentType);
  }

  public void setContentType(String contentType) {
    setHeader(Headers.ContentType, contentType);
  }

  public List<Cookie> getCookies() {
    return cookies.values()
                  .stream()
                  .flatMap(map -> map.values().stream())
                  .collect(Collectors.toList());
  }

  public Throwable getException() {
    return exception;
  }

  public void setException(Throwable exception) {
    this.exception = exception;
  }

  public String getHeader(String name) {
    String key = name.toLowerCase();
    return headers.containsKey(key) && headers.get(key).size() > 0 ? headers.get(key).get(0) : null;
  }

  public List<String> getHeaders(String key) {
    return headers.get(key.toLowerCase());
  }

  public Map<String, List<String>> getHeadersMap() {
    return headers;
  }

  public OutputStream getOutputStream() {
    return outputStream;
  }

  public String getRedirect() {
    return getHeader(Headers.Location);
  }

  public int getStatus() {
    return status;
  }

  public void setStatus(int status) {
    this.status = status;
  }

  public String getStatusMessage() {
    return statusMessage;
  }

  public void setStatusMessage(String statusMessage) {
    this.statusMessage = statusMessage;
  }

  public Writer getWriter() {
    Charset charset = getCharset();
    if (writer == null) {
      writer = new OutputStreamWriter(getOutputStream(), charset);
    }

    return writer;
  }

  /**
   * @return True if the response has been committed, meaning at least one byte was written back to the client. False otherwise.
   */
  public boolean isCommitted() {
    return committed;
  }

  /**
   * Sets the committed status of the response.
   *
   * @param committed The status.
   */
  public void setCommitted(boolean committed) {
    this.committed = committed;
  }

  /**
   * @return true if compression will be utilized when writing the HTTP OutputStream.
   */
  public boolean isCompress() {
    return outputStream.isCompress();
  }

  /**
   * Provides runtime configuration for HTTP response compression. This can be called as many times as you wish prior to the first byte
   * being written to the HTTP OutputStream.
   * <p>
   * An {@link IllegalStateException} will be thrown if you call this method after writing to the OutputStream.
   *
   * @param compress true to enable the response to be written back compressed.
   */
  public void setCompress(boolean compress) {
    outputStream.setCompress(compress);
  }

  /**
   * @return If the connection should be kept open (keep-alive) or not. The default is to return the Connection: keep-alive header, which
   *     this method does.
   */
  public boolean isKeepAlive() {
    String value = getHeader(Headers.Connection);
    return value == null || value.equalsIgnoreCase(Connections.KeepAlive);
  }

  public void removeCookie(String name) {
    cookies.values().forEach(map -> map.remove(name));
  }

  public void sendRedirect(String uri) {
    setHeader(Headers.Location, uri);
    status = Status.MovedTemporarily;
  }

  public void setDateHeader(String name, ZonedDateTime value) {
    addHeader(name, DateTimeFormatter.RFC_1123_DATE_TIME.format(value));
  }

  public void setDefaultCompress(boolean compress) {
    outputStream.setDefaultCompress(compress);
  }

  /**
   * Set the header, replacing any existing header values. If you wish to add to existing response headers, use the
   * {@link #addHeader(String, String)} instead.
   * <p>
   * If either parameter are null, the header will not be added to the response.
   *
   * @param name  the header name
   * @param value the header value
   */
  public void setHeader(String name, String value) {
    if (name == null || value == null) {
      return;
    }

    headers.put(name.toLowerCase(), new ArrayList<>(List.of(value)));
  }
}

