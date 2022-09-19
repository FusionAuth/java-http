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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import io.fusionauth.http.log.LoggerFactory;
import io.fusionauth.http.log.SystemOutLoggerFactory;

public class HTTPServerConfiguration implements Configurable<HTTPServerConfiguration> {
  private Path baseDir = Path.of("");

  private InetAddress bindAddress;

  private Duration clientTimeoutDuration = Duration.ofSeconds(10);

  private String contextPath = "";

  private ExpectValidator expectValidator;

  private HTTPHandler handler;

  private Instrumenter instrumenter;

  private LoggerFactory loggerFactory = SystemOutLoggerFactory.FACTORY;

  private int maxHeadLength;

  private int multipartBufferSize = 16 * 1024;

  private int numberOfWorkerThreads = 40;

  private int port = 8080;

  private int preambleBufferSize = 4096;

  private int requestBufferSize = 16 * 1024;

  private int responseBufferSize = 16 * 1024;

  private Duration shutdownDuration = Duration.ofSeconds(10);

  public HTTPServerConfiguration() {
    try {
      this.bindAddress = InetAddress.getByName("::");
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

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

  public InetAddress getBindAddress() {
    return bindAddress;
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

  public int getPort() {
    return port;
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
  public HTTPServerConfiguration withBindAddress(InetAddress address) {
    this.bindAddress = address;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withClientTimeoutDuration(Duration duration) {
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
  public HTTPServerConfiguration withLoggerFactory(LoggerFactory loggerFactory) {
    Objects.requireNonNull(loggerFactory);
    this.loggerFactory = loggerFactory;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMaxPreambleLength(int maxLength) {
    this.maxHeadLength = maxLength;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMultipartBufferSize(int multipartBufferSize) {
    this.multipartBufferSize = multipartBufferSize;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withNumberOfWorkerThreads(int numberOfWorkerThreads) {
    this.numberOfWorkerThreads = numberOfWorkerThreads;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withPort(int port) {
    this.port = port;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withPreambleBufferSize(int size) {
    this.preambleBufferSize = size;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withRequestBufferSize(int requestBufferSize) {
    this.requestBufferSize = requestBufferSize;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withResponseBufferSize(int responseBufferSize) {
    this.responseBufferSize = responseBufferSize;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withShutdownDuration(Duration duration) {
    this.shutdownDuration = duration;
    return this;
  }
}
