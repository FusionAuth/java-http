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
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.io.MultipartConfiguration;
import io.fusionauth.http.io.MultipartFileUploadPolicy;
import io.fusionauth.http.server.CountingInstrumenter;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPServer;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

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
      Content-Type: text/plain; charset=ISO-8859-1\r
      \r
      filecontents\r
      ------WebKitFormBoundaryTWfMVJErBoLURJIe--""";

  public static final String ExpectedResponse = "{\"version\":\"42\"}";

  @Test(dataProvider = "schemes")
  public void post(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(req.getContentType(), "multipart/form-data");

      Map<String, List<String>> form = req.getFormData();
      assertEquals(form.get("foo"), List.of("bar"));

      List<FileInfo> files = req.getFiles();
      assertEquals(files.getFirst().getContentType(), "text/plain");
      assertEquals(files.getFirst().getEncoding(), StandardCharsets.ISO_8859_1);
      assertEquals(files.getFirst().getName(), "file");
      assertEquals(Files.readString(files.getFirst().getFile()), "filecontents");

      Files.delete(files.getFirst().getFile());

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
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter)
        .withMultipartConfiguration(new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow))
        .start();
         var client = makeClient(scheme, null)) {
      URI uri = makeURI(scheme, "");
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
        .withFileSize(10 * 1024 * 1024) // 10 Mb
        .withConfiguration(new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow)
                                                       // Max file size is 2Mb
                                                       .withMaxFileSize(2 * 1024 * 1024)
                                                       // Max request size is 15 Mb
                                                       .withMaxRequestSize(15 * 1024 * 1024))
        .expectResponse("""
            HTTP/1.1 413 \r
            connection: close\r
            content-length: 0\r
            \r
            """)
        .expectExceptionOnWrite(SocketException.class);
  }

  @Test(dataProvider = "schemes")
  public void post_server_configuration_file_upload_allow(String scheme) throws Exception {
    // File uploads allowed
    withScheme(scheme)
        .withFileCount(5)
        .withConfiguration(new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow))
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-type: application/json\r
            content-length: 16\r
            \r
            {"version":"42"}""")
        .expectNoExceptionOnWrite();
  }

  @Test(dataProvider = "schemes")
  public void post_server_configuration_file_upload_ignore(String scheme) throws Exception {
    // File uploads ignored
    withScheme(scheme)
        .withFileCount(5)
        .withConfiguration(new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Ignore))
        // Ignored means that we will not see any files in the request handler
        .expectedFileCount(0)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-type: application/json\r
            content-length: 16\r
            \r
            {"version":"42"}""")
        .expectNoExceptionOnWrite();
  }

  @Test(dataProvider = "schemes")
  public void post_server_configuration_file_upload_reject(String scheme) throws Exception {
    // File uploads rejected
    withScheme(scheme)
        .withConfiguration(new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Reject))
        // 5 * 1 Mb = 5 Mb
        .withFileCount(5)
        .withFileSize(1024 * 1024)
        .expectResponse("""
            HTTP/1.1 422 \r
            connection: close\r
            content-length: 0\r
            \r
            """)
        // If the request is large enough, because we throw an exception before we have emptied the InputStream
        // we will take an exception while trying to write all the bytes to the server.
        .expectExceptionOnWrite(SocketException.class);

  }

  @Test(dataProvider = "schemes")
  public void post_server_configuration_requestTooBig(String scheme) throws Exception {
    // Request too big, file size is ok, overall request size too big.
    withScheme(scheme)
        .withFileSize(1024 * 1024)   // 1 Mb
        .withFileCount(10)           // 10 files
        .withConfiguration(new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow)
                                                       // Max file size is 2 Mb bytes
                                                       .withMaxFileSize(2 * 1024 * 1024)
                                                       // Max request size is 3 Mb
                                                       .withMaxRequestSize(3 * 1024 * 1024))
        .expectResponse("""
            HTTP/1.1 413 \r
            connection: close\r
            content-length: 0\r
            \r
            """)
        // If the request is large enough, because we throw an exception before we have emptied the InputStream
        // we will take an exception while trying to write all the bytes to the server.
        .expectExceptionOnWrite(SocketException.class);
  }

  private Builder withConfiguration(MultipartConfiguration configuration) throws Exception {
    return new Builder("http").withConfiguration(configuration);
  }

  private Builder withScheme(String scheme) {
    return new Builder(scheme);
  }

  @SuppressWarnings({"StringConcatenationInLoop", "unused", "UnusedReturnValue"})
  private class Builder {
    private MultipartConfiguration configuration;

    private int expectedFileCount = 1;

    private int fileCount = 1;

    private int fileSize = 42;

    private String scheme;

    private Exception thrownOnWrite;

    public Builder(String scheme) {
      this.scheme = scheme;
    }

    public Builder expectExceptionOnWrite(Class<? extends Exception> clazz) {
      assertNotNull(thrownOnWrite);
      assertEquals(thrownOnWrite.getClass(), clazz);
      return this;
    }

    public Builder expectNoExceptionOnWrite() {
      assertNull(thrownOnWrite);
      return this;
    }

    public Builder expectResponse(String response) throws Exception {
      HTTPHandler handler = (req, res) -> {
        assertEquals(req.getContentType(), "multipart/form-data");

        Map<String, List<String>> form = req.getFormData();
        assertEquals(form.get("foo"), List.of("bar"));

        List<FileInfo> files = req.getFiles();
        assertEquals(files.size(), expectedFileCount);
        for (FileInfo file : files) {
          assertEquals(file.getContentType(), "text/plain");
          assertEquals(file.getEncoding(), StandardCharsets.ISO_8859_1);
          assertEquals(file.getName(), "file");
          assertEquals(Files.readString(file.getFile()), "X".repeat(fileSize));
          Files.delete(file.getFile());
        }

        res.setHeader(Headers.ContentType, "application/json");
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

      // Server level configuration : File uploads are disabled
      try (HTTPServer ignore = makeServer(scheme, handler, null)
          .withInitialReadTimeout(Duration.ofSeconds(30))
          .withKeepAliveTimeoutDuration(Duration.ofSeconds(30))
          .withMinimumWriteThroughput(1024)
          .withMinimumReadThroughput(1024)
          .withMultipartConfiguration(configuration)
          .start();
           Socket socket = makeClientSocket(scheme)) {

        socket.setSoTimeout((int) Duration.ofSeconds(30).toMillis());

        // Build the request body per the builder specs.
        String boundary = "-----WebKitFormBoundaryTWfMVJErBoLURJIe";
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
              Content-Type: text/plain; charset=ISO-8859-1\r
              \r
              {file}\r
              """.replace("{file}", file);
        }

        body += boundary + "--";

        int contentLength = body.getBytes(StandardCharsets.UTF_8).length;
        String request = """
            POST / HTTP/1.1\r
            Host: cyberdyne-systems.com\r
            Content-Length: {contentLength}\r
            Content-Type: multipart/form-data; boundary=---WebKitFormBoundaryTWfMVJErBoLURJIe\r
            \r
            {body}""".replace("{body}", body)
                     .replace("{contentLength}", contentLength + "");

        var os = socket.getOutputStream();
        // Do our best to write, but catch exceptions, and continue, and we can optionally asert on them.
        try {
          os.write(request.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
          thrownOnWrite = e;
        }

        assertHTTPResponseEquals(socket, response);
      }

      return this;
    }

    public Builder expectedFileCount(int expectedFileCount) {
      this.expectedFileCount = expectedFileCount;
      return this;
    }

    public Builder withConfiguration(MultipartConfiguration configuration) {
      this.configuration = configuration;
      return this;
    }

    public Builder withFileCount(int fileCount) {
      this.fileCount = fileCount;
      this.expectedFileCount = fileCount;
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
