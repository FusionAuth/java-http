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

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import io.fusionauth.http.HTTPValues.Connections;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.log.Level;
import io.fusionauth.http.log.SystemOutLoggerFactory;
import io.fusionauth.http.server.CountingInstrumenter;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServer;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.fail;

/**
 * Tests the HTTP server.
 *
 * @author Brian Pontarelli
 */
public class CoreTest {
  public static final String ExpectedResponse = "{\"version\":\"42\"}";

  public static final String RequestBody = "{\"message\":\"Hello World\"";

  static {
    System.setProperty("sun.net.http.retryPost", "false");
    System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection");
    SystemOutLoggerFactory.FACTORY.getLogger(CoreTest.class).setLevel(Level.Info);
  }

  @Test
  public void clientTimeout() {
    HTTPHandler handler = (req, res) -> {
      System.out.println("Handling");
      res.setStatus(200);
      res.setContentLength(0L);
      res.getOutputStream().close();
    };

    try (HTTPServer server = new HTTPServer().withHandler(handler).withClientTimeout(Duration.ofSeconds(1)).withNumberOfWorkerThreads(1).withListener(new HTTPListenerConfiguration(4242))) {
      server.start();

      URI uri = URI.create("http://localhost:4242/api/system/version");
      try {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setDoInput(true);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.addRequestProperty("Content-Length", "42");
        connection.connect();
        var os = connection.getOutputStream();
        os.write("start".getBytes());
        os.flush();
        sleep(3_500L);
        os.write("more".getBytes());
        connection.getResponseCode(); // Should fail on the read
        fail("Should have timed out");
      } catch (Exception e) {
        // Expected
        e.printStackTrace();
      }
    }
  }

  @Test
  public void emptyContentType() throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertNull(req.getContentType());
      res.setStatus(200);
    };

    try (HTTPServer ignore = new HTTPServer().withHandler(handler).withNumberOfWorkerThreads(1).withListener(new HTTPListenerConfiguration(4242)).start()) {
      var client = HttpClient.newHttpClient();
      URI uri = URI.create("http://localhost:4242/api/system/version");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "").POST(BodyPublishers.noBody()).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void emptyContentTypeWithEncoding() throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(req.getContentType(), "");
      assertEquals(req.getCharacterEncoding(), StandardCharsets.UTF_16);
      res.setStatus(200);
    };

    try (HTTPServer ignore = new HTTPServer().withHandler(handler).withNumberOfWorkerThreads(1).withListener(new HTTPListenerConfiguration(4242)).start()) {
      var client = HttpClient.newHttpClient();
      URI uri = URI.create("http://localhost:4242/api/system/version");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "; charset=UTF-16").POST(BodyPublishers.noBody()).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void handlerFailureGet() throws Exception {
    HTTPHandler handler = (req, res) -> {
      throw new IllegalStateException("Bad state");
    };

    try (HTTPServer ignore = new HTTPServer().withHandler(handler).withNumberOfWorkerThreads(1).withListener(new HTTPListenerConfiguration(4242)).start()) {
      var client = HttpClient.newHttpClient();
      URI uri = URI.create("http://localhost:4242/api/system/version");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).GET().build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 500);
    }
  }

  @Test
  public void handlerFailurePost() throws Exception {
    HTTPHandler handler = (req, res) -> {
      throw new IllegalStateException("Bad state");
    };

    try (HTTPServer ignore = new HTTPServer().withHandler(handler).withNumberOfWorkerThreads(1).withListener(new HTTPListenerConfiguration(4242)).start()) {
      var client = HttpClient.newHttpClient();
      URI uri = URI.create("http://localhost:4242/api/system/version");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 500);
    }
  }

  @Test
  public void performance() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader("Content-Length", "16");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = new HTTPServer().withHandler(handler).withInstrumenter(instrumenter).withNumberOfWorkerThreads(1).withListener(new HTTPListenerConfiguration(4242)).start()) {
      var client = HttpClient.newHttpClient();
      URI uri = URI.create("http://localhost:4242/api/system/version");
      long start = System.currentTimeMillis();
      for (int i = 0; i < 100_000; i++) {
        var response = client.send(
            HttpRequest.newBuilder().uri(uri).GET().build(),
            r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), ExpectedResponse);

        if (i % 1_000 == 0) {
          System.out.println(i);
        }
      }

      long end = System.currentTimeMillis();
      double average = (end - start) / 10_000D;
      System.out.println("Average linear request time is [" + average + "]ms");
    }

    assertEquals(instrumenter.getConnections(), 1);
  }

  @Test
  public void performanceNoKeepAlive() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader("Content-Length", "16");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = new HTTPServer().withHandler(handler).withInstrumenter(instrumenter).withNumberOfWorkerThreads(1).withListener(new HTTPListenerConfiguration(4242)).start()) {
      var client = HttpClient.newHttpClient();
      URI uri = URI.create("http://localhost:4242/api/system/version");
      for (int i = 0; i < 500; i++) {
        var response = client.send(
            HttpRequest.newBuilder().uri(uri).header(Headers.Connection, Connections.Close).GET().build(),
            r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), ExpectedResponse);
      }
    }

    assertEquals(instrumenter.getConnections(), 500);
  }

  @Test
  public void simpleGet() throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(req.getAcceptEncodings(), List.of("deflate", "compress", "identity", "gzip", "br"));
      assertEquals(req.getBaseURL(), "http://localhost:4242");
      assertEquals(req.getContentType(), "text/plain");
      assertEquals(req.getCharacterEncoding(), StandardCharsets.ISO_8859_1);
      assertEquals(req.getHeader(Headers.Origin), "https://example.com");
      assertEquals(req.getHeader(Headers.Referer), "foobar.com");
      assertEquals(req.getHeader(Headers.UserAgent), "java-http test");
      assertEquals(req.getHost(), "localhost");
      assertEquals(req.getIPAddress(), "127.0.0.1");
      assertEquals(req.getLocales(), List.of(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH));
      assertEquals(req.getMethod(), HTTPMethod.GET);
      assertEquals(req.getParameter("foo"), "bar");
      assertEquals(req.getPath(), "/api/system/version");
      assertEquals(req.getPort(), 4242);
      assertEquals(req.getProtocol(), "HTTP/1.1");
      assertEquals(req.getScheme(), "http");
      assertEquals(req.getURLParameter("foo"), "bar");

      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader("Content-Length", "16");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (HTTPServer ignore = new HTTPServer().withHandler(handler).withNumberOfWorkerThreads(1).withListener(new HTTPListenerConfiguration(4242)).start()) {
      var client = HttpClient.newHttpClient();
      URI uri = URI.create("http://localhost:4242/api/system/version?foo=bar");
      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(uri)
                                       .header(Headers.AcceptEncoding, "deflate, compress, br;q=0.5, gzip;q=0.8, identity;q=1.0")
                                       .header(Headers.AcceptLanguage, "en, fr;q=0.7, de;q=0.8")
                                       .header(Headers.ContentType, "text/plain; charset=ISO8859-1")
                                       .header(Headers.Origin, "https://example.com")
                                       .header(Headers.Referer, "foobar.com")
                                       .header(Headers.UserAgent, "java-http test")
                                       .GET()
                                       .build();
      var response = client.send(request, r -> BodySubscribers.ofString(StandardCharsets.UTF_8));

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
    }
  }

  @Test
  public void simplePost() throws Exception {
    HTTPHandler handler = (req, res) -> {
      System.out.println("Handling");
      assertEquals(req.getHeader(Headers.ContentType), "application/json"); // Mixed case

      try {
        System.out.println("Reading");
        byte[] body = req.getInputStream().readAllBytes();
        assertEquals(new String(body), RequestBody);
      } catch (IOException e) {
        fail("Unable to parse body", e);
      }

      System.out.println("Done");
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader("Content-Length", "16");
      res.setStatus(200);

      try {
        System.out.println("Writing");
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (HTTPServer ignore = new HTTPServer().withHandler(handler).withNumberOfWorkerThreads(1).withListener(new HTTPListenerConfiguration(4242)).start()) {
      var client = HttpClient.newHttpClient();
      URI uri = URI.create("http://localhost:4242/api/system/version");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
    }
  }

  @Test
  public void statusOnly() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
    };

    try (HTTPServer ignore = new HTTPServer().withHandler(handler).withNumberOfWorkerThreads(1).withListener(new HTTPListenerConfiguration(4242)).start()) {
      var client = HttpClient.newHttpClient();
      URI uri = URI.create("http://localhost:4242/api/system/version");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void writer() throws Exception {
    HTTPHandler handler = (req, res) -> {
      try {
        req.getInputStream().readAllBytes();

        res.setHeader(Headers.ContentType, "text/plain; charset=UTF-16");
        res.setHeader("Content-Length", "" + ExpectedResponse.getBytes(StandardCharsets.UTF_16).length); // Recalculate the byte length using UTF-16
        res.setStatus(200);

        Writer writer = res.getWriter();
        writer.write(ExpectedResponse);
        writer.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (HTTPServer ignore = new HTTPServer().withHandler(handler).withNumberOfWorkerThreads(1).withListener(new HTTPListenerConfiguration(4242)).start()) {
      var client = HttpClient.newHttpClient();
      URI uri = URI.create("http://localhost:4242/api/system/version");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_16)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
    }
  }

  private void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      // Ignore
    }
  }
}