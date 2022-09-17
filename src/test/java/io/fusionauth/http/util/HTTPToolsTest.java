/*
 * Copyright (c) 2022, FusionAuth, All Rights Reserved
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
import java.util.Map;

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
  }
}
