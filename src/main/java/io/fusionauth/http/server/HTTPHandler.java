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

/**
 * Handles an HTTP request from a client.
 *
 * @author Brian Pontarelli
 */
public interface HTTPHandler {
  /**
   * Handles the processing of a request and filling out the response. If this method returns normally, it is assumed that the HTTP response
   * is complete and the headers match the body (or lack thereof) so that the client can process the response properly.
   * <p>
   * If the handler wishes to close the connection, it should either include the header {@code Connection: close} or throw an exception. If
   * an exception is thrown, the HTTPServer will determine if the response is malformed (i.e. truncated), in which case the connection will
   * be closed automatically, or if the response can be changed to a {@code 500} response.
   *
   * @param request  The request from the client.
   * @param response The response sent back to the client.
   * @throws Exception If the processing failed and the response should be truncated or replaced. The connection will always be closed.
   */
  void handle(HTTPRequest request, HTTPResponse response) throws Exception;
}
