/*
 * Copyright (c) 2022-2023, FusionAuth, All Rights Reserved
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
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.InflaterInputStream;

import com.inversoft.net.ssl.SSLTools;
import com.inversoft.rest.ClientResponse;
import com.inversoft.rest.RESTClient;
import com.inversoft.rest.TextResponseHandler;
import io.fusionauth.http.HTTPValues.Connections;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.log.AccumulatingLoggerFactory;
import io.fusionauth.http.log.Level;
import io.fusionauth.http.server.CountingInstrumenter;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServer;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Tests the HTTP server.
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("UastIncorrectHttpHeaderInspection")
public class CoreTest extends BaseTest {
  public static final String ExpectedResponse = "{\"version\":\"42\"}";

  public static final String LongString = "12345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890".repeat(64);

  public static final String RequestBody = "{\"message\":\"Hello World\"";

  static {
    System.setProperty("sun.net.http.retryPost", "false");
    System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection");
  }

  @Test(dataProvider = "schemes")
  public void badLanguage(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertTrue(req.getLocales().isEmpty());
      res.setStatus(200);
      res.getOutputStream().close();
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      var client = makeClient(scheme, null);
      URI uri = makeURI(scheme, "");
      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(uri)
                                       .header(Headers.AcceptLanguage, "en, fr_bad;q=0.7")
                                       .GET()
                                       .build();

      var response = client.send(request, r -> BodySubscribers.ofInputStream());
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void badPreambleButReset() throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertNull(req.getHeader("Bad-Header"));
      assertEquals(req.getHeader("Good-Header"), "Good-Header");
      res.setStatus(200);
    };

    var instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer("http", handler, instrumenter).start()) {
      sendBadRequest("""
          GET / HTTP/1.1\r
          X-Bad-Header: Bad-Header\r\r
          """);

      var client = HttpClient.newHttpClient();
      URI uri = makeURI("http", "");
      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(uri)
                                       .header("Good-Header", "Good-Header")
                                       .GET()
                                       .build();
      var response = client.send(request, r -> BodySubscribers.ofString(StandardCharsets.UTF_8));
      assertEquals(response.statusCode(), 200);
    }

    assertEquals(instrumenter.getBadRequests(), 1);
  }

  @Test
  public void certificateChain() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.getOutputStream().close();
    };

    try (HTTPServer ignore = makeServer("https", handler).start()) {
      var client = makeClient("https", null);
      URI uri = makeURI("https", "");
      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(uri)
                                       .GET()
                                       .build();

      var response = client.send(request, r -> BodySubscribers.ofInputStream());
      assertEquals(response.statusCode(), 200);

      var sslSession = response.sslSession().get();
      var peerCerts = sslSession.getPeerCertificates();

      // Verify that we received all intermediates, and can verify the chain all the way up to rootCertificate.
      validateCertPath(rootCertificate, peerCerts);
    }
  }

  @Test
  public void clientTimeout() throws Exception {
    HTTPHandler handler = (req, res) -> {
      System.out.println("Handling");
      res.setStatus(200);
      res.setContentLength(0L);
      res.getOutputStream().close();
    };

    var instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer("http", handler, instrumenter).withClientTimeout(Duration.ofSeconds(1)).start(); Socket socket = new Socket("127.0.0.1", 4242)) {
      var out = socket.getOutputStream();
      out.write("""
          GET / HTTP/1.1\r
          Content-Length: 4\r
          \r
          body
          """.getBytes());
      out.flush();
      sleep(3_000L);

      var in = socket.getInputStream();
      assertEquals(in.read(), -1);
      assertEquals(instrumenter.getClosedConnections(), 1);
    }
  }

  @Test(dataProvider = "schemes")
  public void emptyContentType(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertNull(req.getContentType());
      res.setStatus(200);
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "").POST(BodyPublishers.noBody()).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
    }
  }

  @Test(dataProvider = "schemes")
  public void emptyContentTypeWithEncoding(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(req.getContentType(), "");
      assertEquals(req.getCharacterEncoding(), StandardCharsets.UTF_16);
      res.setStatus(200);
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "; charset=UTF-16").POST(BodyPublishers.noBody()).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
    }
  }

  @Test(dataProvider = "schemes")
  public void handlerFailureGet(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      throw new IllegalStateException("Bad state");
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).GET().build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 500);
    }
  }

  @Test(dataProvider = "schemes")
  public void handlerFailurePost(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      throw new IllegalStateException("Bad state");
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 500);
    }
  }

  @Test(dataProvider = "schemes")
  public void hugeHeaders(String scheme) throws Exception {
    // 260 characters for a total of 16,640 bytes per header value. 5 headers for a total of 83,200 bytes
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader(Headers.ContentLength, "16");
      res.setHeader("X-Huge-Header-1", LongString);
      res.setHeader("X-Huge-Header-2", LongString);
      res.setHeader("X-Huge-Header-3", LongString);
      res.setHeader("X-Huge-Header-4", LongString);
      res.setHeader("X-Huge-Header-5", LongString);
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder()
                     .uri(uri)
                     .header("X-Huge-Header-1", LongString)
                     .header("X-Huge-Header-2", LongString)
                     .header("X-Huge-Header-3", LongString)
                     .header("X-Huge-Header-4", LongString)
                     .header("X-Huge-Header-5", LongString)
                     .POST(BodyPublishers.ofString(RequestBody))
                     .build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void keepAliveTimeout() {
    AtomicBoolean called = new AtomicBoolean(false);
    HTTPHandler handler = (req, res) -> {
      assertNull(req.getContentType());
      if (!called.getAndSet(true)) {
        System.out.println("Pausing");
        sleep(5 * 60_000);
      }

      res.setStatus(200);
    };

    try (HTTPServer ignore = makeServer("http", handler).start()) {
      URI uri = makeURI("http", "");
      ClientResponse<Void, Void> response = new RESTClient<>(Void.TYPE, Void.TYPE)
          .url(uri.toString())
          .connectTimeout(0)
          .readTimeout(0)
          .post()
          .go();

      if (response.status != 200) {
        System.out.println(response.exception);
      }

      assertEquals(response.status, 200);
      response = new RESTClient<>(Void.TYPE, Void.TYPE)
          .url(uri.toString())
          .connectTimeout(0)
          .readTimeout(0)
          .post()
          .go();

      if (response.status != 200) {
        System.out.println(response.exception);
      }

      assertEquals(response.status, 200);
    }
  }

  @Test
  public void logger() {
    // Test replacement values and ensure we are handling special regex characters.
    logger.setLevel(Level.Debug);
    logger.info("Class name: [{}]", "io.fusionauth.http.Test$InnerClass");

    // Expect that we do not encounter an exception.
    String output = logger.toString();
    assertTrue(output.endsWith("Class name: [io.fusionauth.http.Test$InnerClass]"));
  }

  @Test(dataProvider = "schemes", groups = "performance")
  public void performance(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader(Headers.ContentLength, "16");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    int iterations = 100_000;
    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      long start = System.currentTimeMillis();
      for (int i = 0; i < iterations; i++) {
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
      double average = (end - start) / (double) iterations;
      System.out.println("Average linear request time is [" + average + "]ms");
    }

    assertEquals(instrumenter.getConnections(), 1);
  }

  @Test(dataProvider = "schemes", groups = "performance")
  public void performanceNoKeepAlive(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader(Headers.ContentLength, "16");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    int iterations = 1_000;
    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      long start = System.currentTimeMillis();
      for (int i = 0; i < iterations; i++) {
        var response = client.send(
            HttpRequest.newBuilder().uri(uri).header(Headers.Connection, Connections.Close).GET().build(),
            r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), ExpectedResponse);
      }

      long end = System.currentTimeMillis();
      double average = (end - start) / (double) iterations;
      System.out.println("Average linear request time without keep-alive is [" + average + "]ms");
    }

    assertEquals(instrumenter.getConnections(), iterations);
  }

  /**
   * This test uses Restify in order to leverage the URLConnection implementation of the JDK. That implementation is not smart enough to
   * realize that a socket in the connection pool that was using Keep-Alives with the server is potentially dead. Since we are shutting down
   * the server and doing another request, this ensures that the server itself is sending a socket close signal back to the URLConnection
   * and removing the socket form the connection pool.
   */
  @Test(dataProvider = "schemes")
  public void serverClosesSockets(String scheme) {
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader(Headers.ContentLength, "16");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      SSLTools.disableSSLValidation();
      URI uri = makeURI(scheme, "");
      var response = new RESTClient<>(String.class, String.class).url(uri.toString())
                                                                 .connectTimeout(600_000)
                                                                 .readTimeout(600_000)
                                                                 .get()
                                                                 .successResponseHandler(new TextResponseHandler())
                                                                 .errorResponseHandler(new TextResponseHandler())
                                                                 .go();
      assertEquals(response.status, 200);
      assertEquals(response.successResponse, ExpectedResponse);
    } finally {
      SSLTools.enableSSLValidation();
    }

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      SSLTools.disableSSLValidation();
      URI uri = makeURI(scheme, "");
      var response = new RESTClient<>(String.class, String.class).url(uri.toString())
                                                                 .connectTimeout(600_000)
                                                                 .readTimeout(600_000)
                                                                 .get()
                                                                 .successResponseHandler(new TextResponseHandler())
                                                                 .errorResponseHandler(new TextResponseHandler())
                                                                 .go();
      assertEquals(response.status, 200);
      assertEquals(response.successResponse, ExpectedResponse);
    } finally {
      SSLTools.enableSSLValidation();
    }
  }

  @Test(dataProvider = "schemes")
  public void simpleGet(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(req.getAcceptEncodings(), List.of("deflate", "compress", "identity", "gzip", "br"));
      assertEquals(req.getBaseURL(), scheme.equals("http") ? "http://localhost:4242" : "https://local.fusionauth.io:4242");
      assertEquals(req.getContentType(), "text/plain");
      assertEquals(req.getCharacterEncoding(), StandardCharsets.ISO_8859_1);
      assertEquals(req.getHeader(Headers.Origin), "https://example.com");
      assertEquals(req.getHeader(Headers.Referer), "foobar.com");
      assertEquals(req.getHeader(Headers.UserAgent), "java-http test");
      assertEquals(req.getHost(), scheme.equals("http") ? "localhost" : "local.fusionauth.io");
      assertEquals(req.getIPAddress(), "127.0.0.1");
      assertEquals(req.getLocales(), List.of(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH));
      assertEquals(req.getMethod(), HTTPMethod.GET);
      assertEquals(req.getParameter("foo "), "bar ");
      assertEquals(req.getPath(), "/api/system/version");
      assertEquals(req.getPort(), 4242);
      assertEquals(req.getProtocol(), "HTTP/1.1");
      assertEquals(req.getQueryString(), "foo%20=bar%20");
      assertEquals(req.getScheme(), scheme);
      assertEquals(req.getURLParameter("foo "), "bar ");

      res.setHeader(Headers.ContentType, "text/plain");
      // Compression is on by default, don't write a Content-Length header it will be wrong.
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      var client = makeClient(scheme, null);
      URI uri = makeURI(scheme, "?foo%20=bar%20");
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

      var response = client.send(request, r -> BodySubscribers.ofInputStream());

      assertEquals(response.statusCode(), 200);
      var result = new String(new InflaterInputStream(response.body()).readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(result, ExpectedResponse);
    }
  }

  @Test
  public void simpleGetMultiplePorts() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader(Headers.ContentLength, "16");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    setupCertificates();
    var certChain = new Certificate[]{certificate, intermediateCertificate};

    try (HTTPServer ignore = new HTTPServer().withHandler(handler)
                                             .withListener(new HTTPListenerConfiguration(4242))
                                             .withListener(new HTTPListenerConfiguration(4243))
                                             .withListener(new HTTPListenerConfiguration(4244, certChain, keyPair.getPrivate()))
                                             .withLoggerFactory(AccumulatingLoggerFactory.FACTORY)
                                             .withNumberOfWorkerThreads(1)
                                             .start()) {
      var client = makeClient("https", null);
      URI uri = URI.create("http://localhost:4242/api/system/version?foo=bar");
      HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
      var response = client.send(request, r -> BodySubscribers.ofString(StandardCharsets.UTF_8));

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);

      // Try the other port
      uri = URI.create("http://localhost:4243/api/system/version?foo=bar");
      request = HttpRequest.newBuilder().uri(uri).GET().build();
      response = client.send(request, r -> BodySubscribers.ofString(StandardCharsets.UTF_8));

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);

      // Try the TLS port
      uri = URI.create("https://local.fusionauth.io:4244/api/system/version?foo=bar");
      request = HttpRequest.newBuilder().uri(uri).GET().build();
      response = client.send(request, r -> BodySubscribers.ofString(StandardCharsets.UTF_8));

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
    }
  }

  @Test(dataProvider = "schemes")
  public void simplePost(String scheme) throws Exception {
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
      res.setHeader(Headers.ContentLength, "16");
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

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      var client = makeClient(scheme, null);
      URI uri = makeURI(scheme, "?foo=bar");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
    }
  }

  @Test(dataProvider = "schemes")
  public void slowWrites(String scheme) throws Exception {
    // Test a slow connection where the HTTP server is blocked because we cannot write to the output stream as fast as we'd like

    // The default test config will use a 16k buffer and a queue size of 16.
    // - Trying to write 8 MB slowly should cause an error.

    // 8 MB bytes
    byte[] bytes = new byte[1024 * 1024 * 8];
    new SecureRandom().nextBytes(bytes);

    // Base64 encoded large string
    String largeRequest = Base64.getEncoder().encodeToString(bytes);

    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain; charset=UTF-8");
      int contentLength = largeRequest.getBytes(StandardCharsets.UTF_8).length;
      res.setHeader(Headers.ContentLength, String.valueOf(contentLength));
      res.setStatus(200);

      Writer writer = res.getWriter();
      writer.write(largeRequest);
      writer.close();
    };

    AtomicBoolean slept = new AtomicBoolean(false);
    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      client.send(
          HttpRequest.newBuilder().uri(uri).GET().build(),
          r -> BodySubscribers.ofByteArrayConsumer(optional -> {
            byte[] actual = optional.orElse(null);
            if (actual != null) {
              // Sleep once only since the server should fail after the first batch, but since Java or the OS might cache a lot of bytes it
              // read from the socket, we can't sleep for too long, otherwise, this test will never complete
              if (!slept.get()) {
                int sleep = 5 * 1_000;
                System.out.println("received [" + actual.length + "] bytes. Sleep [" + sleep + "]");
                sleep(sleep); // We expect to only wait for 2,000 ms
                slept.set(true);
              }
            } else {
              System.out.println("no bytes");
            }
          })
      );

      fail("Should have thrown");
    } catch (IOException e) {
      // Expected
    }
  }

  @Test(dataProvider = "schemes")
  public void statusOnly(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> res.setStatus(200);

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
    }
  }

  @Test(dataProvider = "schemes")
  public void unicode(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(URLDecoder.decode(req.getPath(), StandardCharsets.UTF_8), "/위키백과:대문");

      res.setHeader(Headers.ContentType, "text/plain");
      res.setStatus(200);
      res.getOutputStream().close();
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      var client = makeClient(scheme, null);
      URI uri = URI.create("http://localhost:4242/위키백과:대문");
      if (scheme.equals("https")) {
        uri = URI.create("https://local.fusionauth.io:4242/위키백과:대문");
      }
      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(uri)
                                       .GET()
                                       .build();

      var response = client.send(request, r -> BodySubscribers.discarding());
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test(dataProvider = "schemes")
  public void writer(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      try {
        req.getInputStream().readAllBytes();

        res.setHeader(Headers.ContentType, "text/plain; charset=UTF-16");
        res.setHeader(Headers.ContentLength, String.valueOf(ExpectedResponse.getBytes(StandardCharsets.UTF_16).length)); // Recalculate the byte length using UTF-16
        res.setStatus(200);

        Writer writer = res.getWriter();
        writer.write(ExpectedResponse);
        writer.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
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
