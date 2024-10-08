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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.inversoft.json.ToString;
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
    assertEquals(HTTPTools.parseHeaderValue("text/plain; charset=iso8859-1"), new HeaderValue("text/plain", Map.of("charset", "iso8859-1")));
    assertEquals(HTTPTools.parseHeaderValue("text/plain; charset=iso8859-1; boundary=FOOBAR"), new HeaderValue("text/plain", Map.of("boundary", "FOOBAR", "charset", "iso8859-1")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename=foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename=\"foo.jpg\""), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename*=UTF-8''foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename=ignore.jpg; filename*=UTF-8''foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename*=UTF-8'en'foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename=ignore.jpg; filename*=UTF-8'en'foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename*=ISO8859-1''foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename*=ISO8859-1'en'foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename=ignore.jpg; filename*=ISO8859-1'en'foo.jpg"), new HeaderValue("form-data", Map.of("filename", "foo.jpg")));

    // Encoded
    assertEquals(HTTPTools.parseHeaderValue("form-data; filename*=ISO8859-1'en'" + URLEncoder.encode(iso, StandardCharsets.ISO_8859_1)), new HeaderValue("form-data", Map.of("filename", iso)));
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
}
