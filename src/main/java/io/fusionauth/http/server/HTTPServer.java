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
package io.fusionauth.http.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import io.fusionauth.http.HTTPRequest;
import io.fusionauth.http.HTTPResponse;
import io.fusionauth.http.server.HTTPRequestProcessor.RequestState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTPServer extends Thread implements Closeable {
  private static final Logger logger = LoggerFactory.getLogger(HTTPServer.class);

  final Queue<SelectionKey> clientKeys = new ConcurrentLinkedQueue<>();

  private InetAddress address;

  private ServerSocketChannel channel;

  private ClientReaperThread clientReaper;

  private ExecutorService executor;

  private BiConsumer<HTTPRequest, HTTPResponse> handler;

  private int numberOfWorkerThreads = 40;

  private int port = 8080;

  private Selector selector;

  private int shutdownSeconds;

  public HTTPServer() {
    try {
      this.address = InetAddress.getByName("::");
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    logger.info("HTTP server shutdown requested.");
    clientReaper.shutdown();

    try {
      selector.close();
    } catch (Throwable t) {
      logger.error("Unable to close the Selector.", t);
    }

    try {
      channel.close();
    } catch (Throwable t) {
      logger.error("Unable to close the Channel.", t);
    }

    executor.shutdownNow();
    try {
      if (executor.awaitTermination(shutdownSeconds, TimeUnit.SECONDS)) {
        logger.info("HTTP server shutdown successfully.");
      } else {
        logger.error("HTTP server shutdown failed. Harsh!");
      }
    } catch (InterruptedException e) {
      // Ignore and exit
    }
  }

  public void run() {
    while (true) {
      try {
        // If the selector has been closed, shut the thread down
        if (!selector.isOpen()) {
          return;
        }

        int numberOfKeys = selector.select();
        if (numberOfKeys <= 0) {
          continue;
        }

        var keys = selector.selectedKeys();
        Iterator<SelectionKey> iterator = keys.iterator();
        while (iterator.hasNext()) {
          var key = iterator.next();
          if (key.isAcceptable()) {
            System.out.println("Accepting");
            accept();
          } else if (key.isReadable()) {
            read(key);
          } else if (key.isWritable()) {
            write(key);
          }

          iterator.remove();
        }
      } catch (IOException e) {
        e.printStackTrace();
      } catch (ClosedSelectorException cse) {
        // Shut down
        break;
      }
    }
  }

  @Override
  public synchronized void start() {
    try {
      selector = Selector.open();
      channel = ServerSocketChannel.open();
      channel.configureBlocking(false);
      channel.bind(new InetSocketAddress(address, port));
      channel.register(selector, SelectionKey.OP_ACCEPT);
    } catch (IOException e) {
      logger.error("Unable to start the HTTP server due to an error opening an NIO resource. See the stack trace of the cause for the specific error.", e);
      throw new IllegalStateException("Unable to start the HTTP server due to an error opening an NIO resource. See the stack trace of the cause for the specific error.", e);
    }

    clientReaper = new ClientReaperThread(this);
    clientReaper.start();

    // Start the worker executor
    AtomicInteger threadCount = new AtomicInteger(1);
    executor = Executors.newFixedThreadPool(numberOfWorkerThreads, runnable -> new Thread(runnable, "HTTP server worker thread " + threadCount.incrementAndGet()));

    super.start();
    logger.info("HTTP server started successfully and listening on port [{}]", port);
  }

  /**
   * Sets the bind address that this server listens on. Defaults to *.
   *
   * @param address The bind address.
   * @return This.
   */
  public HTTPServer withBindAddress(InetAddress address) {
    this.address = address;
    return this;
  }

  /**
   * Sets the handler that will process the requests.
   *
   * @param handler The handler that processes the requests.
   * @return This.
   */
  public HTTPServer withHandler(BiConsumer<HTTPRequest, HTTPResponse> handler) {
    this.handler = handler;
    return this;
  }

  /**
   * Sets the number of worker threads that will handle requests coming into the HTTP server. Defaults to 40.
   *
   * @param numberOfWorkerThreads The number of worker threads.
   * @return This.
   */
  public HTTPServer withNumberOfWorkerThreads(int numberOfWorkerThreads) {
    this.numberOfWorkerThreads = numberOfWorkerThreads;
    return this;
  }

  /**
   * Sets the port that this server listens on for HTTP (non-TLS). Defaults to 8080.
   *
   * @param port The port.
   * @return This.
   */
  public HTTPServer withPort(int port) {
    this.port = port;
    return this;
  }

  /**
   * Sets the number of seconds the server will wait for running requests to be completed.
   *
   * @param seconds The number of seconds the server will wait for all running request processing threads to complete their work.
   * @return This.
   */
  public HTTPServer withShutdownSeconds(int seconds) {
    this.shutdownSeconds = seconds;
    return this;
  }

  private void accept() throws IOException {
    var clientChannel = channel.accept();
    clientChannel.configureBlocking(false);

    var clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
    clientKey.attach(new HTTPWorker(handler));
    clientKeys.add(clientKey);
  }

  @SuppressWarnings("resource")
  private void read(SelectionKey key) throws IOException {
    SocketChannel client = (SocketChannel) key.channel();
    HTTPWorker worker = (HTTPWorker) key.attachment();
    worker.markUsed();

    HTTPRequestProcessor processor = worker.requestProcessor();
    RequestState state = processor.state();
    ByteBuffer buffer;
    if (state == RequestState.Head) {
      buffer = processor.headBuffer();
    } else if (state == RequestState.Body) {
      buffer = processor.bodyBuffer();
    } else {
      key.interestOps(SelectionKey.OP_WRITE);
      return;
    }

    long read = client.read(buffer);
    if (read <= 0) {
      return;
    }

    if (processor.update() == RequestState.Complete) {
      key.interestOps(SelectionKey.OP_WRITE);
    }
  }

  @SuppressWarnings("resource")
  private void write(SelectionKey key) throws IOException {
    SocketChannel client = (SocketChannel) key.channel();
    HTTPWorker worker = (HTTPWorker) key.attachment();
    data.markUsed();

    client.write(data.response);

    if (data.response.position() == data.response.limit()) {
      key.interestOps(SelectionKey.OP_READ);
      data.reset();
    }
  }
}
