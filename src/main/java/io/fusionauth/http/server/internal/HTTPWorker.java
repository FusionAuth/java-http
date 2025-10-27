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
import java.nio.file.Files;

import io.fusionauth.http.HTTPProcessingException;
import io.fusionauth.http.HTTPValues;
import io.fusionauth.http.HTTPValues.Connections;
import io.fusionauth.http.HTTPValues.ContentTypes;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.HTTPValues.Protocols;
import io.fusionauth.http.ParseException;
import io.fusionauth.http.io.MultipartConfiguration;
import io.fusionauth.http.io.PushbackInputStream;
import io.fusionauth.http.log.Logger;
import io.fusionauth.http.server.ExceptionHandlerContext;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPResponse;
import io.fusionauth.http.server.HTTPServerConfiguration;
import io.fusionauth.http.server.Instrumenter;
import io.fusionauth.http.server.io.ConnectionClosedException;
import io.fusionauth.http.server.io.HTTPInputStream;
import io.fusionauth.http.server.io.HTTPOutputStream;
import io.fusionauth.http.server.io.Throughput;
import io.fusionauth.http.server.io.ThroughputInputStream;
import io.fusionauth.http.server.io.ThroughputOutputStream;
import io.fusionauth.http.server.io.TooManyBytesToDrainException;
import io.fusionauth.http.util.HTTPTools;

/**
 * An HTTP worker that is a delegate Runnable to an {@link HTTPHandler}.
 *
 * @author Brian Pontarelli
 */
public class HTTPWorker implements Runnable {
  private final HTTPBuffers buffers;

  private final HTTPServerConfiguration configuration;

  private final PushbackInputStream inputStream;

  private final Instrumenter instrumenter;

  private final HTTPListenerConfiguration listener;

  private final Logger logger;

  private final Socket socket;

  private final long startInstant;

  private final Throughput throughput;

  private long handledRequests;

  private volatile State state;

  public HTTPWorker(Socket socket, HTTPServerConfiguration configuration, Instrumenter instrumenter, HTTPListenerConfiguration listener,
                    Throughput throughput) throws IOException {
    this.socket = socket;
    this.configuration = configuration;
    this.instrumenter = instrumenter;
    this.listener = listener;
    this.throughput = throughput;
    this.buffers = new HTTPBuffers(configuration);
    this.logger = configuration.getLoggerFactory().getLogger(HTTPWorker.class);
    this.inputStream = new PushbackInputStream(new ThroughputInputStream(socket.getInputStream(), throughput), instrumenter);
    this.state = State.Read;
    this.startInstant = System.currentTimeMillis();
    logger.trace("[{}] Starting HTTP worker.", Thread.currentThread().threadId());
  }

  public long getHandledRequests() {
    return handledRequests;
  }

  public Socket getSocket() {
    return socket;
  }

  public long getStartInstant() {
    return startInstant;
  }

  @Override
  public void run() {
    HTTPInputStream httpInputStream;
    HTTPRequest request = null;
    HTTPResponse response = null;

    try {
      if (instrumenter != null) {
        instrumenter.workerStarted();
      }

      while (true) {
        logger.trace("[{}] Running HTTP worker. Block while we wait to read the preamble", Thread.currentThread().threadId());
        request = new HTTPRequest(configuration.getContextPath(), listener.getCertificate() != null ? "https" : "http", listener.getPort(), socket.getInetAddress().getHostAddress());

        // Create a deep copy of the MultipartConfiguration so that the request may optionally modify the configuration on a per-request basis.
        request.getMultiPartStreamProcessor().setMultipartConfiguration(new MultipartConfiguration(configuration.getMultipartConfiguration()));

        // Set up the output stream so that if we fail we have the opportunity to write a response that contains a status code.
        var throughputOutputStream = new ThroughputOutputStream(socket.getOutputStream(), throughput);
        response = new HTTPResponse();

        HTTPOutputStream outputStream = new HTTPOutputStream(configuration, request.getAcceptEncodings(), response, throughputOutputStream, buffers, () -> state = State.Write);
        response.setOutputStream(outputStream);

        // Not this line of code will block
        // - When a client is using Keep-Alive - we will loop and block here while we wait for the client to send us bytes.
        byte[] requestBuffer = buffers.requestBuffer();
        HTTPTools.parseRequestPreamble(inputStream, configuration.getMaxRequestHeaderSize(), request, requestBuffer, () -> state = State.Read);
        if (logger.isTraceEnabled()) {
          int availableBufferedBytes = inputStream.getAvailableBufferedBytesRemaining();
          if (availableBufferedBytes != 0) {
            logger.trace("[{}] Preamble parser had [{}] left over bytes. These will be used in the HTTPInputStream.", availableBufferedBytes);
          }
        }

        // Once we have performed an initial read, we can count this as a handled request.
        handledRequests++;
        if (instrumenter != null) {
          instrumenter.acceptedRequest();
        }

        // Configure maximum content length
        int maximumContentLength = getMaximumContentLength(request);
        httpInputStream = new HTTPInputStream(configuration, request, inputStream, maximumContentLength);
        request.setInputStream(httpInputStream);

        // Set the Connection response header as soon as possible
        // - This needs to occur after we have parsed the pre-amble so we can read the request headers
        response.setHeader(Headers.Connection, request.isKeepAlive() ? Connections.KeepAlive : Connections.Close);

        // Ensure the preamble is valid
        Integer status = validatePreamble(request);
        if (status != null) {
          closeSocketOnError(response, status);
          return;
        }

        // Handle the Expect: 100-continue request header.
        String expect = request.getHeader(Headers.Expect);
        if (expect != null && expect.equalsIgnoreCase(HTTPValues.Status.ContinueRequest)) {
          state = State.Write;

          boolean doContinue = handleExpectContinue(request);
          if (!doContinue) {
            // Note that the expectContinue code already wrote to the OutputStream, all we need to do is close the socket.
            closeSocketOnly(CloseSocketReason.Expected);
            return;
          }

          // Otherwise, transition the state to Read
          state = State.Read;
        }

        // Transition to processing
        state = State.Process;
        logger.trace("[{}] Set state [{}]. Call the request handler.", Thread.currentThread().threadId(), state);
        try {
          configuration.getHandler().handle(request, response);
          logger.trace("[{}] Handler completed successfully", Thread.currentThread().threadId());
        } finally {
          // Clean up temporary files if instructed to do so.
          // - Note that this is using the request scoped configuration. It is possible for the request handler to disable
          //   deletion of temporary files on a request basis.
          var multiPartProcessor = request.getMultiPartStreamProcessor();
          if (multiPartProcessor.getMultiPartConfiguration().isDeleteTemporaryFiles()) {
            var fileManager = multiPartProcessor.getMultipartFileManager();
            for (var file : fileManager.getTemporaryFiles()) {
              try {
                logger.debug("Delete temporary file [{}]", file);
                Files.deleteIfExists(file);
              } catch (Exception e) {
                logger.error("Unable to delete temporary file. [" + file + "]", e);
              }
            }
          }
        }

        // Do this before we write the response preamble. The normal Keep-Alive check below will handle closing the socket.
        if (handledRequests >= configuration.getMaxRequestsPerConnection()) {
          logger.trace("[{}] Maximum requests per connection has been reached. Turn off Keep-Alive.", Thread.currentThread().threadId());
          response.setHeader(Headers.Connection, Connections.Close);
        }

        response.close();

        boolean keepSocketAlive = keepSocketAlive(request, response);
        // Close the socket.
        if (!keepSocketAlive) {
          logger.trace("[{}] Closing socket. No Keep-Alive.", Thread.currentThread().threadId());
          closeSocketOnly(CloseSocketReason.Expected);
          return;
        }

        // Transition to Keep-Alive state and reset the SO timeout
        state = State.KeepAlive;
        int soTimeout = (int) configuration.getKeepAliveTimeoutDuration().toMillis();
        logger.trace("[{}] Enter Keep-Alive state [{}] Reset socket timeout [{}].", Thread.currentThread().threadId(), state, soTimeout);
        socket.setSoTimeout(soTimeout);

        // Drain the InputStream so we can complete this request
        long startDrain = System.currentTimeMillis();
        int drained = httpInputStream.drain();
        if (drained > 0 && logger.isTraceEnabled()) {
          long drainDuration = System.currentTimeMillis() - startDrain;
          logger.trace("[{}] Drained [{}] bytes from the InputStream. Duration [{}] ms.", Thread.currentThread().threadId(), drained, drainDuration);
        }
      }
    } catch (ConnectionClosedException e) {
      // The client closed the socket. Trace log this since it is an expected case.
      logger.trace("[{}] Closing socket. Client closed the connection. Reason [{}].", Thread.currentThread().threadId(), e.getMessage());
      closeSocketOnly(CloseSocketReason.Expected);
    } catch (HTTPProcessingException e) {
      // Note that I am only tracing this, because this exception is mostly expected. Use closeSocketOnError so we can attempt to write a response.
      logger.trace("[{}] Closing socket with status [{}]. An unhandled [{}] exception was taken. Reason [{}].", Thread.currentThread().threadId(), e.getStatus(), e.getClass().getSimpleName(), e.getMessage());
      closeSocketOnError(response, e.getStatus());
    } catch (TooManyBytesToDrainException e) {
      // The request handler did not read the entire InputStream, we tried to drain it but there were more bytes remaining than the configured maximum.
      // - Close the connection, unless we drain it, the connection cannot be re-used.
      // - Treating this as an expected case because if we are in a keep-alive state, no big deal, the client can just re-open the request. If we
      //   are not ina keep alive state, the request does not need to be re-used anyway.
      logger.debug("[{}] Closing socket [{}]. Too many bytes remaining in the InputStream. Drained [{}] bytes. Configured maximum bytes [{}].", Thread.currentThread().threadId(), state, e.getDrainedBytes(), e.getMaximumDrainedBytes());
      closeSocketOnly(CloseSocketReason.Expected);
    } catch (SocketTimeoutException e) {
      // This might be a read timeout or a Keep-Alive timeout. The reason is based on the worker state.
      CloseSocketReason reason = state == State.KeepAlive ? CloseSocketReason.Expected : CloseSocketReason.Unexpected;
      String message = state == State.Read ? "Initial read timeout" : "Keep-Alive expired";
      if (reason == CloseSocketReason.Expected) {
        logger.trace("[{}] Closing socket [{}]. {}.", Thread.currentThread().threadId(), state, message);
      } else {
        logger.debug("[{}] Closing socket [{}]. {}.", Thread.currentThread().threadId(), state, message);
      }
      closeSocketOnly(reason);
    } catch (ParseException e) {
      logger.debug("[{}] Closing socket with status [{}]. Bad request, failed to parse request. Reason [{}] Parser state [{}]", Thread.currentThread().threadId(), Status.BadRequest, e.getMessage(), e.getState());
      closeSocketOnError(response, Status.BadRequest);
    } catch (SocketException e) {
      // When the HTTPServerThread shuts down, we will interrupt each client thread, so debug log it accordingly.
      // - This will cause the socket to throw a SocketException, so log it.
      if (Thread.currentThread().isInterrupted()) {
        logger.debug("[{}] Closing socket. Server is shutting down.", Thread.currentThread().threadId());
      } else {
        logger.debug("[{}] Closing socket. The socket was closed by a client, proxy or otherwise.", Thread.currentThread().threadId());
      }
      closeSocketOnly(CloseSocketReason.Expected);
    } catch (IOException e) {
      logger.debug(String.format("[%s] Closing socket with status [%d]. An IO exception was thrown during processing. These are pretty common.", Thread.currentThread().threadId(), Status.InternalServerError), e);
      closeSocketOnError(response, Status.InternalServerError);
    } catch (Throwable e) {
      ExceptionHandlerContext context = new ExceptionHandlerContext(logger, request, Status.InternalServerError, e);
      try {
        configuration.getUnexpectedExceptionHandler().handle(context);
      } catch (Throwable ignore) {
      }

      // Signal an error
      closeSocketOnError(response, context.getStatusCode());
    } finally {
      if (instrumenter != null) {
        instrumenter.workerStopped();
      }
    }
  }

  public State state() {
    return state;
  }

  private void closeSocketOnError(HTTPResponse response, int status) {
    if (status >= 400 && status <= 499 && instrumenter != null) {
      instrumenter.badRequest();
    }

    try {
      // If the conditions are perfect, we can still write back a status code.
      // - If the response is committed, someone already wrote to the client so we can't affect the response status, headers, etc.
      if (response != null && !response.isCommitted()) {
        // Note that we are intentionally not purging the InputStream prior to writing the response. In the most ideal sense, purging
        // the input stream would allow the client to read this response more easily. However, most of the error conditions that would cause
        // this path are malformed requests or potentially malicious payloads.
        // It is still possible to read a response, the client simply needs to handle the socket reset, and reconnect to read the response.
        // - Perhaps this is not common, but it is possible.

        // Note that reset() clears the Connection response header.
        response.reset();
        response.setHeader(Headers.Connection, Connections.Close);
        response.setStatus(status);
        response.setContentLength(0L);
        response.close();
      }
    } catch (IOException e) {
      logger.debug(String.format("[%s] Could not close the HTTP response.", Thread.currentThread().threadId()), e);
    } finally {
      // It is plausible that calling response.close() could throw an exception. We must ensure we close the socket.
      closeSocketOnly(CloseSocketReason.Unexpected);
    }
  }

  private void closeSocketOnly(CloseSocketReason reason) {
    if (reason == CloseSocketReason.Unexpected && instrumenter != null) {
      instrumenter.connectionClosed();
    }

    try {
      socket.close();
    } catch (IOException e) {
      logger.debug(String.format("[%s] Could not close the socket.", Thread.currentThread().threadId()), e);
    }
  }

  private int getMaximumContentLength(HTTPRequest request) {
    var maximumContentLength = -1;
    if (ContentTypes.Form.equalsIgnoreCase(request.getContentType())) {
      maximumContentLength = configuration.getMaxFormDataSize();
    }

    if (maximumContentLength == -1) {
      maximumContentLength = configuration.getMaxRequestBodySize();
    }

    return maximumContentLength;
  }

  private boolean handleExpectContinue(HTTPRequest request) throws IOException {
    var expectResponse = new HTTPResponse();
    configuration.getExpectValidator().validate(request, expectResponse);

    // Write directly to the socket because the HTTPOutputStream.close() does a lot of extra work that we don't want
    OutputStream out = socket.getOutputStream();
    HTTPTools.writeResponsePreamble(expectResponse, out);
    out.flush();

    return expectResponse.getStatus() == 100;
  }

  /**
   * Determine if we should keep the socket alive.
   * <p>
   * Note that the HTTP request handler may have modified the 'Connection' response header, so verify the current state using the HTTP
   * response header.
   * <p>
   * When the client has requested HTTP/1.0, the default behavior will be to close the connection. When the client has requested HTTP/1.1,
   * the default behavior will be to keep the connection alive.
   * <p>
   * The reason this is an important distinction is that an HTTP/1.0 client, unless it is explicitly asking to keep the connection open,
   * will not reuse the connection. So if we do not close it, we will have to wait until we reach the socket timeout before closing the
   * socket. In the meantime the HTTP/1.0 client will keep opening new connections. This leads to very poor performance for these clients,
   * and there are still HTTP/1.0 benchmark tools around.
   *
   * @param request  the http request
   * @param response the http response
   * @return true if the socket should be kept alive.
   */
  private boolean keepSocketAlive(HTTPRequest request, HTTPResponse response) {
    var connectionHeader = response.getHeader(Headers.Connection);
    return request.getProtocol().equals(Protocols.HTTTP1_1)
        ? !Connections.Close.equalsIgnoreCase(connectionHeader)
        : Connections.KeepAlive.equalsIgnoreCase(connectionHeader);
  }

  private Integer validatePreamble(HTTPRequest request) {
    var debugEnabled = logger.isDebugEnabled();

    // Validate protocol. Protocol version is required.
    String protocol = request.getProtocol();
    if (protocol == null) {
      logger.debug("Invalid request. Missing HTTP Protocol");
      return Status.BadRequest;
    }

    // Only HTTP/ protocol is supported.
    if (!protocol.startsWith("HTTP/")) {
      if (debugEnabled) {
        logger.debug("Invalid request. Invalid protocol [{}]. Supported versions [{}].", protocol, Protocols.HTTTP1_1);
      }

      return Status.BadRequest;
    }

    // Minor versions less than 1 are allowed per spec. For example, HTTP/1.0 should be allowed and is considered to be compatible enough.
    if (!protocol.equals("HTTP/1.0") && !protocol.equals("HTTP/1.1")) {
      if (debugEnabled) {
        logger.debug("Invalid request. Unsupported HTTP version [{}]. Supported versions [{}].", protocol, Protocols.HTTTP1_1);
      }

      return Status.HTTPVersionNotSupported;
    }

    // Host header is required
    var host = request.getRawHost();
    if (host == null) {
      logger.debug("Invalid request. Missing Host header.");
      return Status.BadRequest;
    }

    var hostHeaders = request.getHeaders(Headers.Host);
    if (hostHeaders.size() != 1) {
      if (debugEnabled) {
        logger.debug("Invalid request. Duplicate Host headers. [{}]", String.join(", ", hostHeaders));
      }

      return Status.BadRequest;
    }

    // If Transfer-Encoding is present, ignore Content-Length
    // - In theory it is an error to send both the Transfer-Encoding and the Content-Length headers.
    //   However, as long as we ignore Content-Length we should be ok. Earlier specs indicate Transfer-Encoding should take precedence,
    //   later specs imply it is an error. Seems ok to allow it and just ignore it.
    if (request.getHeader(Headers.TransferEncoding) == null) {
      var contentLength = request.getContentLength();
      var requestedContentLengthHeaders = request.getHeaders(Headers.ContentLength);
      if (requestedContentLengthHeaders != null) {
        if (requestedContentLengthHeaders.size() != 1) {
          if (debugEnabled) {
            logger.debug("Invalid request. Duplicate Content-Length headers. [{}]", String.join(", ", requestedContentLengthHeaders));
          }

          // If we cannot trust the Content-Length it is unlikely we can correctly drain the InputStream in order for the client to read our response.
          return Status.BadRequest;
        }

        if (contentLength == null || contentLength < 0) {
          if (debugEnabled) {
            logger.debug("Invalid request. The Content-Length must be >= 0 and <= 9,223,372,036,854,775,807. [{}]", requestedContentLengthHeaders.getFirst());
          }

          // If we cannot trust the Content-Length it is unlikely we can correctly drain the InputStream in order for the client to read our response.
          return Status.BadRequest;
        }
      }
    } else {
      // To simplify downstream code and remove ambiguity. If we have a Transfer-Encoding request header, remove Content-Length.
      request.setContentLength(null);
      request.removeHeader(Headers.ContentLength);
    }

    return null;
  }

  public enum State {
    Read,
    Process,
    Write,
    KeepAlive
  }

  private enum CloseSocketReason {
    Expected,
    Unexpected
  }

  private static class Status {
    public static final int BadRequest = 400;

    public static final int HTTPVersionNotSupported = 505;

    public static final int InternalServerError = 500;
  }
}
