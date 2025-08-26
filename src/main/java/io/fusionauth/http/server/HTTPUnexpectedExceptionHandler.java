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

/**
 * An interface defining the HTTP unexpected exception handler contract.
 *
 * @author Daniel DeGroff
 */
public interface HTTPUnexpectedExceptionHandler {
  /**
   *
   * This handler will be called when an unexpected exception is taken while processing an HTTP request by the HTTP worker.
   * <p>
   * The intent is that this provides additional flexibility on the status code and the logging behavior when an unexpected exception
   * caught.
   *
   * @param t the unexpected exception to handle.
   * @return the desired HTTP status code. Note that if the response has already been committed this will be ignored.
   */
  int handle(Throwable t);
}
