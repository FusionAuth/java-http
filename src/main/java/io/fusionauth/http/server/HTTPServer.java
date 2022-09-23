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
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

import io.fusionauth.http.log.Logger;
import io.fusionauth.http.util.ThreadPool;

public class HTTPServer extends Thread implements Closeable, Notifier, Configurable<HTTPServer> {
  private ServerSocketChannel channel;

  private HTTPServerConfiguration configuration = new HTTPServerConfiguration();

  private HTTPContext context;

  private Logger logger = configuration.getLoggerFactory().getLogger(HTTPServer.class);

  // This is shared across all worker, processors, etc.
  private ByteBuffer preambleBuffer;

  private Selector selector;

  private ThreadPool threadPool;

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
  public HTTPServerConfiguration configuration() {
    return configuration;
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
            logger.trace("(A)");
            var clientChannel = channel.accept();
            HTTPProcessor processor = new HTTP11Processor(configuration, this, preambleBuffer, threadPool, clientChannel.socket().getInetAddress());
            processor.accept(key, clientChannel);

            if (instrumenter != null) {
              instrumenter.acceptedConnection();
            }
          } else if (key.isReadable()) {
            logger.trace("(R)");
            HTTPProcessor processor = (HTTPProcessor) key.attachment();
            long bytes = processor.read(key);

            if (instrumenter != null) {
              instrumenter.readFromClient(bytes);
            }
          } else if (key.isWritable()) {
            logger.trace("(W)");
            HTTPProcessor processor = (HTTPProcessor) key.attachment();
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
      channel.bind(new InetSocketAddress(configuration.getBindAddress(), configuration.getPort()));
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
   * Specify the full configuration object for the server rather than using the {@code with} builder methods.
   *
   * @param configuration The configuration for the server.
   * @return This.
   */
  public HTTPServer withConfiguration(HTTPServerConfiguration configuration) {
    this.configuration = configuration;
    this.logger = configuration.getLoggerFactory().getLogger(HTTPServer.class);
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
