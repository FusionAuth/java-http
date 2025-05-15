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

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import io.fusionauth.http.ConnectionClosedException;
import io.fusionauth.http.HTTPValues;
import io.fusionauth.http.HTTPValues.Connections;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.HTTPValues.Protocols;
import io.fusionauth.http.ParseException;
import io.fusionauth.http.log.Logger;
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
  private final HTTPBuffers buffers;

  private final HTTPServerConfiguration configuration;

  private final Instrumenter instrumenter;

  private final HTTPListenerConfiguration listener;

  private final Logger logger;

  private final Socket socket;

  private final Throughput throughput;

  private volatile State state;

  public HTTPWorker(Socket socket, HTTPServerConfiguration configuration, Instrumenter instrumenter, HTTPListenerConfiguration listener,
                    Throughput throughput) {
    this.socket = socket;
    this.configuration = configuration;
    this.instrumenter = instrumenter;
    this.listener = listener;
    this.throughput = throughput;
    this.buffers = new HTTPBuffers(configuration);
    this.logger = configuration.getLoggerFactory().getLogger(HTTPWorker.class);
    this.state = State.Read;
    logger.debug("[{}] Starting HTTP worker virtual thread", Thread.currentThread().threadId());
  }

  public Socket getSocket() {
    return socket;
  }

  @Override
  public void run() {
    HTTPResponse response = null;
    boolean keepAlive = false;
    try {
      if (instrumenter != null) {
        instrumenter.threadStarted();
      }

      while (true) {
        logger.trace("[{}] Running HTTP worker and preparing to read preamble", Thread.currentThread().threadId());
        var request = new HTTPRequest(configuration.getContextPath(), configuration.getMultipartBufferSize(),
            listener.getCertificate() != null ? "https" : "http", listener.getPort(), socket.getInetAddress().getHostAddress());

        // Set up the output stream so that if we fail we have the opportunity to write a response that contains a status code.
        var throughputOutputStream = new ThroughputOutputStream(socket.getOutputStream(), throughput);
        response = new HTTPResponse();

        HTTPOutputStream outputStream = new HTTPOutputStream(configuration, request.getAcceptEncodings(), response, throughputOutputStream, buffers, () -> state = State.Write);
        response.setOutputStream(outputStream);

        var inputStream = new ThroughputInputStream(socket.getInputStream(), throughput);
        var bodyBytes = HTTPTools.parseRequestPreamble(inputStream, request, buffers.requestBuffer(), instrumenter, () -> state = State.Read);
        var httpInputStream = new HTTPInputStream(configuration, request, inputStream, bodyBytes);
        request.setInputStream(httpInputStream);

        // Set the Connection response header as soon as possible
        if (request.isKeepAlive()) {
          response.setHeader(Headers.Connection, Connections.KeepAlive);
          keepAlive = true;
        } else {
          response.setHeader(Headers.Connection, Connections.Close);
          keepAlive = false;
        }

        // Ensure the preamble is valid
        Integer status = validatePreamble(request);
        if (status != null) {
          close(status, response);
          return;
        }

        // Handle the "expect" response
        String expect = request.getHeader(Headers.Expect);
        if (expect != null && expect.equalsIgnoreCase(HTTPValues.Status.ContinueRequest)) {
          state = State.Write;

          // If the "expect" wasn't accepted, close the socket and exit
          if (!expectContinue(request)) {
            closeSocketOnly();
            return;
          }

          // Otherwise, transition the state to Read
          state = State.Read;
        }

        var handler = configuration.getHandler();
        state = State.Process; // Transition to processing
        logger.debug("[{}] Set state [{}]. Call the request handler.", Thread.currentThread().threadId(), state);
        handler.handle(request, response);
        response.close();
        logger.trace("[{}] Handler completed successfully", Thread.currentThread().threadId());

        // Since the Handler can change the Keep-Alive state, we use the response here
        if (!keepAlive) {
          logger.debug("[{}] Close socket. No Keep-Alive", Thread.currentThread().threadId());
          closeSocketOnly();
          break;
        }

        // Transition to Keep-Alive state and reset the SO timeout
        state = State.KeepAlive;
        logger.debug("[{}] Set state [{}]", Thread.currentThread().threadId(), state);
        socket.setSoTimeout((int) configuration.getKeepAliveTimeoutDuration().toMillis());

        // Purge the extra bytes in case the handler didn't read everything
        int purged = httpInputStream.purge();
        if (purged > 0) {
          logger.debug("[{}] Purged [{}] bytes.", purged, Thread.currentThread().threadId());
        }

      }
    } catch (SocketTimeoutException | ConnectionClosedException e) {
      // This might be a read timeout or a Keep-Alive timeout. The failure state is based on that flag
      var status = keepAlive ? Status.OK : Status.InternalServerError; // 200 or 500
      close(status, response);

      if (keepAlive) {
        logger.debug(String.format("[%s] Closing socket with status [%d]. Keep-Alive expired.", Thread.currentThread().threadId(), status), e);
      } else {
        logger.debug("[{}] Closing socket with status [{}]. Socket timed out.", Thread.currentThread().threadId(), status);
      }
    } catch (ParseException pe) {
      if (instrumenter != null) {
        instrumenter.badRequest();
      }

      int status = 400;
      logger.debug("[{}] Closing socket with status [{}]. Bad request, failed to parse request. Reason [{}] Parser state [{}]", Thread.currentThread().threadId(), status, pe.getMessage(), pe.getState());
      close(status, response);
    } catch (SocketException e) {
      // This should only happen when the server is shutdown and this thread is waiting to read or write. In that case, this will throw a
      // SocketException and the thread will be interrupted. Since the server is being shutdown, we should let the client know.
      if (Thread.currentThread().isInterrupted()) {
        var status = Status.OK;
        logger.debug("[{}] Closing socket with status [{}]. Server is shutting down.", Thread.currentThread().threadId(), status);
        close(status, response);
      }
    } catch (IOException io) {
      var status = Status.InternalServerError;
      logger.debug(String.format("[%s] Closing socket with status [%d]. An IO exception was thrown during processing. These are pretty common.", Thread.currentThread().threadId(), status), io);
      close(status, response);
    } catch (Throwable t) {
      // Log the error and signal a failure
      var status = Status.InternalServerError;
      logger.error(String.format("[%s] Closing socket with status [%d]. An HTTP worker threw an exception while processing a request.", Thread.currentThread().threadId(), status), t);
      close(status, response);
    } finally {
      if (instrumenter != null) {
        instrumenter.threadExited();
      }
    }
  }

  public State state() {
    return state;
  }

  private void close(int status, HTTPResponse response) {
    boolean error = status > 299;
    if (error && instrumenter != null) {
      instrumenter.connectionClosed();
    }

    try {
      // If the conditions are perfect, we can still write back a 500
      if (error && response != null && !response.isCommitted()) {
        // Note that reset() clears the Connection response header.
        response.reset();
        response.setStatus(status);
        // TODO: Daniel : Should we always be sending back a Connection: close on an error such as this?
        //      - I think this is correct since we are calling socket.close() below?
        response.setHeader(Headers.Connection, Connections.Close);
        response.setContentLength(0L);
        response.close();
      }

      socket.close();
    } catch (IOException e) {
      logger.debug(String.format("[%s] Could not close the connection because the socket threw an exception.", Thread.currentThread().threadId()), e);
    }
  }

  private void closeSocketOnly() {
    try {
      socket.close();
    } catch (IOException e) {
      logger.debug(String.format("[%s] Could not close the connection because the socket threw an exception.", Thread.currentThread().threadId()), e);
    }
  }

  private boolean expectContinue(HTTPRequest request) throws IOException {
    var expectResponse = new HTTPResponse();
    // Default to 100, the validator is optional. The HTTP response will default to 200.
    expectResponse.setStatus(100);

    // Invoke the optional validator
    var validator = configuration.getExpectValidator();
    if (validator != null) {
      validator.validate(request, expectResponse);
    }

    // Ignore the status message if set by the validator. A status code of 100 should always have a message of Continue.
    if (expectResponse.getStatus() == 100) {
      expectResponse.setStatusMessage("Continue");
    }

    // Write directly to the socket because the HTTPOutputStream does a lot of extra work that we don't want
    OutputStream out = socket.getOutputStream();
    HTTPTools.writeResponsePreamble(expectResponse, out);
    out.flush();

    // A status code of 100 means go for it.
    return expectResponse.getStatus() == 100;
  }

  private Integer validatePreamble(HTTPRequest request) {
    // Validate protocol. Only HTTP/1.1 is supported
    String protocol = request.getProtocol();
    if (protocol == null) {
      if (logger.isTraceEnabled()) {
        logger.trace("Invalid request. Missing HTTP Protocol");
      }
      return Status.BadRequest;
    }

    if (!protocol.startsWith("HTTP/")) {
      if (logger.isTraceEnabled()) {
        logger.trace("Invalid request. Invalid protocol [{}]. Supported versions [{}].", protocol, Protocols.HTTTP1_1);
      }
      return Status.BadRequest;
    }

    if (!Protocols.HTTTP1_1.equals(protocol)) {
      if (logger.isTraceEnabled()) {
        logger.trace("Invalid request. Unsupported HTTP version [{}]. Supported versions [{}].", protocol, Protocols.HTTTP1_1);
      }
      return Status.HTTPVersionNotSupported;
    }

    // Host header is required
    var host = request.getRawHost();
    if (host == null) {
      logger.trace("Invalid request. Missing Host header.");
      return Status.BadRequest;
    }

    var hostHeaders = request.getHeaders(Headers.Host);
    if (hostHeaders.size() != 1) {
      if (logger.isTraceEnabled()) {
        logger.trace("Invalid request. Duplicate Host headers. [{}]", String.join(", ", hostHeaders));
      }
      return Status.BadRequest;
    }

    // Content-Length cannot be negative
    var contentLength = request.getContentLength();
    if (contentLength != null && contentLength < 0) {
      if (logger.isTraceEnabled()) {
        logger.trace("Invalid request. The Content-Length cannot be negative. [{}]", contentLength);
      }
      return Status.BadRequest;
    }

    return null;
  }

  public enum State {
    Read,
    Process,
    Write,
    KeepAlive
  }

  private static class Status {
    public static final int BadRequest = 400;

    public static final int HTTPVersionNotSupported = 505;

    public static final int InternalServerError = 500;

    public static final int OK = 200;
  }
}
