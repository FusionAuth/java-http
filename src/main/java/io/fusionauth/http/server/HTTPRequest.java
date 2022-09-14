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
package io.fusionauth.http.server;

import java.io.InputStream;
import java.net.URLDecoder;
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
import java.util.Objects;

import io.fusionauth.http.Buildable;
import io.fusionauth.http.Cookie;
import io.fusionauth.http.HTTPMethod;
import io.fusionauth.http.HTTPValues.ContentTypes;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.HTTPValues.TransferEncodings;

/**
 * An HTTP request that is received by the HTTP server. This contains all the relevant information from the request including any file
 * uploads and the InputStream that the server can read from to handle the HTTP body.
 * <p>
 * This is mutable because the server is not trying to enforce that the request is always the same as the one it received. There are many
 * cases where requests values are mutated, removed, or replaced. Rather than using a janky delegate or wrapper, this is simply mutable.
 *
 * @author Brian Pontarelli
 */
public class HTTPRequest implements Buildable<HTTPRequest> {
  private final Map<String, Cookie> cookies = new HashMap<>();

  private final Map<String, List<String>> headers = new HashMap<>();

  private final List<Locale> locales = new ArrayList<>();

  private final Map<String, List<String>> parameters = new HashMap<>(0);

  private List<String> acceptEncoding;

  private Long contentLength;

  private String contentType;

  private Charset encoding = StandardCharsets.UTF_8;

  private String host;

  private InputStream inputStream;

  private String ipAddress;

  private HTTPMethod method;

  private Boolean multipart;

  private String multipartBoundary;

  private String path = "/";

  private int port = -1;

  private String protocol;

  private String scheme;

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

  public void addHeader(String name, String value) {
    name = name.toLowerCase();
    headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
    decodeHeader(name, value);
  }

  public void addHeaders(String name, String... values) {
    name = name.toLowerCase();
    headers.computeIfAbsent(name, key -> new ArrayList<>()).addAll(List.of(values));

    for (String value : values) {
      decodeHeader(name, value);
    }
  }

  public void addHeaders(String name, Collection<String> values) {
    name = name.toLowerCase();
    headers.computeIfAbsent(name, key -> new ArrayList<>()).addAll(values);

    for (String value : values) {
      decodeHeader(name, value);
    }
  }

  public void addHeaders(Map<String, List<String>> params) {
    params.forEach(this::addHeaders);
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
    params.forEach(this::addParameters);
  }

  public void deleteCookie(String name) {
    cookies.remove(name);
  }

  public List<String> getAcceptEncoding() {
    if (acceptEncoding == null) {
      acceptEncoding = new ArrayList<>();

      String header = getHeader(Headers.AcceptEncoding);
      if (header == null || header.isBlank()) {
        return acceptEncoding;
      }

      String[] values = header.split("\\s*,\\s*");
      for (String value : values) {
        if (value.isBlank()) {
          continue;
        }

        acceptEncoding.add(value.trim());
      }
    }

    return acceptEncoding;
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

  public String getHeader(String key) {
    List<String> values = getHeaders(key);
    return values != null && values.size() > 0 ? values.get(0) : null;
  }

  public List<String> getHeaders(String key) {
    return headers.get(key.toLowerCase());
  }

  public Map<String, List<String>> getHeaders() {
    return headers;
  }

  public void setHeaders(Map<String, List<String>> parameters) {
    this.headers.clear();
    parameters.forEach(this::setHeaders);
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

  public InputStream getInputStream() {
    return inputStream;
  }

  public void setInputStream(InputStream inputStream) {
    this.inputStream = inputStream;
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
    parameters.clear();

    // Parse the parameters
    char[] chars = path.toCharArray();
    int questionMark = path.indexOf('?');
    if (questionMark > 0 && questionMark != path.length() - 1) {
      int start = questionMark + 1;
      boolean inName = true;
      String name = null;
      String value;
      for (int i = start; i < chars.length; i++) {
        if (chars[i] == '=' && inName) {
          // Names can't start with an equal sign
          if (i == start) {
            start++;
            continue;
          }

          inName = false;

          try {
            name = URLDecoder.decode(new String(chars, start, i - start), StandardCharsets.UTF_8);
          } catch (Exception e) {
            name = null; // Malformed
          }

          start = i + 1;
        } else if (chars[i] == '&' && !inName) {
          inName = true;

          if (name == null || start > i) {
            continue; // Malformed
          }

          try {
            if (start < i) {
              value = URLDecoder.decode(new String(chars, start, i - start), StandardCharsets.UTF_8);
            } else {
              value = "";
            }

            addParameter(name, value);
          } catch (Exception e) {
            // Ignore
          }

          start = i + 1;
          name = null;
        }
      }

      if (name != null && !inName) {
        if (start < chars.length) {
          value = URLDecoder.decode(new String(chars, start, chars.length - start), StandardCharsets.UTF_8);
        } else {
          value = "";
        }

        addParameter(name, value);
      }
    }

    // Only save the path portion
    this.path = questionMark > 0 ? new String(chars, 0, questionMark) : path;
  }

  public int getPort() {
    String xPort = getHeader(Headers.XForwardedPort);
    return xPort == null ? port : Integer.parseInt(xPort);
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getProtocol() {
    return protocol;
  }

  public void setProtocol(String protocol) {
    this.protocol = protocol;
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

  public boolean isChunked() {
    return getTransferEncoding() != null && getTransferEncoding().equalsIgnoreCase(TransferEncodings.Chunked);
  }

  public boolean isMultipart() {
    return multipart;
  }

  public void removeHeader(String name) {
    headers.remove(name.toLowerCase());
  }

  public void removeHeader(String name, String... values) {
    List<String> actual = headers.get(name.toLowerCase());
    if (actual != null) {
      actual.removeAll(List.of(values));
    }
  }

  public void setHeader(String name, String value) {
    name = name.toLowerCase();
    this.headers.put(name, new ArrayList<>(List.of(value)));
    decodeHeader(name, value);
  }

  public void setHeaders(String name, String... values) {
    name = name.toLowerCase();
    this.headers.put(name, new ArrayList<>(List.of(values)));

    for (String value : values) {
      decodeHeader(name, value);
    }
  }

  public void setHeaders(String name, Collection<String> values) {
    name = name.toLowerCase();
    this.headers.put(name, new ArrayList<>(values));

    for (String value : values) {
      decodeHeader(name, value);
    }
  }

  public void setParameter(String name, String value) {
    setParameters(name, value);
  }

  public void setParameters(String name, String... values) {
    setParameters(name, new ArrayList<>(List.of(values)));
  }

  public void setParameters(String name, Collection<String> values) {
    List<String> list = new ArrayList<>();
    this.parameters.put(name, list);

    values.stream()
          .filter(Objects::nonNull)
          .forEach(list::add);
  }

  private void decodeHeader(String name, String value) {
    switch (name) {
      case Headers.ContentTypeLower:
        this.contentType = value;
        this.multipart = contentType.toLowerCase().startsWith("multipart/");

        if (multipart) {
          int index = contentType.indexOf(ContentTypes.Boundary);
          this.multipartBoundary = contentType.substring(index + ContentTypes.Boundary.length());

          // Strip quotes if needed
          int length = multipartBoundary.length();
          if (multipartBoundary.charAt(0) == '"' && multipartBoundary.charAt(length - 1) == '"') {
            multipartBoundary = multipartBoundary.substring(1, length - 1);
          }
        }
        break;
      case Headers.ContentLengthLower:
        if (value == null || value.isBlank()) {
          contentLength = null;
        } else {
          try {
            contentLength = Long.parseLong(value);
          } catch (NumberFormatException e) {
            contentLength = null;
          }
        }
        break;
      case Headers.CookieLower:
        addCookies(Cookie.fromRequestHeader(value));
        break;
    }
  }
}
