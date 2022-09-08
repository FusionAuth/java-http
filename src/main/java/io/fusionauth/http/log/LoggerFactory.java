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
package io.fusionauth.http.log;

/**
 * A simple interface used by the HTTP server/client instances to create loggers. This removes any coupling between the HTTP server and
 * specific logging frameworks like JUL or SLF4J. Mapping between this logger and other frameworks is simple though.
 *
 * @author Brian Pontarelli
 */
public interface LoggerFactory {
  /**
   * Get the logger for the given class.
   *
   * @param klass The class.
   * @return The Logger and never null.
   */
  Logger getLogger(Class<?> klass);
}
