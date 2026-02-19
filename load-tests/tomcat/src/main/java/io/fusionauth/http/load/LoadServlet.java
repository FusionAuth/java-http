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

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class LoadServlet extends HttpServlet {
  private static final Map<Integer, byte[]> Blobs = new HashMap<>();

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) {
    doPost(req, res);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res) {
    // Note that this should be mostly the same between all load tests.
    // - See load-tests/self
    switch (req.getPathInfo()) {
      case "/" -> handleNoOp(req, res);
      case "/no-read" -> handleNoRead(req, res);
      case "/hello" -> handleHello(req, res);
      case "/file" -> handleFile(req, res);
      case "/load" -> handleLoad(req, res);
      default -> handleFailure(req, res);
    }
  }

  private void handleFailure(HttpServletRequest req, HttpServletResponse res) {
    // Path does not match handler.
    res.setStatus(400);
    byte[] response = ("Invalid path [" + req.getPathInfo() + "]. Supported paths include [/, /no-read, /hello, /file, /load].").getBytes(StandardCharsets.UTF_8);
    res.setContentLength(response.length);
    res.setContentType("text/plain");
    try {
      res.getOutputStream().write(response);
    } catch (IOException e) {
      res.setStatus(500);
    }
  }

  private void handleFile(HttpServletRequest req, HttpServletResponse res) {
    try (InputStream is = req.getInputStream()) {
      is.readAllBytes();

      int size = 1024 * 1024;
      var requestedSize = req.getParameter("size");
      if (requestedSize != null) {
        size = Integer.parseInt(requestedSize);
      }

      // Ensure we only build one file
      byte[] blob = Blobs.get(size);
      if (blob == null) {
        synchronized (Blobs) {
          blob = Blobs.get(size);
          if (blob == null) {
            System.out.println("Build file with size : " + size);
            String s = "Lorem ipsum dolor sit amet";
            String body = s.repeat((size + s.length() - 1) / s.length()).substring(0, size);
            assert body.length() == size;
            Blobs.put(size, body.getBytes(StandardCharsets.UTF_8));
            blob = Blobs.get(size);
            assert blob != null;
          }
        }
      }

      res.setStatus(200);
      res.setContentType("application/octet-stream");
      res.setContentLength(blob.length);

      try (OutputStream os = res.getOutputStream()) {
        os.write(blob);
      }
    } catch (Exception e) {
      res.setStatus(500);
    }
  }

  private void handleHello(HttpServletRequest req, HttpServletResponse res) {
    try (InputStream is = req.getInputStream()) {
      // Empty the InputStream
      is.readAllBytes();

      // Hello world
      res.setStatus(200);
      res.setContentType("text/plain");
      byte[] response = "Hello world".getBytes(StandardCharsets.UTF_8);
      res.setContentLength(response.length);

      try (OutputStream os = res.getOutputStream()) {
        os.write(response);
      }
    } catch (Exception e) {
      res.setStatus(500);
    }
  }

  private void handleLoad(HttpServletRequest req, HttpServletResponse res) {
    // Note that this should be mostly the same between all load tests.
    // - See load-tests/self
    try (InputStream is = req.getInputStream()) {
      byte[] body = is.readAllBytes();
      byte[] result = Base64.getEncoder().encode(body);
      res.setContentLength(result.length);
      res.setContentType("text/plain");
      res.setStatus(200);
      res.getOutputStream().write(result);
    } catch (Exception e) {
      res.setStatus(500);
    }
  }

  private void handleNoOp(HttpServletRequest req, HttpServletResponse res) {
    try (InputStream is = req.getInputStream()) {
      // Just read the bytes from the InputStream and return. Do no other worker.
      is.readAllBytes();
      res.setStatus(200);
    } catch (Exception e) {
      res.setStatus(500);
    }
  }

  @SuppressWarnings("unused")
  private void handleNoRead(HttpServletRequest req, HttpServletResponse res) {
    // Note that it is intentionally that we are not reading the InputStream. This will cause the server to have to drain it.
    res.setStatus(200);
  }
}
