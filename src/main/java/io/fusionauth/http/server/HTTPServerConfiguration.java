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

  private boolean compressByDefault = true;

  private String contextPath = "";

  private ExpectValidator expectValidator = new AlwaysContinueExpectValidator();

  private HTTPHandler handler;

  private Duration initialReadTimeoutDuration = Duration.ofSeconds(2);

  private Instrumenter instrumenter;

  private Duration keepAliveTimeoutDuration = Duration.ofSeconds(20);

  private LoggerFactory loggerFactory = SystemOutLoggerFactory.FACTORY;

  private int maxBytesToDrain = 128 * 1024; // 128k bytes

  private int maxResponseChunkSize = 16 * 1024; // 16k bytes

  private long minimumReadThroughput = 16 * 1024; // 16k/second

  private long minimumWriteThroughput = 16 * 1024; // 16k/second

  private int multipartBufferSize = 16 * 1024; // 16k bytes

  private Duration processingTimeoutDuration = Duration.ofSeconds(10);

  private Duration readThroughputCalculationDelayDuration = Duration.ofSeconds(5);

  private int requestBufferSize = 16 * 1024;

  private int responseBufferSize = 64 * 1024;

  private Duration shutdownDuration = Duration.ofSeconds(10);

  private Duration writeThroughputCalculationDelayDuration = Duration.ofSeconds(5);

  /**
   * @return This.
   */
  @Override
  public HTTPServerConfiguration configuration() {
    return this;
  }

  /**
   * @return The base dir for the entire server. This can be used to calculate files from as needed.
   */
  public Path getBaseDir() {
    return baseDir;
  }

  /**
   * @return The context page that the entire server serves requests under or null.
   */
  public String getContextPath() {
    return contextPath;
  }

  /**
   * @return The expect validator or null.
   */
  public ExpectValidator getExpectValidator() {
    return expectValidator;
  }

  /**
   * @return The HTTP handler for this server. Cannot be null and is required.
   */
  public HTTPHandler getHandler() {
    return handler;
  }

  /**
   * @return The timeout between a socket being accepted by the server and the first byte being read. This is distinct and separate from the
   *     timeout for subsequent reads after the connection has been "kept alive".
   */
  public Duration getInitialReadTimeoutDuration() {
    return initialReadTimeoutDuration;
  }

  /**
   * @return The instrumenter or null.
   */
  public Instrumenter getInstrumenter() {
    return instrumenter;
  }

  /**
   * @return The timeout between requests when the server is in Keep-Alive mode. This is the maximum value to prevent DoS attacks that use
   *     the HTTP headers to set extremely long timeouts.
   */
  public Duration getKeepAliveTimeoutDuration() {
    return keepAliveTimeoutDuration;
  }

  /**
   * @return All configured listeners (if any) or an empty list.
   */
  public List<HTTPListenerConfiguration> getListeners() {
    return listeners;
  }

  /**
   * @return The logger factory.
   */
  public LoggerFactory getLoggerFactory() {
    return loggerFactory;
  }

  /**
   * @return The maximum number of bytes to drain from the InputStream when the request handler did not read all available bytes and the
   *     connection is using a keep-alive which means the server must drain the InputStream in preparation for the next request. Defaults to
   *     128k.
   */
  public int getMaxBytesToDrain() {
    return maxBytesToDrain;
  }

  /**
   * @return The max chunk size in the response. Defaults to 16k bytes.
   */
  public int getMaxResponseChunkSize() {
    return maxResponseChunkSize;
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

  /**
   * @return The multipart buffer size in bytes. This is primary used for parsing multipart requests by the {@link HTTPRequest} class.
   *     Defaults to 16k bytes.
   */
  public int getMultipartBufferSize() {
    return multipartBufferSize;
  }

  /**
   * @return The timeout between when the request has been fully read and the first byte is written. This provides the worker thread to
   *     perform work before it begins to write. This timeout should be relatively short depending on how long you want the browser/client
   *     to wait before the response comes back. Defaults to 10 seconds.
   */
  public Duration getProcessingTimeoutDuration() {
    return processingTimeoutDuration;
  }

  /**
   * @return the duration that will be used to delay the calculation and enforcement of the minimum read throughput.
   */
  public Duration getReadThroughputCalculationDelay() {
    return readThroughputCalculationDelayDuration;
  }

  /**
   * @return The size of the buffer used to read the request. This defaults to 16k bytes.
   */
  public int getRequestBufferSize() {
    return requestBufferSize;
  }

  /**
   * @return The size of the buffer used to store the response. This allows the server to handle exceptions and errors without writing back
   *     a 200 response that is actually an error. This defaults to 64k bytes.
   */
  public int getResponseBufferSize() {
    return responseBufferSize;
  }

  /**
   * @return The duration that the server will wait while worker threads complete before forcibly shutting itself down. Defaults to 10
   *     seconds.
   */
  public Duration getShutdownDuration() {
    return shutdownDuration;
  }

  /**
   * @return the duration that will be used to delay the calculation and enforcement of the minimum write throughput.
   */
  public Duration getWriteThroughputCalculationDelay() {
    return writeThroughputCalculationDelayDuration;
  }

  /**
   * @return Whether all responses are compressed by default. Defaults to true.
   */
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
  public HTTPServerConfiguration withInitialReadTimeout(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the client timeout to null");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The client timeout duration must be greater than 0");
    }


    this.initialReadTimeoutDuration = duration;
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
  public HTTPServerConfiguration withKeepAliveTimeoutDuration(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the keep-alive timeout duration to null");

    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The keep-alive timeout duration must be grater than 0");
    }

    this.keepAliveTimeoutDuration = duration;
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
  public HTTPServerConfiguration withMaxResponseChunkSize(int size) {
    this.maxResponseChunkSize = size;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMaximumBytesToDrain(int maxBytesToDrain) {
    if (maxBytesToDrain <= 1024 || maxBytesToDrain >= 256 * 1024 * 1024) {
      throw new IllegalArgumentException("The maximum bytes to drain must be greater than or equal to 1024 and less than or equal to 268,435,456 (256 megabytes)");
    }

    this.maxBytesToDrain = maxBytesToDrain;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMinimumReadThroughput(long bytesPerSecond) {
    if (bytesPerSecond != -1 && bytesPerSecond < 1024) {
      throw new IllegalArgumentException("The minimum bytes per second must be greater than 1024. This should probably be faster than a 28.8 baud modem!");
    }

    this.minimumReadThroughput = bytesPerSecond;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  public HTTPServerConfiguration withMinimumWriteThroughput(long bytesPerSecond) {
    if (bytesPerSecond != -1 && bytesPerSecond < 1024) {
      throw new IllegalArgumentException("The minimum bytes per second must be greater than 1024. This should probably be faster than a 28.8 baud modem!");
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
  public HTTPServerConfiguration withProcessingTimeoutDuration(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the processing timeout duration to null");

    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The processing timeout duration must be grater than 0");
    }

    this.processingTimeoutDuration = duration;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withReadThroughputCalculationDelayDuration(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the read throughput delay duration to null");

    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The read throughput delay duration must be grater than 0");
    }

    this.readThroughputCalculationDelayDuration = duration;
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

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withWriteThroughputCalculationDelayDuration(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the write throughput delay duration to null");

    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The write throughput delay duration must be grater than 0");
    }

    this.writeThroughputCalculationDelayDuration = duration;
    return this;
  }
}
