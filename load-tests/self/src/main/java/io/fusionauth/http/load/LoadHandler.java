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

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Base64;

import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPResponse;

public class LoadHandler implements HTTPHandler {
  @Override
  public void handle(HTTPRequest req, HTTPResponse res) {
    if (req.getPath().equals("/text")) {
      System.out.println("Text");
      res.setStatus(200);
      res.setContentType("text/plain");

      try (OutputStream os = res.getOutputStream()) {
        System.out.println("Wrote");
        os.write("Hello world".getBytes());
        os.flush();
      } catch (Exception e) {
        System.out.println("Failed");
        res.setStatus(500);
      }
    } else {
      try (InputStream is = req.getInputStream()) {
        byte[] body = is.readAllBytes();
        String result = Base64.getEncoder().encodeToString(body);
        res.setStatus(200);

        try (OutputStream os = res.getOutputStream()) {
          os.write(result.getBytes());
          os.flush();
        }
      } catch (Exception e) {
        res.setStatus(500);
      }
    }
  }
}
