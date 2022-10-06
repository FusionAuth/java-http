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

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.fusionauth.http.log.LoggerFactory;
import io.fusionauth.http.log.SystemOutLoggerFactory;

public class HTTPServerConfiguration implements Configurable<HTTPServerConfiguration> {
  private final List<HTTPListenerConfiguration> listeners = new ArrayList<>();

  private Path baseDir = Path.of("");

  private Duration clientTimeoutDuration = Duration.ofSeconds(20);

  private String contextPath = "";

  private ExpectValidator expectValidator;

  private HTTPHandler handler;

  private Instrumenter instrumenter;

  private LoggerFactory loggerFactory = SystemOutLoggerFactory.FACTORY;

  private int maxHeadLength = 128 * 1024;

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

  public int getMultipartBufferSize() {
    return multipartBufferSize;
  }

  public int getNumberOfWorkerThreads() {
    return numberOfWorkerThreads;
  }

  public int getPreambleBufferSize() {
    return preambleBufferSize;
  }

  public int getRequestBufferSize() {
    return requestBufferSize;
  }

  public int getResponseBufferSize() {
    return responseBufferSize;
  }

  public Duration getShutdownDuration() {
    return shutdownDuration;
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
      throw new IllegalArgumentException("You cannot set the client timeout less than 0");
    }


    this.clientTimeoutDuration = duration;
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
  public HTTPServerConfiguration withMaxPreambleLength(int maxLength) {
    if (maxLength <= 0) {
      throw new IllegalArgumentException("You cannot set the max preamble length than 0");
    }

    this.maxHeadLength = maxLength;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMultipartBufferSize(int multipartBufferSize) {
    if (multipartBufferSize <= 0) {
      throw new IllegalArgumentException("You cannot set the multipart buffer size less than 0");
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
      throw new IllegalArgumentException("You cannot set the number of worker threads less than 0");
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
      throw new IllegalArgumentException("You cannot set the preamble buffer size less than 0");
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
      throw new IllegalArgumentException("You cannot set the request buffer size less than 0");
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
      throw new IllegalArgumentException("You cannot set the response buffer size less than 0");
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
      throw new IllegalArgumentException("You cannot set the shutdown duration less than 0");
    }

    this.shutdownDuration = duration;
    return this;
  }
}
