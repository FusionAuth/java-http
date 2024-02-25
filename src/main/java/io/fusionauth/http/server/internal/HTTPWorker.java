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

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import io.fusionauth.http.ConnectionClosedException;
import io.fusionauth.http.HTTPValues.Connections;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.HTTPValues.Status;
import io.fusionauth.http.ParseException;
import io.fusionauth.http.io.FastByteArrayOutputStream;
import io.fusionauth.http.log.Logger;
import io.fusionauth.http.server.ExpectValidator;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPResponse;
import io.fusionauth.http.server.HTTPServerConfiguration;
import io.fusionauth.http.server.Instrumenter;
import io.fusionauth.http.server.io.HTTPInputStream;
import io.fusionauth.http.server.io.HTTPOutputStream;
import io.fusionauth.http.server.io.Throughput;
import io.fusionauth.http.server.io.ThroughputInputStream;
import io.fusionauth.http.server.io.ThroughputOutputStream;
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

  private final byte[] requestBuffer;

  private final FastByteArrayOutputStream responsePreambleStream;

  private final Socket socket;

  private final Throughput throughput;

  private volatile HTTPOutputStream outputStream;

  private HTTPResponse response;

  public HTTPWorker(Socket socket, HTTPServerConfiguration configuration, Instrumenter instrumenter, HTTPListenerConfiguration listener,
                    Throughput throughput) {
    this.socket = socket;
    this.configuration = configuration;
    this.instrumenter = instrumenter;
    this.listener = listener;
    this.throughput = throughput;
    this.logger = configuration.getLoggerFactory().getLogger(HTTPWorker.class);
    this.requestBuffer = new byte[16 * 1024];
    this.responsePreambleStream = new FastByteArrayOutputStream(1024, 256); // We'll reuse this for Keep-Alive, so it belongs here rather than HTTPOutputStream
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

        var inputStream = new ThroughputInputStream(socket.getInputStream(), throughput);
        var bodyBytes = HTTPTools.parseRequestPreamble(inputStream, request, requestBuffer);
        var httpInputStream = new HTTPInputStream(configuration, request, inputStream, bodyBytes);
        request.setInputStream(httpInputStream);

        var throughputOutputStream = new ThroughputOutputStream(socket.getOutputStream(), throughput);
        response = new HTTPResponse();
        outputStream = new HTTPOutputStream(configuration, throughput, request, response, throughputOutputStream, responsePreambleStream);
        response.setOutputStream(outputStream);

        // Handle the "expect" response
        String expect = request.getHeader(Headers.Expect);
        if (expect != null && expect.equalsIgnoreCase(Status.ContinueRequest)) {
          // If the "expect" wasn't accepted, close the socket and exit
          if (!expectContinue(request)) {
            close(Result.Success);
            return;
          }
        }

        var connection = request.getHeader(Headers.Connection);
        if (connection == null || !connection.equalsIgnoreCase(Connections.Close)) {
          response.setHeader(Headers.Connection, Connections.KeepAlive);
          keepAlive = true;
        } else {
          response.setHeader(Headers.Connection, Connections.Close);
          keepAlive = false;
        }

        var handler = configuration.getHandler();
        handler.handle(request, response);
        response.close();
        outputStream = null;

        if (!response.isKeepAlive()) {
          logger.debug("Closing because no Keep-Alive.");
          close(Result.Success);
          break;
        }

        // Purge the extra bytes in case the handler didn't read everything
        httpInputStream.purge();
      }
    } catch (SocketTimeoutException | ConnectionClosedException e) {
      // This might be a read timeout or a Keep-Alive timeout. The failure state is based on that flag
      close(keepAlive ? Result.Success : Result.Failure);

      if (keepAlive) {
        logger.debug("Closing because the Keep-Alive expired.", e);
      }
    } catch (ParseException pe) {
      if (instrumenter != null) {
        instrumenter.badRequest();
      }

      logger.debug("Closing because of a bad request.");
      close(Result.Failure);
    } catch (SocketException e) {
      // This should only happen when the server is shutdown and this thread is waiting to read or write. In that case, this will throw a
      // SocketException and the thread will be interrupted. Since the server is being shutdown, we should let the client know.
      if (Thread.currentThread().isInterrupted()) {
        logger.debug("Closing because server was shutdown.");
        close(Result.Success);
      }
    } catch (IOException io) {
      logger.debug("An IO exception was thrown during processing. These are pretty common.", io);
      close(Result.Failure);
    } catch (Throwable t) {
      // Log the error and signal a failure
      logger.error("HTTP worker threw an exception while processing a request.", t);
      close(Result.Failure);
    }
  }

  public WorkerState state() {
    return outputStream != null && outputStream.isCommitted() ? WorkerState.Write : WorkerState.Read;
  }

  private void close(Result result) {
    // If the conditions are perfect, we can still write back a 500
    if (result == Result.Failure && outputStream != null && !outputStream.isCommitted() && response != null) {
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
      socket.close();
    } catch (IOException e) {
      logger.debug("Could not close the connection because the socket threw an exception.", e);
    }
  }

  private boolean expectContinue(HTTPRequest request) throws IOException {
    HTTPResponse expectResponse = new HTTPResponse();
    ExpectValidator validator = configuration.getExpectValidator();
    if (validator != null) {
      validator.validate(request, expectResponse);
    }

    // Write directly to the socket because the HTTPOutputStream does a lot of extra work that we don't want
    OutputStream out = socket.getOutputStream();
    HTTPTools.writeResponsePreamble(expectResponse, out);
    out.flush();

    return expectResponse.getStatus() == 100;
  }

  private enum Result {
    Failure,
    Success
  }
}
