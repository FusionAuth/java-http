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
package io.fusionauth.http.server;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

import io.fusionauth.http.log.Logger;
import io.fusionauth.http.server.internal.HTTPServerThread;
import io.fusionauth.http.util.HTTPTools;

/**
 * The server bro!
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("unused")
public class HTTPServer implements Closeable, Configurable<HTTPServer> {
  private final List<HTTPServerThread> servers = new ArrayList<>();

  private HTTPServerConfiguration configuration = new HTTPServerConfiguration();

  private volatile HTTPContext context;

  private Logger logger;

  @Override
  public void close() {
    long start = System.currentTimeMillis();
    long shutdownDuration = configuration.getShutdownDuration().toMillis();
    logger.info("HTTP server shutdown requested. Attempting to close each listener. Wait up to [{}] ms.", shutdownDuration);

    // First, shutdown all the threads
    for (HTTPServerThread thread : servers) {
      thread.shutdown();
    }

    // Next, try joining on them
    for (Thread thread : servers) {
      try {
        thread.join(shutdownDuration);
      } catch (InterruptedException e) {
        // Ignore so we join on all the threads
      }

      // Just bail
      if (System.currentTimeMillis() - start > shutdownDuration) {
        break;
      }
    }

    logger.info("HTTP server shutdown successfully.");
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

  public HTTPServer start() {
    if (context != null) {
      return this;
    }

    // On server start-up, validate inter-dependant configuration options.
    int maxRequestBodySize = configuration.getMaxRequestBodySize();
    int maxFormDataSize = configuration.getMaxFormDataSize();
    long maxMultipartRequestSize = configuration.getMultipartConfiguration().getMaxRequestSize();
    if (maxRequestBodySize != -1) {
      // The user may disable maxFormDataSize or maxMultipartRequestSize by setting them to -1, that is ok. In this case, maxRequestBodySize will still be enforced.
      if (maxRequestBodySize < maxFormDataSize || maxRequestBodySize < maxMultipartRequestSize ) {
        logger.error("Unable to start the HTTP server because the configuration is invalid. The [maxRequestBodySize] configuration is intended as a fail-safe, and must be greater than or equal to [maxFormDataSize] and [multipartStreamConfiguration.maxRequestSize].");
        return this;
      }
    }

    // Set up the server logger and the static loggers
    logger = configuration.getLoggerFactory().getLogger(HTTPServer.class);
    HTTPTools.initialize(configuration().getLoggerFactory());

    logger.info("Starting the HTTP server. Buckle up!");

    context = new HTTPContext(configuration.getBaseDir());

    try {
      for (HTTPListenerConfiguration listener : configuration.getListeners()) {
        HTTPServerThread server = new HTTPServerThread(configuration, listener);
        servers.add(server);
        server.start();
        logger.info("HTTP server listening on port [{}]", listener.getPort());
      }

      logger.info("HTTP server started successfully");
    } catch (Exception e) {
      logger.error("Unable to start the HTTP server because one of the listeners threw an exception.", e);

      // Clean up the threads that did start
      close();
    }

    return this;
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
