/*
 * Copyright (c) 2022-2025, FusionAuth, All Rights Reserved
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPResponse;

public class LoadHandler implements HTTPHandler {
  private final byte[] HelloWorld = "Hello world".getBytes(StandardCharsets.UTF_8);
  private final Map<Integer, byte[]> Blobs = new HashMap<>();
  @Override
  public void handle(HTTPRequest req, HTTPResponse res) {
    String path = req.getPath();

    // No-op, return OK.
    if (path.equals("/")) {
      res.setStatus(200);
      return;
    }

    // Hello world
    if (path.equals("/text")) {
      res.setStatus(200);
      res.setContentType("text/plain");
      res.setContentLength(HelloWorld.length);

      try (OutputStream os = res.getOutputStream()) {
        os.write(HelloWorld);
        os.flush();
      } catch (Exception e) {
        res.setStatus(500);
      }

      return;
    }

    // Return a file
    if (path.equals("/file")) {
      System.out.println("/file");
      int size = 1024 * 1024;
      var requestedSize = req.getURLParameter("size");
      if (requestedSize != null) {
        size = Integer.parseInt(requestedSize);
        System.out.println("Requested size: " + size);
      } else {
        System.out.println("Default size: " + size);
      }

      byte[] blob = Blobs.get(size);
      if (blob == null) {
        System.out.println("Build file with size : " + size);
        String s = "Lorem ipsum dolor sit amet";
        String body = s.repeat(size / s.length() + (size % s.length()));
        assert body.length() == size;
        Blobs.put(size, body.getBytes(StandardCharsets.UTF_8));
        blob = Blobs.get(size);
        assert blob != null;
      } else {
        System.out.println("Already built file with size: " + size);
      }

      res.setStatus(200);
      res.setContentType("application/octet-stream");
      System.out.println("Write back a file with [" + blob.length + "] bytes");
      res.setContentLength(blob.length);

      try (OutputStream os = res.getOutputStream()) {
        os.write(blob);
        os.flush();
      } catch (Exception e) {
        res.setStatus(500);
      }

      return;
    }

    // Whatever is written in the request, echo it back on the response.
    if (path.equals("/echo")) {
      try (InputStream is = req.getInputStream()) {
        byte[] body = is.readAllBytes();
        String result = Base64.getEncoder().encodeToString(body);

        res.setHeader("Content-Length", body.length + "");
        res.setHeader("Content-Type", "application/octet-stream");
        res.setStatus(200);

        try (OutputStream os = res.getOutputStream()) {
          os.write(result.getBytes());
          os.flush();
        }
      } catch (Exception e) {
        res.setStatus(500);
      }

      return;
    }
  }
}
