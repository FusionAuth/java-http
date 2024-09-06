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
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import io.fusionauth.http.HTTPValues.ContentEncodings;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.server.CountingInstrumenter;
import io.fusionauth.http.server.HTTPHandler;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.AssertJUnit.fail;

/**
 * Tests the HTTP server handles compression properly.
 *
 * @author Brian Pontarelli
 */
public class CompressionTest extends BaseTest {
  private final Path file = Paths.get("src/test/java/io/fusionauth/http/ChunkedTest.java");

  @DataProvider(name = "chunkedSchemes")
  public Object[][] chunkedSchemes() {
    return new Object[][]{
        {true, "http"},
        {true, "https"},
        {false, "http"},
        {false, "https"}
    };
  }

  @Test(dataProvider = "compressedChunkedSchemes")
  public void compress(String encoding, boolean chunked, String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      // Testing an indecisive user, can't make up their mind... this is allowed as long as you have not written ay bytes.
      res.setCompress(true);
      res.setCompress(false);
      res.setCompress(true);
      res.setCompress(false);
      res.setCompress(true);

      res.setHeader(Headers.ContentType, "text/plain");
      res.setStatus(200);

      if (!chunked) {
        res.setContentLength(Files.size(file));
      }

      try (InputStream is = Files.newInputStream(file)) {
        OutputStream outputStream = res.getOutputStream();
        is.transferTo(outputStream);

        // Try to call setCompress, expect an exception - we cannot change modes once we've written to the OutputStream.
        try {
          res.setCompress(false);
          fail("Expected setCompress(false) to fail hard!");
        } catch (IllegalStateException expected) {
        }

        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder().header(Headers.AcceptEncoding, encoding).uri(uri).GET().build(),
          r -> BodySubscribers.ofInputStream()
      );

      var result = new String(
          encoding.equals(ContentEncodings.Deflate)
              ? new InflaterInputStream(response.body()).readAllBytes()
              : new GZIPInputStream(response.body()).readAllBytes(), StandardCharsets.UTF_8);

      assertEquals(response.headers().firstValue(Headers.ContentEncoding).orElse(null), encoding);
      assertEquals(response.statusCode(), 200);
      assertEquals(result, Files.readString(file));
    }
  }

  @Test(dataProvider = "compressedChunkedSchemes")
  public void compress_onByDefault(String encoding, boolean chunked, String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      // Use case, do not call response.setCompress(true)
      res.setHeader(Headers.ContentType, "text/plain");
      res.setStatus(200);

      if (!chunked) {
        res.setContentLength(Files.size(file));
      }

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
          HttpRequest.newBuilder().header(Headers.AcceptEncoding, encoding).uri(uri).GET().build(),
          r -> BodySubscribers.ofInputStream()
      );

      var result = new String(
          encoding.equals(ContentEncodings.Deflate)
              ? new InflaterInputStream(response.body()).readAllBytes()
              : new GZIPInputStream(response.body()).readAllBytes(), StandardCharsets.UTF_8);

      assertEquals(response.headers().firstValue(Headers.ContentEncoding).orElse(null), encoding);
      assertEquals(response.statusCode(), 200);
      assertEquals(result, Files.readString(file));
    }
  }

  @Test(enabled = false, groups = "performance")
  public void compress_performance() throws Exception {
    HTTPHandler handler = (req, res) -> {
      // Use case, do not call response.setCompress(true)
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

    for (boolean compress : List.of(true, false)) {
      CountingInstrumenter instrumenter = new CountingInstrumenter();
      try (var client = makeClient("http", null); var ignore = makeServer("http", handler, instrumenter).withCompressByDefault(compress).start()) {
        URI uri = makeURI("http", "");
        int counter = 25_000;
        long start = System.currentTimeMillis();
        for (int i = 0; i < counter; i++) {
          var response = compress
              ? client.send(HttpRequest.newBuilder().header(Headers.AcceptEncoding, ContentEncodings.Gzip).uri(uri).GET().build(), r -> BodySubscribers.ofInputStream())
              : client.send(HttpRequest.newBuilder().header(Headers.AcceptEncoding, ContentEncodings.Gzip).uri(uri).GET().build(), r -> BodySubscribers.ofString(StandardCharsets.UTF_8));

          var result = compress
              ? new String(new GZIPInputStream((InputStream) response.body()).readAllBytes(), StandardCharsets.UTF_8)
              : response.body();

          assertEquals(response.headers().firstValue(Headers.ContentEncoding).orElse(null), compress ? ContentEncodings.Gzip : null);
          assertEquals(response.statusCode(), 200);
          assertEquals(result, Files.readString(file));
        }

        // With small requests like this, we expect compression to be a bit slower.
        //
        // [compression: true ][25,000] requests. Total time [7,677] ms, Avg [0.30708] ms
        // [compression: false][25,000] requests. Total time [3,298] ms, Avg [0.13192] ms

        long end = System.currentTimeMillis();
        long total = end - start;
        @SuppressWarnings("BigDecimalMethodWithoutRoundingCalled")
        BigDecimal avg = new BigDecimal(total).divide(new BigDecimal(counter));
        DecimalFormat formatter = new DecimalFormat("#,###");
        @SuppressWarnings("ConstantConditions")
        String mode = compress ? compress + " " : compress + "";
        System.out.println("[compression: " + mode + "][" + formatter.format(counter) + "] requests. Total time [" + (formatter.format(total)) + "] ms, Avg [" + avg + "] ms");
      }
    }

  }

  @DataProvider(name = "compressedChunkedSchemes")
  public Object[][] compressedChunkedSchemes() {
    return new Object[][]{
        // encoding, chunked, schema

        // Chunked http
        {ContentEncodings.Deflate, true, "http"},
        {ContentEncodings.Gzip, true, "http"},

        // Chunked https
        {ContentEncodings.Deflate, true, "https"},
        {ContentEncodings.Gzip, true, "https"},

        // Non chunked http
        {ContentEncodings.Deflate, false, "http"},
        {ContentEncodings.Gzip, false, "http"},

        // Non chunked https
        {ContentEncodings.Deflate, false, "https"},
        {ContentEncodings.Gzip, false, "https"}
    };
  }

  @Test(dataProvider = "chunkedSchemes")
  public void requestedButNotAccepted(boolean chunked, String scheme) throws Exception {
    // Use case: setCompress(true), but the request does not contain the 'Accept-Encoding' header.
    //     Result: no compression
    HTTPHandler handler = (req, res) -> {
      res.setCompress(true);
      res.setHeader(Headers.ContentType, "text/plain");
      res.setStatus(200);

      if (!chunked) {
        res.setContentLength(Files.size(file));
      }

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
          HttpRequest.newBuilder().uri(uri).GET().build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertNull(response.headers().firstValue(Headers.ContentEncoding).orElse(null));
      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), Files.readString(file));
    }
  }

  @Test(dataProvider = "chunkedSchemes")
  public void requestedButNotAccepted_unSupportedEncoding(boolean chunked, String scheme) throws Exception {
    // Use case: setCompress(true), and 'Accept-Encoding: br' which is valid, but not yet supported
    //     Result: no compression
    HTTPHandler handler = (req, res) -> {
      res.setCompress(true);
      res.setHeader(Headers.ContentType, "text/plain");
      res.setStatus(200);

      if (!chunked) {
        res.setContentLength(Files.size(file));
      }

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
          HttpRequest.newBuilder().header(Headers.AcceptEncoding, "br").uri(uri).GET().build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertNull(response.headers().firstValue(Headers.ContentEncoding).orElse(null));
      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), Files.readString(file));
    }
  }
}