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

import io.fusionauth.http.log.LoggerFactory;
import io.fusionauth.http.log.SystemOutLoggerFactory;

/**
 * An interface that identifies something that is configurable. Mainly, this allows the HTTPServer to be configured or to be passed a
 * configuration.
 *
 * @param <T> The type of configurable for casting.
 */
@SuppressWarnings("unchecked")
public interface Configurable<T extends Configurable<T>> {
  /**
   * @return The configuration object.
   */
  HTTPServerConfiguration configuration();

  /**
   * Sets the base directory for this server. This is passed to the HTTPContext, which is available from this class. This defaults to the
   * current working directory of the process.
   *
   * @param baseDir The base dir.
   * @return This.
   */
  default T withBaseDir(Path baseDir) {
    configuration().withBaseDir(baseDir);
    return (T) this;
  }

  /**
   * Sets the duration that the server will allow client connections to remain open. This includes Keep-Alive as well as read timeout.
   * Defaults to 20 seconds.
   *
   * @param duration The duration.
   * @return This.
   */
  default T withClientTimeout(Duration duration) {
    configuration().withClientTimeout(duration);
    return (T) this;
  }

  /**
   * Sets the default compression behavior for the HTTP response. This behavior can be optionally set per response. See
   * {@link HTTPResponse#setCompress(boolean)}. Defaults to true.
   *
   * @param compressByDefault true if you want to compress by default, or false to not compress by default.
   * @return This.
   */
  default T withCompressByDefault(boolean compressByDefault) {
    configuration().withCompressByDefault(compressByDefault);
    return (T) this;
  }

  /**
   * Sets the prefix of the URIs that this server handles. Technically, the server will accept all inbound connections, but if a context
   * path is set, it can assist the application with building URIs (in HTML for example). This value will be accessible via the
   * {@link HTTPRequest#getContextPath()} method.
   *
   * @param contextPath The context path for the server.
   * @return This.
   */
  default T withContextPath(String contextPath) {
    configuration().withContextPath(contextPath);
    return (T) this;
  }

  /**
   * Sets an ExpectValidator that is used if a client sends the server a {@code Expect: 100-continue} header.
   *
   * @param validator The validator.
   * @return This.
   */
  default T withExpectValidator(ExpectValidator validator) {
    configuration().withExpectValidator(validator);
    return (T) this;
  }

  /**
   * Sets the handler that will process the requests.
   *
   * @param handler The handler that processes the requests.
   * @return This.
   */
  default T withHandler(HTTPHandler handler) {
    configuration().withHandler(handler);
    return (T) this;
  }

  /**
   * Sets an instrumenter that the server will notify when events and conditions happen.
   *
   * @param instrumenter The instrumenter.
   * @return This.
   */
  default T withInstrumenter(Instrumenter instrumenter) {
    configuration().withInstrumenter(instrumenter);
    return (T) this;
  }

  /**
   * Adds a listener configuration for the server. This will listen on the address and port of the configuration but will share the thread
   * pool of the server.
   *
   * @param listener The listener.
   * @return This.
   */
  default T withListener(HTTPListenerConfiguration listener) {
    configuration().withListener(listener);
    return (T) this;
  }

  /**
   * Sets the logger factory that all the HTTP server classes use to retrieve specific loggers. Defaults to the
   * {@link SystemOutLoggerFactory}.
   *
   * @param loggerFactory The factory.
   * @return This.
   */
  default T withLoggerFactory(LoggerFactory loggerFactory) {
    configuration().withLoggerFactory(loggerFactory);
    return (T) this;
  }

  /**
   * This configures the minimum number of bytes per second that a client must send a request to the server before the server closes the
   * connection.
   *
   * @param bytesPerSecond The bytes per second throughput.
   * @return This.
   */
  default T withMinimumReadThroughput(long bytesPerSecond) {
    configuration().withMinimumReadThroughput(bytesPerSecond);
    return (T) this;
  }

  /**
   * This configures the minimum number of bytes per second that a client must read the response from the server before the server closes
   * the connection.
   *
   * @param bytesPerSecond The bytes per second throughput.
   * @return This.
   */
  default T withMinimumWriteThroughput(long bytesPerSecond) {
    configuration().withMinimumWriteThroughput(bytesPerSecond);
    return (T) this;
  }

  /**
   * Sets the size of the buffer that is used to process the multipart request body. This defaults to 16k.
   *
   * @param multipartBufferSize The size of the buffer.
   * @return This.
   */
  default T withMultipartBufferSize(int multipartBufferSize) {
    configuration().withMultipartBufferSize(multipartBufferSize);
    return (T) this;
  }

  /**
   * This configures the duration of the initial delay before calculating and enforcing the minimum read throughput. Defaults to 5 seconds.
   * <p>
   * This accounts for some warm up period, and exempts short-lived connections that may have smaller payloads that are more difficult to
   * calculate a reasonable minimum read throughput.
   *
   * @param duration The duration to delay the enforcement of the minimum read throughput.
   * @return This.
   */
  default T withReadThroughputCalculationDelayDuration(Duration duration) {
    configuration().withReadThroughputCalculationDelayDuration(duration);
    return (T) this;
  }

  /**
   * Sets the size of the buffer that is used to process the HTTP request. This defaults to 16k.
   *
   * @param requestBufferSize The size of the buffer.
   * @return This.
   */
  default T withRequestBufferSize(int requestBufferSize) {
    configuration().withRequestBufferSize(requestBufferSize);
    return (T) this;
  }

  /**
   * Sets the duration the server will wait for running requests to be completed. Defaults to 10 seconds.
   *
   * @param duration The duration the server will wait for all running request processing threads to complete their work.
   * @return This.
   */
  default T withShutdownDuration(Duration duration) {
    configuration().withShutdownDuration(duration);
    return (T) this;
  }

  /**
   * This configures the duration of the initial delay before calculating and enforcing the minimum write throughput. Defaults to 5
   * seconds.
   * <p>
   * This accounts for some warm up period, and exempts short-lived connections that may have smaller payloads that are more difficult to
   * calculate a reasonable minimum write throughput.
   *
   * @param duration The duration to delay the enforcement of the minimum write throughput.
   * @return This.
   */
  default T withWriteThroughputCalculationDelayDuration(Duration duration) {
    configuration().withWriteThroughputCalculationDelayDuration(duration);
    return (T) this;
  }
}
