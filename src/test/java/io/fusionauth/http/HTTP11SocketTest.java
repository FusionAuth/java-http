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

import io.fusionauth.http.server.HTTPServer;
import org.testng.annotations.Test;

/**
 * Tests the HTTP server by writing directly to the server and reading using sockets to test lower level semantics.
 *
 * @author Daniel DeGroff
 */
public class HTTP11SocketTest extends BaseTest {
  @Test
  public void duplicate_host_header() throws Exception {
    // Duplicate Host header
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void bad_request() throws Exception {
    // Invalid HTTP header
    withRequest("""
        cat /etc/password\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }


  @Test
  public void duplicate_host_header_withTransferEncoding() throws Exception {
    // Duplicate Host header w/ Transfer-Encoding instead of Content-Length
    // - In this case the Transfer-Encoding is only to ensure we can correctly drain the InputStream so the client can read the response.
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * Host header requirements. Must be provided, and not duplicated.
   * </p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc7230#section-5.4">RFC 7230 Section 5.4</a>
   * </p>
   * <pre>
   *   A server MUST respond with a 400 (Bad Request) status code to any
   *    HTTP/1.1 request message that lacks a Host header field and to any
   *    request message that contains more than one Host header field or a
   *    Host header field with an invalid field-value.
   * </pre>
   */
  @Test
  public void host_header_required() throws Exception {
    // Host header is required, return 400 if not provided
    withRequest("""
        GET / HTTP/1.1\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void host_header_required_with_X_Forwarded_Host() throws Exception {
    // Ensure that X-Forwarded-Host doesn't count for the Host header
    withRequest("""
        GET / HTTP/1.1\r
        X-Forwarded-Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * Content-Length header requirements for size.
   * </p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc7230#section-3.3.2">RFC 7230 Section 3.3.2</a>
   * </p>
   * <pre>
   *   Any Content-Length field value greater than or equal to zero is
   *    valid.  Since there is no predefined limit to the length of a
   *    payload, a recipient MUST anticipate potentially large decimal
   *    numerals and prevent parsing errors due to integer conversion
   *    overflows (Section 9.3).
   * </pre>
   */
  @Test
  public void invalid_content_length() throws Exception {
    // In this implementation the Content-Length is stored as a long, and as such we will take a Number Format Exception if the number exceeds Long.MAX_VALUE.
    // - The above-mentioned RFC does indicate we should account for this - Long.MAX_VALUE is 2^63 - 1 which seems like a reasonable limit.

    // Too large, we will take a NumberFormatException and set this value to null in the request.
    // - So as it is written, we won't return the user an error, but we will assume that a body is not present.
    withRequest(("""
            GET / HTTP/1.1\r
            Host: cyberdyne-systems.com\r
            Content-Type: plain/text\r
            Content-Length: 9223372036854775808\r
            \r
            {body}
            """)
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Negative
    withRequest(("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: -1\r
        \r
        {body}
        """)
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Max Long - but negative
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: -9223372036854775807\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * Protocol Versioning requirements
   * </p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc7230#section-2.6">RFC 7230 Section 2.6</a>
   * </p>
   */
  @Test
  public void invalid_version() throws Exception {
    // Invalid: HTTP/1
    // - missing the '.' (dot) and the second digit.
    withRequest("""
        GET / HTTP/1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}
        """
    ).expectResponse(
        """
            HTTP/1.1 505 \r
            connection: close\r
            content-length: 0\r
            \r
            """);

    // Invalid: HTTP/1.
    // - missing the minor version digit
    withRequest("""
        GET / HTTP/1.\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 505 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Minor version less than the highest supported: HTTP/1.0
    // - While this is an HTTP 1.1 server, RFC 7230 indicates that a message with a higher minor version that is supported
    //   should be accepted and treated as the highest minor version. See section 2.6.
    //
    // Section 2.6
    //
    //          When an HTTP message is received with a major version number that the
    //          recipient implements, but a higher minor version number than what the
    //          recipient implements, the recipient SHOULD process the message as if
    //          it were in the highest minor version within that major version to
    //          which the recipient is conformant.  A recipient can assume that a
    //          message with a higher minor version, when sent to a recipient that
    //          has not yet indicated support for that higher version, is
    //          sufficiently backwards-compatible to be safely processed by any
    //          implementation of the same major version
    withRequest("""
        GET / HTTP/1.0\r
        Host: cyberdyne-systems.com\r
        Connection: close\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 200 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Invalid: HTTP/1.2
    withRequest("""
        GET / HTTP/1.2\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 505 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Invalid: HTTP/9.9
    withRequest("""
        GET / HTTP/9.9\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 505 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void keepAlive_bodyNeverRead() throws Exception {
    // Use case: Using keep alive, and the request handler doesn't ready the payload.
    // - Ensure the HTTP worker is able to drain the bytes so the next request starts with an empty byte array.
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 200 \r
        connection: keep-alive\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void mangled_version() throws Exception {
    // HTTP reversed, does not begin with HTTP/
    // - This will fail during the preamble parsing so we are not returning a 505 in this case. My opinion is that
    //   this is not an invalid protocol version, it is simply a malformed request.
    withRequest("""
        GET / PTTH/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Repeated allowed characters, does not begin with HTTP/
    // - This will fail during the preamble parsing so we are not returning a 505 in this case. My opinion is that
    //   this is not an invalid protocol version, it is simply a malformed request.
    withRequest("""
        GET / HHHH/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void missing_protocol() throws Exception {
    // - This will fail during the preamble parsing so we are not returning a 505 in this case. My opinion is that
    //   this is not an invalid protocol version, it is simply a malformed request.
    withRequest("""
        GET /\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void test_HTTP_10_OK() throws Exception {
    // Minor version less than the highest supported: HTTP/1.0
    // - While this is an HTTP 1.1 server, RFC 7230 indicates that a message with a higher minor version that is supported
    //   should be accepted and treated as the highest minor version. See section 2.6.
    //
    // Section 2.6
    //
    //          When an HTTP message is received with a major version number that the
    //          recipient implements, but a higher minor version number than what the
    //          recipient implements, the recipient SHOULD process the message as if
    //          it were in the highest minor version within that major version to
    //          which the recipient is conformant.  A recipient can assume that a
    //          message with a higher minor version, when sent to a recipient that
    //          has not yet indicated support for that higher version, is
    //          sufficiently backwards-compatible to be safely processed by any
    //          implementation of the same major version
    withRequest("""
        GET / HTTP/1.0\r
        Host: cyberdyne-systems.com\r
        Connection: close\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 200 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * Content-Length header requirements.
   * </p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc7230#section-3.3.2">RFC 7230 Section 3.3.2</a> See <a
   * href="https://httpwg.org/specs/rfc7230.html#header.content-length">RFC 7230 Content-Length</a>
   * </p>
   * <pre>
   *  A sender MUST NOT send a Content-Length header field in any message
   *  that contains a Transfer-Encoding header field.
   * </pre>
   */
  @Test
  public void transfer_encoding_content_length() throws Exception {
    // When Transfer-Encoding is provided, Content-Length should be omitted. If they are both present, Content-Length is ignored.
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        Transfer-Encoding: chunked\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 200 \r
        connection: keep-alive\r
        content-length: 0\r
        \r
        """);
  }

  private void assertResponse(String request, String response) throws Exception {
    try (HTTPServer ignore = makeServer("http", (req, res) -> res.setStatus(200))
        .withInitialReadTimeout(Duration.ofMinutes(2))
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
        for (var i = 0; i < body.length(); i += 100) {
          var endIndex = Math.min(i + 100, body.length());
          var chunk = body.substring(i, endIndex);
          var chunkLength = chunk.getBytes(StandardCharsets.UTF_8).length;
          String hex = Integer.toHexString(chunkLength);
          //noinspection StringConcatenationInLoop
          result += (hex + "\r\n" + body.substring(i, endIndex) + "\r\n");
        }
        body = result + "0\r\n\r\n";
      }

      request = request.replace("{body}", body);
      request = request.replace("{contentLength}", body.getBytes(StandardCharsets.UTF_8).length + "");

      var os = socket.getOutputStream();
      os.write(request.getBytes(StandardCharsets.UTF_8));
      os.flush();

      assertHTTPResponseEquals(socket, response);
    }
  }

  private Builder withRequest(String request) {
    return new Builder(request);
  }

  private class Builder {
    public String request;

    public Builder(String request) {
      this.request = request;
    }

    public void expectResponse(String response) throws Exception {
      assertResponse(request, response);
    }
  }
}
