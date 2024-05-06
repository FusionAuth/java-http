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
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.InflaterInputStream;

import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.server.HTTPHandler;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * Tests the HTTP/2 server.
 *
 * @author Brian Pontarelli
 */
public class CoreHTTP2Test extends BaseTest {
  @Test(dataProvider = "schemes")
  public void applicationProtocol(String scheme) throws Exception {
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
        outputStream.write(CoreTest.ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "?foo%20=bar%20");
      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(uri)
                                       .version(Version.HTTP_2)
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
      assertEquals(result, CoreTest.ExpectedResponse);
    }
  }
}
