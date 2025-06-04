/*
 * Copyright (c) 2022-2025, FusionAuth, All Rights Reserved
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
@SuppressWarnings({"unchecked", "UnusedReturnValue", "unused"})
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
   * Sets the buffer size for the chunked input stream. Defaults to 2k.
   *
   * @param chunkedBufferSize the buffer size used to read a request body that was encoded using 'chunked' transfer-encoding.
   * @return This.
   */
  default T withChunkedBufferSize(int chunkedBufferSize) {
    configuration().withChunkedBufferSize(chunkedBufferSize);
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
   * <p>
   * Must not be null.
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
   * <p>
   * Must not be null.
   *
   * @param handler The handler that processes the requests.
   * @return This.
   */
  default T withHandler(HTTPHandler handler) {
    configuration().withHandler(handler);
    return (T) this;
  }

  /**
   * Sets the duration that the server will attempt to read the first byte from a client. This is the very first byte after the socket
   * connection has been accepted by the server. Defaults to 2 seconds.
   *
   * @param duration The duration.
   * @return This.
   */
  default T withInitialReadTimeout(Duration duration) {
    configuration().withInitialReadTimeout(duration);
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
   * Sets the duration that the server will allow client connections to remain open and idle after each request has been processed. This is
   * the Keep-Alive state before the first byte of the next request is read. Defaults to 20 seconds.
   *
   * @param duration The duration.
   * @return This.
   */
  default T withKeepAliveTimeoutDuration(Duration duration) {
    configuration().withKeepAliveTimeoutDuration(duration);
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
   * Sets the maximum number of pending socket connections per HTTP listener.
   * <p>
   * This number represents how many pending socket connections are allowed to queue before they are rejected. Once the connection is
   * accepted by the server socket, a client socket is created and handed to an HTTP Worker. This queue length only needs to be large enough
   * to buffer the incoming requests as fast as we can accept them and hand them to a worker.
   * <p>
   * Defaults to 200.
   *
   * @return This.
   */
  default T withMaxPendingSocketConnections(int maxPendingSocketConnections) {
    configuration().withMaxPendingSocketConnections(maxPendingSocketConnections);
    return (T) this;
  }

  /**
   * Sets the base directory for this server. This is passed to the HTTPContext, which is available from this class. This defaults to the
   * current working directory of the process. Defaults to 100,000.
   *
   * @param maxRequestsPerConnection The maximum number of requests that can be handled by a single persistent connection.
   * @return This.
   */
  default T withMaxRequestsPerConnection(int maxRequestsPerConnection) {
    configuration().withMaxRequestsPerConnection(maxRequestsPerConnection);
    return (T) this;
  }

  /**
   * This configures the maximum size of a chunk in the response when the server is using chunked response encoding. Defaults to 16k.
   *
   * @param size The size in bytes.
   * @return This.
   */
  default T withMaxResponseChunkSize(int size) {
    configuration().withMaxResponseChunkSize(size);
    return (T) this;
  }

  /**
   * Sets the maximum number of bytes the server will allow worker threads to drain after calling the request handler. If the request
   * handler does not read all the bytes, and this limit is exceeded the connection will be closed. Defaults to 128k bytes.
   *
   * @param maxBytesToDrain The maximum number of bytes to drain from the InputStream if the request handler did not read all the available
   *                        bytes.
   * @return This.
   */
  default T withMaximumBytesToDrain(int maxBytesToDrain) {
    configuration().withMaximumBytesToDrain(maxBytesToDrain);
    return (T) this;
  }

  /**
   * This configures the minimum number of bytes per second that a client must send a request to the server before the server closes the
   * connection. Set this to -1 to disable this check.
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
   * the connection. Set this to -1 to disable this check.
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
   * Sets the duration that the server will allow worker threads to run after the final request byte is read and before the first response
   * byte is written. Defaults to 10 seconds.
   *
   * @param duration The duration.
   * @return This.
   */
  default T withProcessingTimeoutDuration(Duration duration) {
    configuration().withProcessingTimeoutDuration(duration);
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
   * Sets the size of the buffer that is used to store the HTTP response before any bytes are written back to the client. This is useful
   * when the server is generating the response but encounters an error. In this case, the server will throw out the response and change to
   * a 500 error response. This defaults to 64k. Negative values disable the response buffer.
   *
   * @param responseBufferSize The size of the buffer. Set to -1 to disable buffering completely.
   * @return This.
   */
  default T withResponseBufferSize(int responseBufferSize) {
    configuration().withResponseBufferSize(responseBufferSize);
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
