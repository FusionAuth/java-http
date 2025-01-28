/*
 * Copyright (c) 2022-2025, FusionAuth, All Rights Reserved
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.server.HTTPRequest;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Tests the HTTPRequest.
 *
 * @author Brian Pontarelli
 */
public class HTTPRequestTest {
  @Test
  public void acceptEncoding() {
    HTTPRequest request = new HTTPRequest();
    request.addHeader(Headers.AcceptEncoding, "foo, bar  , baz");
    assertEquals(request.getAcceptEncodings(), List.of("foo", "bar", "baz"));
  }

  @Test
  public void decodeHeaders() {
    HTTPRequest request = new HTTPRequest();
    request.addHeader("coNTent-LENGTH", "42");
    request.addHeader("coNTent-type", "multipart/form-data; boundary=--foobarbaz");
    assertTrue(request.isMultipart());
    assertEquals(request.getMultipartBoundary(), "--foobarbaz");
    assertEquals(request.getContentLength(), (Long) 42L);

    request.addHeader("coNTent-type", "text/html; charset=UTF-8");
    assertEquals(request.getCharacterEncoding(), StandardCharsets.UTF_8);
  }

  @Test
  public void getBaseURL() {
    // Use case 1: missing X-Forwarded-Port, infer it from https
    assertBaseURL("https://acme.com",
        "X-Forwarded-Host", "acme.com",
        "X-Forwarded-Proto", "https");

    // Use case 2: Set a port on the Host, we will use that.
    assertBaseURL("https://acme.com:8192",
        "X-Forwarded-Host", "acme.com:8192",
        "X-Forwarded-Proto", "https");

    // Use case 3: Set port from the X-Forwarded-Port header
    assertBaseURL("https://acme.com",
        "X-Forwarded-Host", "acme.com",
        "X-Forwarded-Port", "443",
        "X-Forwarded-Proto", "https");

    // Use case 4: Set port from the X-Forwarded-Port header, non-standard
    assertBaseURL("https://acme.com:8192",
        "X-Forwarded-Host", "acme.com",
        "X-Forwarded-Port", "8192",
        "X-Forwarded-Proto", "https");

    // Use case 5: Missing X-Forwarded-Proto header, cannot infer 443
    assertBaseURL("http://acme.com:8080",
        "X-Forwarded-Host", "acme.com");

    // Use case 6: Malformed X-Forwarded-Host header, so we'll ignore the port on the -Host header.
    assertBaseURL("https://acme.com:8080",
        "X-Forwarded-Host", "acme.com:##",
        "X-Forwarded-Proto", "https");

    // Use case 7: Missing X-Forwarded-Host
    assertBaseURL("https://localhost:8080",
        "X-Forwarded-Proto", "https");

    // Use case 8: http and port 80
    assertBaseURL("https://localhost",
        request -> request.setPort(80),
        "X-Forwarded-Proto", "https");

    // Use case 9: https and port 80
    assertBaseURL("http://localhost",
        request -> {
          request.setPort(80);
          request.setScheme("https");
        },
        "X-Forwarded-Proto", "http");
  }

  @Test
  public void getHost() {
    // Single host
    assertGetHost("acme.com", "acme.com");

    // Single host with port
    assertGetHost("acme.com:42", "acme.com");

    // Multiple hosts
    assertGetHost("acme.com, example.com", "acme.com");

    // Multiple hosts with spacing
    assertGetHost("acme.com ,  example.com ", "acme.com");

    // Multiple hosts with spacing and ports
    assertGetHost("acme.com:41 ,  example.com:42 ", "acme.com");
  }

  @Test
  public void getIPAddress() {
    // Single IP
    assertGetIPAddress("192.168.1.1", "192.168.1.1");

    // Multiple IPs
    assertGetIPAddress("192.168.1.1, 192.168.1.2", "192.168.1.1");

    // Multiple IPs with spacing
    assertGetIPAddress("192.168.1.1 ,  192.168.1.2 ", "192.168.1.1");
  }

  @Test
  public void hostHeaderPortHandling() {
    // positive cases
    assertURLs("http", "myhost", "myhost", -1, "http://myhost");
    assertURLs("https", "myhost", "myhost", -1, "https://myhost");
    assertURLs("http", "myhost:80", "myhost", 80, "http://myhost");
    assertURLs("https", "myhost:80", "myhost", 80, "https://myhost:80");
    assertURLs("http", "myhost:443", "myhost", 443, "http://myhost:443");
    assertURLs("https", "myhost:443", "myhost", 443, "https://myhost");
    assertURLs("http", "myhost:9011", "myhost", 9011, "http://myhost:9011");
    assertURLs("https", "myhost:9011", "myhost", 9011, "https://myhost:9011");
    // negative cases
    assertURLs("http", "myhost:abc", "myhost", -1, "http://myhost");
    assertURLs("https", "myhost:abc", "myhost", -1, "https://myhost");
    assertURLs("https", "otherhost:abc  ", "otherhost", -1, "https://otherhost");
  }

  @Test
  public void queryString() {
    HTTPRequest request = new HTTPRequest();
    request.setPath("/path?name=value");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getURLParameters(), Map.of("name", List.of("value")));

    request.setPath("/path?name=value&name1=value1");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getURLParameters(), Map.of("name", List.of("value"), "name1", List.of("value1")));

    request.setPath("/path?name+space=value+space&name1+space=value1+space");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getURLParameters(), Map.of("name space", List.of("value space"), "name1 space", List.of("value1 space")));

    request.setPath("/path?name");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getURLParameters(), Map.of());

    request.setPath("/path?name==");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getURLParameters(), Map.of("name", List.of("=")));

    request.setPath("/path?==");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getURLParameters(), Map.of());

    request.setPath("/path?==name=value");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getURLParameters(), Map.of("name", List.of("value")));

    request.setPath("/path?name=a=b=c");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getURLParameters(), Map.of("name", List.of("a=b=c")));

    request.setPath("/path?name&&");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getURLParameters(), Map.of());

    request.setPath("/path?name=&");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getURLParameters(), Map.of("name", List.of("")));

    request.setPath("/path?name=%26&");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getURLParameters(), Map.of("name", List.of("&")));

    request.setPath("/path?name%3D=%26&");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getURLParameters(), Map.of("name=", List.of("&")));

    request.setPath("/path?name=");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getURLParameters(), Map.of("name", List.of("")));
  }

  private void assertBaseURL(String expected, Consumer<HTTPRequest> consumer, String... headers) {
    HTTPRequest request = new HTTPRequest();

    request.setScheme("http");
    request.setHost("localhost");
    request.setPort(8080);

    if (consumer != null) {
      consumer.accept(request);
    }

    if (headers.length % 2 != 0) {
      fail("You need to provide pairs.");
    }

    for (int i = 0; i < headers.length; i = i + 2) {
      request.setHeader(headers[i], headers[i + 1]);
    }

    assertEquals(request.getBaseURL(), expected);
  }

  private void assertBaseURL(String expected, String... headers) {
    assertBaseURL(expected, null, headers);
  }

  private void assertGetHost(String header, @SuppressWarnings("SameParameterValue") String expected) {
    HTTPRequest request = new HTTPRequest();
    request.setHeader(Headers.XForwardedHost, header);
    assertEquals(request.getHost(), expected);
  }

  private void assertGetIPAddress(String header, @SuppressWarnings("SameParameterValue") String expected) {
    HTTPRequest request = new HTTPRequest();
    request.setHeader(Headers.XForwardedFor, header);
    assertEquals(request.getIPAddress(), expected);
  }

  private void assertURLs(String scheme, String source, String host, int port, String baseURL) {
    HTTPRequest request = new HTTPRequest();
    request.setScheme(scheme);
    request.addHeader(Headers.HostLower, source);
    assertEquals(request.getHost(), host);
    assertEquals(request.getPort(), port);
    assertEquals(request.getBaseURL(), baseURL);
  }
}
