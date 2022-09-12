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
import java.time.Duration;
import java.util.Iterator;
import java.util.Objects;

import io.fusionauth.http.log.Logger;
import io.fusionauth.http.log.LoggerFactory;
import io.fusionauth.http.log.SystemOutLoggerFactory;
import io.fusionauth.http.util.ThreadPool;

public class HTTPServer extends Thread implements Closeable, Notifier {
  private InetAddress address;

  private ServerSocketChannel channel;

  private Duration clientTimeoutDuration = Duration.ofSeconds(10);

  private ExpectValidator expectValidator;

  private HTTPHandler handler;

  private Instrumenter instrumenter;

  private LoggerFactory loggerFactory = SystemOutLoggerFactory.FACTORY;

  private Logger logger = loggerFactory.getLogger(HTTPServer.class);

  private int maxHeadLength;

  private int numberOfWorkerThreads = 40;

  private int port = 8080;

  // This is shared across all worker, processors, etc.
  private ByteBuffer preambleBuffer;

  private int preambleBufferSize = 4096;

  private Selector selector;

  private Duration shutdownDuration = Duration.ofSeconds(10);

  private ThreadPool threadPool;

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

    if (threadPool.shutdown()) {
      logger.info("HTTP server shutdown successfully.");
    } else {
      logger.error("HTTP server shutdown failed. Harsh!");
    }
  }

  @Override
  public void notifyNow() {
    // Wake-up! Time to put on a little make-up!
    selector.wakeup();
  }

  public void run() {
    while (true) {
      SelectionKey key = null;
      try {
        // If the selector has been closed, shut the thread down
        if (!selector.isOpen()) {
          return;
        }

        selector.select(1_000L);

        var keys = selector.selectedKeys();
        Iterator<SelectionKey> iterator = keys.iterator();
        while (iterator.hasNext()) {
          key = iterator.next();
          if (key.isAcceptable()) {
            logger.debug("Accepting incoming connection");
            var clientChannel = channel.accept();
            clientChannel.configureBlocking(false);

            HTTPProcessor processor = new HTTP11Processor(expectValidator, handler, instrumenter, maxHeadLength, this, loggerFactory, preambleBuffer, threadPool);
            clientChannel.register(selector, SelectionKey.OP_READ, processor);
            logger.trace("(A)");

            if (instrumenter != null) {
              instrumenter.acceptedConnection();
            }
          } else if (key.isReadable()) {
            logger.debug("Reading from client");
            HTTPProcessor processor = (HTTP11Processor) key.attachment();
            long bytes = processor.read(key);

            if (instrumenter != null) {
              instrumenter.readFromClient(bytes);
            }
          } else if (key.isWritable()) {
            logger.debug("Writing to client");
            HTTPProcessor processor = (HTTP11Processor) key.attachment();
            long bytes = processor.write(key);

            if (instrumenter != null) {
              instrumenter.wroteToClient(bytes);
            }
          }

          iterator.remove();
          key = null;
        }

        cleanup();
      } catch (ClosedSelectorException cse) {
        // Shut down
        break;
      } catch (Throwable t) {
        logger.error("An exception was thrown during processing", t);

        if (key != null) {
          try (var ignore = key.channel()) {
            key.cancel();
          } catch (Throwable t2) {
            logger.error("An exception was thrown while trying to cancel a SelectionKey and close a channel with a client", t2);
          }
        }
      }
    }
  }

  @Override
  public synchronized void start() {
    preambleBuffer = ByteBuffer.allocate(preambleBufferSize);

    try {
      selector = Selector.open();
      channel = ServerSocketChannel.open();
      channel.configureBlocking(false);
      channel.bind(new InetSocketAddress(address, port));
      channel.register(selector, SelectionKey.OP_ACCEPT);

      if (instrumenter != null) {
        instrumenter.serverStarted();
      }
    } catch (IOException e) {
      logger.error("Unable to start the HTTP server due to an error opening an NIO resource. See the stack trace of the cause for the specific error.", e);
      throw new IllegalStateException("Unable to start the HTTP server due to an error opening an NIO resource. See the stack trace of the cause for the specific error.", e);
    }

    // Start the thread pool for the workers
    threadPool = new ThreadPool(numberOfWorkerThreads, "HTTP Server Worker Thread", shutdownDuration);

    super.start();
    logger.info("HTTP server started successfully and listening on port [{}]", port);
  }

  /**
   * Sets the bind address that this server listens on. Defaults to `::`.
   *
   * @param address The bind address.
   * @return This.
   */
  public HTTPServer withBindAddress(InetAddress address) {
    this.address = address;
    return this;
  }

  /**
   * Sets the duration that the server will allow client connections to remain open. This includes Keep-Alive as well as read timeout.
   *
   * @param duration The duration.
   * @return This.
   */
  public HTTPServer withClientTimeoutDuration(Duration duration) {
    this.clientTimeoutDuration = duration;
    return this;
  }

  /**
   * Sets an ExpectValidator that is used if a client sends the server a {@code Expect: 100-continue} header.
   *
   * @param validator The validator.
   * @return This.
   */
  public HTTPServer withExpectValidator(ExpectValidator validator) {
    this.expectValidator = validator;
    return this;
  }

  /**
   * Sets the handler that will process the requests.
   *
   * @param handler The handler that processes the requests.
   * @return This.
   */
  public HTTPServer withHandler(HTTPHandler handler) {
    this.handler = handler;
    return this;
  }

  /**
   * Sets an instrumenter that the server will notify when events and conditions happen.
   *
   * @param instrumenter The instrumenter.
   * @return This.
   */
  public HTTPServer withInstrumenter(Instrumenter instrumenter) {
    this.instrumenter = instrumenter;
    return this;
  }

  /**
   * Sets the logger factory that all the HTTP server classes use to retrieve specific loggers. Defaults to the
   * {@link SystemOutLoggerFactory}.
   *
   * @param loggerFactory The factory.
   * @return This.
   */
  public HTTPServer withLoggerFactory(LoggerFactory loggerFactory) {
    Objects.requireNonNull(loggerFactory);
    this.loggerFactory = loggerFactory;
    this.logger = loggerFactory.getLogger(HTTPServer.class);
    return this;
  }

  /**
   * Sets the max preamble length (the start-line and headers constitute the head). Defaults to 64k
   *
   * @param maxLength The max preamble length.
   * @return This.
   */
  public HTTPServer withMaxPreambleLength(int maxLength) {
    this.maxHeadLength = maxLength;
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
   * Sets the size of the preamble buffer (that is the buffer that reads the start-line and headers). Defaults to 4096.
   *
   * @param size The buffer size.
   * @return This.
   */
  public HTTPServer withPreambleBufferSize(int size) {
    this.preambleBufferSize = size;
    return this;
  }

  /**
   * Sets the duration the server will wait for running requests to be completed. Defaults to 10 seconds.
   *
   * @param duration The duration the server will wait for all running request processing threads to complete their work.
   * @return This.
   */
  public HTTPServer withShutdownDuration(Duration duration) {
    this.shutdownDuration = duration;
    return this;
  }

  private void cleanup() {
    long now = System.currentTimeMillis();
    selector.keys()
            .stream()
            .filter(key -> key.attachment() != null)
            .filter(key -> ((HTTPProcessor) key.attachment()).lastUsed() < now - clientTimeoutDuration.toMillis())
            .forEach(key -> {
              var client = (SocketChannel) key.channel();
              try {
                logger.debug("Closing client connection [{}] due to inactivity", client.getRemoteAddress().toString());
              } catch (IOException e) {
                // Ignore because we are just debugging
              }

              try {
                key.channel().close();
                key.cancel();
              } catch (Throwable t) {
                logger.error("Error while closing client connection", t);
              }
            });
  }
}
