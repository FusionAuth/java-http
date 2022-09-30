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
package io.fusionauth.http.util;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Technically, this is a delegate to the thread pool, but close enough. It uses an ExecutorService to submit work to be processed.
 * <p>
 * This should be used in a singleton fashion, but it is not a singleton (yuck).
 *
 * @author Brian Pontarelli
 */
public class ThreadPool {
  private final ExecutorService executor;

  private final Duration shutdown;

  /**
   * Constructs a ThreadPool with the given number of threads, thread name prefix, and shutdown duration. All the threads are started
   * immediately and are core system threads (not the new Java light threads). The shutdown duration is the time that the thread that calls
   * {@link #shutdown()} will wait for an orderly shutdown of the pool, meaning that the threads will be finished working.
   *
   * @param numberOfThreads The number of threads. All the
   * @param namePrefix      The name prefix for the worker threads.
   * @param shutdown        The shutdown duration.
   */
  public ThreadPool(int numberOfThreads, String namePrefix, Duration shutdown) {
    AtomicInteger threadCount = new AtomicInteger(1);
    this.executor = Executors.newFixedThreadPool(numberOfThreads, runnable -> new Thread(runnable, namePrefix + " " + threadCount.getAndIncrement()));
    this.shutdown = shutdown;
  }

  public boolean shutdown() {
    executor.shutdownNow();

    try {
      return executor.awaitTermination(shutdown.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      // Ignore and exit
      return false;
    }
  }

  public Future<?> submit(Runnable runnable) {
    return this.executor.submit(runnable);
  }
}
