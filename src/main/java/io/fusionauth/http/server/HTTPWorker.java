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

import io.fusionauth.http.log.Logger;
import io.fusionauth.http.log.LoggerFactory;
import io.fusionauth.http.util.ThreadPool;

/**
 * An HTTP worker that is a delegate Runnable to an {@link HTTPHandler}. This provides the interface and handling for use with the
 * {@link ThreadPool}.
 *
 * @author Brian Pontarelli
 */
public class HTTPWorker implements Runnable {
  private final HTTPHandler handler;

  private final Logger logger;

  private final HTTPProcessor processor;

  private final HTTPRequest request;

  private final HTTPResponse response;

  public HTTPWorker(HTTPHandler handler, LoggerFactory loggerFactory, HTTPProcessor processor, HTTPRequest request, HTTPResponse response) {
    this.handler = handler;
    this.logger = loggerFactory.getLogger(HTTPWorker.class);
    this.processor = processor;
    this.request = request;
    this.response = response;
  }

  @Override
  public void run() {
    try {
      handler.handle(request, response);
    } catch (Throwable t) {
      // Log the error and signal a failure
      logger.error("HTTP worker threw an exception while processing a request", t);
      processor.failure(t);
    }
  }
}
