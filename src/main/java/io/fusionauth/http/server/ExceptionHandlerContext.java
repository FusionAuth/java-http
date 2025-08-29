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

/**
 * Provide context to the exception handler.
 *
 * @author Daniel DeGroff
 */
public class ExceptionHandlerContext {
  private final Logger logger;

  private final HTTPRequest request;

  private final Throwable throwable;

  private int statusCode;

  public ExceptionHandlerContext(Logger logger, HTTPRequest request, int statusCode, Throwable throwable) {
    this.logger = logger;
    this.request = request;
    this.statusCode = statusCode;
    this.throwable = throwable;
  }

  /**
   * This is provided for convenience, but you may wish to use your own logger.
   *
   * @return the optional logger to use in the exception handler.
   */
  public Logger getLogger() {
    return logger;
  }

  /**
   * This may be useful if you wish to know additional context of the exception such as the URI of the current HTTP request.
   * <p>
   * Modifications to this object will have no effect on current or futures requests.
   *
   * @return the current HTTP request, or null if this exception was taking prior to constructing the HTTP request. This is unlikely but
   *     please account for this value being null.
   */
  public HTTPRequest getRequest() {
    return request;
  }

  /**
   * @return the desired status code for the HTTP response.
   */
  public int getStatusCode() {
    return statusCode;
  }

  /**
   * Suggest a status code for the HTTP response. This value will be used unless the response has already been committed meaning bytes have
   * already been written to the client and the HTTP server is not able to modify the response code.
   *
   * @param statusCode the desired status code to set on the HTTP response.
   */
  public void setStatusCode(int statusCode) {
    this.statusCode = statusCode;
  }

  /**
   * @return the unexpected exception to handle
   */
  public Throwable getThrowable() {
    return throwable;
  }
}
