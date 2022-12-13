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
package io.fusionauth.http.load;

import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServer;

public class Main {
  public static void main(String[] args) throws Exception {
    System.out.println("Starting java-http server");
    try (HTTPServer ignore = new HTTPServer().withHandler(new LoadHandler())
                                             .withCompressByDefault(false)
                                             .withListener(new HTTPListenerConfiguration(8080))
                                             .withNumberOfWorkerThreads(200)
                                             .start()) {
      Thread.sleep(1_000_000);
      System.out.println("Shutting down java-http server");
    }
  }
}
