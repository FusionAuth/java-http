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

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPServer;
import static org.testng.Assert.assertEquals;

/**
 * A base class to provide some helpers for socket based tests. A socket test doesn't use an HTTP client, but instead manually writes to the
 * socket in order to have more control over the input.
 *
 * @author Daniel DeGroff
 */
public abstract class BaseSocketTest extends BaseTest {
  protected Builder withRequest(String request) {
    return new Builder(request);
  }

  private void assertResponse(String request, String chunkedExtension, String response) throws Exception {
    HTTPHandler handler = (req, res) -> {
      // Read the request body
      req.getInputStream().readAllBytes();
      res.setStatus(200);
    };

    try (HTTPServer ignore = makeServer("http", handler)
        .withReadThroughputCalculationDelayDuration(Duration.ofMinutes(2))
        .withWriteThroughputCalculationDelayDuration(Duration.ofMinutes(2))

        // Using various timeouts to make it easier to debug which one we are hitting.
        .withKeepAliveTimeoutDuration(Duration.ofSeconds(23))
        .withInitialReadTimeout(Duration.ofSeconds(19))
        .withProcessingTimeoutDuration(Duration.ofSeconds(27))

        // Default is 8k, reduce this 512 to ensure we overflow this and have to read from the input stream again
        .withRequestBufferSize(512)
        .start();
         Socket socket = makeClientSocket("http")) {

      socket.setSoTimeout((int) Duration.ofSeconds(30).toMillis());

      var bodyString = "These pretzels are making me thirsty. ";
      // Ensure this is larger than the default configured size for the request buffer.
      // - This body is added to each request to ensure we correctly drain the InputStream before we can write the HTTP response.
      // - This should ensure that the body is the length of the (BodyString x 2) larger than the configured request buffer. This ensures
      //   that there are bytes remaining in the InputStream after we have parsed the preamble.
      var requestBufferSize = ignore.configuration().getRequestBufferSize();
      var body = bodyString.repeat(((requestBufferSize / bodyString.length())) * 2);

      if (request.contains("Transfer-Encoding: chunked")) {
        //noinspection ExtractMethodRecommender
        var result = "";
        // Chunk in 100 byte increments. Using a smaller chunk size to ensure we don't end up with a single chunk.
        int chunkSize = 100;
        for (var i = 0; i < body.length(); i += chunkSize) {
          var endIndex = Math.min(i + chunkSize, body.length());
          var chunk = body.substring(i, endIndex);
          var chunkLength = chunk.getBytes(StandardCharsets.UTF_8).length;
          String hex = Integer.toHexString(chunkLength);
          //noinspection StringConcatenationInLoop
          result += hex;

          if (chunkedExtension != null) {
            result += chunkedExtension;
          }

          result += ("\r\n" + chunk + "\r\n");
        }
        body = result + "0\r\n\r\n";
      }

      request = request.replace("{body}", body);
      var contentLength = body.getBytes(StandardCharsets.UTF_8).length;
      request = request.replace("{contentLength}", contentLength + "");

      // Ensure the caller didn't add an extra line return to the request.
      int bodyStart = request.indexOf("\r\n\r\n") + 4;
      String payload = request.substring(bodyStart);
      assertEquals(contentLength, payload.getBytes(StandardCharsets.UTF_8).length, "Check the value you provided for 'withRequest' it looks like you may have a trailing line return or something.\n");

      var os = socket.getOutputStream();
      os.write(request.getBytes(StandardCharsets.UTF_8));

      // 1. Write bytes, the content-length is short by one byte.
      // 2. Next byte \n
      // 3. Success.
      // 4. Next keep-alive request reads preamble, and reads the \n and throws an exception

      assertHTTPResponseEquals(socket, response);
    }
  }

  protected class Builder {
    public String chunkedExtension;

    public String request;

    public Builder(String request) {
      this.request = request;
    }

    public void expectResponse(String response) throws Exception {
      assertResponse(request, chunkedExtension, response);
    }

    public Builder withChunkedExtension(String extension) {
      chunkedExtension = extension;
      return this;
    }
  }
}
