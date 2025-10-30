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
import io.fusionauth.http.HTTPValues.ContentEncodings;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPServerConfiguration;
import io.fusionauth.http.server.io.HTTPInputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * @author Daniel DeGroff
 */
public class HTTPInputStreamTest extends BaseTest {
  @DataProvider(name = "contentEncoding")
  public Object[][] contentEncoding() {
    return new Object[][]{
        {""},
        {"gzip"},
        {"deflate"},
        {"gzip, deflate"},
        {"deflate, gzip"}
    };
  }

  @Test(dataProvider = "contentEncoding")
  public void read_chunked_withPushback(String contentEncoding) throws Exception {
    // Ensure that when we read a chunked encoded body that the InputStream returns the correct number of bytes read even when
    // we read past the end of the current request and use the PushbackInputStream.

    String content = "These pretzels are making me thirsty. These pretzels are making me thirsty. These pretzels are making me thirsty.";
    byte[] payload = content.getBytes(StandardCharsets.UTF_8);
    int contentLength = payload.length;

    // Optionally compress the payload
    if (!contentEncoding.isEmpty()) {
      var requestEncodings = contentEncoding.toLowerCase().trim().split(",");
      for (String part : requestEncodings) {
        String encoding = part.trim();
        if (encoding.equals(ContentEncodings.Deflate)) {
          payload = deflate(payload);
        } else if (encoding.equals(ContentEncodings.Gzip)) {
          payload = gzip(payload);
        }
      }
    }

    // Chunk the content, add part of the next request
    payload = chunkEncoded(payload, 38, null);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(payload);

    // Add part of the next request
    out.write("GET / HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8));

    HTTPRequest request = new HTTPRequest();
    request.setHeader(Headers.ContentEncoding, contentEncoding);
    request.setHeader(Headers.TransferEncoding, "chunked");

    byte[] bytes = out.toByteArray();
    assertReadWithPushback(bytes, content, contentLength, 16, request);
  }

  @Test(dataProvider = "contentEncoding")
  public void read_fixedLength_withPushback(String contentEncoding) throws Exception {
    // Ensure that when we read a fixed length body that the InputStream returns the correct number of bytes read even when
    // we read past the end of the current request and use the PushbackInputStream.

    // Fixed length body with the start of the next request in the buffer
    String content = "These pretzels are making me thirsty. These pretzels are making me thirsty. These pretzels are making me thirsty.";
    byte[] payload = content.getBytes(StandardCharsets.UTF_8);
    int contentLength = payload.length;

    // Optionally compress the payload
    if (!contentEncoding.isEmpty()) {
      var requestEncodings = contentEncoding.toLowerCase().trim().split(",");
      for (String part : requestEncodings) {
        String encoding = part.trim();
        if (encoding.equals(ContentEncodings.Deflate)) {
          payload = deflate(payload);
        } else if (encoding.equals(ContentEncodings.Gzip)) {
          payload = gzip(payload);
        }
      }
    }

    // Content-Length must be the compressed length
    int compressedLength = payload.length;

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(payload);

    // Add part of the next request
    out.write("GET / HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8));

    HTTPRequest request = new HTTPRequest();
    request.setHeader(Headers.ContentEncoding, contentEncoding);
    request.setHeader(Headers.ContentLength, compressedLength + "");

    // body length is 113, when compressed it is 68 (gzip) or 78 (deflate)
    // The number of bytes available is 129.
    byte[] bytes = out.toByteArray();
    assertReadWithPushback(bytes, content, contentLength, 16, request);
  }

  private void assertReadWithPushback(byte[] bytes, String content, int contentLength, int pushedBack, HTTPRequest request)
      throws Exception {
    int bytesAvailable = bytes.length;
    HTTPServerConfiguration configuration = new HTTPServerConfiguration().withRequestBufferSize(bytesAvailable + 100);

    ByteArrayInputStream is = new ByteArrayInputStream(bytes);
    PushbackInputStream pushbackInputStream = new PushbackInputStream(is, null);

    HTTPInputStream httpInputStream = new HTTPInputStream(configuration, request, pushbackInputStream, -1);
    byte[] buffer = new byte[configuration.getRequestBufferSize()];
    int read = httpInputStream.read(buffer);

    // TODO : Hmm.. with compression, this is returning the compressed bytes read instead of the un-compressed bytes read. WTF?
    assertEquals(read, contentLength);
    assertEquals(new String(buffer, 0, read), content);

    // We should be at the end of the request, so expect -1 even though we have extra bytes in the PushbackInputStream
    int secondRead = httpInputStream.read(buffer);
    assertEquals(secondRead, -1);

    // We have 16 bytes left over
    assertEquals(pushbackInputStream.getAvailableBufferedBytesRemaining(), pushedBack);

    // Next read should start at the next request
    byte[] leftOverBuffer = new byte[100];
    int leftOverRead = pushbackInputStream.read(leftOverBuffer);
    assertEquals(leftOverRead, pushedBack);
    assertEquals(new String(leftOverBuffer, 0, leftOverRead), "GET / HTTP/1.1\r\n");
  }
}
