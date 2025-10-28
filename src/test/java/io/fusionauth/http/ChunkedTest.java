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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.inversoft.net.ssl.SSLTools;
import com.inversoft.rest.RESTClient;
import com.inversoft.rest.TextResponseHandler;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.server.CountingInstrumenter;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPServer;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Tests various chunked Transfer-Encoding capabilities of the HTTP server.
 *
 * @author Brian Pontarelli
 */
public class ChunkedTest extends BaseTest {
  public static final String ExpectedResponse = "{\"version\":\"42\"}";

  public static final String RequestBody = "{\"message\":\"Hello World\"}";

  @Test(dataProvider = "schemes")
  public void chunkedRequest(String scheme) throws Exception {
    // Use a large chunked request
    String responseBody = "These pretzels are making me thirsty. ".repeat(16_000);
    byte[] responseBodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);

    HTTPHandler handler = (req, res) -> {
      assertTrue(req.isChunked());

      try {
        byte[] body = req.getInputStream().readAllBytes();
        assertEquals(body, responseBodyBytes);
      } catch (IOException e) {
        fail("Unable to parse body", e);
      }

      byte[] responseBytes = ExpectedResponse.getBytes(StandardCharsets.UTF_8);
      res.setHeader(Headers.ContentType, "application/json");
      res.setHeader("Content-Length", responseBytes.length + "");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(responseBytes);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (var client = makeClient(scheme, null); HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");

      // This ensures we are not just passing the test because we interrupt the InputStream and cause it to not hang.
      for (int i = 0; i < 1_000; i++) {
        var response = client.send(HttpRequest.newBuilder()
                                              .uri(uri)
                                              .header(Headers.ContentType, "text/plain")
                                              .POST(BodyPublishers.ofInputStream(() ->
                                                  new ByteArrayInputStream(responseBodyBytes)))
                                              .build(),
            r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), ExpectedResponse);
        assertEquals(instrumenter.getChunkedRequests(), (i + 1));
      }
    }
  }

  @SuppressWarnings({"SpellCheckingInspection", "GrazieInspection"})
  @Test(dataProvider = "schemes")
  public void chunkedRequest_doNotReadTheInputStream(String scheme) throws Exception {
    // Use a large chunked request
    String responseBody = "These pretzels are making me thirsty. ".repeat(16_000);
    byte[] responseBodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);

    HTTPHandler handler = (req, res) -> {
      assertTrue(req.isChunked());

      //
      // Nobody read the InputStream, the InputStream has gone bad!
      //                        - William Lichter (Can't Hardly Wait)

      // By not reading the InputStream, the server will have to drain it before writing the response.

      byte[] responseBytes = ExpectedResponse.getBytes(StandardCharsets.UTF_8);
      res.setHeader(Headers.ContentType, "application/json");
      res.setHeader("Content-Length", responseBytes.length + "");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(responseBytes);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (var client = makeClient(scheme, null); HTTPServer ignore = makeServer(scheme, handler, instrumenter)
        // Ensure we can drain the entire body. In practice, the number of bytes drained will be larger than the body
        // because it is encoded using Transfer-Encoding: chunked which contains other data. So double it for good measure.
        .withMaximumBytesToDrain(responseBodyBytes.length * 2)
        .start()) {
      URI uri = makeURI(scheme, "");

      // This ensures we are not just passing the test because we interrupt the InputStream and cause it to not hang.
      for (int i = 0; i < 10; i++) {
        var response = client.send(HttpRequest.newBuilder()
                                              .uri(uri)
                                              .header(Headers.ContentType, "text/plain")
                                              // Note that using a InputStream based publisher will caues the JDK to
                                              // enable Transfer-Encoding: chunked
                                              .POST(BodyPublishers.ofInputStream(() ->
                                                  new ByteArrayInputStream(responseBodyBytes)))
                                              .build(),
            r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), ExpectedResponse);
        // We didn't read the InputStream, but the drain() will have - so this will count as a chunkedRequest.
        // - Note if you run this a bunch of times in a tight loop this next check doesn't always line up. I think it is because of the
        //   Java HTTP client being slow. If I just run it 10 times in a loop it is fine, if I run it 100 times in a loop it fails interminttentl
        //   and the error is the chunked requests don't match the iteration count. I can confirm the getChunkedRequests() matches
        //   the number of times we commit the InputStream which causes us to mark the request as chunked.
        assertEquals(instrumenter.getChunkedRequests(), i + 1);
      }
    }
  }

  @Test(dataProvider = "schemes")
  public void chunkedResponse(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
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
    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").GET().build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
      assertEquals(instrumenter.getChunkedResponses(), 1);
    }
  }

  @Test(dataProvider = "schemes")
  public void chunkedResponseRestify(String scheme) {
    String html = """
        Success!
        parm=some values
        theRest=some other values
        """;
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/html; charset=UTF-8");
      res.setHeader(Headers.CacheControl, "no-cache");
      res.setStatus(200);

      try {
        Writer writer = res.getWriter();
        writer.write(html);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      SSLTools.disableSSLValidation();
      URI uri = makeURI(scheme, "");
      var response = new RESTClient<>(String.class, String.class).url(uri.toString())
                                                                 .get()
                                                                 .successResponseHandler(new TextResponseHandler())
                                                                 .errorResponseHandler(new TextResponseHandler())
                                                                 .go();
      assertEquals(response.status, 200);
      assertEquals(response.successResponse, html);
      assertEquals(instrumenter.getChunkedResponses(), 1);
    } finally {
      SSLTools.enableSSLValidation();
    }
  }

  @Test(dataProvider = "schemes")
  public void chunkedResponseStreamingFile(String scheme) throws Exception {
    Path file = Paths.get("src/test/java/io/fusionauth/http/ChunkedTest.java");
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setStatus(200);

      try (InputStream is = Files.newInputStream(file)) {
        OutputStream outputStream = res.getOutputStream();
        is.transferTo(outputStream);
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").GET().build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), Files.readString(file));
      assertEquals(instrumenter.getChunkedResponses(), 1);
    }
  }

  @Test(dataProvider = "schemes")
  public void chunkedResponseWriter(String scheme) throws Exception {
    String html = """
        Success!
        parm=some values
        theRest=some other values
        """;
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/html; charset=UTF-8");
      res.setHeader(Headers.CacheControl, "no-cache");
      res.setStatus(200);

      try {
        Writer writer = res.getWriter();
        writer.write(html);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).GET().build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );
      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), html);
      assertEquals(instrumenter.getChunkedResponses(), 1);
    }
  }

  @Test(dataProvider = "schemes", groups = "performance")
  public void performanceChunked(String scheme) throws Exception {
    verbose = true;
    String responseBody = "These pretzels are making me thirsty. ".repeat(16_000);
    byte[] responseBodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);

    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(responseBodyBytes);
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    int iterations = 15_000;
    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler, instrumenter)
        // Note default max requests per connection is 100k, so we shouldn't be closing the connections based upon the total requests.
        .start()) {
      URI uri = makeURI(scheme, "");
      long start = System.currentTimeMillis();
      long lastLog = start;

      for (int i = 0; i < iterations; i++) {
        var response = client.send(
            HttpRequest.newBuilder().uri(uri).GET().build(),
            r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), responseBody);

        if (System.currentTimeMillis() - lastLog > 5_000) {
          long now = System.currentTimeMillis();
          double currentAverage = (now - start) / (double) i;
          printf("Chunked Performance: Iterations [%,d] Response body [%,d] bytes. Running average is [%f] ms.\n", i, responseBodyBytes.length, currentAverage);
          lastLog = System.currentTimeMillis();
        }
      }

      long end = System.currentTimeMillis();
      double average = (end - start) / (double) iterations;
      printf("Chunked Performance: Iterations [%,d] Response body [%,d] bytes. Final average is [%f] ms.\n", iterations, responseBodyBytes.length, average);

      // HTTP
      // Chunked Performance: Iterations [15,000] Response body [608,000] bytes. Final average is [0.563467] ms.
      // Chunked Performance: Iterations [15,000] Response body [608,000] bytes. Final average is [0.572667] ms
      // - With updates to ChunkedInputStream to read 1 byte at a time until we find the chunkSize
      // Chunked Performance: Iterations [15,000] Response body [608,000] bytes. Final average is [0.567000] ms.
      // - With updates to loop on processChunk
      // Chunked Performance: Iterations [15,000] Response body [608,000] bytes. Final average is [0.587400] ms.
      // Chunked Performance: Iterations [15,000] Response body [608,000] bytes. Final average is [0.588800] ms.
      // - With only a larger buffer size
      // Chunked Performance: Iterations [15,000] Response body [608,000] bytes. Final average is [0.560933] ms.

      // HTTPS
      // Chunked Performance: Iterations [15,000] Response body [608,000] bytes. Final average is [0.998800] ms.
      // Chunked Performance: Iterations [15,000] Response body [608,000] bytes. Final average is [1.025333] ms.
      // - With updates to ChunkedInputStream to read 1 byte at a time until we find the chunkSize
      // Chunked Performance: Iterations [15,000] Response body [608,000] bytes. Final average is [1.047000] ms.
      // - With updates to loop on processChunk
      // Chunked Performance: Iterations [15,000] Response body [608,000] bytes. Final average is [0.984133] ms.
      // Chunked Performance: Iterations [15,000] Response body [608,000] bytes. Final average is [0.997933] ms.
      // - With only a larger buffer size
      // Chunked Performance: Iterations [15,000] Response body [608,000] bytes. Final average is [1.000400] ms.
    }

    // We are using keep-alive, so expect 1 connection, and the total requests accepted, and chunked responses should equal the iteration count.
    // - This assertion does seem to fail every once in a while, and it will be 2 instead of 1. My guess is that this is ok - we don't always know
    //   how an HTTP client is going to work, and it may decide to cycle a connection even if the server didn't force it. We are using the JDK
    //   REST client which does seem to be fairly predictable, but for example, using a REST client that uses HttpURLConnection is much less
    //   predictable, but fast. 😀
    //
    // - Going to call this a pass if we have one or two connections.
    assertTrue(instrumenter.getConnections() == 1 || instrumenter.getConnections() == 2);
    assertEquals(instrumenter.getChunkedResponses(), iterations);
    assertEquals(instrumenter.getAcceptedRequests(), iterations);
  }

  @Test(dataProvider = "schemes")
  public void smallChunkedRequest(String scheme) throws Exception {
    // Use a very small chunked request. This is testing various buffer sizes.

    HTTPHandler handler = (req, res) -> {
      assertTrue(req.isChunked());

      try {
        byte[] body = req.getInputStream().readAllBytes();
        assertEquals(new String(body), RequestBody);
      } catch (IOException e) {
        fail("Unable to parse body", e);
      }

      byte[] responseBytes = ExpectedResponse.getBytes(StandardCharsets.UTF_8);
      res.setHeader(Headers.ContentType, "application/json");
      res.setHeader("Content-Length", responseBytes.length + "");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(responseBytes);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (var client = makeClient(scheme, null); HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder()
                     .uri(uri)
                     .header(Headers.ContentType, "application/json")
                     .POST(BodyPublishers.ofInputStream(() ->
                         new ByteArrayInputStream(RequestBody.getBytes())))
                     .build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
      assertEquals(instrumenter.getChunkedRequests(), 1);
    }
  }
}
