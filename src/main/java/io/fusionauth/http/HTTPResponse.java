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
package io.fusionauth.http;

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

import io.fusionauth.http.HTTPValues.Status;

/**
 * Default class for HTTPResponse.
 *
 * @author Brian Pontarelli
 */
public class HTTPResponse {
  private final Map<String, Map<String, Cookie>> cookies = new HashMap<>(); // <Path, <Name, Cookie>>

  private final Map<String, List<String>> headers = new HashMap<>();

  private final OutputStream outputStream;

  private Throwable exception;

  private int status;

  public HTTPResponse(OutputStream outputStream) {
    this.outputStream = outputStream;
  }

  public void addCookie(Cookie cookie) {
    String path = cookie.path != null ? cookie.path : "/";
    cookies.computeIfAbsent(path, key -> new HashMap<>()).put(cookie.name, cookie);
  }

  public void addHeader(String name, String value) {
    String key = name.toLowerCase();
    headers.putIfAbsent(key, new ArrayList<>());
    headers.get(key).add(value);
  }

  public boolean containsHeader(String name) {
    String key = name.toLowerCase();
    return headers.containsKey(key) && headers.get(key).size() > 0;
  }

  public boolean failure() {
    return status < 200 || status > 299;
  }

  public Charset getCharset() {
    Charset charset = StandardCharsets.UTF_8;
    String contentType = getContentType();
    if (contentType != null) {
      String lower = contentType.toLowerCase();
      int index = lower.indexOf("charset=");
      if (index > 0) {
        int colon = lower.indexOf(';', index);
        if (colon < 0) {
          colon = lower.length();
        }

        charset = Charset.forName(contentType.substring(index, colon));
      }
    }

    return charset;
  }

  public Long getContentLength() {
    if (containsHeader("Content-Length")) {
      return Long.parseLong(getHeader("Content-Length"));
    }

    return null;
  }

  public void setContentLength(Long length) {
    setHeader("Content-Length", length.toString());
  }

  public String getContentType() {
    if (containsHeader("Content-Type")) {
      return getHeader("Content-Type");
    }

    return null;
  }

  public void setContentType(String contentType) {
    setHeader("Content-Type", contentType);
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
    return getHeader("Location");
  }

  public int getStatus() {
    return status;
  }

  public void setStatus(int status) {
    this.status = status;
  }

  public Writer getWriter() {
    Charset charset = getCharset();
    return new OutputStreamWriter(getOutputStream(), charset);
  }

  public void removeCookie(String name) {
    cookies.values().forEach(map -> map.remove(name));
  }

  public void sendRedirect(String uri) {
    setHeader("Location", uri);
    status = Status.MovedTemporarily;
  }

  public void setDateHeader(String name, ZonedDateTime value) {
    addHeader(name, DateTimeFormatter.RFC_1123_DATE_TIME.format(value));
  }

  public void setHeader(String name, String value) {
    if (name == null || value == null) {
      return;
    }

    addHeader(name, value);
  }
}

