/*
 * Copyright (c) 2022-2023, FusionAuth, All Rights Reserved
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
package io.fusionauth.http.util;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.inversoft.json.ToString;
import io.fusionauth.http.HTTPMethod;
import io.fusionauth.http.io.PushbackInputStream;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPServerConfiguration;
import io.fusionauth.http.server.internal.HTTPBuffers;
import io.fusionauth.http.server.io.HTTPInputStream;
import io.fusionauth.http.util.HTTPTools.HeaderValue;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * Tests the HTTPTools utility class.
 *
 * @author Brian Pontarelli
 */
@Test
public class HTTPToolsTest {
  @Test
  public void parseEncodedData() {
    // Test bad encoding

    Map<String, List<String>> actual = new HashMap<>();

    // Happy path
    byte[] body = "foo=bar".getBytes(StandardCharsets.UTF_8);
    HTTPTools.parseEncodedData(body, 0, body.length, actual);
    assertEquals(actual, Map.of("foo", List.of("bar")), "Actual:\n" + ToString.toString(actual));

    // Note, there are 3 try/catches in the parseEncodedDate. This tests them in order, each hitting a specific try/catch.

    // Bad name encoding
    actual.clear();
    byte[] badName = "foo=bar&%%%=baz".getBytes(StandardCharsets.UTF_8);
    HTTPTools.parseEncodedData(badName, 0, badName.length, actual);
    assertEquals(actual, Map.of("foo", List.of("bar")), "Actual:\n" + ToString.toString(actual));

    // Bad value encoding
    actual.clear();
    byte[] badValue1 = "foo=bar&bar=ba%å&=boom".getBytes(StandardCharsets.UTF_8);
    HTTPTools.parseEncodedData(badValue1, 0, badValue1.length, actual);
    assertEquals(actual, Map.of("foo", List.of("bar")), "Actual:\n" + ToString.toString(actual));

    // Bad value encoding
    actual.clear();
    byte[] badValue2 = "foo=bar&bar=% % %".getBytes(StandardCharsets.UTF_8);
    HTTPTools.parseEncodedData(badValue2, 0, badValue2.length, actual);
    assertEquals(actual, Map.of("foo", List.of("bar")), "Actual:\n" + ToString.toString(actual));
  }

  @Test
  public void parseHeaderValue() {
    String iso = "åpple";
    String utf = "😁";
    assertEquals(HTTPTools.parseHeaderValue("text/plain; charset=iso-8859-1"), new HeaderValue("text/plain", Map.of("charset", "iso-8859-1")));
    assertEquals(HTTPTools.parseHeaderValue("text/plain; charset=iso-8859-1; boundary=FOOBAR"), new HeaderValue("text/plain", Map.of("boundary", "FOOBAR", "charset", "iso-8859-1")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename=foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename=\"foo.jpg\""), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename*=UTF-8''foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename=ignore.jpg; filename*=UTF-8''foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename*=UTF-8'en'foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename=ignore.jpg; filename*=UTF-8'en'foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename*=ISO-8859-1''foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename*=ISO-8859-1'en'foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename=ignore.jpg; filename*=ISO-8859-1'en'foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));

    // Encoded
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename*=ISO-8859-1'en'" + URLEncoder.encode(iso, StandardCharsets.ISO_8859_1)), new HeaderValue("form-data", Map.of("filename", iso)));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename*=UTF-8'en'" + URLEncoder.encode(utf, StandardCharsets.UTF_8)), new HeaderValue("form-data", Map.of("filename", utf)));

    // Edge cases
    assertEquals(HTTPTools.parseHeaderValue("value; list=a; b; another=param"), new HeaderValue("value", Map.of("another", "param", "b", "", "list", "a")));
    assertEquals(HTTPTools.parseHeaderValue("value; list=a; b=; another=param"), new HeaderValue("value", Map.of("another", "param", "b", "", "list", "a")));
    assertEquals(HTTPTools.parseHeaderValue("value; list=\"a;b\"; another=param"), new HeaderValue("value", Map.of("another", "param", "list", "a;b")));
    assertEquals(HTTPTools.parseHeaderValue("value; list=\"a\";"), new HeaderValue("value", Map.of("list", "a")));
    assertEquals(HTTPTools.parseHeaderValue("value; list*"), new HeaderValue("value", Map.of("list", "")));
    assertEquals(HTTPTools.parseHeaderValue("value;"), new HeaderValue("value", Map.of()));
    assertEquals(HTTPTools.parseHeaderValue("value; "), new HeaderValue("value", Map.of()));
    assertEquals(HTTPTools.parseHeaderValue("value; f"), new HeaderValue("value", Map.of("f", "")));
    assertEquals(HTTPTools.parseHeaderValue("value;  f"), new HeaderValue("value", Map.of("f", "")));
    assertEquals(HTTPTools.parseHeaderValue("value; f="), new HeaderValue("value", Map.of("f", "")));
    assertEquals(HTTPTools.parseHeaderValue("value;  f="), new HeaderValue("value", Map.of("f", "")));
    assertEquals(HTTPTools.parseHeaderValue("value; f=f"), new HeaderValue("value", Map.of("f", "f")));
    assertEquals(HTTPTools.parseHeaderValue("value; f =f"), new HeaderValue("value", Map.of("f ", "f")));
    assertEquals(HTTPTools.parseHeaderValue("value; f= f"), new HeaderValue("value", Map.of("f", " f")));
  }

  @Test
  public void parsePreamble() throws Exception {
    // Ensure that we can correctly read the preamble when the InputStream contains the next request.

    //noinspection ExtractMethodRecommender
    String request = """
        GET / HTTP/1.1\r
        Host: localhost:42\r
        Connection: close\r
        Content-Length: 113\r
        Header1: Value1\r
        Header2: Value2\r
        Header3: Value3\r
        Header4: Value4\r
        Header5: Value5\r
        Header6: Value6\r
        Header7: Value7\r
        Header8: Value8\r
        Header9: Value9\r
        Header10: Value10\r
        \r
        These pretzels are making me thirsty. These pretzels are making me thirsty. These pretzels are making me thirsty.GET / HTTP/1.1\r
        """;

    // Fixed length body with the start of the next request in the buffer
    byte[] bytes = request.getBytes(StandardCharsets.UTF_8);
    int bytesAvailable = bytes.length;

    // Ensure the request buffer size will contain the entire request.
    HTTPServerConfiguration configuration = new HTTPServerConfiguration().withRequestBufferSize(bytesAvailable + 100);

    ByteArrayInputStream is = new ByteArrayInputStream(bytes);
    PushbackInputStream pushbackInputStream = new PushbackInputStream(is, null);

    HTTPRequest httpRequest = new HTTPRequest();
    HTTPBuffers buffers = new HTTPBuffers(configuration);
    byte[] requestBuffer = buffers.requestBuffer();

    HTTPTools.initialize(configuration.getLoggerFactory());
    HTTPTools.parseRequestPreamble(pushbackInputStream, 128 * 1024, httpRequest, requestBuffer, () -> {
    });

    // Ensure we parsed the request and that the right number of bytes is left over
    assertEquals(httpRequest.getMethod(), HTTPMethod.GET);
    assertEquals(httpRequest.getHost(), "localhost");
    assertEquals(httpRequest.getPort(), 42);
    assertEquals(httpRequest.getContentLength(), 113);
    assertEquals(httpRequest.getHeaders().size(), 13);
    assertEquals(httpRequest.getHeader("Content-Length"), "113");
    assertEquals(httpRequest.getHeader("Connection"), "close");
    assertEquals(httpRequest.getHeader("Host"), "localhost:42");
    for (int i = 1; i <= 10; i++) {
      assertEquals(httpRequest.getHeader("Header" + i), "Value" + i);
    }

    // Expect 129 bytes left over which is 113 for the body + 16 from the next request
    assertEquals(pushbackInputStream.getAvailableBufferedBytesRemaining(), 113 + 16);

    // Read the remaining bytes for this request, we should still have some left over.
    HTTPInputStream httpInputStream = new HTTPInputStream(configuration, httpRequest, pushbackInputStream, -1);
    byte[] buffer = new byte[1024];
    int read = httpInputStream.read(buffer);
    assertEquals(read, 113);

    // Another read should return -1 because we are at the end of this request.
    int nextRead = httpInputStream.read(buffer);
    assertEquals(nextRead, -1);

    // The next read from the pushback which will be used by the next request should return the remaining bytes.
    assertEquals(pushbackInputStream.getAvailableBufferedBytesRemaining(), 16);
    int nextRequestRead = pushbackInputStream.read(buffer);
    assertEquals(nextRequestRead, 16);
  }
}
