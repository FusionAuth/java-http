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
package io.fusionauth.http;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;

import io.fusionauth.http.server.ExpectValidator;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServer;
import io.fusionauth.http.server.Instrumenter;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.DataProvider;

/**
 * Base class for tests in order to provide data providers and other reusable code.
 *
 * @author Brian Pontarelli
 */
public class BaseTest {
  public static String certificate;

  public static String privateKey;

  @BeforeSuite
  public static void loadFiles() throws IOException {
    String homeDir = System.getProperty("user.home");
    certificate = Files.readString(Paths.get(homeDir + "/dev/certificates/fusionauth.pem"));
    privateKey = Files.readString(Paths.get(homeDir + "/dev/certificates/fusionauth.key"));
  }

  public HTTPServer makeServer(String scheme, HTTPHandler handler) {
    return makeServer(scheme, handler, null);
  }

  public HTTPServer makeServer(String scheme, HTTPHandler handler, Instrumenter instrumenter) {
    return makeServer(scheme, handler, instrumenter, null);
  }

  @SuppressWarnings("resource")
  public HTTPServer makeServer(String scheme, HTTPHandler handler, Instrumenter instrumenter, ExpectValidator expectValidator) {
    boolean tls = scheme.equals("https");
    HTTPListenerConfiguration listenerConfiguration;
    if (tls) {
      listenerConfiguration = new HTTPListenerConfiguration(4242, certificate, privateKey);
    } else {
      listenerConfiguration = new HTTPListenerConfiguration(4242);
    }

    return new HTTPServer().withHandler(handler)
                           .withClientTimeout(Duration.ofSeconds(600_000L))
                           .withExpectValidator(expectValidator)
                           .withInstrumenter(instrumenter)
                           .withNumberOfWorkerThreads(1)
                           .withListener(listenerConfiguration);
  }

  public URI makeURI(String scheme, String params) {
    if (scheme.equals("https")) {
      return URI.create("https://local.fusionauth.io:4242/api/system/version" + params);
    }

    return URI.create("http://localhost:4242/api/system/version" + params);
  }

  /**
   * @return The possible schemes - {@code http} and {@code https}.
   */
  @DataProvider
  public Object[][] schemes() {
    return new Object[][]{
        {"http"},
        {"https"}
    };
  }
}
