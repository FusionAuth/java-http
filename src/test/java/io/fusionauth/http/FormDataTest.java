/*
 * Copyright (c) 2025, FusionAuth, All Rights Reserved
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import io.fusionauth.http.HTTPValues.ContentTypes;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPServer;
import io.fusionauth.http.server.HTTPServerConfiguration;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

/**
 * Tests the HTTP server with application/x-www-form-urlencoded requests.
 *
 * @author Daniel DeGroff
 */
public class FormDataTest extends BaseTest {
  public static final String ExpectedResponse = """
      {
        "version": "42"
      }
      """
      .replaceAll("\\s", "");

  @Test(dataProvider = "schemesAndChunked")
  public void post_server_configuration_max_form_data(String scheme, boolean chunked) throws Exception {
    // File too big even though the overall request size is ok.
    withScheme(scheme)
        .withChunked(chunked)
        .withBodyParameterCount(4096)
        .withBodyParameterSize(32)
        // Body is [180,223]
        //
        // Account for the equals size and a separator of & except for the first value
        // - This should mean we have just exactly the right size of configuration for this request body
        // Config is [180,223]
        .withConfiguration(config -> config.withMaxRequestBodySize(Map.of(ContentTypes.Form, (4096 * 10) + (4096 * 32) + (4096 * 2) - 1)))
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-type: application/json\r
            content-length: 16\r
            \r
            {"version":"42"}""")
        .expectNoExceptionOnWrite();

    // Exceeded
    withScheme(scheme)
        .withChunked(chunked)
        .withBodyParameterCount(42 * 1024)
        .withBodyParameterSize(128)
        // 4k * 33 > 128k
        // - Use a UC Content-Type to make sure it still works
        .withConfiguration(config -> config.withMaxRequestBodySize(Map.of(ContentTypes.Form.toUpperCase(Locale.ROOT), 128 * 1024)))
        .expectResponse("""
            HTTP/1.1 413 \r
            connection: close\r
            content-length: 0\r
            \r
            """)
        .assertOptionalExceptionOnWrite(SocketException.class);

    // Large, but max size has been disabled
    withScheme(scheme)
        .withChunked(chunked)
        .withBodyParameterCount(42 * 1024)
        .withBodyParameterSize(128)
        // Disable the limit
        .withConfiguration(config -> config.withMaxRequestBodySize(Map.of(ContentTypes.Form, -1)))
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-type: application/json\r
            content-length: 16\r
            \r
            {"version":"42"}""")
        .expectNoExceptionOnWrite();

    // Large, enforce using default w/out a specific configuration for application/x-www-form-urlencoded
    withScheme(scheme)
        .withChunked(chunked)
        .withBodyParameterCount(42 * 1024)
        .withBodyParameterSize(128)
        // Remove the limit for form data, and fall back to the global
        .withConfiguration(config -> config.withMaxRequestBodySize(Map.of("*", 128 * 1024)))
        .expectResponse("""
            HTTP/1.1 413 \r
            connection: close\r
            content-length: 0\r
            \r
            """)
        .assertOptionalExceptionOnWrite(SocketException.class);
  }

  @Test(dataProvider = "schemes")
  public void post_server_configuration_max_request_header_size(String scheme) throws Exception {
    // Header size ok.
    withScheme(scheme)
        // Try and get as close as we can to the maximum. Default is 128k, which is 131,072 bytes.
        //
        // Name is 11, followed by ': ' which is 2 characters, adding a line return.
        //  Example: header00000: X{64}\n
        // http: (1655 * (11 + 2 + 64 + 2)) + 274 = 131,019
        //   - This will include HTTP 2 upgrade headers
        // https: (1655 * (11 + 2 + 64 + 2)) + 175 = 130,920
        .withHeaderCount(1655)
        .withHeaderSize(64)
        // Default is 128k, this should fit.
        // - The header size includes the request line, and the request headers.
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-type: application/json\r
            content-length: 16\r
            \r
            {"version":"42"}""")
        .expectNoExceptionOnWrite();

    // Headers too big
    withScheme(scheme)
        // Default config, 128 Kilobytes
        //   2048 * 64 == 131,072 + request line will exceed 128k
        .withHeaderCount(1024 * 1024)
        .withHeaderSize(128)
        .expectResponse("""
            HTTP/1.1 431 \r
            connection: close\r
            content-length: 0\r
            \r
            """)
        .assertOptionalExceptionOnWrite(SocketException.class);

    // Same big size, but disable header size limit.
    withScheme(scheme)
        .withHeaderCount(1024 * 1024)
        .withHeaderSize(128)
        // Disable the limit
        .withConfiguration(config -> config.withMaxRequestHeaderSize(-1))
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-type: application/json\r
            content-length: 16\r
            \r
            {"version":"42"}""")
        .expectNoExceptionOnWrite();
  }

  private Builder withScheme(String scheme) {
    return new Builder(scheme);
  }

  @SuppressWarnings({"unused", "UnusedReturnValue"})
  private class Builder {
    private int bodyParameterCount = 1;

    private int bodyParameterSize = 42;

    private boolean chunked;

    private Consumer<HTTPServerConfiguration> configuration;

    private int headerCount;

    private int headerSize;

    private String scheme;

    private Exception thrownOnWrite;

    public Builder(String scheme) {
      this.scheme = scheme;
    }

    public Builder assertOptionalExceptionOnWrite(Class<? extends Exception> clazz) {
      // Note that this assertion really depends upon the system the test is run on, the size of the request, and the amount of data that can be cached.
      // - So this is an optional assertion - if exception is not null, then we should be able to assert some attributes.
      // - With the larger sizes this exception is mostly always thrown when running tests locally, but in GHA, it doesn't always occur.
      if (thrownOnWrite != null) {
        assertEquals(thrownOnWrite.getClass(), clazz);
      }

      return this;
    }

    public Builder expectNoExceptionOnWrite() {
      assertNull(thrownOnWrite);
      return this;
    }

    public Builder expectResponse(String response) throws Exception {
      HTTPHandler handler = (req, res) -> {
        assertEquals(req.getContentType(), "application/x-www-form-urlencoded");

        Map<String, List<String>> form = req.getFormData();
        assertEquals(form.size(), bodyParameterCount);

        String value = "X".repeat(bodyParameterSize);
        for (int i = 0; i < bodyParameterCount; i++) {
          assertEquals(form.get("param" + String.format("%05d", i)), List.of(value));
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

      //noinspection resource
      HTTPServer server = makeServer(scheme, handler, null)
          .withMinimumWriteThroughput(-1)
          .withMinimumReadThroughput(-1)

          .withReadThroughputCalculationDelayDuration(Duration.ofMinutes(2))
          .withWriteThroughputCalculationDelayDuration(Duration.ofMinutes(2))

          // Using various timeouts to make it easier to debug which one we are hitting.
          .withKeepAliveTimeoutDuration(Duration.ofSeconds(23))
          .withInitialReadTimeout(Duration.ofSeconds(19))
          .withProcessingTimeoutDuration(Duration.ofSeconds(27));

      if (configuration != null) {
        configuration.accept(server.configuration());
      }

      try (HTTPServer ignore = server.start();
           Socket socket = makeClientSocket(scheme)) {

        socket.setSoTimeout((int) Duration.ofSeconds(5).toMillis());

        String request = """
            GET / HTTP/1.1\r
            Host: cyberdyne-systems.com\r
            Content-Type: application/x-www-form-urlencoded\r
            """;

        String body = "";
        String value = "X".repeat(bodyParameterSize);
        List<String> bodyParameters = new ArrayList<>();
        for (int i = 0; i < bodyParameterCount; i++) {
          bodyParameters.add("param" + String.format("%05d", i) + "=" + value);
        }

        if (bodyParameters.size() > 0) {
          body += String.join("&", bodyParameters);
        }

        if (chunked) {
          request += """
              Transfer-Encoding: chunked\r
              """;

          // Convert body to chunked
          // - Using a small chunk to ensure we end up with more than one chunk.
          body = new String(chunkEncode(body.getBytes(StandardCharsets.UTF_8), 100, null));
        } else {
          var contentLength = body.getBytes(StandardCharsets.UTF_8).length;
          if (contentLength > 0) {
            request += """
                Content-Length: {contentLength}\r
                """.replace("{contentLength}", contentLength + "");
          }
        }

        List<String> headers = new ArrayList<>();
        for (int i = 0; i < headerCount; i++) {
          headers.add("header" + String.format("%05d", i) + ": " + "X".repeat(headerSize));
        }

        if (headers.size() > 0) {
          request += String.join("\r\n", headers) + "\r\n";
        }

        request = request + "\r\n" + body;

        var os = socket.getOutputStream();
        // Do our best to write, but ignore exceptions.
        try {
          os.write(request.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
          thrownOnWrite = e;
        }

        assertHTTPResponseEquals(socket, response);
      }

      return this;
    }

    public Builder withBodyParameterCount(int bodyParameterCount) {
      this.bodyParameterCount = bodyParameterCount;
      return this;
    }

    public Builder withBodyParameterSize(int bodyParameterSize) {
      this.bodyParameterSize = bodyParameterSize;
      return this;
    }

    public Builder withChunked(boolean chunked) {
      this.chunked = chunked;
      return this;
    }

    public Builder withConfiguration(Consumer<HTTPServerConfiguration> configuration) {
      this.configuration = configuration;
      return this;
    }

    public Builder withHeaderCount(int headerCount) {
      this.headerCount = headerCount;
      return this;
    }

    public Builder withHeaderSize(int headerSize) {
      this.headerSize = headerSize;
      return this;
    }

    public void withScheme(String scheme) {
      this.scheme = scheme;
    }
  }
}
