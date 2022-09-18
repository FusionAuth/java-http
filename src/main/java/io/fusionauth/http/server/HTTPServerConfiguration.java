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
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import io.fusionauth.http.log.LoggerFactory;
import io.fusionauth.http.log.SystemOutLoggerFactory;

public class HTTPServerConfiguration {
  private InetAddress address;

  private Path baseDir = Path.of("");

  private Duration clientTimeoutDuration = Duration.ofSeconds(10);

  private String contextPath = "";

  private ExpectValidator expectValidator;

  private HTTPHandler handler;

  private Instrumenter instrumenter;

  private LoggerFactory loggerFactory = SystemOutLoggerFactory.FACTORY;

  private int maxHeadLength;

  private int numberOfWorkerThreads = 40;

  private int port = 8080;

  private int preambleBufferSize = 4096;

  private Duration shutdownDuration = Duration.ofSeconds(10);

  public InetAddress getAddress() {
    return address;
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

  public LoggerFactory getLoggerFactory() {
    return loggerFactory;
  }

  public int getMaxHeadLength() {
    return maxHeadLength;
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

  public Duration getShutdownDuration() {
    return shutdownDuration;
  }

  /**
   * Sets the base directory for this server. This is passed to the HTTPContext, which is available from this class. This defaults to the
   * current working directory of the process.
   *
   * @param baseDir The base dir.
   * @return This.
   */
  public HTTPServerConfiguration withBaseDir(Path baseDir) {
    this.baseDir = baseDir;
    return this;
  }

  /**
   * Sets the bind address that this server listens on. Defaults to `::`.
   *
   * @param address The bind address.
   * @return This.
   */
  public HTTPServerConfiguration withBindAddress(InetAddress address) {
    this.address = address;
    return this;
  }

  /**
   * Sets the duration that the server will allow client connections to remain open. This includes Keep-Alive as well as read timeout.
   *
   * @param duration The duration.
   * @return This.
   */
  public HTTPServerConfiguration withClientTimeoutDuration(Duration duration) {
    this.clientTimeoutDuration = duration;
    return this;
  }

  /**
   * Sets the prefix of the URIs that this server handles. Technically, the server will accept all inbound connections, but if a context
   * path is set, it can assist the application with building URIs (in HTML for example). This value will be accessible via the
   * {@link HTTPRequest#getContextPath()} method.
   *
   * @param contextPath The context path for the server.
   * @return This.
   */
  public HTTPServerConfiguration withContextPath(String contextPath) {
    this.contextPath = contextPath;
    return this;
  }

  /**
   * Sets an ExpectValidator that is used if a client sends the server a {@code Expect: 100-continue} header.
   *
   * @param validator The validator.
   * @return This.
   */
  public HTTPServerConfiguration withExpectValidator(ExpectValidator validator) {
    this.expectValidator = validator;
    return this;
  }

  /**
   * Sets the handler that will process the requests.
   *
   * @param handler The handler that processes the requests.
   * @return This.
   */
  public HTTPServerConfiguration withHandler(HTTPHandler handler) {
    this.handler = handler;
    return this;
  }

  /**
   * Sets an instrumenter that the server will notify when events and conditions happen.
   *
   * @param instrumenter The instrumenter.
   * @return This.
   */
  public HTTPServerConfiguration withInstrumenter(Instrumenter instrumenter) {
    this.instrumenter = instrumenter;
    return this;
  }

  /**
   * Sets the logger factory that all the HTTP server classes use to retrieve specific loggers. Defaults to the
   * {@link SystemOutLoggerFactory}.
   *
   * @param loggerFactory The factory.
   * @return This.
   */
  public HTTPServerConfiguration withLoggerFactory(LoggerFactory loggerFactory) {
    Objects.requireNonNull(loggerFactory);
    this.loggerFactory = loggerFactory;
    return this;
  }

  /**
   * Sets the max preamble length (the start-line and headers constitute the head). Defaults to 64k
   *
   * @param maxLength The max preamble length.
   * @return This.
   */
  public HTTPServerConfiguration withMaxPreambleLength(int maxLength) {
    this.maxHeadLength = maxLength;
    return this;
  }

  /**
   * Sets the number of worker threads that will handle requests coming into the HTTP server. Defaults to 40.
   *
   * @param numberOfWorkerThreads The number of worker threads.
   * @return This.
   */
  public HTTPServerConfiguration withNumberOfWorkerThreads(int numberOfWorkerThreads) {
    this.numberOfWorkerThreads = numberOfWorkerThreads;
    return this;
  }

  /**
   * Sets the port that this server listens on for HTTP (non-TLS). Defaults to 8080.
   *
   * @param port The port.
   * @return This.
   */
  public HTTPServerConfiguration withPort(int port) {
    this.port = port;
    return this;
  }

  /**
   * Sets the size of the preamble buffer (that is the buffer that reads the start-line and headers). Defaults to 4096.
   *
   * @param size The buffer size.
   * @return This.
   */
  public HTTPServerConfiguration withPreambleBufferSize(int size) {
    this.preambleBufferSize = size;
    return this;
  }

  /**
   * Sets the duration the server will wait for running requests to be completed. Defaults to 10 seconds.
   *
   * @param duration The duration the server will wait for all running request processing threads to complete their work.
   * @return This.
   */
  public HTTPServerConfiguration withShutdownDuration(Duration duration) {
    this.shutdownDuration = duration;
    return this;
  }
}
