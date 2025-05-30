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
 * Thrown when the server has attempted to drain the InputStream and the configured maximum number of bytes to drain have been exceeded.
 *
 * @author Daniel DeGroff
 */
public class TooManyBytesToDrainException extends RuntimeException {
  private final long drainedBytes;

  private final long maximumDrainedBytes;

  public TooManyBytesToDrainException(long drainedBytes, long maximumDrainedBytes) {
    super();
    this.drainedBytes = drainedBytes;
    this.maximumDrainedBytes = maximumDrainedBytes;
  }

  public long getDrainedBytes() {
    return drainedBytes;
  }

  public long getMaximumDrainedBytes() {
    return maximumDrainedBytes;
  }
}
