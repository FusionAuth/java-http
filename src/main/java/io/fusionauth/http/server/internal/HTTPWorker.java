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
import java.nio.ByteBuffer;

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

  private final long startInstant;

  private final Throughput throughput;

  // TODO : Daniel : Review : Services such as Apache Tomcat have a maximum number of requests per keep-alive to protect against DOS attacks.
  //        Needs more investigation, but we could cap the duration of worker by time or number of requests.
  private long handledRequests;

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
    HTTPInputStream httpInputStream = null;
    HTTPResponse response = null;

    try {
      if (instrumenter != null) {
        instrumenter.threadStarted();
      }

      while (true) {
        logger.trace("[{}] Running HTTP worker. Block while we wait to read the preamble", Thread.currentThread().threadId());
        var request = new HTTPRequest(configuration.getContextPath(), configuration.getMultipartBufferSize(),
            listener.getCertificate() != null ? "https" : "http", listener.getPort(), socket.getInetAddress().getHostAddress());

        // Set up the output stream so that if we fail we have the opportunity to write a response that contains a status code.
        var throughputOutputStream = new ThroughputOutputStream(socket.getOutputStream(), throughput);
        response = new HTTPResponse();

        HTTPOutputStream outputStream = new HTTPOutputStream(configuration, request.getAcceptEncodings(), response, throughputOutputStream, buffers, () -> state = State.Write);
        response.setOutputStream(outputStream);

        var inputStream = new ThroughputInputStream(socket.getInputStream(), throughput);

        ByteBuffer bodyBytes = null;
//        byte[] bodyBytes = null;
//        try {
        // TODO : Daniel : Review since this is single threaded - can the bodyBytes just be a pointer into buffers.requestBuffer?
        //        Or is it critical that we are done with the requestBuffer after this method returns to be used for another request?
        //        If the default requestBuffer is 8k, then each virtual thread that is spun up to handle a request requires at least 8k?
        //        Example: 200 virtual threads, 1,638,400 bytes in request buffer.
        // System.out.println(" > Wait for the preamble.... (blocking)");
        // Not this line of code will block
        // - When a client is using Keep-Alive - we will loop and block here while we wait for the client to send us bytes.
        byte[] requestBuffer = buffers.requestBuffer();
//        bodyBytes = HTTPTools.parseRequestPreamble(inputStream, request, requestBuffer, instrumenter, () -> state = State.Read);
        bodyBytes = HTTPTools.parseRequestPreamble(inputStream, request, requestBuffer, instrumenter, () -> state = State.Read);

        // Once we have performed an initial read, we can count this as a handled request.
        handledRequests++;
        if (instrumenter != null) {
          instrumenter.acceptedRequests();
        }

//        } finally {
        // System.out.println(" > Read the preamble, build an HTTP InputStream....");
        // parseRequestPreamble may throw a ParseException, but we want to be sure we have set the HTTPInputStream in all cases.
        httpInputStream = new HTTPInputStream(configuration, request, inputStream, bodyBytes);
        request.setInputStream(httpInputStream);
        // System.out.println(" > set the InputStream on the HTTP request....");
//        }

        // System.out.println(" > Handle keep-alive headers");

        // Set the Connection response header as soon as possible
        // - This needs to occur after we have parsed the pre-amble so we can read the request headers
        response.setHeader(Headers.Connection, request.isKeepAlive() ? Connections.KeepAlive : Connections.Close);

        // System.out.println(" > validate the preamble");

        // Ensure the preamble is valid
        // TODO : Daniel : Review : Write a test that sends in a large payload that will fail this check and see what happens.
        //                 Expect this to hang? We have not yet read the input stream.
        //        Daniel : Review : Write a test that uses transfer encoding with a large payload, try and validate the payload read that is too large and
        //                          see if we can fail nicely to the client.
        //                 .
        //        Daniel : Review : If Content-Length !=0 OR Transfer-Encoding is NON null - then only option may be to close socket and not write back status?
        //                 .
        //                 How does Apache validate max payload size.. once they read n bytes, how do they bail?
        //                 Do you have to read the entire input stream before we write to the output stream? Which means you either need to read to the end
        //                 of the Content-Length, or parse the body per Transfer-Encoding and wait until you see the end of the request.
        //
        Integer status = validatePreamble(request);
        if (status != null) {
          // System.out.println(" > validatePreamble failed. Return status [" + status + "]");
          closeSocket(CloseOption.TryToWriteFinalResponse, CloseOptionReason.Failure, httpInputStream, response, status);
          return;
        }

        // System.out.println(" > handle Expect headers....");

        // Handle the "expect" response
        String expect = request.getHeader(Headers.Expect);
        if (expect != null && expect.equalsIgnoreCase(HTTPValues.Status.ContinueRequest)) {
          state = State.Write;

          // If the "expect" wasn't accepted, close the socket and exit
          if (!expectContinue(request)) {
            // TODO : Daniel : Review : Call closeSocket instead.
            System.out.println("Expect continue - no, close socket.");
            closeSocketOnly();
//            closeSocket(CloseOption.CloseSocketOnly, CloseOptionReason.Ok, request, response, -1);
            return;
          }
          // TODO : Daniel : Review : If we flush the status code in the expectContinue code.. does that mean the client starts writing a new request right away?
          //        - And we just go back to the while(true) code?

          // Otherwise, transition the state to Read
          state = State.Read;
        }

        var handler = configuration.getHandler();
        // Transition to processing
        state = State.Process;
        logger.trace("[{}] Set state [{}]. Call the request handler.", Thread.currentThread().threadId(), state);
        handler.handle(request, response);
        response.close();
        logger.trace("[{}] Handler completed successfully", Thread.currentThread().threadId());

        // Purge the extra bytes in case the handler didn't read everything
        long purged = httpInputStream.purge();
        if (purged > 0 && logger.isTraceEnabled()) {
          logger.trace("[{}] Purged [{}] bytes.", purged, Thread.currentThread().threadId());
        }

        // The HTTP Request Handler may have modified the Connection header, so verify using the HTTP Response here.
        // TODO : Daniel : Review : Write a test where the request handler sets Connection: close to ensure this is honored.
        // - Note that we may nave not purged the InputStream in this case.
        var connectionHeader = response.getHeader(Headers.Connection);
        boolean keepSocketAlive = request.getProtocol().equals(Protocols.HTTTP1_1)
            ? !Connections.Close.equalsIgnoreCase(connectionHeader)
            : Connections.KeepAlive.equalsIgnoreCase(connectionHeader);

        if (!keepSocketAlive) {
          logger.trace("[{}] Closing socket. No Keep-Alive.", Thread.currentThread().threadId());
          closeSocketOnly();
          break;
        }

        // Transition to Keep-Alive state and reset the SO timeout
        state = State.KeepAlive;
        int soTimeout = (int) configuration.getKeepAliveTimeoutDuration().toMillis();
        logger.trace("[{}] Enter Keep-Alive state [{}] Reset socket timeout [{}].", Thread.currentThread().threadId(), state, soTimeout);
        socket.setSoTimeout(soTimeout);
      }
    } catch (SocketTimeoutException | ConnectionClosedException e) {
      boolean socketTimeoutException = e instanceof SocketTimeoutException;
      if (socketTimeoutException) {
        logger.debug(String.format("[%s] Closing socket. Keep-Alive expired.", Thread.currentThread().threadId()), e);
      } else {
        logger.debug("[{}] Closing socket. Client closed the connection. Reason [{}].", Thread.currentThread().threadId(), e.getMessage());
      }

      // This might be a read timeout or a Keep-Alive timeout. The failure state is based on that flag
      closeSocket(CloseOption.CloseSocketOnly, CloseOptionReason.Failure, httpInputStream, response, -1);
    } catch (ParseException pe) {
      if (instrumenter != null) {
        instrumenter.badRequest();
      }

      logger.debug("[{}] Closing socket with status [{}]. Bad request, failed to parse request. Reason [{}] Parser state [{}]", Thread.currentThread().threadId(), Status.BadRequest, pe.getMessage(), pe.getState());
//      closeSocket(CloseOption.CloseSocketOnly, CloseOptionReason.Failure, httpInputStream, response, Status.BadRequest);
      // TODO : Daniel : This seems to cause two response headers to be written? Or we are getting a 200 written prior to this and then we end up
      //  a      with 200 and 400?
      closeSocket(CloseOption.TryToWriteFinalResponse, CloseOptionReason.Failure, httpInputStream, response, Status.BadRequest);
    } catch (SocketException e) {
      // This should only happen when the server is shutdown and this thread is waiting to read or write. In that case, this will throw a
      // SocketException and the thread will be interrupted. Since the server is being shutdown, we should let the client know.
      if (Thread.currentThread().isInterrupted()) {
        // Close socket only. We do not want to potentially delay the shutdown at all.
        logger.debug("[{}] Closing socket. Server is shutting down.", Thread.currentThread().threadId());
//        System.out.println("SocketException: shutting down the server. Close the socket.");
        closeSocket(CloseOption.CloseSocketOnly, CloseOptionReason.Failure, httpInputStream, response, -1);
      }
    } catch (IOException io) {
      logger.debug(String.format("[%s] Closing socket with status [%d]. An IO exception was thrown during processing. These are pretty common.", Thread.currentThread().threadId(), Status.InternalServerError), io);
      System.out.println("IOException: close the socket.");
      closeSocket(CloseOption.TryToWriteFinalResponse, CloseOptionReason.Failure, httpInputStream, response, Status.InternalServerError);
    } catch (Throwable t) {
      // Log the error and signal a failure
      var status = Status.InternalServerError;
      logger.error(String.format("[%s] Closing socket with status [%d]. An HTTP worker threw an exception while processing a request.", Thread.currentThread().threadId(), status), t);
      System.out.println("Throwable: close the socket.\n" + t.getMessage());
      closeSocket(CloseOption.TryToWriteFinalResponse, CloseOptionReason.Failure, httpInputStream, response, status);
    } finally {
      // TODO : Daniel : Review : Is this true? If we are using a keep-alive and we have not caught an exception, we have not yet exited the thread.
      if (instrumenter != null) {
        instrumenter.threadExited();
      }
    }
  }

  public State state() {
    return state;
  }

  private void closeSocket(CloseOption closeOption, CloseOptionReason reason, HTTPInputStream inputStream, HTTPResponse response,
                           int status) {
    if (reason == CloseOptionReason.Failure && instrumenter != null) {
      instrumenter.connectionClosed();
    }

    try {
      // If the conditions are perfect, we can still write back a status code.
      // - If the response is committed, someone already wrote to the client so we can't affect the response status, headers, etc.
      if (closeOption == CloseOption.TryToWriteFinalResponse && response != null && !response.isCommitted()) {
        // Ensure we have emptied the input stream before trying to write back a response.
        // TODO : Daniel : Note that it may not actually be required to drain this... we can still write a response. Now... the client may not
        //        be able to read it. Maybe that is ok. Not having to read the entire payload may be nice... and could avoid some potential DOS attacks.
        //        In testing, I am able to read the HTTP response even w/out draining it.. it just requires the you reconnect the socket because upon
        //        reading you will catch a SocketException due to the reset.
        boolean drainIt = false;
        if (inputStream != null) {
          try {
            if (drainIt) {
              // TODO : Daniel : Review : this is essentially skip. Can we use that instead?
              //        - It is also very similar to HTTPInputStream.purge() but it looks like that will not
              //          do anything with a chunked InputStream.
              //        - Seems like purge should also handle Chunked InputStream, or is it not intended to do that?
              //noinspection ResultOfMethodCallIgnored

              inputStream.purge();
//              inputStream.purge();
//              socket.getInputStream().close(); // THis causes a SocketClosed Exception when closing the response.
            }
          } catch (IOException e) {
            // TODO : Daniel : Review : Can I write a test to force this condition?
            logger.debug(String.format("[%s] Could not drain the HTTP InputStream.", Thread.currentThread().threadId()), e);
          }
        }

        // Note that reset() clears the Connection response header.
        try {
          response.reset();
          response.setHeader(Headers.Connection, Connections.Close);
          response.setStatus(status);
          response.setContentLength(0L);
          response.close();
        } catch (Exception e) {
          System.out.println("\n\nFailed to close the response!!!");
          throw e;
        }
      }

    } catch (IOException e) {
      logger.debug(String.format("[%s] Could not close the HTTP response.", Thread.currentThread().threadId()), e);
    } finally {
      // It is plausible that calling response.close() could throw an exception. We must ensure we close the socket.
      // TODO : Daniel : Review : Can I write a test to force this condition?
      closeSocketOnly();
    }
  }

  private void closeSocketOnly() {
    try {
      socket.close();
    } catch (IOException e) {
      logger.debug(String.format("[%s] Could not close the socket.", Thread.currentThread().threadId()), e);
    }
  }

  private boolean expectContinue(HTTPRequest request) throws IOException {
    var expectResponse = new HTTPResponse();

    // Invoke the optional validator. If the validator is not provided, default to 100 Continue.
    var validator = configuration.getExpectValidator();
    if (validator != null) {
      validator.validate(request, expectResponse);
    } else {
      expectResponse.setStatus(100);
      expectResponse.setStatusMessage("Continue");
    }

    // If we are not continuing, do not re-use this connection.
    boolean expectContinue = expectResponse.getStatus() == 100;
    if (!expectContinue && !expectResponse.containsHeader(Headers.Connection)) {
      expectResponse.setHeader(Headers.Connection, Connections.Close);
    }

    // Write directly to the socket because the HTTPOutputStream does a lot of extra work that we don't want
    OutputStream out = socket.getOutputStream();
    HTTPTools.writeResponsePreamble(expectResponse, out);
    out.flush();

    return expectContinue;
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
    //   However, as long as we ignore Content-Length we should be ok.
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
    }

    return null;
  }

  public enum State {
    Read,
    Process,
    Write,
    KeepAlive
  }

  // TODO : Daniel : Review : Do we need this or can we just call closeSocketOnly?
  private enum CloseOption {
    CloseSocketOnly,
    TryToWriteFinalResponse
  }

  // TODO : Daniel : Review : Do I need this?
  private enum CloseOptionReason {
    Ok,
    Failure
  }

  private static class Status {
    public static final int BadRequest = 400;

    public static final int HTTPVersionNotSupported = 505;

    public static final int InternalServerError = 500;
  }
}
