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
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import io.fusionauth.http.io.NonBlockingByteBufferOutputStream;
import io.fusionauth.http.log.Logger;
import io.fusionauth.http.util.ThreadPool;

/**
 * A worker that handles a single request/response from a client.
 *
 * @author Brian Pontarelli
 */
public class HTTP11Processor implements HTTPProcessor {
  final HTTPServerConfiguration configuration;

  final HTTPListenerConfiguration listener;

  final String ipAddress;

  final Logger logger;

  final Notifier notifier;

  final ByteBuffer preambleBuffer;

  final HTTPRequest request;

  final HTTPRequestProcessor requestProcessor;

  final HTTPResponse response;

  final HTTPResponseProcessor responseProcessor;

  final ThreadPool threadPool;

  private long lastUsed = System.currentTimeMillis();

  public HTTP11Processor(HTTPServerConfiguration configuration, HTTPListenerConfiguration listener, Notifier notifier,
                         ByteBuffer preambleBuffer, ThreadPool threadPool, String ipAddress) {
    this.configuration = configuration;
    this.listener = listener;
    this.logger = configuration.getLoggerFactory().getLogger(HTTP11Processor.class);
    this.notifier = notifier;
    this.preambleBuffer = preambleBuffer;
    this.threadPool = threadPool;
    this.ipAddress = ipAddress;

    this.request = new HTTPRequest(configuration.getContextPath(), configuration.getMultipartBufferSize(), "http", listener.getPort(), ipAddress);
    this.requestProcessor = new HTTPRequestProcessor(configuration, request);

    NonBlockingByteBufferOutputStream outputStream = new NonBlockingByteBufferOutputStream(notifier, configuration.getResponseBufferSize());
    this.response = new HTTPResponse(outputStream, request);
    this.responseProcessor = new HTTPResponseProcessor(configuration, request, response, outputStream);
  }

  /**
   * Since this is an HTTP implementation, this simply puts the connection into a read state.
   *
   * @param key           The selection key for the client.
   * @param clientChannel The socket connection with the client.
   * @throws IOException If the registration failed.
   */
  @Override
  public void accept(SelectionKey key, SocketChannel clientChannel) throws IOException {
    clientChannel.configureBlocking(false);
    clientChannel.register(key.selector(), SelectionKey.OP_READ, this);
  }

  @Override
  public void failure(Throwable t) {
    responseProcessor.failure();
    notifier.notifyNow();
  }

  public long lastUsed() {
    return lastUsed;
  }

  public void markUsed() {
    lastUsed = System.currentTimeMillis();
  }

  @Override
  public int read(SelectionKey key) throws IOException {
    markUsed();

    RequestState state = requestProcessor.state();
    ByteBuffer buffer;
    if (state == RequestState.Preamble) {
      logger.trace("(RP)");
      buffer = preambleBuffer;
    } else if (state == RequestState.Body) {
      logger.trace("(RB)");
      buffer = requestProcessor.bodyBuffer();
    } else {
      logger.trace("(RD1)");
      key.interestOps(SelectionKey.OP_WRITE);
      return 0;
    }

    SocketChannel client = (SocketChannel) key.channel();
    int read = client.read(buffer);
    if (read <= 0) {
      return 0;
    }

    logger.trace("Read [{}] bytes from client", read);

    if (state == RequestState.Preamble) {
      buffer.flip();
      state = requestProcessor.processPreambleBytes(buffer);

      // Reset the preamble buffer because it is shared
      buffer.clear();

      // If the next state is not preamble, that means we are done processing that and ready to handle the request in a separate thread
      if (state != RequestState.Preamble && state != RequestState.Expect) {
        logger.trace("(RW)");
        threadPool.submit(new HTTPWorker(configuration.getHandler(), configuration.getLoggerFactory(), this, request, response));
      }
    } else {
      state = requestProcessor.processBodyBytes();
    }

    if (state == RequestState.Expect) {
      var expectValidator = configuration.getExpectValidator();
      if (expectValidator != null) {
        expectValidator.validate(request, response);
      } else {
        response.setStatus(100);
      }

      responseProcessor.resetState(ResponseState.Expect);
      key.interestOps(SelectionKey.OP_WRITE);
    } else if (state == RequestState.Complete) {
      logger.trace("(RD2)");
      key.interestOps(SelectionKey.OP_WRITE);
    }

    return read;
  }

  @Override
  public long write(SelectionKey key) throws IOException {
    markUsed();

    SocketChannel client = (SocketChannel) key.channel();
    ResponseState state = responseProcessor.state();
    if (state == ResponseState.Expect || state == ResponseState.Preamble || state == ResponseState.Body) {
      ByteBuffer[] buffers = responseProcessor.currentBuffer();
      if (buffers == null) {
        // Nothing to write
        return 0;
      }

      long bytes = client.write(buffers);
      if (bytes > 0) {
        response.setCommitted(true);
      }

      logger.debug("Wrote [{}] bytes to the client", bytes);
      return bytes;
    }

    if (state == ResponseState.Continue) {
      // Flip back to reading and back to the preamble state, so we write the real response headers. Then start the worker thread and flip the ops
      requestProcessor.resetState(RequestState.Body);
      responseProcessor.resetState(ResponseState.Preamble);
      logger.trace("(RW)");
      threadPool.submit(new HTTPWorker(configuration.getHandler(), configuration.getLoggerFactory(), this, request, response));
      key.interestOps(SelectionKey.OP_READ);
    } else if (state == ResponseState.Failure) {
      // If we've written at least one byte back to the client, close the connection and bail. Otherwise, the failure was noted and the
      // Preamble will contain a 500 response. Therefore, we need to reset the processor, so it writes the preamble
      if (response.isCommitted()) {
        client.close();
        key.cancel();
      } else {
        responseProcessor.resetState(ResponseState.Preamble);
      }
    } else if (state == ResponseState.KeepAlive) {
      HTTP11Processor processor = new HTTP11Processor(configuration, listener, notifier, preambleBuffer, threadPool, ipAddress);
      key.attach(processor);
      key.interestOps(SelectionKey.OP_READ);
      logger.trace("(WD)");
    } else if (state == ResponseState.Close) {
      client.close();
      key.cancel();
    }

    return 0L;
  }
}
