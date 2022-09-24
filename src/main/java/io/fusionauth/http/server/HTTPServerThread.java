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
import java.security.GeneralSecurityException;
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

  public HTTPServerThread(HTTPServerConfiguration configuration, HTTPListenerConfiguration listenerConfiguration,
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
    if (!selector.isOpen()) {
      return;
    }

    // Update the keys based on any changes to the processor state
    var keys = selector.keys();
    for (SelectionKey key : keys) {
      HTTPProcessor processor = (HTTPProcessor) key.attachment();
      if (processor != null) {
        ProcessorState state = processor.state();
        if (state == ProcessorState.Read && key.interestOps() != SelectionKey.OP_READ) {
          logger.debug("Flipping a SelectionKey to Read because it wasn't in the right state");
          key.interestOps(SelectionKey.OP_READ);
        } else if (state == ProcessorState.Write && key.interestOps() != SelectionKey.OP_WRITE) {
          logger.debug("Flipping a SelectionKey to Write because it wasn't in the right state");
          key.interestOps(SelectionKey.OP_WRITE);
        }
      }
    }

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
            accept(key);
          } else if (key.isReadable()) {
            read(key);
          } else if (key.isWritable()) {
            write(key);
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
        cancelAndCloseKey(key);
      } catch (Throwable t) {
        logger.error("An exception was thrown during processing", t);
        cancelAndCloseKey(key);
      }
    }
  }

  @SuppressWarnings("MagicConstant")
  private void accept(SelectionKey key) throws GeneralSecurityException, IOException {
    var client = channel.accept();
    HTTPProcessor processor = processorFactory.build(this, preambleBuffer, ipAddress(client));
    client.configureBlocking(false);
    client.register(key.selector(), processor.initialKeyOps(), processor);

    if (instrumenter != null) {
      instrumenter.acceptedConnection();
    }
  }

  private void cancelAndCloseKey(SelectionKey key) {
    if (key != null) {
      try (var ignore = key.channel()) {
        key.cancel();
      } catch (Throwable t) {
        logger.error("An exception was thrown while trying to cancel a SelectionKey and close a channel with a client due to an exception being thrown for that specific client. Enable debug logging to see the error", t);
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

  private String ipAddress(SocketChannel client) throws IOException {
    InetSocketAddress ipAddress = (InetSocketAddress) client.getRemoteAddress();
    return ipAddress.getAddress().getHostAddress();
  }

  private void read(SelectionKey key) throws IOException {
    HTTPProcessor processor = (HTTPProcessor) key.attachment();
    ProcessorState state = processor.state();
    SocketChannel client = (SocketChannel) key.channel();
    if (state == ProcessorState.Read) {
      ByteBuffer buffer = processor.readBuffer();
      if (buffer != null) {
        int num = client.read(buffer);
        if (num < 0) {
          state = processor.close();
        } else {
          logger.debug("Read [{}] bytes from client", num);

          buffer.flip();
          state = processor.read(buffer);

          if (instrumenter != null) {
            instrumenter.readFromClient(num);
          }
        }
      }
    }

    // Turn the key around to start writing back the response or cancel and close it if instructed to
    if (state == ProcessorState.Close) {
      cancelAndCloseKey(key);
    } else if (state == ProcessorState.Write) {
      key.interestOps(SelectionKey.OP_WRITE);
    }
  }

  private void write(SelectionKey key) throws GeneralSecurityException, IOException {
    HTTPProcessor processor = (HTTPProcessor) key.attachment();
    ProcessorState state = processor.state();
    SocketChannel client = (SocketChannel) key.channel();
    ByteBuffer[] buffers = processor.writeBuffers();
    if (state == ProcessorState.Write) {
      long num = 0;
      if (buffers != null) {
        num = client.write(buffers);
      }

      if (num < 0) {
        state = processor.close();
      } else {
        if (num > 0) {
          logger.debug("Wrote [{}] bytes to the client", num);

          if (instrumenter != null) {
            instrumenter.wroteToClient(num);
          }
        }

        // Always call wrote to update the state even if zero bytes were written
        state = processor.wrote(num);
      }
    }

    // If the key is done, cancel and close it out. Otherwise, turn it around for KeepAlive handling to start reading the next request
    if (state == ProcessorState.Close) {
      cancelAndCloseKey(key);
    } else if (state == ProcessorState.Read) {
      key.interestOps(SelectionKey.OP_READ);
    } else if (state == ProcessorState.Reset) {
      processor = processorFactory.build(this, preambleBuffer, ipAddress(client));
      key.attach(processor);
      key.interestOps(SelectionKey.OP_READ);
    }
  }
}
