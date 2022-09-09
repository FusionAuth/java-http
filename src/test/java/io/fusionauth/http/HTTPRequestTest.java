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
package io.fusionauth.http;

import java.util.List;
import java.util.Map;

import io.fusionauth.http.server.HTTPRequest;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * Tests the HTTPRequest.
 *
 * @author Brian Pontarelli
 */
public class HTTPRequestTest {
  @Test
  public void queryString() {
    HTTPRequest request = new HTTPRequest();
    request.setPath("/path?name=value");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getParameters(), Map.of("name", List.of("value")));

    request.setPath("/path?name=value&name1=value1");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getParameters(), Map.of("name", List.of("value"), "name1", List.of("value1")));

    request.setPath("/path?name+space=value+space&name1+space=value1+space");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getParameters(), Map.of("name space", List.of("value space"), "name1 space", List.of("value1 space")));

    request.setPath("/path?name");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getParameters(), Map.of());

    request.setPath("/path?name==");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getParameters(), Map.of("name", List.of("=")));

    request.setPath("/path?==");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getParameters(), Map.of());

    request.setPath("/path?==name=value");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getParameters(), Map.of("name", List.of("value")));

    request.setPath("/path?name=a=b=c");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getParameters(), Map.of("name", List.of("a=b=c")));

    request.setPath("/path?name&&");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getParameters(), Map.of());

    request.setPath("/path?name=&");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getParameters(), Map.of("name", List.of("")));

    request.setPath("/path?name=%26&");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getParameters(), Map.of("name", List.of("&")));

    request.setPath("/path?name%3D=%26&");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getParameters(), Map.of("name=", List.of("&")));

    request.setPath("/path?name=");
    assertEquals(request.getPath(), "/path");
    assertEquals(request.getParameters(), Map.of("name", List.of("")));
  }
}
