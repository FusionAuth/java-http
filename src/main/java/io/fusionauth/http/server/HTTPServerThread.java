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
import java.time.Duration;
import java.util.Iterator;

import io.fusionauth.http.log.Logger;

/**
 * A thread that manages the Selection process for a single server socket. Since some server resources are shared, this separates the shared
 * resources across sockets by selecting separately.
 *
 * @author Brian Pontarelli
 */
public class HTTPServerThread extends Thread implements Closeable, Notifier {
  private final ServerSocketChannel channel;

  private final Duration clientTimeout;

  private final Instrumenter instrumenter;

  private final Logger logger;

  private final ByteBuffer preambleBuffer;

  private final HTTPProcessorFactory processorFactory;

  private final Selector selector;

  public HTTPServerThread(HTTPListenerConfiguration listenerConfiguration, HTTPServerConfiguration configuration,
                          HTTPProcessorFactory processorFactory)
      throws IOException {
    this.clientTimeout = configuration.getClientTimeoutDuration();
    this.instrumenter = configuration.getInstrumenter();
    this.processorFactory = processorFactory;
    this.logger = configuration.getLoggerFactory().getLogger(HTTPServerThread.class);
    this.preambleBuffer = ByteBuffer.allocate(configuration.getPreambleBufferSize());
    this.selector = Selector.open();
    this.channel = ServerSocketChannel.open();
    this.channel.configureBlocking(false);
    this.channel.bind(new InetSocketAddress(listenerConfiguration.getBindAddress(), listenerConfiguration.getPort()));
    this.channel.register(selector, SelectionKey.OP_ACCEPT);

    if (instrumenter != null) {
      instrumenter.serverStarted();
    }
  }

  @Override
  public void close() {
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

    notifyNow();
  }

  @Override
  public void notifyNow() {
    // Wake-up! Time to put on a little make-up!
    selector.wakeup();
  }

  @Override
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
            logger.trace("(A)");
            var clientChannel = channel.accept();
            InetSocketAddress ipAddress = (InetSocketAddress) clientChannel.getRemoteAddress();
            HTTPProcessor processor = processorFactory.build(ipAddress.getAddress().getHostAddress(), preambleBuffer);
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

  private void cleanup() {
    long now = System.currentTimeMillis();
    selector.keys()
            .stream()
            .filter(key -> key.attachment() != null)
            .filter(key -> ((HTTPProcessor) key.attachment()).lastUsed() < now - clientTimeout.toMillis())
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
