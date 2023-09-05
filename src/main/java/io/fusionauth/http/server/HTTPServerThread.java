/*
 * Copyright (c) 2022-2023, FusionAuth, All Rights Reserved
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
import java.util.List;
import java.util.Map;

import io.fusionauth.http.ClientAbortException;
import io.fusionauth.http.ParseException;
import io.fusionauth.http.log.Logger;
import io.fusionauth.http.util.ThreadPool;

/**
 * A thread that manages the Selection process for a single server socket. Since some server resources are shared, this separates the shared
 * resources across sockets by selecting separately.
 *
 * @author Brian Pontarelli
 */
public class HTTPServerThread extends Thread implements Closeable, Notifier {
  private final ServerSocketChannel channel;

  private final Duration clientTimeout;

  private final HTTPServerConfiguration configuration;

  private final Instrumenter instrumenter;

  private final HTTPListenerConfiguration listenerConfiguration;

  private final Logger logger;

  private final long minimumReadThroughput;

  private final long minimumWriteThroughput;

  private final ByteBuffer preambleBuffer;

  private final Selector selector;

  private final ThreadPool threadPool;

  private volatile boolean running = true;

  public HTTPServerThread(HTTPServerConfiguration configuration, HTTPListenerConfiguration listenerConfiguration, ThreadPool threadPool)
      throws IOException {
    super("HTTP Server Thread");
    this.clientTimeout = configuration.getClientTimeoutDuration();
    this.configuration = configuration;
    this.listenerConfiguration = listenerConfiguration;
    this.instrumenter = configuration.getInstrumenter();
    this.logger = configuration.getLoggerFactory().getLogger(HTTPServerThread.class);
    this.minimumReadThroughput = configuration.getMinimumReadThroughput();
    this.minimumWriteThroughput = configuration.getMinimumWriteThroughput();
    this.preambleBuffer = ByteBuffer.allocate(configuration.getPreambleBufferSize());
    this.selector = Selector.open();
    this.threadPool = threadPool;

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
      running = false;
      selector.wakeup();
      join(2_000L);
    } catch (InterruptedException e) {
      logger.error("Unable to shutdown the HTTP server thread after waiting for 2 seconds. 🤷🏻‍️");
    }

    // Close all the client connections as cleanly as possible
    var keys = selector.keys();
    for (SelectionKey key : keys) {
      cancelAndCloseKey(key);
    }

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
    var keys = List.copyOf(selector.keys());
    for (SelectionKey key : keys) {
      try {
        // If the key is toast, skip it
        if (!key.isValid()) {
          continue;
        }

        HTTPProcessor processor = (HTTPProcessor) key.attachment();
        if (processor == null) { // No processor for you!
          continue;
        }

        ProcessorState state = processor.state();
        if (state == ProcessorState.Read && key.interestOps() != SelectionKey.OP_READ) {
          logger.debug("Flipping a SelectionKey to Read because it wasn't in the right state");
          key.interestOps(SelectionKey.OP_READ);
        } else if (state == ProcessorState.Write && key.interestOps() != SelectionKey.OP_WRITE) {
          logger.debug("Flipping a SelectionKey to Write because it wasn't in the right state");
          key.interestOps(SelectionKey.OP_WRITE);
        }
      } catch (Throwable t) {
        // Smother since the key is likely invalid
        logger.debug("Exception occurred while trying to update a key", t);
      }
    }

    // Wake-up! Time to put on a little make-up!
    selector.wakeup();
  }

  @Override
  public void run() {
    while (running) {
      SelectionKey key = null;
      try {
        selector.select(1_000L);

        var keys = selector.selectedKeys();
        var iterator = keys.iterator();
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

          // Always clear the preamble for good measure
          preambleBuffer.clear();
        }

        cleanup();
      } catch (ClosedSelectorException cse) {
        // Shut down
        break;
      } catch (SocketException se) {
        logger.debug("A socket exception was thrown during processing. These are pretty common.", se);
        cancelAndCloseKey(key);
      } catch (ParseException pe) {
        logger.debug("The HTTP request was unparseable. These are pretty common with fuzzers/hackers, so we just debug them here.", pe);
        if (instrumenter != null) {
          instrumenter.badRequest();
        }

        cancelAndCloseKey(key);
      } catch (ClientAbortException e) {
        // A client abort exception is common and should not be error logged.
        logger.debug("A client related exception was thrown during processing", e);
        cancelAndCloseKey(key);
      } catch (Throwable t) {
        logger.error("An exception was thrown during processing", t);
        cancelAndCloseKey(key);
      } finally {
        // Always clear the preamble for good measure
        preambleBuffer.clear();
      }
    }
  }

  @SuppressWarnings("MagicConstant")
  private void accept(SelectionKey key) throws GeneralSecurityException, IOException {
    var client = channel.accept();
    HTTP11Processor httpProcessor = new HTTP11Processor(configuration, listenerConfiguration, this, preambleBuffer, threadPool, ipAddress(client));
    HTTPS11Processor tlsProcessor = new HTTPS11Processor(httpProcessor, configuration, listenerConfiguration);
    client.configureBlocking(false);
    client.register(key.selector(), tlsProcessor.initialKeyOps(), tlsProcessor);

    if (logger.isDebugEnabled()) {
      try {
        logger.debug("Accepted connection from client [{}]", client.getRemoteAddress().toString());
      } catch (IOException e) {
        /// Ignore because we are just debugging
      }
    }

    if (instrumenter != null) {
      instrumenter.acceptedConnection();
    }
  }

  private void cancelAndCloseKey(SelectionKey key) {
    if (key != null) {
      try (var client = key.channel()) {
        if (logger.isDebugEnabled() && client instanceof SocketChannel socketChannel) {
          logger.debug("Closing connection to client [{}]", socketChannel.getRemoteAddress().toString());
        }

        // Close the processor, which should kill the thread
        if (key.attachment() != null) {
          ((HTTPProcessor) key.attachment()).close(false);
        }

        key.cancel();

        if (client.validOps() != SelectionKey.OP_ACCEPT && instrumenter != null) {
          instrumenter.connectionClosed();
        }
      } catch (Throwable t) {
        logger.error("An exception was thrown while trying to cancel a SelectionKey and close a channel with a client due to an exception being thrown for that specific client. Enable debug logging to see the error", t);
      }

      logger.trace("(C)");
    }
  }

  @SuppressWarnings("resource")
  private void cleanup() {
    long now = System.currentTimeMillis();
    for (SelectionKey key : selector.keys()) {
      if (key.attachment() == null) {
        continue;
      }

      var processor = (HTTPProcessor) key.attachment();
      ProcessorState state = processor.state();
      boolean readingSlow = state == ProcessorState.Read && processor.readThroughput() < minimumReadThroughput;
      boolean writingSlow = state == ProcessorState.Write && processor.writeThroughput() < minimumWriteThroughput;
      boolean timedOut = processor.lastUsed() < now - clientTimeout.toMillis();
      boolean badChannel = readingSlow || writingSlow || timedOut;

      if (!badChannel) {
        continue;
      }

      if (logger.isDebugEnabled()) {
        String message = readingSlow
            ? String.format(". Min read throughput [%s], actual throughput [%s]", minimumReadThroughput, processor.readThroughput())
            : writingSlow
            ? String.format(". Max write throughput [%s], actual throughput [%s]", minimumWriteThroughput, processor.writeThroughput())
            : "";

        logger.debug("Closing connection readingSlow=[{}] writingSlow=[{}] timedOut=[{}]{}", readingSlow, writingSlow, timedOut, message);

        var client = (SocketChannel) key.channel();
        try {
          logger.debug("Closing client connection [{}] due to inactivity", client.getRemoteAddress().toString());

          StringBuilder threadDump = new StringBuilder();
          for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            threadDump.append(entry.getKey()).append(" ").append(entry.getKey().getState()).append("\n");
            for (StackTraceElement ste : entry.getValue()) {
              threadDump.append("\tat ").append(ste).append("\n");
            }
            threadDump.append("\n");
          }

          logger.debug("Thread dump from server side.\n" + threadDump);
        } catch (IOException e) {
          // Ignore because we are just debugging
        }
      }

      cancelAndCloseKey(key);
    }
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
        int num;
        try {
          num = client.read(buffer);
        } catch (IOException e) {
          // This is most likely an exception caused by the client.
          throw new ClientAbortException(e);
        }

        if (num < 0) {
          logger.debug("Client terminated the connection. Num bytes is [{}]. Closing connection", num);
          state = processor.close(true);
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

  private void write(SelectionKey key) throws IOException {
    HTTPS11Processor processor = (HTTPS11Processor) key.attachment();
    ProcessorState state = processor.state();
    SocketChannel client = (SocketChannel) key.channel();
    ByteBuffer[] buffers = processor.writeBuffers();
    if (state == ProcessorState.Write) {
      long num = 0;
      if (buffers != null) {
        try {
          num = client.write(buffers);
        } catch (IOException e) {
          // This is most likely an exception caused by the client.
          throw new ClientAbortException(e);
        }
      }

      if (num < 0) {
        logger.debug("Client refused bytes or terminated the connection. Num bytes is [{}]. Closing connection", num);
        state = processor.close(true);
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
      HTTP11Processor httpProcessor = new HTTP11Processor(configuration, listenerConfiguration, this, preambleBuffer, threadPool, ipAddress(client));
      processor.updateDelegate(httpProcessor);
      key.interestOps(SelectionKey.OP_READ);
    }
  }
}
