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
package io.fusionauth.http.client;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

import io.fusionauth.http.HTTPMethod;

/**
 * @author Brian Pontarelli
 * @author Daniel DeGroff
 */
public class HTTPClient {
  private static final HTTPClientThread thread;

  private HTTPClientConfiguration configuration = new HTTPClientConfiguration();

  private String method;

  private URI url;

  static {
    try {
      thread = new HTTPClientThread();
      thread.start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public HTTPClient configuration(HTTPClientConfiguration configuration) {
    this.configuration = configuration;
    return this;
  }

  public HTTPClient get() {
    method = HTTPMethod.GET.name();
    return this;
  }

  public HTTPClient header(String name, String value) {
    this.configuration.addHeader(name, value);
    return this;
  }

  public HTTPClient optionalHeader(boolean test, String name, String value) {
    if (test) {
      this.configuration.addHeader(name, value);
    }

    return this;
  }

  public HTTPClientResponse send() {
    try {
      return sendAsync().get();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public CompletableFuture<HTTPClientResponse> sendAsync() {
    try {
      return thread.add(url, method, configuration);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public HTTPClient url(URI url) {
    this.url = url;
    return this;
  }
}
