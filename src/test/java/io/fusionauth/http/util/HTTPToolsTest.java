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
package io.fusionauth.http.util;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
  public void getMaxRequestBodySize() {
    var configuration = Map.of(
        "*", 1,
        "application/*", 2,
        "application/json", 3,
        "application/x-www-form-urlencoded", 4,
        "multipart/form-data", 5,
        "text/*", 6,
        "text/html", 7
    );

    assertMaxConfiguredSize(null, 1, configuration);
    assertMaxConfiguredSize("application/json", 3, configuration);
    assertMaxConfiguredSize("application/json-patch+json", 2, configuration);
    assertMaxConfiguredSize("application/octet-stream", 2, configuration);
    assertMaxConfiguredSize("application/pdf", 2, configuration);
    assertMaxConfiguredSize("application/x-www-form-urlencoded", 4, configuration);
    assertMaxConfiguredSize("multipart/form-data", 5, configuration);
    assertMaxConfiguredSize("text/css", 6, configuration);
    assertMaxConfiguredSize("text/html", 7, configuration);

    // We don't expect this at runtime, but ideally we won't explode. These would be invalid values.
    // - Some of these are legit Content-Type headers, but at runtime we will have already parsed the header so we do
    //   not expect any attributes;
    assertMaxConfiguredSize("", 1, configuration);
    assertMaxConfiguredSize("null", 1, configuration);
    assertMaxConfiguredSize("application/json; foo=bar", 2, configuration);
    assertMaxConfiguredSize("multipart/form-data; boundary=------", 1, configuration);
    assertMaxConfiguredSize("text/css; charset=ISO-8859-1", 6, configuration);
    assertMaxConfiguredSize("text/unexpected", 6, configuration);
    assertMaxConfiguredSize("text/unexpected/value", 6, configuration);
  }

  @Test
  public void parseEncodedData() {
    // Happy path
    assertEncodedData("bar", "bar", StandardCharsets.UTF_8);

    // Note, there are 3 try/catches in the parseEncodedDate. This tests them in order, each hitting a specific try/catch.

    // Bad name encoding
    assertEncodedData("bar&%%%=baz", "bar", StandardCharsets.UTF_8);

    // Bad value encoding
    assertEncodedData("bar&bar=ba%å&=boom", "bar", StandardCharsets.UTF_8);

    // Bad value encoding
    assertEncodedData("bar&bar=% % %", "bar", StandardCharsets.UTF_8);

    // UTF-8 encoding of characters not in the ISO-8859-1 character set
    assertEncodedData("😎", "😎", StandardCharsets.UTF_8);
    assertEncodedData("€", "€", StandardCharsets.UTF_8);

    // UTF-8 encoding of characters are that also in the ISO-8859-1 character set but have different mappings
    assertEncodedData("é", "é", StandardCharsets.UTF_8);
    assertEncodedData("Héllö", "Héllö", StandardCharsets.UTF_8);

    // These UTF-8 double byte values are outside ISO-88559-1, so we should expect them to not render correctly. See next test.
    assertHexValue("😎", "D83D DE0E");
    assertHexValue("€", "20AC");

    // ISO-8559-1 encoding of characters outside the ISO-8559-1 character set
    assertEncodedData("😎", "?", StandardCharsets.ISO_8859_1);
    assertEncodedData("€", "?", StandardCharsets.ISO_8859_1);

    // These values are within the ISO-8559-1 charset, expect them to render correctly.
    assertHexValue("é", "E9");
    assertHexValue("Héllö", "48 E9 6C 6C F6");

    // ISO-8559-1 encoding of non-ASCII characters inside the character set
    assertEncodedData("é", "é", StandardCharsets.ISO_8859_1);
    assertEncodedData("Héllö", "Héllö", StandardCharsets.ISO_8859_1);

    // Mixing and matching. Expect some wonky behavior.
    // - Encoded using ISO-8559-1 and decoded as UTF-8
    assertEncodedData("Héllö", "H�ll�", StandardCharsets.ISO_8859_1, StandardCharsets.UTF_8);
    assertEncodedData("Hello world", "Hello world", StandardCharsets.ISO_8859_1, StandardCharsets.UTF_8);
    // The é and the ö will fail to render because while this character exists in both character sets, they are encoded differently.
    // - So we should expec them to render incorrectly.
    // - See below, this is just here to validate why the above assertions are accurate.
    assertHexValue("Héllö", "48    E9 6C 6C F6   ", StandardCharsets.ISO_8859_1);
    assertHexValue("Héllö", "48 C3 A9 6C 6C C3 B6", StandardCharsets.UTF_8);

    // - Reverse
    assertEncodedData("Héllö", "HÃ©llÃ¶", StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1);
    assertEncodedData("Hello world", "Hello world", StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1);
    // The é and the ö will fail to render because while this character exists in both character sets, they are encoded differently.
    // - So we should expec them to render incorrectly.
    // - See below, this is just here to validate why the above assertions are accurate.
    assertHexValue("é", "C3 A9", StandardCharsets.UTF_8);
    assertHexValue("Ã©", "C3 A9", StandardCharsets.ISO_8859_1);
    assertHexValue("ö", "C3 B6", StandardCharsets.UTF_8);
    assertHexValue("Ã¶", "C3 B6", StandardCharsets.ISO_8859_1);
  }

  @Test
  public void parseHeaderValue() {
    String iso = "åpple";
    String utf = "😁";
    assertEquals(HTTPTools.parseHeaderValue("text/plain; charset=iso8859-1"), new HeaderValue("text/plain", Map.of("charset", "iso8859-1")));
    assertEquals(HTTPTools.parseHeaderValue("text/plain; charset=iso-8859-1"), new HeaderValue("text/plain", Map.of("charset", "iso-8859-1")));
    assertEquals(HTTPTools.parseHeaderValue("text/plain; charset=iso8859-1; boundary=FOOBAR"), new HeaderValue("text/plain", Map.of("boundary", "FOOBAR", "charset", "iso8859-1")));
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
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename*=ISO8859-1'en'" + URLEncoder.encode(iso, StandardCharsets.ISO_8859_1)), new HeaderValue("form-data", Map.of("filename", iso)));
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

  private void assertEncodedData(String actualValue, String expectedValue, Charset charset) {
    assertEncodedData(actualValue, expectedValue, charset, charset);

  }

  private void assertEncodedData(String actualValue, String expectedValue, Charset encodingCharset, Charset decodingCharset) {
    Map<String, List<String>> result = new HashMap<>(1);
    byte[] encoded = ("foo=" + actualValue).getBytes(encodingCharset);
    HTTPTools.parseEncodedData(encoded, 0, encoded.length, decodingCharset, result);
    assertEquals(result, Map.of("foo", List.of(expectedValue)), "Actual:\n" + ToString.toString(result));
  }

  private void assertHexValue(String s, String expected) {
    assertEquals(hex(s), expected);
  }

  private void assertHexValue(String s, String expected, Charset charset) {
    var trimmed = expected.trim();
    trimmed = trimmed.replaceAll(" +", " ");
    assertEquals(hex(s.getBytes(charset)), trimmed);
  }

  private void assertMaxConfiguredSize(String contentType, int maximumSize, Map<String, Integer> maxRequestBodySize) {
    assertEquals(HTTPTools.getMaxRequestBodySize(contentType, maxRequestBodySize), maximumSize);
  }

  private String hex(byte[] bytes) {
    List<String> result = new ArrayList<>();
    for (byte b : bytes) {

      result.add(Integer.toHexString(0xFF & b).toUpperCase());
    }
    return String.join(" ", result);
  }

  private String hex(String s) {
    List<String> result = new ArrayList<>();
    for (char ch : s.toCharArray()) {
      result.add(Integer.toHexString(ch).toUpperCase());
    }
    return String.join(" ", result);
  }
}
