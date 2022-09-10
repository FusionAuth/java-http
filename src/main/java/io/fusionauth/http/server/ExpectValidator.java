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
 * A validator that is used when the server receives a header of {@code Expect: 100-continue} during the initial request.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface ExpectValidator {
  /**
   * Performs the validation of the request headers and puts a valid response code into the response. This should generally be a {@code 100}
   * or an error of some type.
   * <p>
   * All headers in the response will be ignored and the OutputStream should not be used.
   *
   * @param request  The request.
   * @param response The response.
   */
  void validate(HTTPRequest request, HTTPResponse response);
}
