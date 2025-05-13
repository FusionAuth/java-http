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
package io.fusionauth.http.server.internal;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

import io.fusionauth.http.log.Logger;
import io.fusionauth.http.security.SecurityTools;
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServerConfiguration;
import io.fusionauth.http.server.Instrumenter;
import io.fusionauth.http.server.io.Throughput;

/**
 * A thread that manages the accept process for a single server socket. Once a connection is accepted, the socket is passed to a virtual
 * thread for processing.
 *
 * @author Brian Pontarelli
 */
public class HTTPServerThread extends Thread {
  private final HTTPServerCleanerThread cleaner;

  private final Deque<ClientInfo> clients = new ConcurrentLinkedDeque<>();

  private final HTTPServerConfiguration configuration;

  private final Instrumenter instrumenter;

  private final HTTPListenerConfiguration listener;

  private final Logger logger;

  private final long minimumReadThroughput;

  private final long minimumWriteThroughput;

  private final ServerSocket socket;

  private volatile boolean running;

  public HTTPServerThread(HTTPServerConfiguration configuration, HTTPListenerConfiguration listener)
      throws IOException, GeneralSecurityException {
    super("HTTP server [" + listener.getBindAddress().toString() + ":" + listener.getPort() + "]");

    this.configuration = configuration;
    this.listener = listener;
    this.instrumenter = configuration.getInstrumenter();
    this.logger = configuration.getLoggerFactory().getLogger(HTTPServerThread.class);
    this.minimumReadThroughput = configuration.getMinimumReadThroughput();
    this.minimumWriteThroughput = configuration.getMinimumWriteThroughput();
    this.cleaner = new HTTPServerCleanerThread();

    if (listener.isTLS()) {
      SSLContext context = SecurityTools.serverContext(listener.getCertificateChain(), listener.getPrivateKey());
      this.socket = context.getServerSocketFactory().createServerSocket();
    } else {
      this.socket = new ServerSocket();
    }

    socket.setSoTimeout(0); // Always block
    socket.bind(new InetSocketAddress(listener.getBindAddress(), listener.getPort()));

    if (instrumenter != null) {
      instrumenter.serverStarted();
    }
  }

  @Override
  public void run() {
    running = true;
    cleaner.start();

    while (running) {
      try {
        Socket clientSocket = socket.accept();
        clientSocket.setSoTimeout((int) configuration.getInitialReadTimeoutDuration().toMillis());
        logger.debug("Accepted inbound connection with [{}] existing connections and initial read timeout of [{}]", clients.size(),
            configuration.getInitialReadTimeoutDuration().toMillis());

        if (instrumenter != null) {
          instrumenter.acceptedConnection();
        }

        Throughput throughput = new Throughput(configuration.getReadThroughputCalculationDelay().toMillis(), configuration.getWriteThroughputCalculationDelay().toMillis());
        HTTPWorker runnable = new HTTPWorker(clientSocket, configuration, instrumenter, listener, throughput);
        Thread client = Thread.ofVirtual()
                              .name("HTTP client [" + clientSocket.getRemoteSocketAddress() + "]")
                              .start(runnable);
        clients.add(new ClientInfo(client, runnable, throughput));
      } catch (SocketTimeoutException ignore) {
        // Completely smother since this is expected with the SO_TIMEOUT setting in the constructor
        logger.debug("Nothing accepted. Cleaning up existing connections.");
      } catch (SocketException e) {
        // This should only happen when the server is shutdown
        if (socket.isClosed()) {
          running = false;
          logger.debug("The server socket was closed. Shutting down the server.", e);
        } else {
          logger.error("An exception was thrown while accepting incoming connections.", e);
        }
      } catch (IOException ignore) {
        // Completely smother since most IO exceptions are common during the connection phase
        logger.debug("IO exception. Likely a fuzzer or a bad client or a TLS issue, all of which are common and can mostly be ignored.");
      } catch (Throwable t) {
        logger.error("An exception was thrown during server processing. This is a fatal issue and we need to shutdown the server.", t);
        break;
      }
    }

    // Close all the client connections as cleanly as possible
    for (ClientInfo client : clients) {
      client.thread().interrupt();
    }
  }

  public void shutdown() {
    running = false;
    try {
      cleaner.interrupt();
      socket.close();
    } catch (IOException ignore) {
      // Ignorable since we are shutting down regardless
    }
  }

  record ClientInfo(Thread thread, HTTPWorker runnable, Throughput throughput) {
  }

  private class HTTPServerCleanerThread extends Thread {
    public HTTPServerCleanerThread() {
      super("Cleaner for HTTP server [" + listener.getBindAddress().toString() + ":" + listener.getPort() + "]");
    }

    public void run() {
      while (running) {
        logger.trace("Wake up. Clean up server threads.");

        Iterator<ClientInfo> iterator = clients.iterator();
        while (iterator.hasNext()) {
          ClientInfo client = iterator.next();
          Thread thread = client.thread();
          long threadId = thread.threadId();
          if (!thread.isAlive()) {
            logger.debug("[{}] Thread is dead. Removing.", threadId);
            iterator.remove();
            continue;
          }

          long now = System.currentTimeMillis();
          Throughput throughput = client.throughput();
          HTTPWorker worker = client.runnable();
          HTTPWorker.State state = worker.state();
          long workerLastUsed = throughput.lastUsed();
          boolean readingSlow = false;
          boolean writingSlow = false;
          boolean timedOut = false;
          boolean badClient = false;

          // -1 means we have not yet calculated these values
          long readThroughput = -1;
          long writeThroughput = -1;

          String badClientReason = "[" + threadId + "] Checking worker in state [" + state + "]";
          if (state == HTTPWorker.State.Read) {
            // Here the SO_TIMEOUT set above or the Keep-Alive timeout in HTTPWorker will dictate if the socket has timed out. This prevents slow readers
            // or network issues where the client reads 1 byte per timeout value (i.e. 1 byte per 2 seconds or something like that)
            readThroughput = throughput.readThroughput(now);
            badClient = readThroughput < minimumReadThroughput;
            readingSlow = badClient;
            badClientReason += " readingSlow=[" + readingSlow + "] readThroughput=[" + readThroughput + "] minimumReadThroughput=[" + minimumReadThroughput + "]";
          } else if (state == HTTPWorker.State.Write) {
            // Check for slow clients when writing (or network issues)
            writeThroughput = throughput.writeThroughput(now);
            badClient = writeThroughput < minimumWriteThroughput;
            writingSlow = badClient;
            badClientReason += " writingSlow=[" + writingSlow + "] writeThroughput=[" + writeThroughput + "] minimumWriteThroughput=[" + minimumWriteThroughput + "]";
          } else if (state == HTTPWorker.State.Process) {
            // Here lastUsed was the instant the last byte was read, so we calculate distance between that and now to see if it is beyond the timeout
            long waited = (now - workerLastUsed);
            badClient = waited > configuration.getProcessingTimeoutDuration().toMillis();
            timedOut = badClient;
            badClientReason += " timedOut=[" + timedOut + "] waited=[" + waited + "] processingTimeoutDuration=[" + configuration.getProcessingTimeoutDuration().toMillis() + "]";
          }

          logger.debug(badClientReason);

          if (!badClient) {
            continue;
          }

          // Bad client, first things first, remove the client from the list
          iterator.remove();

          if (logger.isDebugEnabled()) {
            String message = "";

            if (readingSlow) {
              message += String.format(" Min read throughput [%s], actual throughput [%s].", minimumReadThroughput, readThroughput);
            }

            if (writingSlow) {
              message += String.format(" Min write throughput [%s], actual throughput [%s].", minimumWriteThroughput, writeThroughput);
            }

            if (timedOut) {
              message += String.format(" Connection timed out while processing. Last used [%s]ms ago. Configured client timeout [%s]ms.", (now - workerLastUsed), configuration.getProcessingTimeoutDuration().toMillis());
            }

            logger.debug("[{}] Closing connection readingSlow=[{}] writingSlow=[{}] timedOut=[{}] {}", threadId, readingSlow, writingSlow, timedOut, message);
            logger.debug("[{}] Closing client connection [{}] due to inactivity", threadId, worker.getSocket().getRemoteSocketAddress());

            StringBuilder threadDump = new StringBuilder();
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
              threadDump.append(entry.getKey()).append(" ").append(entry.getKey().getState()).append("\n");
              for (StackTraceElement ste : entry.getValue()) {
                threadDump.append("\tat ").append(ste).append("\n");
              }
              threadDump.append("\n");
            }

            logger.debug("Thread dump from server side.\n" + threadDump);
          }

          try {
            var socket = worker.getSocket();
            socket.close();

            if (instrumenter != null) {
              instrumenter.connectionClosed();
            }
          } catch (IOException e) {
            // Log but ignore
            logger.debug(String.format("[%s] Unable to close connection to client. [{}]", threadId), e);
          }
        }

        // Take a break
        try {
          //noinspection BusyWait
          sleep(2_000);
        } catch (InterruptedException ignore) {
          // Ignore
        }
      }
    }
  }
}
