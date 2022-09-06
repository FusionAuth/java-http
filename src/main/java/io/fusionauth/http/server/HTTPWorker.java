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

import java.util.function.BiConsumer;

import io.fusionauth.http.HTTPRequest;
import io.fusionauth.http.HTTPResponse;
import io.fusionauth.http.io.NonBlockingByteBufferOutputStream;

/**
 * A worker that handles a single request/response from a client.
 *
 * @author Brian Pontarelli
 */
public class HTTPWorker implements Runnable {
  private final BiConsumer<HTTPRequest, HTTPResponse> handler;

  private final HTTPRequest request;

  private final HTTPRequestProcessor requestProcessor;

  private final HTTPResponse response;

  private final HTTPResponseProcessor responseProcessor;

  private long lastUsed = System.currentTimeMillis();

  public HTTPWorker(BiConsumer<HTTPRequest, HTTPResponse> handler, int maxHeadLength, Notifier notifier) {
    this.handler = handler;

    this.request = new HTTPRequest();
    this.requestProcessor = new HTTPRequestProcessor(request);

    NonBlockingByteBufferOutputStream outputStream = new NonBlockingByteBufferOutputStream(notifier);
    this.response = new HTTPResponse(outputStream);
    this.responseProcessor = new HTTPResponseProcessor(response, outputStream, maxHeadLength);
  }

  public long lastUsed() {
    return lastUsed;
  }

  public void markUsed() {
    lastUsed = System.currentTimeMillis();
  }

  public HTTPRequestProcessor requestProcessor() {
    return requestProcessor;
  }

  public HTTPResponse response() {
    return response;
  }

  public HTTPResponseProcessor responseProcessor() {
    return responseProcessor;
  }

  @Override
  public void run() {
    handler.accept(request, response);

    // Close the stream - for good measure in case the Handler didn't close it
    if (!responseProcessor.outputStream().isClosed()) {
      responseProcessor.outputStream().close();
    }
  }
}
