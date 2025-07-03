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
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.io.MultipartConfiguration;
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
  public void post(String scheme) throws Exception {
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
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter)
        .withMultipartConfiguration(new MultipartConfiguration().withFileUploadEnabled(true))
        .start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder()
                     .uri(uri)
                     .header(Headers.ContentType, "multipart/form-data; boundary=----WebKitFormBoundaryTWfMVJErBoLURJIe")
                     .POST(BodyPublishers.ofString(Body)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
    }
  }

  @Test(dataProvider = "schemes")
  public void post_server_configuration_fileTooBig(String scheme) throws Exception {
    // File too big even though the overall request size is ok.
    withScheme(scheme)
        .withFileSize(513) // 513 bytes
        .withConfiguration(new MultipartConfiguration().withFileUploadEnabled(true)
                                                       // Max file size is 2Mb
                                                       .withMaxFileSize(512)
                                                       // Max request size is 10 Mb
                                                       .withMaxRequestSize(10 * 1024 * 1024))
        .expect(response -> assertEquals(response.statusCode(), 413));
  }

  @Test(dataProvider = "schemes")
  public void post_server_configuration_file_upload_disabled(String scheme) throws Exception {
    // File uploads disabled
    withScheme(scheme)
        .withConfiguration(new MultipartConfiguration().withFileUploadEnabled(false))
        .expect(response -> assertEquals(response.statusCode(), 422));
  }

  @Test(dataProvider = "schemes")
  public void post_server_configuration_requestTooBig(String scheme) throws Exception {
    // Request too big
    withScheme(scheme)
        .withFileSize(512)   // 512 bytes
        .withFileCount(5)    // 5 files, for a total of 2,560 bytes in the request
        .withConfiguration(new MultipartConfiguration().withFileUploadEnabled(true)
                                                       // Max file size is 1024 bytes, our files will be 512
                                                       .withMaxFileSize(1024)
                                                       // Max request size is 2048 bytes
                                                       .withMaxRequestSize(2048))
        .expect(response -> assertEquals(response.statusCode(), 413));
  }

  private Builder withConfiguration(MultipartConfiguration configuration) throws Exception {
    return new Builder("http").withConfiguration(configuration);
  }

  private Builder withScheme(String scheme) throws Exception {
    return new Builder(scheme);
  }

  private class Builder {
    private MultipartConfiguration configuration;

    private int fileCount = 1;

    private int fileSize = 42;

    private String scheme;

    public Builder(String scheme) {
      this.scheme = scheme;
    }

    public void expect(Consumer<HttpResponse<String>> consumer) throws Exception {
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

      // Server level configuration : File uploads are disabled
      try (HTTPServer ignore = makeServer(scheme, handler, null)
          .withClientTimeout(Duration.ofSeconds(30))
          .withMinimumWriteThroughput(1024)
          .withMinimumReadThroughput(1024)
          .withMultipartConfiguration(configuration)
          .start()) {

        // Build the request body per the builder specs.
        String boundary = "------WebKitFormBoundaryTWfMVJErBoLURJIe";
        String body = boundary +
            """
                \r
                Content-Disposition: form-data; name="foo"\r
                \r
                bar\r
                """;

        String file = "X".repeat(fileSize);
        // Append files to the request body
        for (int i = 0; i < fileCount; i++) {
          body += boundary;
          body += """
              \r
              Content-Disposition: form-data; name="file"; filename="foo.jpg"\r
              Content-Type: text/plain; charset=ISO8859-1\r
              \r
              {file}\r
              """.replace("{file}", file);
        }

        body += boundary + "--";


        var uri = makeURI(scheme, "");
        var client = makeClient(scheme, null);
        // File uploads are disabled.
        var response = client.send(
            HttpRequest.newBuilder()
                       .uri(uri)
                       .timeout(Duration.ofSeconds(30))
                       .header(Headers.ContentType, "multipart/form-data; boundary=----WebKitFormBoundaryTWfMVJErBoLURJIe")
                       .POST(BodyPublishers.ofString(body)).build(),
            r -> BodySubscribers.ofString(StandardCharsets.UTF_8));

        consumer.accept(response);
      }
    }

    public Builder withConfiguration(MultipartConfiguration configuration) throws Exception {
      this.configuration = configuration;
      return this;
    }

    public Builder withFileCount(int fileCount) {
      this.fileCount = fileCount;
      return this;
    }

    public Builder withFileSize(int fileSize) {
      this.fileSize = fileSize;
      return this;
    }

    public void withScheme(String scheme) {
      this.scheme = scheme;
    }
  }
}
