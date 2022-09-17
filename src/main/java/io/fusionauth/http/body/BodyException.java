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
package io.fusionauth.http.body;

/**
 * Exception that is thrown if any HTTP body fails to be read and/or processed.
 *
 * @author Brian Pontarelli
 */
public class BodyException extends RuntimeException {
  public BodyException(String message) {
    super(message);
  }

  public BodyException(String message, Throwable cause) {
    super(message, cause);
  }
}
