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
package io.fusionauth.http;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import io.fusionauth.http.HTTPValues.Headers;

public class HTTPRequest implements Buildable<HTTPRequest> {
  public final Map<String, Cookie> cookies = new HashMap<>();

  public final List<FileInfo> files = new ArrayList<>();

  public final Map<String, List<String>> headers = new HashMap<>();

  public final List<Locale> locales = new ArrayList<>();

  public final Map<String, List<String>> parameters = new HashMap<>(0);

  public ByteBuffer body;

  public Long contentLength;

  public String contentType;

  public Charset encoding = StandardCharsets.UTF_8;

  public String host;

  public String ipAddress;

  public HTTPMethod method;

  public Boolean multipart;

  public String multipartBoundary;

  public String path = "/";

  public int port = -1;

  public String protocl;

  public String queryString;

  public String scheme;

  public void addCookies(Cookie... cookies) {
    for (Cookie cookie : cookies) {
      this.cookies.put(cookie.name, cookie);
    }
  }

  public void addCookies(Collection<Cookie> cookies) {
    if (cookies == null) {
      return;
    }

    for (Cookie cookie : cookies) {
      this.cookies.put(cookie.name, cookie);
    }
  }

  public void addFile(FileInfo fileInfo) {
    this.files.add(fileInfo);
  }

  public void addHeader(String name, String value) {
    headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
  }

  public void addHeaders(String name, String... values) {
    headers.computeIfAbsent(name, key -> new ArrayList<>()).addAll(List.of(values));
  }

  public void addHeaders(String name, Collection<String> values) {
    headers.computeIfAbsent(name, key -> new ArrayList<>()).addAll(values);
  }

  public void addHeaders(Map<String, List<String>> params) {
    for (String name : params.keySet()) {
      headers.put(name, params.get(name));
    }
  }

  public void addLocales(Locale... locales) {
    this.locales.addAll(Arrays.asList(locales));
  }

  public void addLocales(Collection<Locale> locales) {
    this.locales.addAll(locales);
  }

  public void addParameter(String name, String value) {
    parameters.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
  }

  public void addParameters(String name, String... values) {
    parameters.computeIfAbsent(name, key -> new ArrayList<>()).addAll(List.of(values));
  }

  public void addParameters(String name, Collection<String> values) {
    parameters.computeIfAbsent(name, key -> new ArrayList<>()).addAll(values);
  }

  public void addParameters(Map<String, List<String>> params) {
    for (String name : params.keySet()) {
      parameters.put(name, params.get(name));
    }
  }

  public void deleteCookie(String name) {
    cookies.remove(name);
  }

  public String getBaseURL() {
    // Setting the wrong value in the X-Forwarded-Proto header seems to be a common issue that causes an exception during URI.create. Assuming request.getScheme() is not the problem and it is related to the proxy configuration.
    String scheme = getScheme().toLowerCase();
    if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
      throw new IllegalArgumentException("The request scheme is invalid. Only http or https are valid schemes. The X-Forwarded-Proto header has a value of [" + getHeader(Headers.XForwardedProto) + "], this is likely an issue in your proxy configuration.");
    }

    String serverName = getHost().toLowerCase();
    int serverPort = getPort();
    // Ignore port 80 for http
    if (getScheme().equalsIgnoreCase("http") && serverPort == 80) {
      serverPort = -1;
    }

    String uri = scheme + "://" + serverName;
    if (serverPort > 0) {
      if ((scheme.equalsIgnoreCase("http") && serverPort != 80) || (scheme.equalsIgnoreCase("https") && serverPort != 443)) {
        uri += ":" + serverPort;
      }
    }

    return uri;
  }

  public ByteBuffer getBody() {
    return body;
  }

  public void setBody(ByteBuffer body) {
    this.body = body;
    this.contentLength = (long) body.position();
  }

  public Charset getCharacterEncoding() {
    return encoding;
  }

  public void setCharacterEncoding(Charset encoding) {
    this.encoding = encoding;
  }

  public Long getContentLength() {
    return contentLength;
  }

  public void setContentLength(Long contentLength) {
    this.contentLength = contentLength;
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(String contentType) {
    this.contentType = contentType;
  }

  public Cookie getCookie(String name) {
    return cookies.get(name);
  }

  public List<Cookie> getCookies() {
    return new ArrayList<>(cookies.values());
  }

  public Instant getDateHeader(String key) {
    String header = getHeader(key);
    return header != null ? ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() : null;
  }

  public List<FileInfo> getFiles() {
    return files;
  }

  public String getHeader(String key) {
    List<String> values = getHeaders(key);
    return values != null && values.size() > 0 ? values.get(0) : null;
  }

  public List<String> getHeaders(String key) {
    return headers.entrySet()
                  .stream()
                  .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                  .map(Entry::getValue)
                  .findFirst()
                  .orElse(null);
  }

  public Map<String, List<String>> getHeadersMap() {
    return headers;
  }

  public String getHost() {
    String xHost = getHeader(Headers.XForwardedHost);
    return xHost == null ? host : xHost;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public String getIPAddress() {
    String xIPAddress = getHeader(Headers.XForwardedFor);
    if (xIPAddress == null || xIPAddress.trim().length() == 0) {
      return ipAddress;
    }

    String[] ips = xIPAddress.split(",");
    if (ips.length < 1) {
      return xIPAddress.trim();
    }

    return ips[0].trim();
  }

  public void setIPAddress(String ipAddress) {
    this.ipAddress = ipAddress;
  }

  public Locale getLocale() {
    return locales.size() > 0 ? locales.get(0) : Locale.getDefault();
  }

  public List<Locale> getLocales() {
    return locales;
  }

  public HTTPMethod getMethod() {
    return method;
  }

  public void setMethod(HTTPMethod method) {
    this.method = method;
  }

  public String getMultipartBoundary() {
    if (isMultipart() && multipartBoundary == null) {
      String contentType = getContentType();
      int index = contentType.indexOf("boundary=");
      multipartBoundary = contentType.substring(index);

      // Strip quotes if needed
      int length = multipartBoundary.length();
      if (multipartBoundary.charAt(0) == '"' && multipartBoundary.charAt(length - 1) == '"') {
        multipartBoundary = multipartBoundary.substring(1, length - 1);
      }
    }

    return multipartBoundary;
  }

  public String getParameterValue(String key) {
    List<String> values = parameters.get(key);
    return (values != null && values.size() > 0) ? values.get(0) : null;
  }

  public List<String> getParameterValues(String key) {
    return parameters.get(key);
  }

  public Map<String, List<String>> getParameters() {
    return parameters;
  }

  public void setParameters(Map<String, List<String>> parameters) {
    this.parameters.clear();
    this.parameters.putAll(parameters);
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public int getPort() {
    String xPort = getHeader(Headers.XForwardedPort);
    return xPort == null ? port : Integer.parseInt(xPort);
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getQueryString() {
    return queryString;
  }

  public void setQueryString(String queryString) {
    this.queryString = queryString;
  }

  public String getScheme() {
    String xScheme = getHeader(Headers.XForwardedProto);
    return xScheme == null ? scheme : xScheme;
  }

  public void setScheme(String scheme) {
    this.scheme = scheme;
  }

  public String getTransferEncoding() {
    return getHeader(Headers.TransferEncoding);
  }

  public boolean isMultipart() {
    if (multipart == null) {
      String contentType = getContentType().toLowerCase();
      multipart = contentType.startsWith("multipart/");
    }

    return multipart;
  }

  public void removeHeader(String name) {
    headers.remove(name);
  }

  public void removeHeader(String name, String... values) {
    List<String> actual = headers.get(name);
    if (actual != null) {
      actual.removeAll(List.of(values));
    }
  }

  public void setHeader(String name, String value) {
    this.headers.put(name, new ArrayList<>(List.of(value)));
  }

  public void setHeaders(String name, String... values) {
    this.headers.put(name, new ArrayList<>(List.of(values)));
  }

  public void setHeaders(String name, Collection<String> values) {
    this.headers.put(name, new ArrayList<>(values));
  }

  public void setHeaders(Map<String, List<String>> parameters) {
    this.headers.clear();
    this.headers.putAll(parameters);
  }

  public void setParameter(String name, String value) {
    setParameters(name, value);
  }

  public void setParameters(String name, String... values) {
    setParameters(name, Arrays.asList(values));
  }

  public void setParameters(String name, Collection<String> values) {
    List<String> list = new ArrayList<>();
    this.parameters.put(name, list);

    values.stream()
          .filter(Objects::nonNull)
          .forEach(list::add);
  }
}
