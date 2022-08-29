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
import java.util.concurrent.Future;

public class SimpleNIOClient {
  private static final NIOClientThread thread;

  public String method;

  public String url;

  public SimpleNIOClient get() {
    this.method = "GET";
    return this;
  }

  public int go() {
    try {
      Future<Integer> future = thread.add(URI.create(url), method);
      return future.get();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public SimpleNIOClient url(String url) {
    this.url = url;
    return this;
  }

  static {
    try {
      thread = new NIOClientThread();
      thread.start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
