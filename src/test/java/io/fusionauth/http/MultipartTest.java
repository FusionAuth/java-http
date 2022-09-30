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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.server.CountingInstrumenter;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPServer;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * Tests the HTTP server with multipart/form-data requests.
 *
 * @author Brian Pontarelli
 */
public class MultipartTest extends BaseTest {
  public static final String Body = """
      ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
      Content-Disposition: form-data; name="foo"\r
      \r
      bar\r
      ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
      Content-Disposition: form-data; name="file"; filename="foo.jpg"\r
      Content-Type: text/plain; charset=ISO8859-1\r
      \r
      filecontents\r
      ------WebKitFormBoundaryTWfMVJErBoLURJIe--""";

  public static final String ExpectedResponse = "{\"version\":\"42\"}";

  @Test(dataProvider = "schemes")
  public void post(String scheme) throws IOException, InterruptedException {
    HTTPHandler handler = (req, res) -> {
      System.out.println("Handling");
      assertEquals(req.getContentType(), "multipart/form-data");

      Map<String, List<String>> form = req.getFormData();
      assertEquals(form.get("foo"), List.of("bar"));

      List<FileInfo> files = req.getFiles();
      assertEquals(files.get(0).getContentType(), "text/plain");
      assertEquals(files.get(0).getEncoding(), StandardCharsets.ISO_8859_1);
      assertEquals(files.get(0).getName(), "file");
      assertEquals(Files.readString(files.get(0).getFile()), "filecontents");

      Files.delete(files.get(0).getFile());

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

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter)) {
      URI uri = makeURI(scheme, "");
      var client = HttpClient.newHttpClient();
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "multipart/form-data; boundary=----WebKitFormBoundaryTWfMVJErBoLURJIe").POST(BodyPublishers.ofString(Body)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
    }
  }
}