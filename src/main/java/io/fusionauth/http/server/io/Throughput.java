/*
 * Copyright (c) 2024-2025, FusionAuth, All Rights Reserved
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
package io.fusionauth.http.server.io;

import io.fusionauth.http.server.internal.HTTPWorker;

/**
 * This class allows the {@link HTTPWorker} to hook into other classes without passing around interfaces, loggers, and configuration.
 *
 * @author Brian Pontarelli
 */
public class Throughput {
  private long firstReadInstant;

  private long firstWroteInstant;

  private long lastReadInstant;

  private long lastWroteInstant;

  private long numberOfBytesRead;

  private long numberOfBytesWritten;

  private final long readThroughputDelay;

  private final long writeThroughputDelay;

  public Throughput(long readThroughputDelay, long writeThroughputDelay) {
    this.readThroughputDelay = readThroughputDelay;
    this.writeThroughputDelay = writeThroughputDelay;
  }

  public synchronized long lastUsed() {
    if (lastReadInstant == 0 && lastWroteInstant == 0) {
      return Long.MAX_VALUE;
    }

    return Math.max(lastReadInstant, lastWroteInstant);
  }

  /**
   * Signals that some number of bytes were read from a client.
   *
   * @param numberOfBytes The number of bytes.
   */
  public synchronized void read(long numberOfBytes) {
    long now = System.currentTimeMillis();
    if (firstReadInstant == 0) {
      firstReadInstant = now;
    }

    numberOfBytesRead += numberOfBytes;
    lastReadInstant = now;
  }

  public synchronized long readThroughput(long now) {
    // Haven't read anything yet, or we read everything in the first read (instants are equal)
    if (firstReadInstant == 0 || numberOfBytesRead == 0 || lastReadInstant == firstReadInstant) {
      return Long.MAX_VALUE;
    }

    // No bytes have been written
    if (numberOfBytesWritten == 0) {
      long millis = now - firstReadInstant;
      // We are within the read delay and do not yet have enough data to make a meaningful calculation for actual throughput.
      if (millis < readThroughputDelay) {
        return Long.MAX_VALUE;
      }

      // Always zero
      return numberOfBytesWritten;
    }

    // The number of bytes read in seconds
    double result = ((double) numberOfBytesRead / (double) (lastReadInstant - firstReadInstant)) * 1_000;
    return Math.round(result);
  }

  public synchronized long writeThroughput(long now) {
    // Haven't written anything yet or not enough time has passed to calculated throughput (2s)
    if (firstWroteInstant == 0 || numberOfBytesWritten == 0) {
      return Long.MAX_VALUE;
    }

    // Always use currentTime since this calculation is ongoing until the client reads all the bytes
    long millis = now - firstWroteInstant;
    if (millis < writeThroughputDelay) {
      return Long.MAX_VALUE;
    }

    double result = ((double) numberOfBytesWritten / (double) millis) * 1_000;
    return Math.round(result);
  }

  /**
   * Signals that some number of bytes were wrote to a client.
   *
   * @param numberOfBytes The number of bytes.
   */
  public synchronized void wrote(long numberOfBytes) {
    long now = System.currentTimeMillis();
    if (firstWroteInstant == 0) {
      firstWroteInstant = now;
    }

    numberOfBytesWritten += numberOfBytes;
    lastWroteInstant = now;
  }
}
