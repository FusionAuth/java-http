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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.InflaterInputStream;

import com.inversoft.net.ssl.SSLTools;
import com.inversoft.rest.RESTClient;
import com.inversoft.rest.TextResponseHandler;
import io.fusionauth.http.HTTPValues.Connections;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.log.AccumulatingLogger;
import io.fusionauth.http.log.AccumulatingLoggerFactory;
import io.fusionauth.http.log.Level;
import io.fusionauth.http.server.CountingInstrumenter;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServer;
import io.fusionauth.http.server.HTTPServerConfiguration;
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
@SuppressWarnings({"UastIncorrectHttpHeaderInspection", "OptionalGetWithoutIsPresent", "ThrowablePrintedToSystemOut"})
public class CoreTest extends BaseTest {
  public static final String ExpectedResponse = """
      {
        "version": "42"
      }
      """
      .replaceAll("\\s", "");

  // This string is 16,640 characters long
  public static final String LongString = "1234567890".repeat(1_664);

  public static final String RequestBody = """
      {
        "message": "Hello World"
      }
      """;

  @Test(dataProvider = "schemes")
  public void badLanguage(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertTrue(req.getLocales().isEmpty());
      res.setStatus(200);
      res.getOutputStream().close();
    };

    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler).start()) {
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
    try (var client = HttpClient.newHttpClient(); var ignore = makeServer("http", handler, instrumenter).start()) {
      // Invalid request, missing Host header
      // - This should cause the socket to be reset
      // TODO : If we return a 400 since this is a client error and not a server error we are not currently resetting the request.
      //        Do we need to?
      sendBadRequest("""
          GET / HTTP/1.1\r
          X-Bad-Header: Bad-Header\r\r
          """);

      // TODO : Daniel : Review : What is this test doing? If I comment out reset() in HTTPWorker.close() the test still passes.
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

  @Test(enabled = false)
  public void certificateChain() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.getOutputStream().close();
    };

    try (var client = makeClient("https", null); var ignore = makeServer("https", handler).start()) {
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

  @Test(dataProvider = "schemes")
  public void emptyContentType(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertNull(req.getContentType());
      res.setStatus(200);
    };

    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
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

    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
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

    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
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

    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 500);
    }
  }

  @Test(groups = "timeouts")
  public void initialReadTimeout() {
    // This test simulates if the client doesn't send bytes for the initial timeout
    HTTPHandler handler = (req, res) -> {
      byte[] response = "Hey, looks like the timeout didn't work!".getBytes(StandardCharsets.UTF_8);
      res.setHeader(Headers.ContentLength,  response.length + "");
      res.setHeader(Headers.ContentType, "text/plain");
      var os = res.getOutputStream();
      os.write(response);
      os.close();
    };

    var instrumenter = new CountingInstrumenter();
    try (var ignore = makeServer("http", handler, instrumenter)
        .withInitialReadTimeout(Duration.ofMillis(250))
        .start();
         var socket = new Socket("127.0.0.1", 4242)) {

      // Open a socket to the server and then wait to write any bytes.
      sleep(1_000);
      var out = socket.getOutputStream();

      // Now write something for the server to read
      out.write("""
          GET / HTTP/1.1\r
          Host: localhost:42\r
          Connection: close\r
          Content-Length: 4\r
          \r
          body
          """.getBytes());
      out.flush();

      var response = socket.getInputStream().readAllBytes();
      assertEquals(response.length, 0, new String(response, StandardCharsets.UTF_8));
    } catch (Exception ignore) {
      // Expected
    }

    // Expect to have seen one connection closed due to a timeout.
    // - Because we were not able to ready any bytes from the client, we have 0 accepted requests.
    assertEquals(instrumenter.getAcceptedRequests(), 0);
    assertEquals(instrumenter.getClosedConnections(), 1);
  }

  @Test(groups = "timeouts")
  public void keepAliveTimeout() {
    // This test only works with GET and the URLConnection because this setup will re-submit the same request if the Keep-Alive connection
    // is terminated by the server
    HTTPHandler handler = (req, res) -> {
      assertNull(req.getContentType());
      res.setStatus(200);
    };

    try (var ignore = makeServer("http", handler).withKeepAliveTimeoutDuration(Duration.ofSeconds(1)).start()) {
      URI uri = makeURI("http", "");
      var response = new RESTClient<>(Void.TYPE, Void.TYPE)
          .url(uri.toString())
          .connectTimeout(0)
          .readTimeout(0)
          .get()
          .go();

      if (response.status != 200) {
        System.out.println(response.exception);
      }
      assertEquals(response.status, 200);

      // This will cause the keep-alive on the server to expire and that means the socket will be dead but the client should receover
      sleep(2_000L);

      response = new RESTClient<>(Void.TYPE, Void.TYPE)
          .url(uri.toString())
          .connectTimeout(0)
          .readTimeout(0)
          .get()
          .go();

      if (response.status != 200) {
        System.out.println(response.exception);
      }
      assertEquals(response.status, 200);
    }
  }

  @Test(dataProvider = "schemes")
  public void largeCSS(String scheme) throws Exception {
    var css = Files.readString(Paths.get("src/test/resources/fontawesome-6.0.0.min.css"), StandardCharsets.UTF_8);

    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);

      try {
        var out = res.getOutputStream();
        out.write(css.getBytes(StandardCharsets.UTF_8));
        out.close();
      } catch (Throwable t) {
        System.out.println(t);
      }
    };

    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(uri)
                                       .GET()
                                       .build();

      var response = client.send(request, r -> BodySubscribers.ofString(StandardCharsets.UTF_8));
      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), css);
    }
  }

  @Test
  public void large_body() throws Exception {
    // Ensure that when the body bytes overflow from the initial "left over" bytes read
    // during reading of the preamble, the remaining bytes read from the HTTPInputStream
    // properly use offset and lengths when reading.

    var value = "1234567890";
    var valueLength = (new HTTPServerConfiguration().getRequestBufferSize() / value.length()) + 42;
    var payload = "foo=" + value.repeat(valueLength);
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.setContentType("application/x-www-form-urlencoded");
      res.setContentLength(req.getBodyBytes().length);
      res.getOutputStream().write(req.getBodyBytes());
      res.getOutputStream().close();

      // This will hose up the works - or it did until we fixed a bug in the HTTPRequest.getBodyBytes method.
      req.getFormData();

      // Ensure you can call this method and get the same value in return each time.
      assertEquals(req.getBodyBytes(), req.getBodyBytes());
    };

    var instrumenter = new CountingInstrumenter();
    try (var ignore = makeServer("http", handler, instrumenter).start(); var socket = new Socket("127.0.0.1", 4242)) {
      var out = socket.getOutputStream();
      var request = """
          GET / HTTP/1.1\r
          Host: localhost:42\r
          Content-Type: application/x-www-form-urlencoded\r
          Content-Length: {contentLength}\r
          \r
          {body}
          """.replace("{contentLength}", "" + bytes.length)
             .replace("{body}", payload);
      out.write(request.getBytes());
      out.flush();
      var in = socket.getInputStream();

      var responseBytes = in.readAllBytes();
      var responseString = new String(responseBytes);

      assertEquals(responseBytes.length, 16918);
      var payloadIndex = responseString.indexOf("\r\n\r\n") + 4;
      assertEquals(responseString.substring(payloadIndex, responseBytes.length - 1), payload);
    }
  }

  @SuppressWarnings("ExtractMethodRecommender")
  @Test(dataProvider = "schemes")
  public void large_headers(String scheme) throws Exception {
    // Use case: Ensure we can read headers from the request, and send headers on the response that exceed the default request and repsonse buffer lengths.

    // Ensure the headers in total exceed the response buffer sizes.
    var requestBufferLength = new HTTPServerConfiguration().getRequestBufferSize();
    var headersRequiredToExceedRequestBufferLength = (requestBufferLength / LongString.length()) + 1;

    // Ensure the headers in total exceed the response buffer sizes.
    var responseBufferLength = new HTTPServerConfiguration().getResponseBufferSize();
    var headersRequiredToExceedResponseBufferLength = (responseBufferLength / LongString.length()) + 1;

    // The server will return these larger headers on the HTTP response
    HTTPHandler handler = (req, res) -> {

      // Expect the headers sent by the client
      for (int i = 0; i < headersRequiredToExceedRequestBufferLength; i++) {
        assertEquals(req.getHeader("X-Huge-Header-" + i), LongString);
      }

      // Now write large headers back on the response
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader(Headers.ContentLength, ExpectedResponse.getBytes().length + "");
      for (int i = 0; i < headersRequiredToExceedResponseBufferLength; i++) {
        res.setHeader("X-Huge-Header-" + i, LongString);
      }
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var builder = HttpRequest.newBuilder()
                               .uri(uri);


      // The client is going to send these large headers on the request
      for (int i = 0; i < headersRequiredToExceedRequestBufferLength; i++) {
        builder.setHeader("X-Huge-Header-" + i, LongString);
      }

      var response = client.send(builder
              .POST(BodyPublishers.ofString(RequestBody))
              .build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);

      // Ensure each header came back on the response
      for (int i = 0; i < headersRequiredToExceedResponseBufferLength; i++) {
        assertEquals(response.headers().firstValue("X-Huge-Header-" + i).get(), LongString);
      }
    }
  }

  @Test
  public void logger() {
    // Test replacement values and ensure we are handling special regex characters.
    AccumulatingLogger logger = new AccumulatingLogger();
    logger.setLevel(Level.Debug);
    logger.info("Class name: [{}]", "io.fusionauth.http.Test$InnerClass");

    // Expect that we do not encounter an exception.
    String output = logger.toString();
    assertTrue(output.endsWith("Class name: [io.fusionauth.http.Test$InnerClass]"));
  }

  @Test(dataProvider = "schemes")
  public void partialWriteThenException(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.getWriter().write("Here some body that should not be flushed");
      throw new RuntimeException("Failure");
    };

    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder()
                     .uri(uri)
                     .GET()
                     .build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 500);
    }
  }

  @Test(dataProvider = "schemes")
  public void partialWriteThenFlushThenException(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);

      Writer writer = res.getWriter();
      writer.write("Here some body that should not be flushed");
      writer.flush();
      throw new RuntimeException("Failure");
    };

    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder()
                     .uri(uri)
                     .GET()
                     .build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 500);
    }
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
    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
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

    int iterations = 5_000;
    int i = -1;
    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler, instrumenter).start()) {
      try {
        URI uri = makeURI(scheme, "");
        long start = System.currentTimeMillis();
        for (i = 0; i < iterations; i++) {
          var response = client.send(
              HttpRequest.newBuilder()
                         .uri(uri)
                         .header(Headers.Connection, Connections.Close)
                         .POST(BodyPublishers.noBody())
                         .build(),
              r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
          );

          if (i % 1_000 == 0) {
            System.out.println(i);
          }

          assertEquals(response.statusCode(), 200);
          assertEquals(response.body(), ExpectedResponse);
        }

        long end = System.currentTimeMillis();
        double average = (end - start) / (double) iterations;
        System.out.println("Average linear request time without keep-alive is [" + average + "]ms");
      } catch (Exception e) {
        StringBuilder threadDump = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
          threadDump.append(entry.getKey()).append(" ").append(entry.getKey().getState()).append("\n");
          for (StackTraceElement ste : entry.getValue()) {
            threadDump.append("\tat ").append(ste).append("\n");
          }
          threadDump.append("\n");
        }

        System.out.println(threadDump);
        throw e;
      }
    } catch (Exception e) {
      System.out.println("Failed on iteration " + i);
      throw e;
    }

    // Because we are not re-using the connections, we expect this to equal the iteration count.
    assertEquals(instrumenter.getConnections(), iterations);

    // getClosedConnections() should only represent closed connections due to errors.
    assertEquals(instrumenter.getClosedConnections(), 0);
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

    try (var ignore = makeServer(scheme, handler).start()) {
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

    try (var ignore = makeServer(scheme, handler).start()) {
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

  @Test(groups = "timeouts")
  public void serverTimeout() throws Exception {
    // This test simulates if the server has a long-running thread that doesn't write fast enough
    HTTPHandler handler = (req, res) -> {
      System.out.println("Handling ... (slowly)");
      // Note that if you comment out the sleep, the test should fail.
      sleep(4_000L);
      res.setStatus(200);
      var body = "I'm slow but I'm good.".getBytes(StandardCharsets.UTF_8);
      res.setContentLength(body.length);
      res.getOutputStream().write(body);
      res.getOutputStream().close();
      System.out.println("Closed");
    };

    // TODO : Daniel Review : what are we trying to test here, this test isn't doing what we think.
    //        It seems we are writing to the server as a client, and the server is slow to write back.
    //        But this would be a client timeout and not a server timeout?
    //       Seems like we should use withProcessingTimeoutDuration instead of withInitialReadTimeout?
    var instrumenter = new CountingInstrumenter();
    try (var ignore = makeServer("http", handler, instrumenter).withProcessingTimeoutDuration(Duration.ofSeconds(1)).start(); Socket socket = new Socket("127.0.0.1", 4242)) {
      var out = socket.getOutputStream();
      // 1. Write a body to the server
      out.write("""
          GET / HTTP/1.1\r
          Host: localhost:42\r
          Connection: close\r
          Content-Length: 4\r
          \r
          body
          """.getBytes());
      out.flush();

      // 2. Read from the server, assuming you will receive a response.
      //   However, the server is very slow, it is going to wait 4s before it writes the response.
      //    - We have configured the server with a 1s read timeout.
      //    - Expect that we will have received a SocketTimeoutException and as such the response will be empty.
      var response = socket.getInputStream().readAllBytes();
      assertEquals(response.length, 0, new String(response));
      assertEquals(instrumenter.getClosedConnections(), 1);
    }
  }

  @Test(dataProvider = "schemesAndResponseBufferSizes")
  public void simpleGet(String scheme, int responseBufferSize) throws Exception {
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

    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler).withResponseBufferSize(responseBufferSize).start()) {
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
      assertEquals(response.headers().firstValue(Headers.ContentEncoding).get(), "deflate");
      assertEquals(response.headers().firstValue(Headers.TransferEncoding).get(), "chunked");

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

    var certChain = new Certificate[]{certificate, intermediateCertificate};
    try (var client = makeClient("https", null);
         var ignore = new HTTPServer().withHandler(handler)
                                      .withListener(new HTTPListenerConfiguration(4242))
                                      .withListener(new HTTPListenerConfiguration(4243))
                                      .withListener(new HTTPListenerConfiguration(4244, certChain, keyPair.getPrivate()))
                                      .withLoggerFactory(AccumulatingLoggerFactory.FACTORY)
                                      .start()) {
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

  @Test(dataProvider = "schemesAndResponseBufferSizes")
  public void simplePost(String scheme, int responseBufferSize) throws Exception {
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

    try (var ignore = makeServer(scheme, handler).withResponseBufferSize(responseBufferSize).start(); var client = makeClient(scheme, null)) {
      URI uri = makeURI(scheme, "?foo=bar");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
    }
  }

  @Test(dataProvider = "schemes", groups = "timeouts")
  public void slowClient(String scheme) throws Exception {
    // Test a slow connection where the HTTP server is blocked because we cannot write to the output stream as fast as we'd like. The
    // default buffer on macOS seems to be 768k (from my testing). I set this to 8MB which should hopefully cause the writes to back up.
    // - This tests the minimumWriteThroughput and writeThroughputCalculationDelayDuration
    byte[] bytes = new byte[1024 * 1024 * 8];
    new SecureRandom().nextBytes(bytes);

    // The server will write this back to the client
    HTTPHandler handler = (req, res) -> {
      res.setContentType("application/octet-stream");
      res.setContentLength(bytes.length);
      res.setStatus(200);

      int iteration = 1;
      var out = res.getOutputStream();
      for (int i = 0; i < bytes.length; i += 1024 * 8) {
        out.write(bytes, i, 1024 * 8);
        System.out.println("> Wrote    [" + (8 * 1024) + "] bytes. Total bytes written  [" + (iteration++ * 8 * 1024) + "].");
      }
      out.close();
    };

    // Set the min write throughput to 1 Megabit / second
    // - In this case the server is trying to write the client, and it cannot do so fast enough.
    AtomicBoolean slept = new AtomicBoolean(false);
    AtomicInteger totalBytesReceived = new AtomicInteger();
    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler)
        // By default, we will wait 5 seconds before we calculate write throughput, reduce to this to 1 ms for this test.
        .withWriteThroughputCalculationDelayDuration(Duration.ofMillis(1))
        .withMinimumWriteThroughput(1024 * 1024 * 1024).start()) {
      URI uri = makeURI(scheme, "");
      client.send(
          // Don't keep this connection open because the timeouts aren't the same on a keep alive.
          HttpRequest.newBuilder().uri(uri).GET().build(),
          r -> BodySubscribers.ofByteArrayConsumer(optional -> {
            byte[] actual = optional.orElse(null);
            if (actual != null) {
              // Sleep once since the server should fail after the first batch, but since Java or the OS might cache a lot of bytes it
              // read from the socket, we can't sleep for too long, otherwise, this test will never complete
              if (!slept.get()) {
                int sleep = 5_000;
                System.out.println("> Received [" + actual.length + "] bytes. Total bytes received [" + totalBytesReceived.addAndGet(actual.length) + "]. Sleep [" + sleep + "] ms.");
                sleep(sleep); // We expect to only wait for 2,000 ms until the cleaner runs to find slow clients.
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

  @Test(groups = "timeouts")
  public void slowHandler() {
    AtomicBoolean called = new AtomicBoolean(false);
    HTTPHandler handler = (req, res) -> {
      assertNull(req.getContentType());

      // Set the headers first
      res.setContentLength(2L);
      res.setStatus(200);

      // Write a single byte to kick off the write throughput calculations
      var outputStream = res.getOutputStream();
      outputStream.write('4');
      res.flush(); // Force a write to the client

      // Pause on the first request
      if (!called.getAndSet(true)) {
        System.out.println("Pausing for 4 seconds");
        sleep(4_000);
      }

      // Write the other byte now
      outputStream.write('2');
    };

    try (var ignore = makeServer("http", handler).withProcessingTimeoutDuration(Duration.ofSeconds(20))
                                                 .withWriteThroughputCalculationDelayDuration(Duration.ofSeconds(1))
                                                 .start()) {
      URI uri = makeURI("http", "");
      System.out.println("Sending first request");
      var response = new RESTClient<>(String.class, String.class)
          .url(uri.toString())
          .connectTimeout(0)
          .readTimeout(0)
          .errorResponseHandler(new TextResponseHandler())
          .successResponseHandler(new TextResponseHandler())
          .get()
          .go();
      System.out.println("Got first response");

      if (response.status != 200) {
        System.out.println(response.exception);
      }
      assertEquals(response.status, 200);
      assertEquals(response.successResponse, "4"); // Only part of the response will be written

      // Make a second call to ensure that the server is still working
      System.out.println("Sending second request");
      response = new RESTClient<>(String.class, String.class)
          .url(uri.toString())
          .connectTimeout(0)
          .readTimeout(0)
          .errorResponseHandler(new TextResponseHandler())
          .successResponseHandler(new TextResponseHandler())
          .get()
          .go();
      System.out.println("Got second response");

      if (response.status != 200) {
        System.out.println(response.exception);
      }
      assertEquals(response.status, 200);
      assertEquals(response.successResponse, "42");
    }
  }

  @Test(dataProvider = "schemes")
  public void statusOnly(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> res.setStatus(200);

    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void tlsIssues() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.getOutputStream().close();
    };

    try (var client = makeClient("https", null); var ignore = makeServer("https", handler).start()) {
      URI uri = makeURI("https", "");
      var body = BodyPublishers.ofByteArray("primeCSRFToken=QkJCAXsbQBgZhSVe1I4dv9B2ZXYDbUtCzAYUwfkvRjUJcUsBosHTeRbpvHgqXPN8TIK8DkSjG6HeaeJ-Yr4oCnXlUIW8T1r_9tVvuxxo38VKucd8gLnC2Mx7h_QuZu9dHEN79Q%3D%3D&tenantId=&tenant.name=Testing2&tenant.issuer=acme.com&tenant.themeId=75a068fd-e94b-451a-9aeb-3ddb9a3b5987&tenant.formConfiguration.adminUserFormId=ff153db4-d233-fcaa-76fd-98649866ff0b&__cb_tenant.usernameConfiguration.unique.enabled=false&tenant.usernameConfiguration.unique.strategy=OnCollision&tenant.usernameConfiguration.unique.numberOfDigits=5&tenant.usernameConfiguration.unique.separator=%23&tenant.connectorPolicies%5B0%5D.connectorId=e3306678-a53a-4964-9040-1c96f36dda72&connectorDomains%5B0%5D=*&tenant.connectorPolicies%5B0%5D.migrate=false&tenant.emailConfiguration.host=smtp.sendgrid.net&tenant.emailConfiguration.port=587&tenant.emailConfiguration.username=apikey&tenant.emailConfiguration.password=&tenant.emailConfiguration.security=TLS&tenant.emailConfiguration.defaultFromEmail=no-reply%40fusionauth.io&tenant.emailConfiguration.defaultFromName=FusionAuth&additionalEmailHeaders=&__cb_tenant.emailConfiguration.debug=false&__cb_tenant.emailConfiguration.verifyEmail=false&tenant.emailConfiguration.verifyEmail=true&__cb_tenant.emailConfiguration.implicitEmailVerificationAllowed=false&tenant.emailConfiguration.implicitEmailVerificationAllowed=true&__cb_tenant.emailConfiguration.verifyEmailWhenChanged=false&tenant.emailConfiguration.verificationEmailTemplateId=7fa81426-42a9-4eb2-ac09-73c044d410b1&tenant.emailConfiguration.emailVerifiedEmailTemplateId=&tenant.emailConfiguration.verificationStrategy=FormField&tenant.emailConfiguration.unverified.behavior=Gated&__cb_tenant.emailConfiguration.unverified.allowEmailChangeWhenGated=false&__cb_tenant.userDeletePolicy.unverified.enabled=false&tenant.userDeletePolicy.unverified.numberOfDaysToRetain=120&tenant.emailConfiguration.emailUpdateEmailTemplateId=&tenant.emailConfiguration.forgotPasswordEmailTemplateId=0502df1e-4010-4b43-b571-d423fce978b2&tenant.emailConfiguration.loginIdInUseOnCreateEmailTemplateId=&tenant.emailConfiguration.loginIdInUseOnUpdateEmailTemplateId=&tenant.emailConfiguration.loginNewDeviceEmailTemplateId=&tenant.emailConfiguration.loginSuspiciousEmailTemplateId=&tenant.emailConfiguration.passwordResetSuccessEmailTemplateId=&tenant.emailConfiguration.passwordUpdateEmailTemplateId=&tenant.emailConfiguration.passwordlessEmailTemplateId=fa6668cb-8569-44df-b0a2-8fcd996df915&tenant.emailConfiguration.setPasswordEmailTemplateId=e160cc59-a73e-4d95-8287-f82e5c541a5c&tenant.emailConfiguration.twoFactorMethodAddEmailTemplateId=&tenant.emailConfiguration.twoFactorMethodRemoveEmailTemplateId=&__cb_tenant.familyConfiguration.enabled=false&tenant.familyConfiguration.maximumChildAge=12&tenant.familyConfiguration.minimumOwnerAge=21&__cb_tenant.familyConfiguration.allowChildRegistrations=false&tenant.familyConfiguration.allowChildRegistrations=true&tenant.familyConfiguration.familyRequestEmailTemplateId=&tenant.familyConfiguration.confirmChildEmailTemplateId=&tenant.familyConfiguration.parentRegistrationEmailTemplateId=&__cb_tenant.familyConfiguration.parentEmailRequired=false&__cb_tenant.familyConfiguration.deleteOrphanedAccounts=false&tenant.familyConfiguration.deleteOrphanedAccountsDays=30&tenant.multiFactorConfiguration.loginPolicy=Enabled&__cb_tenant.multiFactorConfiguration.authenticator.enabled=false&tenant.multiFactorConfiguration.authenticator.enabled=true&__cb_tenant.multiFactorConfiguration.email.enabled=false&tenant.multiFactorConfiguration.email.templateId=61ee368e-018e-4c15-b7a7-47a696648dba&__cb_tenant.multiFactorConfiguration.sms.enabled=false&tenant.multiFactorConfiguration.sms.messengerId=&tenant.multiFactorConfiguration.sms.templateId=&__cb_tenant.webAuthnConfiguration.enabled=false&tenant.webAuthnConfiguration.enabled=true&tenant.webAuthnConfiguration.relyingPartyId=&tenant.webAuthnConfiguration.relyingPartyName=&__cb_tenant.webAuthnConfiguration.debug=false&__cb_tenant.webAuthnConfiguration.bootstrapWorkflow.enabled=false&tenant.webAuthnConfiguration.bootstrapWorkflow.authenticatorAttachmentPreference=any&tenant.webAuthnConfiguration.bootstrapWorkflow.userVerificationRequirement=required&__cb_tenant.webAuthnConfiguration.reauthenticationWorkflow.enabled=false&tenant.webAuthnConfiguration.reauthenticationWorkflow.enabled=true&tenant.webAuthnConfiguration.reauthenticationWorkflow.authenticatorAttachmentPreference=platform&tenant.webAuthnConfiguration.reauthenticationWorkflow.userVerificationRequirement=required&tenant.httpSessionMaxInactiveInterval=172800&tenant.logoutURL=&tenant.oauthConfiguration.clientCredentialsAccessTokenPopulateLambdaId=&tenant.jwtConfiguration.timeToLiveInSeconds=3600&tenant.jwtConfiguration.accessTokenKeyId=aea58f2a-4943-15ed-2190-0aa051200b64&tenant.jwtConfiguration.idTokenKeyId=092dbedc-30af-4149-9c61-b578f2c72f59&tenant.jwtConfiguration.refreshTokenExpirationPolicy=Fixed&tenant.jwtConfiguration.refreshTokenTimeToLiveInMinutes=43200&tenant.jwtConfiguration.refreshTokenSlidingWindowConfiguration.maximumTimeToLiveInMinutes=43200&tenant.jwtConfiguration.refreshTokenUsagePolicy=Reusable&__cb_tenant.jwtConfiguration.refreshTokenRevocationPolicy.onLoginPrevented=false&tenant.jwtConfiguration.refreshTokenRevocationPolicy.onLoginPrevented=true&__cb_tenant.jwtConfiguration.refreshTokenRevocationPolicy.onMultiFactorEnable=false&__cb_tenant.jwtConfiguration.refreshTokenRevocationPolicy.onPasswordChanged=false&tenant.jwtConfiguration.refreshTokenRevocationPolicy.onPasswordChanged=true&tenant.failedAuthenticationConfiguration.userActionId=&tenant.failedAuthenticationConfiguration.tooManyAttempts=5&tenant.failedAuthenticationConfiguration.resetCountInSeconds=60&tenant.failedAuthenticationConfiguration.actionDuration=3&tenant.failedAuthenticationConfiguration.actionDurationUnit=MINUTES&__cb_tenant.failedAuthenticationConfiguration.actionCancelPolicy.onPasswordReset=false&__cb_tenant.failedAuthenticationConfiguration.emailUser=false&__cb_tenant.passwordValidationRules.breachDetection.enabled=false&tenant.passwordValidationRules.breachDetection.matchMode=High&tenant.passwordValidationRules.breachDetection.onLogin=Off&tenant.passwordValidationRules.breachDetection.notifyUserEmailTemplateId=&tenant.passwordValidationRules.minLength=8&tenant.passwordValidationRules.maxLength=256&__cb_tenant.passwordValidationRules.requireMixedCase=false&__cb_tenant.passwordValidationRules.requireNonAlpha=false&__cb_tenant.passwordValidationRules.requireNumber=false&__cb_tenant.minimumPasswordAge.enabled=false&tenant.minimumPasswordAge.seconds=30&__cb_tenant.maximumPasswordAge.enabled=false&tenant.maximumPasswordAge.days=180&__cb_tenant.passwordValidationRules.rememberPreviousPasswords.enabled=false&tenant.passwordValidationRules.rememberPreviousPasswords.count=1&__cb_tenant.passwordValidationRules.validateOnLogin=false&tenant.passwordEncryptionConfiguration.encryptionScheme=salted-pbkdf2-hmac-sha256&tenant.passwordEncryptionConfiguration.encryptionSchemeFactor=24000&__cb_tenant.passwordEncryptionConfiguration.modifyEncryptionSchemeOnLogin=false&__cb_tenant.eventConfiguration.events%5B%27JWTPublicKeyUpdate%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27JWTPublicKeyUpdate%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27JWTRefreshTokenRevoke%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27JWTRefreshTokenRevoke%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27JWTRefresh%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27JWTRefresh%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27GroupCreate%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27GroupCreate%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27GroupCreateComplete%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27GroupDelete%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27GroupDelete%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27GroupDeleteComplete%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27GroupMemberAdd%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27GroupMemberAdd%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27GroupMemberAddComplete%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27GroupMemberRemove%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27GroupMemberRemove%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27GroupMemberRemoveComplete%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27GroupMemberUpdate%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27GroupMemberUpdate%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27GroupMemberUpdateComplete%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27GroupUpdate%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27GroupUpdate%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27GroupUpdateComplete%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserAction%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserBulkCreate%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserBulkCreate%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserCreate%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserCreate%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserCreateComplete%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserDeactivate%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserDeactivate%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserDelete%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserDelete%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserDeleteComplete%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserEmailUpdate%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserEmailVerified%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserEmailVerified%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserIdentityProviderLink%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserIdentityProviderUnlink%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserLoginIdDuplicateOnCreate%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserLoginIdDuplicateOnUpdate%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserLoginFailed%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserLoginFailed%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserLoginNewDevice%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserLoginNewDevice%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserLoginSuccess%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserLoginSuccess%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserLoginSuspicious%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserLoginSuspicious%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserPasswordBreach%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserPasswordBreach%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserPasswordResetSend%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserPasswordResetStart%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserPasswordResetSuccess%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserPasswordUpdate%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserReactivate%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserReactivate%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserRegistrationCreate%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserRegistrationCreate%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserRegistrationCreateComplete%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserRegistrationDelete%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserRegistrationDelete%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserRegistrationDeleteComplete%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserRegistrationUpdate%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserRegistrationUpdate%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserRegistrationUpdateComplete%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserRegistrationVerified%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserRegistrationVerified%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserTwoFactorMethodAdd%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserTwoFactorMethodRemove%27%5D.enabled=false&__cb_tenant.eventConfiguration.events%5B%27UserUpdate%27%5D.enabled=false&tenant.eventConfiguration.events%5B%27UserUpdate%27%5D.transactionType=None&__cb_tenant.eventConfiguration.events%5B%27UserUpdateComplete%27%5D.enabled=false&tenant.externalIdentifierConfiguration.authorizationGrantIdTimeToLiveInSeconds=30&tenant.externalIdentifierConfiguration.changePasswordIdTimeToLiveInSeconds=600&tenant.externalIdentifierConfiguration.deviceCodeTimeToLiveInSeconds=300&tenant.externalIdentifierConfiguration.emailVerificationIdTimeToLiveInSeconds=86400&tenant.externalIdentifierConfiguration.externalAuthenticationIdTimeToLiveInSeconds=300&tenant.externalIdentifierConfiguration.oneTimePasswordTimeToLiveInSeconds=60&tenant.externalIdentifierConfiguration.passwordlessLoginTimeToLiveInSeconds=180&tenant.externalIdentifierConfiguration.pendingAccountLinkTimeToLiveInSeconds=3600&tenant.externalIdentifierConfiguration.registrationVerificationIdTimeToLiveInSeconds=86400&tenant.externalIdentifierConfiguration.samlv2AuthNRequestIdTimeToLiveInSeconds=300&tenant.externalIdentifierConfiguration.setupPasswordIdTimeToLiveInSeconds=86400&tenant.externalIdentifierConfiguration.trustTokenTimeToLiveInSeconds=180&tenant.externalIdentifierConfiguration.twoFactorIdTimeToLiveInSeconds=300&tenant.externalIdentifierConfiguration.twoFactorOneTimeCodeIdTimeToLiveInSeconds=60&tenant.externalIdentifierConfiguration.twoFactorTrustIdTimeToLiveInSeconds=2592000&tenant.externalIdentifierConfiguration.webAuthnAuthenticationChallengeTimeToLiveInSeconds=180&tenant.externalIdentifierConfiguration.webAuthnRegistrationChallengeTimeToLiveInSeconds=180&tenant.externalIdentifierConfiguration.changePasswordIdGenerator.length=32&tenant.externalIdentifierConfiguration.changePasswordIdGenerator.type=randomBytes&tenant.externalIdentifierConfiguration.emailVerificationIdGenerator.length=32&tenant.externalIdentifierConfiguration.emailVerificationIdGenerator.type=randomBytes&tenant.externalIdentifierConfiguration.emailVerificationOneTimeCodeGenerator.length=6&tenant.externalIdentifierConfiguration.emailVerificationOneTimeCodeGenerator.type=randomAlphaNumeric&tenant.externalIdentifierConfiguration.passwordlessLoginGenerator.length=32&tenant.externalIdentifierConfiguration.passwordlessLoginGenerator.type=randomBytes&tenant.externalIdentifierConfiguration.registrationVerificationIdGenerator.length=32&tenant.externalIdentifierConfiguration.registrationVerificationIdGenerator.type=randomBytes&tenant.externalIdentifierConfiguration.registrationVerificationOneTimeCodeGenerator.length=6&tenant.externalIdentifierConfiguration.registrationVerificationOneTimeCodeGenerator.type=randomAlphaNumeric&tenant.externalIdentifierConfiguration.setupPasswordIdGenerator.length=32&tenant.externalIdentifierConfiguration.setupPasswordIdGenerator.type=randomBytes&tenant.externalIdentifierConfiguration.deviceUserCodeIdGenerator.length=6&tenant.externalIdentifierConfiguration.deviceUserCodeIdGenerator.type=randomAlphaNumeric&tenant.externalIdentifierConfiguration.twoFactorOneTimeCodeIdGenerator.length=6&tenant.externalIdentifierConfiguration.twoFactorOneTimeCodeIdGenerator.type=randomDigits&tenant.emailConfiguration.properties=&__cb_tenant.scimServerConfiguration.enabled=false&tenant.scimServerConfiguration.clientEntityTypeId=&tenant.scimServerConfiguration.serverEntityTypeId=&tenant.lambdaConfiguration.scimUserRequestConverterId=&tenant.lambdaConfiguration.scimUserResponseConverterId=&tenant.lambdaConfiguration.scimEnterpriseUserRequestConverterId=&tenant.lambdaConfiguration.scimEnterpriseUserResponseConverterId=&tenant.lambdaConfiguration.scimGroupRequestConverterId=&tenant.lambdaConfiguration.scimGroupResponseConverterId=&scimSchemas=&__cb_tenant.loginConfiguration.requireAuthentication=false&tenant.loginConfiguration.requireAuthentication=true&tenant.accessControlConfiguration.uiIPAccessControlListId=&__cb_tenant.captchaConfiguration.enabled=false&tenant.captchaConfiguration.enabled=true&tenant.captchaConfiguration.captchaMethod=GoogleRecaptchaV2&tenant.captchaConfiguration.siteKey=6LdGKU8kAAAAAJ75pcseAvWyo3cYnQyIU3eGqulg&tenant.captchaConfiguration.secretKey=6LdGKU8kAAAAALqeN2ECaeLOONJduofuRerZBlyI&tenant.captchaConfiguration.threshold=0.5&tenant.ssoConfiguration.deviceTrustTimeToLiveInSeconds=31536000&blockedDomains=&__cb_tenant.rateLimitConfiguration.failedLogin.enabled=false&tenant.rateLimitConfiguration.failedLogin.limit=5&tenant.rateLimitConfiguration.failedLogin.timePeriodInSeconds=60&__cb_tenant.rateLimitConfiguration.forgotPassword.enabled=false&tenant.rateLimitConfiguration.forgotPassword.limit=5&tenant.rateLimitConfiguration.forgotPassword.timePeriodInSeconds=60&__cb_tenant.rateLimitConfiguration.sendEmailVerification.enabled=false&tenant.rateLimitConfiguration.sendEmailVerification.limit=5&tenant.rateLimitConfiguration.sendEmailVerification.timePeriodInSeconds=60&__cb_tenant.rateLimitConfiguration.sendPasswordless.enabled=false&tenant.rateLimitConfiguration.sendPasswordless.limit=5&tenant.rateLimitConfiguration.sendPasswordless.timePeriodInSeconds=60&__cb_tenant.rateLimitConfiguration.sendRegistrationVerification.enabled=false&tenant.rateLimitConfiguration.sendRegistrationVerification.limit=5&tenant.rateLimitConfiguration.sendRegistrationVerification.timePeriodInSeconds=60&__cb_tenant.rateLimitConfiguration.sendTwoFactor.enabled=false&tenant.rateLimitConfiguration.sendTwoFactor.limit=5&tenant.rateLimitConfiguration.sendTwoFactor.timePeriodInSeconds=60".getBytes(StandardCharsets.UTF_8));
      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(uri)
                                       .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                                       .header("Accept-Encoding", "gzip, deflate, br")
                                       .header("Accept-Language", "en-US,en;q=0.5")
                                       .header("Cache-Control", "max-age=0")
                                       .header("Connection", "keep-alive")
                                       .header("Content-Type", "application/x-www-form-urlencoded")
                                       .header("Cookie", "_pk_id.1.8f36=19a58ac7d2eae34e.1703797254.; _pk_ses.1.8f36=1; fusionauth.locale=en; fusionauth.timezone=America/Denver; fusionauth.trusted-device.LJ9DfxybqbRJDIEar0MjOs1Dh9t4CUII7Ynx6BwZTbM=vAZ_0ETfK-utHZr6ErKdZm3s13LnYSmo8v417oiOB2wYmD09Nb_EYTcZ0RZFfkFf; fusionauth.known-device.LJ9DfxybqbRJDIEar0MjOs1Dh9t4CUII7Ynx6BwZTbM=oR3hEmqnAZQNtsYHG-3iSfkTVaohg0AQx_4WTfIS_213Lz6hxqPrBoj5LePRFqQd; federated.csrf=_I0Ug4kFjA7XhWva; fusionauth.sso=AoG7EJ2m0K5rARXr8LtYE_mjHpZSI2gMuHSzl-LD3e9SyBTTQszseRICR14rbUiqz7cwDfI3FgNcWbJBjvge506EHUfwBw-zGaM6pkDVoXfDIrOJdNUuCa8Ypzlt9lA6EqlTXZVWucErgXU1GxzikDA; fusionauth.remember-device=QkJCARHvawOLQQo6u55SnnjQnB9azwdr-fk7WmSc7NEEokml; fusionauth.rt=532xIZvgP15j7_s6XwKSh33Fj10waD8GeRqE7nEQe4D4CZl2VSitmQ; fusionauth.at=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCIsImd0eSI6WyJhdXRob3JpemF0aW9uX2NvZGUiXSwia2lkIjoiY2QwYWIwYmUzIn0.eyJhdWQiOiIzYzIxOWU1OC1lZDBlLTRiMTgtYWQ0OC1mNGY5Mjc5M2FlMzIiLCJleHAiOjE3MDU3MDI2NzksImlhdCI6MTcwNTcwMjYxOSwiaXNzIjoiYWNtZS5jb20iLCJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEiLCJqdGkiOiIwNzI5YjczMy03OGQ1LTQwNDAtOTY5Ni0zNWVjYWQ5YWYyZDgiLCJhdXRoZW50aWNhdGlvblR5cGUiOiJQSU5HIiwiZW1haWwiOiJhZG1pbkBmdXNpb25hdXRoLmlvIiwiZW1haWxfdmVyaWZpZWQiOnRydWUsImFwcGxpY2F0aW9uSWQiOiIzYzIxOWU1OC1lZDBlLTRiMTgtYWQ0OC1mNGY5Mjc5M2FlMzIiLCJzY29wZSI6Im9mZmxpbmVfYWNjZXNzIiwicm9sZXMiOlsiYWRtaW4iXSwic2lkIjoiODRhNGZmYzMtMWMzYy00OTA3LWIwZTgtZmY0NWNmNDFjMTkxIiwiYXV0aF90aW1lIjoxNzA1NzAyNjE5LCJ0aWQiOiIzMDY2MzEzMi02NDY0LTY2NjUtMzAzMi0zMjY0NjY2MTM5MzQifQ.twCgoWKPVLJuBaTCGTMdKWto8XICHpCk6zp2QkSIjNM; io.fusionauth.app.action.admin.tenant.IndexAction$s=QkJCA9x7yVuN7noZSN59WZMzJZGPcjSGAJjVr5ghJbPz3sOOcoCAS7PrcyMXBax0Cb6JdqCebWfX0tD4Y3lt5EK9KM8=")
                                       .header("Origin", "https://local.fusionauth.io:9013")
                                       .header("Referer", "https://local.fusionauth.io:9013/")
                                       .header("Sec-Fetch-Dest", "document")
                                       .header("Sec-Fetch-Mode", "navigate")
                                       .header("Sec-Fetch-Site", "same-origin")
                                       .header("Sec-Fetch-User", "?1")
                                       .header("Sec-GPC", "1")
                                       .header("Upgrade-Insecure-Requests", "1")
                                       .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                       .header("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Brave\";v=\"120\"")
                                       .header("sec-ch-ua-mobile", "?0")
                                       .header("sec-ch-ua-platform", "\"macOS\"")
                                       .POST(body)
                                       .build();

      var response = client.send(request, r -> BodySubscribers.ofInputStream());
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

    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler).start()) {
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
  public void utf8HeaderValues(String scheme) throws Exception {
    var city = "São Paulo";

    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader(Headers.ContentLength, "" + ExpectedResponse.getBytes().length);
      res.setHeader("X-Response-Header", city);
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write((ExpectedResponse).getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    // Java HttpClient only supports ASCII header values, so send request directly
    try (HTTPServer ignore = makeServer(scheme, handler).start();
         Socket socket = makeClientSocket(scheme)) {

      var os = socket.getOutputStream();
      os.write(String.format("""
                         GET /api/status HTTP/1.1\r
                         Host: localhost:42\r
                         X-Request-Header: %s\r
                         \r
                         """, city)
                     .getBytes(StandardCharsets.UTF_8));
      os.flush();

      var expectedResponse = String.format("""
          HTTP/1.1 200 \r
          connection: keep-alive\r
          content-type: text/plain\r
          content-length: 16\r
          x-response-header: %s\r
          \r
          {"version":"42"}""", city);

      assertHTTPResponseEquals(socket, expectedResponse);
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

    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
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
