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
package io.fusionauth.http;

/**
 * A base HTTP processing exception that is able to suggest a status code to return the client.
 */
public abstract class HTTPProcessingException extends RuntimeException {
  protected final int status;

  protected final String statusMessage;

  protected HTTPProcessingException(int status, String statusMessage) {
    this.status = status;
    this.statusMessage = statusMessage;
  }

  protected HTTPProcessingException(int status, String statusMessage, String detailedMessage) {
    super(detailedMessage);
    this.status = status;
    this.statusMessage = statusMessage;
  }

  public int getStatus() {
    return status;
  }

  public String getStatusMessage() {
    return statusMessage;
  }
}
