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
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.server.AlwaysContinueExpectValidator;
import io.fusionauth.http.server.CountingInstrumenter;
import io.fusionauth.http.server.ExpectValidator;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPServer;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Tests the HTTP server response to 'Expect: 100 Continue'.
 *
 * @author Brian Pontarelli
 */
public class ExpectTest extends BaseTest {
  public static final String ExpectedResponse = "{\"version\":\"42\"}";

  public static final String RequestBody = "{\"message\":\"Hello World\"";

  @Test(dataProvider = "schemes")
  public void expect(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      println("Handling");
      assertEquals(req.getHeader(Headers.ContentType), "application/json"); // Mixed case

      try {
        println("Reading");
        byte[] body = req.getInputStream().readAllBytes();
        assertEquals(new String(body), RequestBody);
      } catch (IOException e) {
        fail("Unable to parse body", e);
      }

      println("Done");
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader("Content-Length", "16");
      res.setStatus(200);

      try {
        println("Writing");
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    AtomicBoolean validated = new AtomicBoolean(false);
    ExpectValidator validator = (req, res) -> {
      println("Validating");
      validated.set(true);
      assertEquals(req.getContentType(), "application/json");
      assertEquals((long) req.getContentLength(), RequestBody.length());
      res.setStatus(100);
      res.setStatusMessage("Continue");
    };

    // Test w/ and w/out a custom expect validator. The default behavior should be to approve the payload.
    CountingInstrumenter instrumenter = new CountingInstrumenter();
    boolean[] validationOptions = {true, false};
    for (boolean validation : validationOptions) {
      ExpectValidator expectValidator = validation
          ? validator                             // Custom
          : new AlwaysContinueExpectValidator();  // Default

      try (HTTPServer ignore = makeServer(scheme, handler, instrumenter, expectValidator).start(); var client = makeClient(scheme, null)) {
        URI uri = makeURI(scheme, "");
        var response = client.send(
            HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").expectContinue(true).POST(BodyPublishers.ofString(RequestBody)).build(),
            r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), ExpectedResponse);
        assertTrue(validated.get());
      }
    }
  }

  @Test(dataProvider = "schemes")
  public void expectReject(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> fail("Should not have been called");

    ExpectValidator validator = (req, res) -> {
      println("Validating");
      assertEquals(req.getContentType(), "application/json");
      assertEquals((long) req.getContentLength(), RequestBody.length());
      res.setStatus(417);
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter, validator).start(); var client = makeClient(scheme, null)) {
      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").expectContinue(true).POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 417);
      assertEquals(response.body(), "");
    }
  }
}
