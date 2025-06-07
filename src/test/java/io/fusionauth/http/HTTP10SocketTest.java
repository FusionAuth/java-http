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

import org.testng.annotations.Test;

/**
 * Tests the HTTP server by writing directly to the server and reading using sockets to test lower level semantics.
 * <p>
 * Tests here should be focused on testing backwards compatibility with HTTP/1.0
 *
 * @author Daniel DeGroff
 */
public class HTTP10SocketTest extends BaseSocketTest {
  @Test
  public void keep_alive_defaults() throws Exception {
    // Ensure that the HTTP/1.0 defaults are observed for keep-alive

    // No Connection header, default should be close on the response
    withRequest("""
        GET / HTTP/1.0\r
        Host: cyberdyne-systems.com\r
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

    // Ask for close, expect close
    withRequest("""
        GET / HTTP/1.0\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        Connection: close\r
        \r
        {body}
        """
    ).expectResponse("""
        HTTP/1.1 200 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Ask for keep-alive, expect keep-alive
    withRequest("""
        GET / HTTP/1.0\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        Connection: keep-alive\r
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
}
