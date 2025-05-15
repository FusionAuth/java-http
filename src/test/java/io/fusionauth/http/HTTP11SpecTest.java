/*
 * Copyright (c) 2025, FusionAuth, All Rights Reserved
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

import java.net.Socket;
import java.nio.charset.StandardCharsets;

import io.fusionauth.http.server.HTTPServer;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * Tests the HTTP server for HTTP 1.1. spec compliance.
 *
 * @author Daniel DeGroff
 */
public class HTTP11SpecTest extends BaseTest {
  @Test
  public void duplicate_host_header() throws Exception {
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Host: cyberdyne-systems.com\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void host_required() throws Exception {
    //Host header is required, return 400 if not provided
    withRequest("""
        GET / HTTP/1.1\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Ensure that X-Forwarded-Host doesn't count for the Host header
    withRequest("""
        GET / HTTP/1.1\r
        X-Forwarded-Host: cyberdyne-systems.com\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void invalid_content_length() throws Exception {
    // Too large, we will take a NumberFormatException and set this value to null in the request.
    // - So as it is written, we won't return the user an error, but we will assume no body is present.
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Length: 9223372036854775808\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 200 \r
        connection: keep-alive\r
        content-length: 0\r
        \r
        """);

    // Negative
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Length: -1\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Max Long - but negative
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Length: -9223372036854775807\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void invalid_version() throws Exception {
    // We should be returning 505 unless we see HTTP/1.1

    // Invalid: HTTP/1
    withRequest("""
        GET / HTTP/1\r
        Host: cyberdyne-systems.com\r
        \r
        """
    ).expectResponse(
        """
            HTTP/1.1 505 \r
            connection: close\r
            content-length: 0\r
            \r
            """);

    // Invalid: HTTP/1.
    withRequest("""
        GET / HTTP/1.\r
        Host: cyberdyne-systems.com\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 505 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Invalid: HTTP/1.0
    withRequest("""
        GET / HTTP/1.0\r
        Host: cyberdyne-systems.com\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 505 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Invalid: HTTP/1.2
    withRequest("""
        GET / HTTP/1.2\r
        Host: cyberdyne-systems.com\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 505 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Invalid: HTTP/9.9
    withRequest("""
        GET / HTTP/9.9\r
        Host: cyberdyne-systems.com\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 505 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void mangled_version() throws Exception {
    // HTTP reversed, does not begin with HTTP/
    withRequest("""
        GET / PTTH/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Repeated allowed characters, does not begin with HTTP/
    withRequest("""
        GET / HHHH/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void missing_protocol() throws Exception {
    withRequest("""
        GET /\r
        Host: cyberdyne-systems.com\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  private void assertResponse(String request, String response) throws Exception {
    try (HTTPServer ignore = makeServer("http", (req, res) -> res.setStatus(200)).start();
         Socket sock = makeClientSocket("http")) {
      var os = sock.getOutputStream();
      os.write(request.getBytes(StandardCharsets.UTF_8));
      os.flush();
      var resp = new String(sock.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(resp, response, "HTTP request:\n" + request + "\n");
    }
  }

  private Builder withRequest(String request) {
    return new Builder(request);
  }

  private class Builder {
    public String request;

    public Builder(String request) {
      this.request = request;
    }

    public void expectResponse(String response) throws Exception {
      assertResponse(request, response);
    }
  }
}
