/*
 * Copyright (c) 2021-2025, FusionAuth, All Rights Reserved
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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.fusionauth.http.HTTPValues.Methods;

/**
 * @author Brian Pontarelli
 */
public class HTTPMethod {
  public static final HTTPMethod CONNECT = new HTTPMethod(Methods.CONNECT);

  public static final HTTPMethod DELETE = new HTTPMethod(Methods.DELETE);

  public static final HTTPMethod GET = new HTTPMethod(Methods.GET);

  public static final HTTPMethod HEAD = new HTTPMethod(Methods.HEAD);

  public static final HTTPMethod OPTIONS = new HTTPMethod(Methods.OPTIONS);

  public static final HTTPMethod PATCH = new HTTPMethod(Methods.PATCH);

  public static final HTTPMethod POST = new HTTPMethod(Methods.POST);

  public static final HTTPMethod PUT = new HTTPMethod(Methods.PUT);

  public static final HTTPMethod TRACE = new HTTPMethod(Methods.TRACE);

  public static Map<String, HTTPMethod> StandardMethods = new HashMap<>();

  private final String name;

  static {
    StandardMethods.put(CONNECT.name(), CONNECT);
    StandardMethods.put(DELETE.name(), DELETE);
    StandardMethods.put(GET.name(), GET);
    StandardMethods.put(HEAD.name(), HEAD);
    StandardMethods.put(OPTIONS.name(), OPTIONS);
    StandardMethods.put(PATCH.name(), PATCH);
    StandardMethods.put(POST.name(), POST);
    StandardMethods.put(PUT.name(), PUT);
    StandardMethods.put(TRACE.name(), TRACE);
  }

  private HTTPMethod(String name) {
    Objects.requireNonNull(name);
    this.name = name.toUpperCase(Locale.ROOT);
  }

  public static HTTPMethod of(String name) {
    name = name.toUpperCase(Locale.ROOT);
    HTTPMethod method = StandardMethods.get(name);
    if (method == null) {
      method = new HTTPMethod(name);
    }

    return method;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof HTTPMethod)) {
      return false;
    }
    HTTPMethod that = (HTTPMethod) o;
    return Objects.equals(name, that.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name);
  }

  public boolean is(HTTPMethod method) {
    return this == method || equals(method);
  }

  public boolean is(String method) {
    return name.equals(method);
  }

  public String name() {
    return name;
  }

  public String toString() {
    return name;
  }
}
