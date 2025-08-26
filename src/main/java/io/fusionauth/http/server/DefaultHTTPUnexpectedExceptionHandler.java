/*
 * Copyright (c) 2025, FusionAuth, All Rights Reserved
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

/**
 * THe default HTTP unexpected exception handler.
 *
 * @author Daniel DeGroff
 */
public class DefaultHTTPUnexpectedExceptionHandler implements HTTPUnexpectedExceptionHandler {

  @Override
  public int handle(LoggerFactory loggerFactory, Throwable t) {
    int internalServerError = 500;
    Logger logger = loggerFactory.getLogger(DefaultHTTPUnexpectedExceptionHandler.class);
    logger.error(String.format("[%s] Closing socket with status [%d]. An HTTP worker threw an exception while processing a request.", Thread.currentThread().threadId(), internalServerError), t);
    return internalServerError;
  }
}
