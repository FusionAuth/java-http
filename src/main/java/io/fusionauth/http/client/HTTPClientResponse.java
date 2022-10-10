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
package io.fusionauth.http.client;

import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.fusionauth.http.Cookie;
import io.fusionauth.http.HTTPValues.Connections;
import io.fusionauth.http.HTTPValues.ContentTypes;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.util.HTTPTools;
import io.fusionauth.http.util.HTTPTools.HeaderValue;

/**
 * An HTTP client response.
 *
 * @author Daniel DeGroff
 */
public class HTTPClientResponse {
  private final Map<String, Map<String, Cookie>> cookies = new HashMap<>(); // <Path, <Name, Cookie>>

  private final Map<String, List<String>> headers = new HashMap<>();

  private byte[] body;

  private volatile boolean committed;

  private Throwable exception;

  private int status = 200;

  private String statusMessage;

  private Writer writer;

  public void clearHeaders() {
    headers.clear();
  }

  public boolean containsHeader(String name) {
    String key = name.toLowerCase();
    return headers.containsKey(key) && headers.get(key).size() > 0;
  }

  public boolean failure() {
    return status < 200 || status > 299;
  }

  public byte[] getBody() {
    return body;
  }

  public void  setBody(byte[] body) {
    this.body = body;
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

  public String getContentType() {
    return getHeader(Headers.ContentType);
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
   * @return If the connection should be kept open (keep-alive) or not. The default is to return the Connection: keep-alive header, which
   *     this method does.
   */
  public boolean isKeepAlive() {
    String value = getHeader(Headers.Connection);
    return value == null || value.equalsIgnoreCase(Connections.KeepAlive);
  }
}

