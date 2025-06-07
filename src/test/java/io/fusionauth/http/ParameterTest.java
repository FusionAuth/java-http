/*
 * Copyright (c) 2021-2025, FusionAuth, All Rights Reserved
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

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.fusionauth.http.HTTPValues.ContentTypes;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.server.CountingInstrumenter;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPServer;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * Tests the URL parameters and form data.
 *
 * @author Brian Pontarelli
 */
public class ParameterTest extends BaseTest {
  @Test(dataProvider = "schemes")
  public void form(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(req.getPath(), "/api/system/version");
      assertEquals(req.getURLParameters().get("one"), List.of("two"));
      assertEquals(req.getURLParameters().get("three"), List.of("four"));
      assertEquals(req.getFormData().get("five"), List.of("six"));
      assertEquals(req.getFormData().get("seven"), List.of("eight"));
      assertEquals(req.getParameters().get("one"), List.of("two", "again"));
      assertEquals(req.getParameters().get("three"), List.of("four"));
      assertEquals(req.getParameters().get("five"), List.of("six"));
      assertEquals(req.getParameters().get("seven"), List.of("eight"));

      res.setStatus(200);
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "?one=two&three=four");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, ContentTypes.Form).POST(BodyPublishers.ofString("one=again&five=six&seven=eight")).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(instrumenter.getServers(), 1);
      assertEquals(instrumenter.getConnections(), 1);
    }
  }

  @Test(dataProvider = "schemes")
  public void urlParameters(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(req.getPath(), "/api/system/version");
      assertEquals(req.getURLParameter("one"), "two");
      assertEquals(req.getURLParameter("three"), "four");

      res.setStatus(200);
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "?one=two&three=four");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).GET().build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(instrumenter.getServers(), 1);
      assertEquals(instrumenter.getConnections(), 1);
    }
  }
}
