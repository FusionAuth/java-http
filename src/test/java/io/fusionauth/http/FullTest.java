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

import io.fusionauth.http.client.SimpleNIOClient;
import io.fusionauth.http.server.SimpleNIOServer;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * Tests the Simple NIO server.
 *
 * @author Brian Pontarelli
 */
public class FullTest {
  @Test
  public void all() throws Exception {
    try (SimpleNIOServer server = new SimpleNIOServer()) {
      server.start();

      for (int i = 0; i < 1_000_000; i++) {
        SimpleNIOClient client = new SimpleNIOClient();
        int status = client.url("http://localhost:9011/api/system/version")
                           .get()
                           .go();

        assertEquals(status, 200);

//        ClientResponse<String, String> response = new RESTClient<>(String.class, String.class)
//            .url("http://localhost:9011/api/system/version")
//            .successResponseHandler(new TextResponseHandler())
//            .errorResponseHandler(new TextResponseHandler())
//            .connectTimeout(1_000_000)
//            .readTimeout(1_000_000)
//            .get()
//            .go();
//
//        assertEquals(response.status, 200);

        if (i % 10_000 == 0) {
          System.out.println(i);
        }
      }
    }
  }
}