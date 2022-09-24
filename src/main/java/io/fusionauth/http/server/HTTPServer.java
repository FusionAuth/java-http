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
import java.util.ArrayList;
import java.util.List;

import io.fusionauth.http.log.Logger;
import io.fusionauth.http.util.ThreadPool;

public class HTTPServer implements Closeable, Configurable<HTTPServer> {
  private final List<HTTPServerThread> threads = new ArrayList<>();

  private HTTPServerConfiguration configuration = new HTTPServerConfiguration();

  private HTTPContext context;

  private Logger logger;

  private ThreadPool threadPool;

  @Override
  public void close() {
    logger.info("HTTP server shutdown requested. Attempting to close each listener. This could take a while.");

    for (HTTPServerThread thread : threads) {
      thread.close();

      try {
        thread.join(10_000);
      } catch (InterruptedException e) {
        // Ignore so we join on all the threads
      }
    }

    if (threadPool.shutdown()) {
      logger.info("HTTP server shutdown successfully.");
    } else {
      logger.error("HTTP server shutdown failed. It's harshing my mellow!");
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

  public void start() {
    logger = configuration.getLoggerFactory().getLogger(HTTPServer.class);
    logger.info("Starting the HTTP server. Buckle up!");

    context = new HTTPContext(configuration.getBaseDir());

    // Start the thread pool for the workers
    threadPool = new ThreadPool(configuration.getNumberOfWorkerThreads(), "HTTP Server Worker Thread", configuration.getShutdownDuration());

    for (HTTPListenerConfiguration listener : configuration.getListeners()) {
      HTTPServerThread thread = new HTTPServerThread(listener, configuration, new HTTPProcessorFactory());
      thread.start();
      threads.add(thread);
    }

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
}
