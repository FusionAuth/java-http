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
package io.fusionauth.http.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.fusionauth.http.log.LoggerFactory;
import io.fusionauth.http.log.SystemOutLoggerFactory;

/**
 * @author Daniel DeGroff
 */
public class HTTPClientConfiguration implements Configurable<HTTPClientConfiguration> {
  public final Map<String, List<String>> headers = new HashMap<>();

  private LoggerFactory loggerFactory = SystemOutLoggerFactory.FACTORY;

  private Duration socketTimeoutDuration = Duration.ofSeconds(20);

  @Override
  public HTTPClientConfiguration addHeader(String name, String value) {
    headers.computeIfAbsent(name.toLowerCase(), key -> new ArrayList<>()).add(value);
    return this;
  }

  @Override
  public HTTPClientConfiguration configuration() {
    return this;
  }

  public LoggerFactory getLoggerFactory() {
    return loggerFactory;
  }

  public Duration getSocketTimeoutDuration() {
    return socketTimeoutDuration;
  }

  @Override
  public HTTPClientConfiguration withLoggerFactory(LoggerFactory loggerFactory) {
    Objects.requireNonNull(loggerFactory, "You cannot set LoggerFactory to null");
    this.loggerFactory = loggerFactory;
    return this;
  }
}
