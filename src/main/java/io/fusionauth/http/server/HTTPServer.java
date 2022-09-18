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
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.Objects;

import io.fusionauth.http.log.Logger;
import io.fusionauth.http.log.LoggerFactory;
import io.fusionauth.http.util.ThreadPool;

public class HTTPServer extends Thread implements Closeable, Notifier {
  private ServerSocketChannel channel;

  private HTTPServerConfiguration configuration = new HTTPServerConfiguration();

  private HTTPContext context;

  private Logger logger = configuration.getLoggerFactory().getLogger(HTTPServer.class);

  // This is shared across all worker, processors, etc.
  private ByteBuffer preambleBuffer;

  private Selector selector;

  private ThreadPool threadPool;

  public HTTPServer() {
    try {
      this.configuration.withBindAddress(InetAddress.getByName("::"));
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

  /**
   * @return The HTTP Context or null if the server hasn't been started yet.
   */
  public HTTPContext getContext() {
    return context;
  }

  @Override
  public void notifyNow() {
    // Wake-up! Time to put on a little make-up!
    selector.wakeup();
  }

  public void run() {
    Instrumenter instrumenter = configuration.getInstrumenter();
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
            var clientChannel = channel.accept();
            clientChannel.configureBlocking(false);

            HTTPProcessor processor = new HTTP11Processor(configuration, this, preambleBuffer, threadPool);
            clientChannel.register(selector, SelectionKey.OP_READ, processor);
            logger.trace("(A)");

            if (instrumenter != null) {
              instrumenter.acceptedConnection();
            }
          } else if (key.isReadable()) {
            logger.trace("(R)");
            HTTPProcessor processor = (HTTP11Processor) key.attachment();
            long bytes = processor.read(key);

            if (instrumenter != null) {
              instrumenter.readFromClient(bytes);
            }
          } else if (key.isWritable()) {
            logger.trace("(W)");
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
      } catch (SocketException se) {
        logger.debug("A socket exception was thrown during processing. These are pretty common.", se);

        if (key != null) {
          try (var ignore = key.channel()) {
            key.cancel();
          } catch (Throwable t) {
            logger.error("An exception was thrown while trying to cancel a SelectionKey and close a channel with a client due to an exception being thrown for that specific client. Enable debug logging to see the error", t);
          }
        }
      } catch (Throwable t) {
        logger.error("An exception was thrown during processing", t);

        if (key != null) {
          try (var ignore = key.channel()) {
            key.cancel();
          } catch (Throwable t2) {
            logger.error("An exception was thrown while trying to cancel a SelectionKey and close a channel with a client due to an exception being thrown for that specific client. Enable debug logging to see the error", t2);
          }
        }
      }
    }
  }

  @Override
  public synchronized void start() {
    preambleBuffer = ByteBuffer.allocate(configuration.getPreambleBufferSize());
    context = new HTTPContext(configuration.getBaseDir());

    try {
      selector = Selector.open();
      channel = ServerSocketChannel.open();
      channel.configureBlocking(false);
      channel.bind(new InetSocketAddress(configuration.getAddress(), configuration.getPort()));
      channel.register(selector, SelectionKey.OP_ACCEPT);

      Instrumenter instrumenter = configuration.getInstrumenter();
      if (instrumenter != null) {
        instrumenter.serverStarted();
      }
    } catch (IOException e) {
      logger.error("Unable to start the HTTP server due to an error opening an NIO resource. See the stack trace of the cause for the specific error.", e);
      throw new IllegalStateException("Unable to start the HTTP server due to an error opening an NIO resource. See the stack trace of the cause for the specific error.", e);
    }

    // Start the thread pool for the workers
    threadPool = new ThreadPool(configuration.getNumberOfWorkerThreads(), "HTTP Server Worker Thread", configuration.getShutdownDuration());

    super.start();
    logger.info("HTTP server started successfully and listening on port [{}]", configuration.getPort());
  }

  /**
   * Convenience method for calling {@link HTTPServerConfiguration#withBaseDir(Path)}.
   *
   * @param baseDir The base dir.
   * @return This.
   */
  public HTTPServer withBaseDir(Path baseDir) {
    this.configuration.withBaseDir(baseDir);
    return this;
  }

  /**
   * Convenience method for calling {@link HTTPServerConfiguration#withBindAddress(InetAddress)}.
   *
   * @param address The bind address.
   * @return This.
   */
  public HTTPServer withBindAddress(InetAddress address) {
    this.configuration.withBindAddress(address);
    return this;
  }

  /**
   * Convenience method for calling {@link HTTPServerConfiguration#withClientTimeoutDuration(Duration)}.
   *
   * @param duration The duration.
   * @return This.
   */
  public HTTPServer withClientTimeoutDuration(Duration duration) {
    this.configuration.withClientTimeoutDuration(duration);
    return this;
  }

  /**
   * Sets the configuration for this server.
   *
   * @param configuration The new configuration.
   * @return This.
   */
  public HTTPServer withConfiguration(HTTPServerConfiguration configuration) {
    this.configuration = configuration;
    return this;
  }

  /**
   * Convenience method for calling {@link HTTPServerConfiguration#withContextPath(String)}.
   *
   * @param contextPath The context path for the server.
   * @return This.
   */
  public HTTPServer withContextPath(String contextPath) {
    this.configuration.withContextPath(contextPath);
    return this;
  }

  /**
   * Convenience method for calling {@link HTTPServerConfiguration#withExpectValidator(ExpectValidator)}.
   *
   * @param validator The validator.
   * @return This.
   */
  public HTTPServer withExpectValidator(ExpectValidator validator) {
    this.configuration.withExpectValidator(validator);
    return this;
  }

  /**
   * Convenience method for calling {@link HTTPServerConfiguration#withHandler(HTTPHandler)}.
   *
   * @param handler The handler that processes the requests.
   * @return This.
   */
  public HTTPServer withHandler(HTTPHandler handler) {
    this.configuration.withHandler(handler);
    return this;
  }

  /**
   * Convenience method for calling {@link HTTPServerConfiguration#withInstrumenter(Instrumenter)}.
   *
   * @param instrumenter The instrumenter.
   * @return This.
   */
  public HTTPServer withInstrumenter(Instrumenter instrumenter) {
    this.configuration.withInstrumenter(instrumenter);
    return this;
  }

  /**
   * Convenience method for calling {@link HTTPServerConfiguration#withLoggerFactory(LoggerFactory)}.
   *
   * @param loggerFactory The factory.
   * @return This.
   */
  public HTTPServer withLoggerFactory(LoggerFactory loggerFactory) {
    Objects.requireNonNull(loggerFactory);
    this.configuration.withLoggerFactory(loggerFactory);
    this.logger = loggerFactory.getLogger(HTTPServer.class);
    return this;
  }

  /**
   * Convenience method for calling {@link HTTPServerConfiguration#withMaxPreambleLength(int)}.
   *
   * @param maxLength The max preamble length.
   * @return This.
   */
  public HTTPServer withMaxPreambleLength(int maxLength) {
    this.configuration.withMaxPreambleLength(maxLength);
    return this;
  }

  /**
   * Convenience method for calling {@link HTTPServerConfiguration#withNumberOfWorkerThreads(int)}.
   *
   * @param numberOfWorkerThreads The number of worker threads.
   * @return This.
   */
  public HTTPServer withNumberOfWorkerThreads(int numberOfWorkerThreads) {
    this.configuration.withNumberOfWorkerThreads(numberOfWorkerThreads);
    return this;
  }

  /**
   * Convenience method for calling {@link HTTPServerConfiguration#withPort(int)}.
   *
   * @param port The port.
   * @return This.
   */
  public HTTPServer withPort(int port) {
    this.configuration.withPort(port);
    return this;
  }

  /**
   * Convenience method for calling {@link HTTPServerConfiguration#withPreambleBufferSize(int)}.
   *
   * @param size The buffer size.
   * @return This.
   */
  public HTTPServer withPreambleBufferSize(int size) {
    this.configuration.withPreambleBufferSize(size);
    return this;
  }

  /**
   * Convenience method for calling {@link HTTPServerConfiguration#withShutdownDuration(Duration)}.
   *
   * @param duration The duration the server will wait for all running request processing threads to complete their work.
   * @return This.
   */
  public HTTPServer withShutdownDuration(Duration duration) {
    this.configuration.withShutdownDuration(duration);
    return this;
  }

  private void cleanup() {
    long now = System.currentTimeMillis();
    selector.keys()
            .stream()
            .filter(key -> key.attachment() != null)
            .filter(key -> ((HTTPProcessor) key.attachment()).lastUsed() < now - configuration.getClientTimeoutDuration().toMillis())
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
