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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.fusionauth.http.log.Logger;

/**
 * A thread that manages the Selection process for a single server socket. Since some server resources are shared, this separates the shared
 * resources across sockets by selecting separately.
 *
 * @author Brian Pontarelli
 */
public class HTTPServerRunnable implements Runnable {
  private final Duration clientTimeout;

  private final List<ClientInfo> clients = new ArrayList<>();

  private final HTTPServerConfiguration configuration;

  private final Instrumenter instrumenter;

  private final HTTPListenerConfiguration listener;

  private final Logger logger;

  private final long minimumReadThroughput;

  private final long minimumWriteThroughput;

  private final ServerSocket socket;

  public HTTPServerRunnable(HTTPServerConfiguration configuration, HTTPListenerConfiguration listener) throws IOException {
    this.configuration = configuration;
    this.listener = listener;
    this.clientTimeout = configuration.getClientTimeoutDuration();
    this.instrumenter = configuration.getInstrumenter();
    this.logger = configuration.getLoggerFactory().getLogger(HTTPServerRunnable.class);
    this.minimumReadThroughput = configuration.getMinimumReadThroughput();
    this.minimumWriteThroughput = configuration.getMinimumWriteThroughput();

    this.socket = new ServerSocket(listener.getPort(), -1, listener.getBindAddress());

    if (instrumenter != null) {
      instrumenter.serverStarted();
    }
  }

  @Override
  public void run() {
    while (true) {
      try {
        Socket clientSocket = socket.accept();
        HTTPWorker runnable = new HTTPWorker(clientSocket, configuration, listener);
        Thread client = Thread.ofVirtual()
                              .name("HTTP client [" + clientSocket.getRemoteSocketAddress() + "]")
                              .start(runnable);
        clients.add(new ClientInfo(client, runnable));
        cleanup();
      } catch (IOException io) {
        logger.debug("An IO exception was thrown during processing. These are pretty common.", io);
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

  private void cleanup() {
    Iterator<ClientInfo> iterator = clients.iterator();
    while (iterator.hasNext()) {
      ClientInfo client = iterator.next();
      Thread thread = client.thread();
      if (!thread.isAlive()) {
        iterator.remove();
      }

      long now = System.currentTimeMillis();
      HTTPWorker worker = client.runnable();
      WorkerState state = worker.state();
      boolean readingSlow = state == WorkerState.Read && worker.readThroughput() < minimumReadThroughput;
      boolean writingSlow = state == WorkerState.Write && worker.writeThroughput() < minimumWriteThroughput;
      boolean timedOut = worker.lastUsed() < now - clientTimeout.toMillis();
      boolean badChannel = readingSlow || writingSlow || timedOut;

      if (!badChannel) {
        continue;
      }

      if (logger.isDebugEnabled()) {
        String message = "";

        if (readingSlow) {
          message += String.format(" Min read throughput [%s], actual throughput [%s].", minimumReadThroughput, worker.readThroughput());
        }

        if (writingSlow) {
          message += String.format(" Min write throughput [%s], actual throughput [%s].", minimumWriteThroughput, worker.writeThroughput());
        }

        if (timedOut) {
          message += String.format(" Connection timed out. Configured client timeout [%s] ms.", clientTimeout.toMillis());
        }

        logger.debug("Closing connection readingSlow=[{}] writingSlow=[{}] timedOut=[{}]{}", readingSlow, writingSlow, timedOut, message);
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
    }
  }

  record ClientInfo(Thread thread, HTTPWorker runnable) {
  }
}
