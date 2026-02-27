/*
 * Copyright (c) 2025, FusionAuth, All Rights Reserved
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

public class JettyLoadServer {
  private static final Map<Integer, byte[]> Blobs = new HashMap<>();

  public static void main(String[] args) throws Exception {
    Server server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setPort(8080);
    connector.setAcceptQueueSize(200);
    server.addConnector(connector);

    server.setHandler(new LoadHandler());
    server.start();
    System.out.println("Jetty server started on port 8080");
    server.join();
  }

  private static byte[] readRequestBody(Request request) throws Exception {
    try (InputStream is = Content.Source.asInputStream(request)) {
      return is.readAllBytes();
    }
  }

  static class LoadHandler extends Handler.Abstract {
    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
      String path = Request.getPathInContext(request);
      try {
        switch (path) {
          case "/" -> handleNoOp(request, response);
          case "/no-read" -> handleNoRead(request, response);
          case "/hello" -> handleHello(request, response);
          case "/file" -> handleFile(request, response);
          case "/load" -> handleLoad(request, response);
          default -> handleFailure(request, response, path);
        }
        callback.succeeded();
      } catch (Exception e) {
        response.setStatus(500);
        callback.succeeded();
      }
      return true;
    }

    private void handleFailure(Request request, Response response, String path) throws Exception {
      readRequestBody(request);
      byte[] body = ("Invalid path [" + path + "]. Supported paths include [/, /no-read, /hello, /file, /load].").getBytes(StandardCharsets.UTF_8);
      response.setStatus(400);
      response.getHeaders().put("Content-Type", "text/plain");
      response.write(true, ByteBuffer.wrap(body), Callback.NOOP);
    }

    private void handleFile(Request request, Response response) throws Exception {
      readRequestBody(request);

      int size = 1024 * 1024;
      String query = request.getHttpURI().getQuery();
      if (query != null) {
        for (String param : query.split("&")) {
          String[] kv = param.split("=", 2);
          if (kv.length == 2 && kv[0].equals("size")) {
            size = Integer.parseInt(kv[1]);
          }
        }
      }

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

      response.setStatus(200);
      response.getHeaders().put("Content-Type", "application/octet-stream");
      response.write(true, ByteBuffer.wrap(blob), Callback.NOOP);
    }

    private void handleHello(Request request, Response response) throws Exception {
      readRequestBody(request);
      byte[] body = "Hello world".getBytes(StandardCharsets.UTF_8);
      response.setStatus(200);
      response.getHeaders().put("Content-Type", "text/plain");
      response.write(true, ByteBuffer.wrap(body), Callback.NOOP);
    }

    private void handleLoad(Request request, Response response) throws Exception {
      // Note that this should be mostly the same between all load tests.
      // - See load-tests/self
      byte[] body = readRequestBody(request);
      byte[] result = Base64.getEncoder().encode(body);
      response.setStatus(200);
      response.getHeaders().put("Content-Type", "text/plain");
      response.write(true, ByteBuffer.wrap(result), Callback.NOOP);
    }

    private void handleNoOp(Request request, Response response) throws Exception {
      readRequestBody(request);
      response.setStatus(200);
    }

    private void handleNoRead(Request request, Response response) {
      response.setStatus(200);
    }
  }
}
