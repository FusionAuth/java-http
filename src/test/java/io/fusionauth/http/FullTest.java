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

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.BiConsumer;

import com.inversoft.rest.RESTClient;
import com.inversoft.rest.TextResponseHandler;
import io.fusionauth.http.server.HTTPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * Tests the HTTP server.
 *
 * @author Brian Pontarelli
 */
public class FullTest {
  private static final Logger logger = LoggerFactory.getLogger(FullTest.class);

  @Test
  public void all() throws Exception {
    BiConsumer<HTTPRequest, HTTPResponse> handler = (req, res) -> {
      res.setHeader("Content-Type", "text/plain");
      res.setHeader("Content-Length", "16");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write("{\"version\":\"42\"}".getBytes());
        outputStream.close();
        logger.debug("Wrote response back to client");
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (HTTPServer server = new HTTPServer().withHandler(handler).withNumberOfWorkerThreads(1).withPort(4242)) {
      server.start();

      for (int i = 0; i < 1_000_000; i++) {
//        SimpleNIOClient client = new SimpleNIOClient();
//        int status = client.url("http://localhost:9011/api/system/version")
//                           .get()
//                           .go();
//
//        assertEquals(status, 200);

        var response = new RESTClient<>(String.class, String.class)
            .url("http://localhost:4242/api/system/version")
            .successResponseHandler(new TextResponseHandler())
            .errorResponseHandler(new TextResponseHandler())
            .connectTimeout(1_000_000)
            .readTimeout(1_000_000)
            .get()
            .go();

        assertEquals(response.status, 200);

        if (i % 1_000 == 0) {
          System.out.println(i);
        }
      }
    }
  }
}