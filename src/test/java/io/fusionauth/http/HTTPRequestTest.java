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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.server.HTTPRequest;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

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
  public void hostPortHandling() {
    record caseRecord(String scheme, String source, String host, int port, String baseUrl) {
    }
    List<caseRecord> testCases = new ArrayList<>();
    testCases.add(new caseRecord("http", "myhost", "myhost", -1, "http://myhost"));
    testCases.add(new caseRecord("https", "myhost", "myhost", -1, "https://myhost"));
    testCases.add(new caseRecord("http", "myhost:80", "myhost", 80, "http://myhost"));
    testCases.add(new caseRecord("https", "myhost:80", "myhost", 80, "https://myhost:80"));
    testCases.add(new caseRecord("http", "myhost:443", "myhost", 443, "http://myhost:443"));
    testCases.add(new caseRecord("https", "myhost:443", "myhost", 443, "https://myhost"));
    testCases.add(new caseRecord("http", "myhost:9011", "myhost", 9011, "http://myhost:9011"));
    testCases.add(new caseRecord("https", "myhost:9011", "myhost", 9011, "https://myhost:9011"));

    for (caseRecord aCase : testCases) {
      HTTPRequest request = new HTTPRequest();
      request.setScheme(aCase.scheme());
      request.addHeader(Headers.HostLower, aCase.source());
      assertEquals(request.getHost(), aCase.host());
      assertEquals(request.getPort(), aCase.port());
      assertEquals(request.getBaseURL(), aCase.baseUrl());
    }
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
}
