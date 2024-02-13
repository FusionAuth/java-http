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

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;

import io.fusionauth.http.ConnectionClosedException;
import io.fusionauth.http.HTTPValues.Connections;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.ParseException;
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

  private final Instrumenter instrumenter;

  private final HTTPListenerConfiguration listener;

  private final Logger logger;

  private final Socket socket;

  private final HTTPThroughput throughput;

  private volatile HTTPOutputStream outputStream;

  private HTTPResponse response;

  public HTTPWorker(Socket socket, HTTPServerConfiguration configuration, Instrumenter instrumenter, HTTPListenerConfiguration listener,
                    HTTPThroughput throughput) {
    this.socket = socket;
    this.configuration = configuration;
    this.instrumenter = instrumenter;
    this.listener = listener;
    this.throughput = throughput;
    this.logger = configuration.getLoggerFactory().getLogger(HTTPWorker.class);
  }

  public Socket getSocket() {
    return socket;
  }

  @Override
  public void run() {
    boolean keepAlive = false;
    try {
      while (true) {
        var request = new HTTPRequest(configuration.getContextPath(), configuration.getMultipartBufferSize(),
            listener.getCertificate() != null ? "https" : "http", listener.getPort(), socket.getInetAddress().getHostAddress());

        var bodyBytes = HTTPTools.parseRequestPreamble(socket.getInputStream(), request);
        var inputStream = new HTTPInputStream(configuration, throughput, request, socket.getInputStream(), bodyBytes);
        request.setInputStream(inputStream);

        response = new HTTPResponse();
        outputStream = new HTTPOutputStream(configuration, throughput, request, response, socket.getOutputStream());
        response.setOutputStream(outputStream);

        var connection = request.getHeader(Headers.Connection);
        if (connection == null || !connection.equalsIgnoreCase(Connections.Close)) {
          response.setHeader(Headers.Connection, Connections.KeepAlive);
          keepAlive = true;
        }

        var handler = configuration.getHandler();
        handler.handle(request, response);
        response.close();
        outputStream = null;

        if (!response.isKeepAlive()) {
          close(Result.Success);
          break;
        }
      }
    } catch (SocketTimeoutException ste) {
      // This might be a read timeout or a Keep-Alive timeout. The failure state is based on that flag
      close(keepAlive ? Result.Success : Result.Failure);

      if (keepAlive) {
        logger.debug("Closing connection because the Keep-Alive expired");
      }
    } catch (ParseException pe) {
      if (instrumenter != null) {
        instrumenter.badRequest();
      }

      close(Result.Failure);
    } catch (IOException io) {
      logger.debug("An IO exception was thrown during processing. These are pretty common.", io);
      close(Result.Failure);
    } catch (Throwable t) {
      // Log the error and signal a failure
      logger.error("HTTP worker threw an exception while processing a request", t);
      close(Result.Failure);
    }
  }

  public WorkerState state() {
    return outputStream != null && outputStream.isCommitted() ? WorkerState.Write : WorkerState.Read;
  }

  private void close(Result result) {
    // The server might have already closed the socket, so we don't need to do that here
    if (socket.isClosed()) {
      return;
    }

    // If the conditions are perfect, we can still write back a 500
    if (outputStream != null && !outputStream.isCommitted() && response != null) {
      response.setStatus(500);
      response.setContentLength(0L);

      try {
        outputStream.close();
      } catch (IOException ignore) {
        // This likely means the client has severed the connection, we can still proceed below and close the socket
      }
    }

    if (result == Result.Failure && instrumenter != null) {
      instrumenter.connectionClosed();
    }

    try {
      logger.debug("Closing connection [{}]", result);
      socket.shutdownInput();
      socket.shutdownOutput();
      socket.close();
    } catch (IOException e) {
      throw new ConnectionClosedException(e);
    }
  }

  private enum Result {
    Failure,
    Success
  }
}
