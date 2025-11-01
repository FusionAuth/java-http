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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import io.fusionauth.http.BaseTest;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPServerConfiguration;
import io.fusionauth.http.server.io.HTTPInputStream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * @author Daniel DeGroff
 */
public class HTTPInputStreamTest extends BaseTest {
  @Test(dataProvider = "contentEncoding")
  public void read_chunked_withPushback(String contentEncoding) throws Exception {
    // Ensure that when we read a chunked encoded body that the InputStream returns the correct number of bytes read even when
    // we read past the end of the current request and use the PushbackInputStream.
    // - Test with optional compression as well.

    String content = "These pretzels are making me thirsty. These pretzels are making me thirsty. These pretzels are making me thirsty.";
    byte[] payload = content.getBytes(StandardCharsets.UTF_8);
    int contentLength = payload.length;

    // Optionally compress
    payload = compressUsingContentEncoding(payload, contentEncoding);

    // Chunk the content, add part of the next request
    payload = chunkEncode(payload, 38, null);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(payload);

    // Add part of the next request
    String nextRequest = "GET / HTTP/1.1\n\r";
    byte[] nextRequestBytes = nextRequest.getBytes(StandardCharsets.UTF_8);
    out.write(nextRequestBytes);

    HTTPRequest request = new HTTPRequest();
    request.setHeader(Headers.ContentEncoding, contentEncoding);
    request.setHeader(Headers.TransferEncoding, "chunked");

    byte[] bytes = out.toByteArray();
    assertReadWithPushback(bytes, content, contentLength, nextRequestBytes, request);
  }

  @Test(dataProvider = "contentEncoding")
  public void read_fixedLength_withPushback(String contentEncoding) throws Exception {
    // Ensure that when we read a fixed length body that the InputStream returns the correct number of bytes read even when
    // we read past the end of the current request and use the PushbackInputStream.
    // - Test with optional compression as well.

    // Fixed length body with the start of the next request in the buffer
    String content = "These pretzels are making me thirsty. These pretzels are making me thirsty. These pretzels are making me thirsty.";
    byte[] payload = content.getBytes(StandardCharsets.UTF_8);
    int contentLength = payload.length;

    // Optionally compress
    payload = compressUsingContentEncoding(payload, contentEncoding);

    // Content-Length must be the compressed length
    int compressedLength = payload.length;

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(payload);

    // Add part of the next request
    String nextRequest = "GET / HTTP/1.1\n\r";
    byte[] nextRequestBytes = nextRequest.getBytes(StandardCharsets.UTF_8);
    out.write(nextRequestBytes);

    HTTPRequest request = new HTTPRequest();
    request.setHeader(Headers.ContentEncoding, contentEncoding);
    request.setHeader(Headers.ContentLength, compressedLength + "");

    // body length is 113, when compressed it is 68 (gzip) or 78 (deflate)
    // The number of bytes available is 129.
    byte[] bytes = out.toByteArray();
    assertReadWithPushback(bytes, content, contentLength, nextRequestBytes, request);
  }

  private void assertReadWithPushback(byte[] bytes, String content, int contentLength, byte[] pushedBackBytes, HTTPRequest request)
      throws Exception {
    int bytesAvailable = bytes.length;
    HTTPServerConfiguration configuration = new HTTPServerConfiguration().withRequestBufferSize(bytesAvailable + 100);

    ByteArrayInputStream is = new ByteArrayInputStream(bytes);
    PushbackInputStream pushbackInputStream = new PushbackInputStream(is, null);

    HTTPInputStream httpInputStream = new HTTPInputStream(configuration, request, pushbackInputStream, -1);
    byte[] buffer = new byte[configuration.getRequestBufferSize()];
    int read = httpInputStream.read(buffer);

    // Note that the HTTPInputStream read will return the number of uncompressed bytes read. So the contentLength passed in
    // needs to represent the actual length of the request body, not the value of the Content-Length sent on the request which
    // would represent the size of the compressed entity body.
    assertEquals(read, contentLength);
    assertEquals(new String(buffer, 0, read), content);

    // We should be at the end of the request, so expect -1 even though we have extra bytes in the PushbackInputStream
    int secondRead = httpInputStream.read(buffer);
    assertEquals(secondRead, -1);

    // We have 16 bytes left over
    assertEquals(pushbackInputStream.getAvailableBufferedBytesRemaining(), pushedBackBytes.length);

    // Next read should start at the next request
    byte[] leftOverBuffer = new byte[100];
    int leftOverRead = pushbackInputStream.read(leftOverBuffer);
    assertEquals(leftOverRead, pushedBackBytes.length);
    assertEquals(new String(leftOverBuffer, 0, leftOverRead), new String(pushedBackBytes));
  }
}
