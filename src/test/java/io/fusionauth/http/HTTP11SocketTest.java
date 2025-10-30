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

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the HTTP server by writing directly to the server and reading using sockets to test lower level semantics.
 *
 * @author Daniel DeGroff
 */
public class HTTP11SocketTest extends BaseSocketTest {
  @Test(invocationCount = 100)
  public void bad_request() throws Exception {
    // Invalid HTTP header
    withRequest("""
        cat /etc/password\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @DataProvider(name = "chunk-extensions")
  public Object[][] chunkExtensions() {
    return new Object[][]{
        {";foo=bar"},          // Single extension
        {";foo"},              // Single extension, no value
        {";foo="},             // Single extension, no value, with =
        {";foo=bar;bar=baz"},  // Two extensions
        {";foo=bar;bar="},     // Two extensions, second no value
        {";foo=bar;bar"},      // Two extensions, second no value, with =
    };
  }

  @Test(invocationCount = 100)
  public void duplicate_host_header() throws Exception {
    // Duplicate Host header
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test(invocationCount = 100)
  public void duplicate_host_header_withTransferEncoding() throws Exception {
    // Duplicate Host header w/ Transfer-Encoding instead of Content-Length
    // - In this case the Transfer-Encoding is only to ensure we can correctly drain the InputStream so the client can read the response.
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Transfer-Encoding: chunked\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
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
  @Test(invocationCount = 100)
  public void host_header_required() throws Exception {
    // Host header is required, return 400 if not provided
    withRequest("""
        GET / HTTP/1.1\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test(invocationCount = 100)
  public void host_header_required_with_X_Forwarded_Host() throws Exception {
    // Ensure that X-Forwarded-Host doesn't count for the Host header
    withRequest("""
        GET / HTTP/1.1\r
        X-Forwarded-Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
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
  @Test(invocationCount = 100)
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
        {body}""")
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
        {body}""")
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
        {body}"""
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
  @Test(invocationCount = 100)
  public void invalid_version() throws Exception {
    // Invalid: HTTP/1
    // - missing the '.' (dot) and the second digit.
    withRequest("""
        GET / HTTP/1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
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
        {body}"""
    ).expectResponse("""
        HTTP/1.1 505 \r
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
        {body}"""
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
        {body}"""
    ).expectResponse("""
        HTTP/1.1 505 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test(invocationCount = 100)
  public void keepAlive_bodyNeverRead() throws Exception {
    // Use case: Using keep alive, and the request handler doesn't read the payload.
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

  @Test(invocationCount = 100)
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
        {body}"""
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
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test(invocationCount = 100)
  public void missing_protocol() throws Exception {
    // - This will fail during the preamble parsing so we are not returning a 505 in this case. My opinion is that
    //   this is not an invalid protocol version, it is simply a malformed request.
    withRequest("""
        GET /\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test(dataProvider = "chunk-extensions", invocationCount = 100)
  public void transfer_encoding_chunked_extensions(String chunkExtension) throws Exception {
    // Ensure we can properly ignore chunked extensions
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Transfer-Encoding: chunked\r
        \r
        {body}"""
    ).withChunkedExtension(chunkExtension)
     .expectResponse("""
         HTTP/1.1 200 \r
         connection: keep-alive\r
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
  @Test(invocationCount = 250)
  public void transfer_encoding_content_length() throws Exception {
    // When Transfer-Encoding is provided, Content-Length should be omitted. If they are both present, Content-Length is ignored.
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        Transfer-Encoding: chunked\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 200 \r
        connection: keep-alive\r
        content-length: 0\r
        \r
        """);
  }
}
