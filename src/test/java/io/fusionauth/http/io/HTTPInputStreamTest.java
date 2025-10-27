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
package io.fusionauth.http.io;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPServerConfiguration;
import io.fusionauth.http.server.io.HTTPInputStream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * @author Daniel DeGroff
 */
public class HTTPInputStreamTest {
  @Test
  public void read_chunked_withPushback() throws Exception {
    // Ensure that when we read a chunked encoded body that the InputStream returns the correct number of bytes read even when
    // we read past the end of the current request and use the PushbackInputStream.

    int contentLength = 113;
    String content = "These pretzels are making me thirsty. These pretzels are making me thirsty. These pretzels are making me thirsty.";

    // Chunk the content
    byte[] bytes = """
        26\r
        These pretzels are making me thirsty. \r
        26\r
        These pretzels are making me thirsty. \r
        25\r
        These pretzels are making me thirsty.\r
        0\r
        \r
        GET / HTTP/1.1\r
        """.getBytes();

    HTTPRequest request = new HTTPRequest();
    request.setHeader("Transfer-Encoding", "chunked");

    assertReadWithPushback(bytes, content, contentLength, request);
  }

  @Test
  public void read_fixedLength_withPushback() throws Exception {
    // Ensure that when we read a fixed length body that the InputStream returns the correct number of bytes read even when
    // we read past the end of the current request and use the PushbackInputStream.

    int contentLength = 113;
    String content = "These pretzels are making me thirsty. These pretzels are making me thirsty. These pretzels are making me thirsty.";

    // Fixed length body with the start of the next request in the buffer
    byte[] bytes = (content + "GET / HTTP/1.1\r\n").getBytes(StandardCharsets.UTF_8);

    HTTPRequest request = new HTTPRequest();
    request.setHeader("Content-Length", contentLength + "");

    assertReadWithPushback(bytes, content, contentLength, request);
  }

  private void assertReadWithPushback(byte[] bytes, String content, int contentLength, HTTPRequest request) throws Exception {
    int bytesAvailable = bytes.length;
    System.out.println("available bytes [" + bytesAvailable + "]");
    System.out.println("buffer size [" + (bytesAvailable + 100) + "]");
    HTTPServerConfiguration configuration = new HTTPServerConfiguration().withRequestBufferSize(bytesAvailable + 100);

    ByteArrayInputStream is = new ByteArrayInputStream(bytes);
    PushbackInputStream pushbackInputStream = new PushbackInputStream(is, null);

    HTTPInputStream httpInputStream = new HTTPInputStream(configuration, request, pushbackInputStream, -1);
    byte[] buffer = new byte[configuration.getRequestBufferSize()];
    int read = httpInputStream.read(buffer);

    assertEquals(read, contentLength);
    assertEquals(new String(buffer, 0, read), content);

    // We should be at the end of the request, so expect -1 even though we have extra bytes in the PushbackInputStream
    int secondRead = httpInputStream.read(buffer);
    assertEquals(secondRead, -1);

    // We have 16 bytes left over
    assertEquals(pushbackInputStream.getAvailableBufferedBytesRemaining(), 16);

    // Next read should start at the next request
    byte[] leftOverBuffer = new byte[100];
    int leftOverRead = pushbackInputStream.read(leftOverBuffer);
    assertEquals(leftOverRead, 16);
    assertEquals(new String(leftOverBuffer, 0, leftOverRead), "GET / HTTP/1.1\r\n");
  }
}
