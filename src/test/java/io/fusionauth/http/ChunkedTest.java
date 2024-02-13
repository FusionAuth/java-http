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
import java.util.function.Supplier;

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
 * Tests the HTTP server.
 *
 * @author Brian Pontarelli
 */
public class ChunkedTest extends BaseTest {
  public static final String ExpectedResponse = "{\"version\":\"42\"}";

  public static final String RequestBody = "{\"message\":\"Hello World\"";

  @Test(dataProvider = "schemes")
  public void chunkedRequest(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertTrue(req.isChunked());

      try {
        byte[] body = req.getInputStream().readAllBytes();
        assertEquals(new String(body), RequestBody);
      } catch (IOException e) {
        fail("Unable to parse body", e);
      }

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
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      Supplier<InputStream> supplier = () -> new ByteArrayInputStream(RequestBody.getBytes());
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofInputStream(supplier)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
      assertEquals(instrumenter.getChunkedRequests(), 1);
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
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
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
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
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
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
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
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
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
    assertEquals(instrumenter.getChunkedResponses(), 100_000);
  }
}