/*
 * Copyright (c) 2022-2024, FusionAuth, All Rights Reserved
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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.fusionauth.http.log.Logger;
import io.fusionauth.http.security.SecurityTools;
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServerConfiguration;
import io.fusionauth.http.server.Instrumenter;
import io.fusionauth.http.server.io.Throughput;

/**
 * A thread that manages the Selection process for a single server socket. Since some server resources are shared, this separates the shared
 * resources across sockets by selecting separately.
 *
 * @author Brian Pontarelli
 */
public class HTTPServerThread extends Thread {
  private final Duration clientTimeout;

  private final List<ClientInfo> clients = new ArrayList<>();

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
    this.clientTimeout = configuration.getClientTimeoutDuration();
    this.instrumenter = configuration.getInstrumenter();
    this.logger = configuration.getLoggerFactory().getLogger(HTTPServerThread.class);
    this.minimumReadThroughput = configuration.getMinimumReadThroughput();
    this.minimumWriteThroughput = configuration.getMinimumWriteThroughput();

    if (listener.isTLS()) {
      SSLContext context = SecurityTools.serverContext(listener.getCertificateChain(), listener.getPrivateKey());
      this.socket = context.getServerSocketFactory().createServerSocket(listener.getPort(), -1, listener.getBindAddress());
    } else {
      this.socket = new ServerSocket(listener.getPort(), -1, listener.getBindAddress());
    }

    socket.setSoTimeout(1_000); // Allows us to clean up periodically

    if (instrumenter != null) {
      instrumenter.serverStarted();
    }
  }

  @Override
  public void run() {
    running = true;
    while (running) {
      try {
        Socket clientSocket = socket.accept();
        clientSocket.setSoTimeout((int) configuration.getClientTimeoutDuration().toMillis());
        logger.info("Accepted inbound connection with [{}] existing connections", clients.size());

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
      } catch (SocketException e) {
        // This should only happen when the server is shutdown
        if (socket.isClosed()) {
          running = false;
        } else {
          logger.error("An exception was thrown while accepting incoming connections.", e);
        }
      } catch (Throwable t) {
        logger.error("An exception was thrown during server processing. This is a fatal issue and we need to shutdown the server.", t);
        break;
      } finally {
        cleanup();
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
      socket.close();
    } catch (IOException ignore) {
      // Ignorable since we are shutting down regardless
    }
  }

  private void cleanup() {
    if (!running) {
      return;
    }

    Iterator<ClientInfo> iterator = clients.iterator();
    while (iterator.hasNext()) {
      ClientInfo client = iterator.next();
      Thread thread = client.thread();
      if (!thread.isAlive()) {
        logger.info("Thread is dead. Removing.");
        iterator.remove();
        continue;
      }

      long now = System.currentTimeMillis();
      Throughput throughput = client.throughput();
      HTTPWorker worker = client.runnable();
      WorkerState state = worker.state();
      long workerLastUsed = throughput.lastUsed();
      boolean readingSlow = state == WorkerState.Read && throughput.readThroughput(now) < minimumReadThroughput;
      boolean writingSlow = state == WorkerState.Write && throughput.writeThroughput(now) < minimumWriteThroughput;
      boolean timedOut = workerLastUsed < now - clientTimeout.toMillis();
      boolean badClient = readingSlow || writingSlow || timedOut;

      if (!badClient) {
        continue;
      }

      if (logger.isDebugEnabled()) {
        String message = "";

        if (readingSlow) {
          message += String.format(" Min read throughput [%s], actual throughput [%s].", minimumReadThroughput, throughput.readThroughput(now));
        }

        if (writingSlow) {
          message += String.format(" Min write throughput [%s], actual throughput [%s].", minimumWriteThroughput, throughput.writeThroughput(now));
        }

        if (timedOut) {
          message += String.format(" Connection timed out. Last used [%s]ms ago. Configured client timeout [%s]ms.", (now - workerLastUsed), clientTimeout.toMillis());
        }

        logger.debug("Closing connection readingSlow=[{}] writingSlow=[{}] timedOut=[{}] {}", readingSlow, writingSlow, timedOut, message);
        logger.debug("Closing client connection [{}] due to inactivity", worker.getSocket().getRemoteSocketAddress());

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
        iterator.remove();

        if (instrumenter != null) {
          instrumenter.connectionClosed();
        }
      } catch (IOException e) {
        // Log but ignore
        logger.debug("Unable to close connection to client. [{}]", e);
      }
    }
  }

  record ClientInfo(Thread thread, HTTPWorker runnable, Throughput throughput) {
  }
}
