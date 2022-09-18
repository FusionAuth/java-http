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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Locale.LanguageRange;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import io.fusionauth.http.Buildable;
import io.fusionauth.http.Cookie;
import io.fusionauth.http.HTTPMethod;
import io.fusionauth.http.HTTPValues.ContentTypes;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.HTTPValues.TransferEncodings;
import io.fusionauth.http.body.BodyException;
import io.fusionauth.http.io.MultipartStream;
import io.fusionauth.http.util.HTTPTools;
import io.fusionauth.http.util.HTTPTools.HeaderValue;

/**
 * An HTTP request that is received by the HTTP server. This contains all the relevant information from the request including any file
 * uploads and the InputStream that the server can read from to handle the HTTP body.
 * <p>
 * This is mutable because the server is not trying to enforce that the request is always the same as the one it received. There are many
 * cases where requests values are mutated, removed, or replaced. Rather than using a janky delegate or wrapper, this is simply mutable.
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("unused")
public class HTTPRequest implements Buildable<HTTPRequest> {
  private final Map<String, Cookie> cookies = new HashMap<>();

  private final List<FileInfo> files = new LinkedList<>();

  private final Map<String, List<String>> headers = new HashMap<>();

  private final List<Locale> locales = new LinkedList<>();

  private final Map<String, List<String>> urlParameters = new HashMap<>();

  private List<String> acceptEncoding;

  private byte[] bodyBytes;

  private Map<String, List<String>> combinedParameters;

  private Long contentLength;

  private String contentType;

  private Charset encoding = StandardCharsets.UTF_8;

  private Map<String, List<String>> formData;

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

  public void addURLParameter(String name, String value) {
    urlParameters.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
    combinedParameters = null;
  }

  public void addURLParameters(String name, String... values) {
    urlParameters.computeIfAbsent(name, key -> new ArrayList<>()).addAll(List.of(values));
    combinedParameters = null;
  }

  public void addURLParameters(String name, Collection<String> values) {
    urlParameters.computeIfAbsent(name, key -> new ArrayList<>()).addAll(values);
    combinedParameters = null;
  }

  public void addURLParameters(Map<String, List<String>> params) {
    params.forEach(this::addURLParameters);
    combinedParameters = null;
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
    // Setting the wrong value in the X-Forwarded-Proto header seems to be a common issue that causes an exception during URI.create.
    // Assuming request.getScheme() is not the problem, and it is related to the proxy configuration.
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

  public byte[] getBodyBytes() throws BodyException {
    if (bodyBytes == null && inputStream != null) {
      try {
        bodyBytes = inputStream.readAllBytes();
      } catch (IOException e) {
        throw new BodyException("Unable to read the HTTP request body bytes", e);
      }
    } else {
      bodyBytes = new byte[0];
    }

    return bodyBytes;
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

  /**
   * Processes the HTTP request body completely by calling {@link #getFormData()}. If the {@code Content-Type} header is multipart, then the
   * processing of the body will extract the files.
   *
   * @return The files, if any.
   */
  public List<FileInfo> getFiles() {
    getFormData();
    return files;
  }

  /**
   * Processes the HTTP request body completely if the {@code Content-Type} header is equal to {@link ContentTypes#Form}. If this method is
   * called multiple times, the body is only processed the first time. This is not thread-safe, so you need to ensure you protect against
   * multiple threads calling this method concurrently.
   * <p>
   * If the {@code Content-Type} is not {@link ContentTypes#Form}, this will always return an empty Map.
   * <p>
   * If the InputStream is not ready or complete, this will block until all the bytes are read from the client.
   *
   * @return The Form data body.
   */
  public Map<String, List<String>> getFormData() {
    if (formData == null) {
      formData = new HashMap<>();

      String contentType = getContentType();
      if (contentType.equalsIgnoreCase(ContentTypes.Form)) {
        byte[] body = getBodyBytes();
        HTTPTools.parseEncodedData(body, 0, body.length, formData);
      } else if (isMultipart()) {
        MultipartStream stream = new MultipartStream(inputStream, getMultipartBoundary().getBytes(), 1024);
        try {
          stream.process(formData, files);
        } catch (IOException e) {
          throw new BodyException("Invalid multipart body.", e);
        }
      }
    }

    return formData;
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
    combinedParameters = null;
    formData = null;
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

  /**
   * Combines the URL parameters and the form data that might exist in the body of the HTTP request. The Map returned is not linked back to
   * the URL parameters or form data. Changing it will not impact either of those Maps. If this method is called multiple times, the merging
   * of all the data is only done the first time and then cached. This is not thread-safe, so you need to ensure you protect against
   * multiple threads calling this method concurrently.
   *
   * @return The combined parameters.
   */
  public Map<String, List<String>> getParameters() {
    if (combinedParameters == null) {
      combinedParameters = new HashMap<>();
      getURLParameters().forEach((name, values) -> combinedParameters.put(name, new LinkedList<>(values)));
      getFormData().forEach((name, value) -> combinedParameters.merge(name, value, (first, second) -> {
        first.addAll(second);
        return first;
      }));
    }

    return combinedParameters;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    urlParameters.clear();

    // Parse the parameters
    byte[] chars = path.getBytes(StandardCharsets.UTF_8);
    int questionMark = path.indexOf('?');
    if (questionMark > 0 && questionMark != path.length() - 1) {
      HTTPTools.parseEncodedData(chars, questionMark + 1, chars.length, urlParameters);
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

  public String getURLParameter(String key) {
    List<String> values = urlParameters.get(key);
    return (values != null && values.size() > 0) ? values.get(0) : null;
  }

  public List<String> getURLParameters(String key) {
    return urlParameters.get(key);
  }

  public Map<String, List<String>> getURLParameters() {
    return urlParameters;
  }

  public void setURLParameters(Map<String, List<String>> parameters) {
    this.urlParameters.clear();
    this.urlParameters.putAll(parameters);
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

  public void setURLParameter(String name, String value) {
    setURLParameters(name, value);
  }

  public void setURLParameters(String name, String... values) {
    setURLParameters(name, new ArrayList<>(List.of(values)));
  }

  public void setURLParameters(String name, Collection<String> values) {
    List<String> list = new ArrayList<>();
    this.urlParameters.put(name, list);

    values.stream()
          .filter(Objects::nonNull)
          .forEach(list::add);

    combinedParameters = null;
  }

  private void decodeHeader(String name, String value) {
    switch (name) {
      case Headers.AcceptLanguage:
        addLocales(LanguageRange.parse(value) // Default to English
                                .stream()
                                .sorted(Comparator.comparing(LanguageRange::getWeight).reversed())
                                .map(LanguageRange::getRange)
                                .map(Locale::forLanguageTag)
                                .collect(Collectors.toList()));
        break;
      case Headers.ContentTypeLower:
        this.encoding = null;
        this.multipart = false;

        HeaderValue headerValue = HTTPTools.parseHeaderValue(value);
        this.contentType = headerValue.value();

        if (headerValue.value().startsWith(ContentTypes.MultipartPrefix)) {
          this.multipart = true;
          this.multipartBoundary = headerValue.parameters().get(ContentTypes.BoundaryParameter);
        }

        String charset = headerValue.parameters().get(ContentTypes.CharsetParameter);
        if (charset != null) {
          this.encoding = Charset.forName(charset);
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
