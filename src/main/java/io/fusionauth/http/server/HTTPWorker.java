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

import java.net.Socket;

import io.fusionauth.http.log.Logger;
import io.fusionauth.http.server.io.HTTPInputStream;
import io.fusionauth.http.server.io.HTTPOutputStream;
import io.fusionauth.http.util.HTTPTools;
import io.fusionauth.http.util.ThreadPool;

/**
 * An HTTP worker that is a delegate Runnable to an {@link HTTPHandler}. This provides the interface and handling for use with the
 * {@link ThreadPool}.
 *
 * @author Brian Pontarelli
 */
public class HTTPWorker implements Runnable {
  private final HTTPServerConfiguration configuration;

  private final HTTPListenerConfiguration listener;

  private final Logger logger;

  private final Socket socket;

  private HTTPInputStream inputStream;

  private HTTPOutputStream outputStream;

  public HTTPWorker(Socket socket, HTTPServerConfiguration configuration, HTTPListenerConfiguration listener) {
    this.socket = socket;
    this.logger = configuration.getLoggerFactory().getLogger(HTTPWorker.class);
    this.configuration = configuration;
    this.listener = listener;
  }

  public Socket getSocket() {
    return socket;
  }

  public long lastUsed() {
    return 0;
  }

  public long readThroughput() {
    long readThroughputCalculationDelay = configuration.getReadThroughputCalculationDelay().toMillis();
    if (inputStream == null || outputStream == null) {
      return readThroughputCalculationDelay;
    }

    return inputStream.readThroughput(outputStream.getFirstByteWroteInstant() == -1, readThroughputCalculationDelay);
  }

  @Override
  public void run() {
    try {
      HTTPRequest request = new HTTPRequest(configuration.getContextPath(), configuration.getMultipartBufferSize(),
          listener.getCertificate() != null ? "https" : "http", listener.getPort(), listener.getBindAddress().getHostAddress());

      var bodyBytes = HTTPTools.parseRequestPreamble(socket.getInputStream(), request);
      inputStream = new HTTPInputStream(request, configuration.getLoggerFactory().getLogger(HTTPInputStream.class), socket.getInputStream(), bodyBytes);
      request.setInputStream(inputStream);

      var response = new HTTPResponse();
      outputStream = new HTTPOutputStream(request, response, socket.getOutputStream(), configuration.isCompressByDefault());
      response.setOutputStream(outputStream);

      var handler = configuration.getHandler();
      handler.handle(request, response);
      response.close();

      if (!response.isKeepAlive()) {
        socket.shutdownInput();
        socket.shutdownOutput();
        socket.close();
      }
    } catch (Throwable t) {
      // Log the error and signal a failure
      logger.error("HTTP worker threw an exception while processing a request", t);
    }
  }

  public WorkerState state() {
    return outputStream.isCommitted() ? WorkerState.Write : WorkerState.Read;
  }

  public long writeThroughput() {
    long writeThroughputCalculationDelay = configuration.getWriteThroughputCalculationDelay().toMillis();
    if (outputStream == null) {
      return writeThroughputCalculationDelay;
    }

    return outputStream.writeThroughput(writeThroughputCalculationDelay);
  }
}
