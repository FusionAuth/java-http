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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class JdkLoadServer {
  private static final Map<Integer, byte[]> Blobs = new HashMap<>();

  public static void main(String[] args) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(8080), 200);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", JdkLoadServer::handleRequest);
    server.start();
    System.out.println("JDK HttpServer started on port 8080");

    // Run for 2 hours then shut down
    Thread.sleep(2 * 60 * 60 * 1000L);
    server.stop(5);
  }

  private static void handleRequest(HttpExchange exchange) throws IOException {
    try {
      String path = exchange.getRequestURI().getPath();
      switch (path) {
        case "/" -> handleNoOp(exchange);
        case "/no-read" -> handleNoRead(exchange);
        case "/hello" -> handleHello(exchange);
        case "/file" -> handleFile(exchange);
        case "/load" -> handleLoad(exchange);
        default -> handleFailure(exchange);
      }
    } catch (Exception e) {
      exchange.sendResponseHeaders(500, -1);
    } finally {
      exchange.close();
    }
  }

  private static void handleFailure(HttpExchange exchange) throws IOException {
    byte[] response = ("Invalid path [" + exchange.getRequestURI().getPath() + "]. Supported paths include [/, /no-read, /hello, /file, /load].").getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain");
    exchange.sendResponseHeaders(400, response.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(response);
    }
  }

  private static void handleFile(HttpExchange exchange) throws IOException {
    try (InputStream is = exchange.getRequestBody()) {
      is.readAllBytes();
    }

    int size = 1024 * 1024;
    String query = exchange.getRequestURI().getQuery();
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

    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
    exchange.sendResponseHeaders(200, blob.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(blob);
    }
  }

  private static void handleHello(HttpExchange exchange) throws IOException {
    try (InputStream is = exchange.getRequestBody()) {
      is.readAllBytes();
    }

    byte[] response = "Hello world".getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain");
    exchange.sendResponseHeaders(200, response.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(response);
    }
  }

  private static void handleLoad(HttpExchange exchange) throws IOException {
    // Note that this should be mostly the same between all load tests.
    // - See load-tests/self
    byte[] body;
    try (InputStream is = exchange.getRequestBody()) {
      body = is.readAllBytes();
    }

    byte[] result = Base64.getEncoder().encode(body);
    exchange.getResponseHeaders().set("Content-Type", "text/plain");
    exchange.sendResponseHeaders(200, result.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(result);
    }
  }

  private static void handleNoOp(HttpExchange exchange) throws IOException {
    try (InputStream is = exchange.getRequestBody()) {
      is.readAllBytes();
    }

    exchange.sendResponseHeaders(200, -1);
  }

  private static void handleNoRead(HttpExchange exchange) throws IOException {
    exchange.sendResponseHeaders(200, -1);
  }
}
