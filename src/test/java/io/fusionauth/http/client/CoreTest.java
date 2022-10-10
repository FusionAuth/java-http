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

import java.net.URI;

import io.fusionauth.http.BaseTest;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPServer;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * @author Daniel DeGroff
 */
public class CoreTest extends BaseTest {

  // TODO : invocationCount = 1 works, but greater than 1 fails on the second iteration with a "Connection reset" SocketException
  @Test(dataProvider = "connection", invocationCount = 1)
  public void get_connection(String connection) {
    HTTPClientThread.counter.set(0);
    HTTPHandler handler = (req, res) -> res.setStatus(200);

    try (HTTPServer ignore = makeServer("http", handler).start()) {
      // Reset the connection pool since we know the server is going to tear down all sockets.
      // TODO : Note, should we handle this by just rebuilding the connection? I would guess we have
      //        to tolerate the server terminating our connection.

      // TODO : Note, if I bump this to 100_000, I'll eventually get
      //        socket errors, so I'm doing something wrong.
      // Using roughly a ration of 1:10 so keep the timings roughly the same. You probably
      // don't want to wait for 50,000 requests to complete w/out keep-alive.
      long iterations = !connection.equals("close")
//          ? 50_000
//          : 5_000;
          ? 500
          : 50;
      long start = System.currentTimeMillis();

      HTTPClient client = new HTTPClient()
          .url(URI.create("http://localhost:4242"))
          .optionalHeader(!connection.equals(""), "Connection", connection)
          .get();

      for (int i = 0; i <= iterations; i++) {
        // TODO : Note if I build the client outside of the loop,
        //        and only call send() in the loop the connection response
        //        header is not consistent. Seems like a bug.
        HTTPClientResponse response;
        try {
          response = client
              .send();
        } catch (Exception e) {
          System.out.println(i + 1);
          throw e;
        }

        assertEquals(response.getStatus(), 200);
        assertEquals(new String(response.getBody()), """
                HTTP/1.1 200 \r
                content-length: 0\r
                connection: {connection}\r
                \r
                """
                .replace("{connection}", connection.equals("") ? "keep-alive" : connection)
            , "iteration [" + (i + 1) + "]");

        if (i > 0 && i % 5_000 == 0) {
          System.out.println(i);
        }
      }

      long end = System.currentTimeMillis();
      double average = (end - start) / (double) iterations;
      System.out.println("\nAverage linear request time is [" + average + "]ms");
      System.out.println("Duration: " + (end - start) + " ms\n");
    }
  }
}
