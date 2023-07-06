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
package io.fusionauth.http.log;

/**
 * A simple logging interface used by the HTTP server/client instances. This removes any coupling between the HTTP server and specific
 * logging frameworks like JUL or SLF4J. Mapping between this logger and other frameworks is simple though.
 *
 * @author Brian Pontarelli
 */
public interface Logger {
  /**
   * Logs a debug message.
   *
   * @param message The message.
   */
  void debug(String message);

  /**
   * Logs a debug message with values.
   *
   * @param message The message.
   * @param values  The values for the message.
   */
  void debug(String message, Object... values);

  /**
   * Logs a debug message and stack trace or exception message.
   *
   * @param message   The message.
   * @param throwable The exception for the stack trace or message.
   */
  void debug(String message, Throwable throwable);

  /**
   * Logs an error message and stack trace or exception message.
   *
   * @param message   The message.
   * @param throwable The exception for the stack trace or message.
   */
  void error(String message, Throwable throwable);

  /**
   * Logs an error message.
   *
   * @param message The message.
   */
  void error(String message);

  /**
   * Logs an info message.
   *
   * @param message The message.
   */
  void info(String message);

  /**
   * Logs an info message with values.
   *
   * @param message The message.
   * @param values  The values for the message.
   */
  void info(String message, Object... values);

  /**
   * @return True if this Logger is enabled for the Debug level, false otherwise.
   */
  default boolean isDebugEnabled() {
    return false;
  }

  /**
   * Deprecated. Prefer the use of {@link #isDebugEnabled()}.
   *
   * @return If this logger has debug enabled.
   */
  @Deprecated
  default boolean isDebuggable() {
    return isDebugEnabled();
  }

  /**
   * Returns whether this Logger is enabled for a given {@link Level}.
   *
   * @param level the level to check
   * @return true if enabled, false otherwise.
   */
  default boolean isEnabledForLevel(Level level) {
    return switch (level) {
      case Trace -> isTraceEnabled();
      case Debug -> isDebugEnabled();
      case Info -> isInfoEnabled();
      case Error -> isErrorEnabled();
    };
  }

  /**
   * @return True if this Logger is enabled for the Error level, false otherwise.
   */
  default boolean isErrorEnabled() {
    return false;
  }

  /**
   * @return True if this Logger is enabled for the Info level, false otherwise.
   */
  default boolean isInfoEnabled() {
    return false;
  }

  /**
   * @return True if this Logger is enabled for the Trace level, false otherwise.
   */
  default boolean isTraceEnabled() {
    return false;
  }

  /**
   * Sets the level of this logger (optional method).
   *
   * @param level The level.
   */
  void setLevel(Level level);

  /**
   * Logs a trace message with values.
   *
   * @param message The message.
   * @param values  The values for the message.
   */
  void trace(String message, Object... values);

  /**
   * Logs a trace message.
   *
   * @param message The message.
   */
  void trace(String message);
}
