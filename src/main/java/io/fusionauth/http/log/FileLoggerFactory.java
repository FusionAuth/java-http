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
 * Simple implementation of the LoggerFactory that returns various FileLoggers
 *
 * @author Brian Pontarelli
 */
public class FileLoggerFactory implements LoggerFactory {
  public static final FileLoggerFactory FACTORY = new FileLoggerFactory();

  private static FileLogger logger;

  public static void setLogger(FileLogger logger) {
    FileLoggerFactory.logger = logger;
  }

  @Override
  public Logger getLogger(Class<?> klass) {
    return logger;
  }
}
