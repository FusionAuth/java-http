/*
 * Copyright (c) 2022-2023, FusionAuth, All Rights Reserved
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

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.fusionauth.http.log.LoggerFactory;
import io.fusionauth.http.log.SystemOutLoggerFactory;

/**
 * The HTTP Server configuration.
 *
 * @author Brian Pontarelli
 */
public class HTTPServerConfiguration implements Configurable<HTTPServerConfiguration> {
  private final List<HTTPListenerConfiguration> listeners = new ArrayList<>();

  private Path baseDir = Path.of("");

  private Duration clientTimeoutDuration = Duration.ofSeconds(20);

  private boolean compressByDefault = true;

  private String contextPath = "";

  private ExpectValidator expectValidator;

  private HTTPHandler handler;

  private Instrumenter instrumenter;

  private LoggerFactory loggerFactory = SystemOutLoggerFactory.FACTORY;

  private int maxHeadLength = 128 * 1024;

  private int maxOutputBufferQueueLength = 128;

  private long minimumReadThroughput = 16 * 1024; // Per second

  private long minimumWriteThroughput = 16 * 1024; // Per second

  private int multipartBufferSize = 16 * 1024;

  private int numberOfWorkerThreads = 40;

  private int preambleBufferSize = 16 * 1024;

  private int requestBufferSize = 16 * 1024;

  private int responseBufferSize = 16 * 1024;

  private Duration shutdownDuration = Duration.ofSeconds(10);

  /**
   * @return This.
   */
  @Override
  public HTTPServerConfiguration configuration() {
    return this;
  }

  public Path getBaseDir() {
    return baseDir;
  }

  public Duration getClientTimeoutDuration() {
    return clientTimeoutDuration;
  }

  public String getContextPath() {
    return contextPath;
  }

  public ExpectValidator getExpectValidator() {
    return expectValidator;
  }

  public HTTPHandler getHandler() {
    return handler;
  }

  public Instrumenter getInstrumenter() {
    return instrumenter;
  }

  public List<HTTPListenerConfiguration> getListeners() {
    return listeners;
  }

  public LoggerFactory getLoggerFactory() {
    return loggerFactory;
  }

  public int getMaxHeadLength() {
    return maxHeadLength;
  }

  /**
   * This configuration will affect the runtime memory requirement.
   * <p>
   * The maximum memory requirement for the output buffer can be calculated multiplying this value by the values returned from
   * {@link HTTPServerConfiguration#getResponseBufferSize()} and * {@link HTTPServerConfiguration#getNumberOfWorkerThreads()}.
   *
   * @return the maximum output buffer queue length.
   */
  public int getMaxOutputBufferQueueLength() {
    return maxOutputBufferQueueLength;
  }

  /**
   * This configuration is the minimum number of bytes per second that a client must send a request to the server before the server closes
   * the connection.
   *
   * @return The minimum throughput for any connection with the server in bytes per second.
   */
  public long getMinimumReadThroughput() {
    return minimumReadThroughput;
  }

  /**
   * This configuration is the minimum number of bytes per second that a client must read the response from the server before the server
   * closes the connection.
   *
   * @return The minimum throughput for any connection with the server in bytes per second.
   */
  public long getMinimumWriteThroughput() {
    return minimumWriteThroughput;
  }

  public int getMultipartBufferSize() {
    return multipartBufferSize;
  }

  /**
   * The number of worker threads. This configuration will affect the runtime memory requirement.
   * <p>
   * The maximum memory requirement for the output buffer can be calculated multiplying this value by the values returned from
   * {@link HTTPServerConfiguration#getMaxOutputBufferQueueLength()} and * {@link HTTPServerConfiguration#getResponseBufferSize()}.
   *
   * @return the number of worker threads.
   */
  public int getNumberOfWorkerThreads() {
    return numberOfWorkerThreads;
  }

  public int getPreambleBufferSize() {
    return preambleBufferSize;
  }

  public int getRequestBufferSize() {
    return requestBufferSize;
  }

  /**
   * The size of the response buffer in bytes. This configuration will affect the runtime memory requirement.
   * <p>
   * The maximum memory requirement for the output buffer can be calculated multiplying this value by the values returned from
   * {@link HTTPServerConfiguration#getMaxOutputBufferQueueLength()} and * {@link HTTPServerConfiguration#getNumberOfWorkerThreads()}.
   *
   * @return the response buffer size in bytes.
   */
  public int getResponseBufferSize() {
    return responseBufferSize;
  }

  public Duration getShutdownDuration() {
    return shutdownDuration;
  }

  public boolean isCompressByDefault() {
    return compressByDefault;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withBaseDir(Path baseDir) {
    this.baseDir = baseDir;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withClientTimeout(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the client timeout to null");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The client timeout duration must be greater than 0");
    }


    this.clientTimeoutDuration = duration;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withCompressByDefault(boolean compressByDefault) {
    this.compressByDefault = compressByDefault;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withContextPath(String contextPath) {
    this.contextPath = contextPath;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withExpectValidator(ExpectValidator validator) {
    this.expectValidator = validator;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withHandler(HTTPHandler handler) {
    Objects.requireNonNull(handler, "You cannot set HTTPHandler to null");
    this.handler = handler;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withInstrumenter(Instrumenter instrumenter) {
    this.instrumenter = instrumenter;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withListener(HTTPListenerConfiguration listener) {
    Objects.requireNonNull(listener, "You cannot set HTTPListenerConfiguration to null");
    this.listeners.add(listener);
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withLoggerFactory(LoggerFactory loggerFactory) {
    Objects.requireNonNull(loggerFactory, "You cannot set LoggerFactory to null");
    this.loggerFactory = loggerFactory;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMaxOutputBufferQueueLength(int outputBufferQueueLength) {
    if (outputBufferQueueLength < 16) {
      throw new IllegalArgumentException("The maximum output buffer queue length must be greater than or equal to 16");
    }

    this.maxOutputBufferQueueLength = outputBufferQueueLength;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMaxPreambleLength(int maxLength) {
    if (maxLength <= 0) {
      throw new IllegalArgumentException("The maximum preamble length must be greater than 0");
    }

    this.maxHeadLength = maxLength;
    return this;
  }

  /**
   * This configures the minimum number of bytes per second that a client must send a request to the server before the server closes the
   * connection.
   *
   * @param bytesPerSecond The bytes per second throughput.
   * @return This.
   */
  @Override
  public HTTPServerConfiguration withMinimumReadThroughput(long bytesPerSecond) {
    if (bytesPerSecond < 1024) {
      throw new IllegalArgumentException("This should probably be faster than a 28.8 baud modem!");
    }

    this.minimumReadThroughput = bytesPerSecond;
    return this;
  }

  /**
   * This configures the minimum number of bytes per second that a client must read the response from the server before the server closes
   * the connection.
   *
   * @param bytesPerSecond The bytes per second throughput.
   * @return This.
   */
  public HTTPServerConfiguration withMinimumWriteThroughput(long bytesPerSecond) {
    if (bytesPerSecond < 1024) {
      throw new IllegalArgumentException("This should probably be faster than a 28.8 baud modem!");
    }

    this.minimumWriteThroughput = bytesPerSecond;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMultipartBufferSize(int multipartBufferSize) {
    if (multipartBufferSize <= 0) {
      throw new IllegalArgumentException("The multi-part buffer size must be greater than 0");
    }

    this.multipartBufferSize = multipartBufferSize;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withNumberOfWorkerThreads(int numberOfWorkerThreads) {
    if (numberOfWorkerThreads <= 0) {
      throw new IllegalArgumentException("The number of worker threads must be greater than 0");
    }

    this.numberOfWorkerThreads = numberOfWorkerThreads;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withPreambleBufferSize(int size) {
    if (size <= 0) {
      throw new IllegalArgumentException("The preamble buffer size must be greater than 0");
    }

    this.preambleBufferSize = size;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withRequestBufferSize(int requestBufferSize) {
    if (requestBufferSize <= 0) {
      throw new IllegalArgumentException("The request buffer size must be greater than 0");
    }

    this.requestBufferSize = requestBufferSize;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withResponseBufferSize(int responseBufferSize) {
    if (responseBufferSize <= 0) {
      throw new IllegalArgumentException("The response buffer size must be greater than 0");
    }

    this.responseBufferSize = responseBufferSize;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withShutdownDuration(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the shutdown duration to null");

    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The shutdown duration must be grater than 0");
    }

    this.shutdownDuration = duration;
    return this;
  }
}
