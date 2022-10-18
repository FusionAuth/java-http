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
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.CookieHandler;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import io.fusionauth.http.log.AccumulatingLogger;
import io.fusionauth.http.log.AccumulatingLoggerFactory;
import io.fusionauth.http.log.Level;
import io.fusionauth.http.security.SecurityTools;
import io.fusionauth.http.server.ExpectValidator;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServer;
import io.fusionauth.http.server.Instrumenter;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import sun.misc.Signal;
import sun.security.util.KnownOIDs;
import sun.security.util.ObjectIdentifier;
import sun.security.x509.AlgorithmId;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateValidity;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.X500Name;
import sun.security.x509.X509CertImpl;
import sun.security.x509.X509CertInfo;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

/**
 * Base class for tests in order to provide data providers and other reusable code.
 *
 * @author Brian Pontarelli
 */
public abstract class BaseTest {
  public Certificate certificate;

  public KeyPair keyPair;

  public static AccumulatingLogger logger = (AccumulatingLogger) AccumulatingLoggerFactory.FACTORY.getLogger(BaseTest.class);

  static {
    logger.setLevel(Level.Trace);
    Signal.handle(new Signal("USR1"), signal -> {
      try {
        Path logFile = Path.of("build/logs.txt");
        if (Files.isRegularFile(logFile)) {
          Files.delete(logFile);
        }

        Files.createFile(logFile);
        Files.writeString(logFile, logger.toString());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  public HttpClient makeClient(String scheme, CookieHandler cookieHandler) throws GeneralSecurityException, IOException {
    var builder = HttpClient.newBuilder();
    if (scheme.equals("https")) {
      builder.sslContext(SecurityTools.clientContext(certificate));
    }

    if (cookieHandler != null) {
      builder.cookieHandler(cookieHandler);
    }

    return builder.build();
  }

  public HTTPServer makeServer(String scheme, HTTPHandler handler, Instrumenter instrumenter) {
    return makeServer(scheme, handler, instrumenter, null);
  }

  public HTTPServer makeServer(String scheme, HTTPHandler handler) {
    return makeServer(scheme, handler, null);
  }

  @SuppressWarnings("resource")
  public HTTPServer makeServer(String scheme, HTTPHandler handler, Instrumenter instrumenter, ExpectValidator expectValidator) {
    boolean tls = scheme.equals("https");
    HTTPListenerConfiguration listenerConfiguration;
    if (tls) {
      keyPair = generateNewRSAKeyPair();
      certificate = generateSelfSignedCertificate(keyPair.getPublic(), keyPair.getPrivate());
      listenerConfiguration = new HTTPListenerConfiguration(4242, certificate, keyPair.getPrivate());
    } else {
      listenerConfiguration = new HTTPListenerConfiguration(4242);
    }

    return new HTTPServer().withHandler(handler)
                           .withClientTimeout(Duration.ofSeconds(600_000L))
                           .withExpectValidator(expectValidator)
                           .withInstrumenter(instrumenter)
                           .withLoggerFactory(AccumulatingLoggerFactory.FACTORY)
                           .withNumberOfWorkerThreads(1)
                           .withListener(listenerConfiguration);
  }

  public URI makeURI(String scheme, String params) {
    if (scheme.equals("https")) {
      return URI.create("https://local.fusionauth.io:4242/api/system/version" + params);
    }

    return URI.create("http://localhost:4242/api/system/version" + params);
  }

  @BeforeMethod
  public void resetLogger() {
    logger.reset();
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

  public void sendBadRequest(String message) {
    try (Socket socket = new Socket("127.0.0.1", 4242); OutputStream os = socket.getOutputStream(); InputStream is = socket.getInputStream()) {
      os.write(message.getBytes());
      os.flush();

      // Sockets are pretty resilient, so this will be closed by the server, but we'll just see that close are zero bytes read. If we were
      // to continue writing above, then that likely would throw an exception because the pipe would be broken
      byte[] buffer = is.readAllBytes();
      assertEquals(buffer.length, 0);
    } catch (Exception e) {
      fail(e.getMessage());
    }
  }

  protected KeyPair generateNewRSAKeyPair() {
    try {
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(2048);
      return keyPairGenerator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  protected Certificate generateSelfSignedCertificate(PublicKey publicKey, PrivateKey privateKey)
      throws IllegalArgumentException {
    try {
      X509CertInfo certInfo = new X509CertInfo();
      CertificateX509Key certKey = new CertificateX509Key(publicKey);
      certInfo.set(X509CertInfo.KEY, certKey);
      // X.509 Certificate version 2 (0 based)
      certInfo.set(X509CertInfo.VERSION, new CertificateVersion(1));
      certInfo.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(new AlgorithmId(ObjectIdentifier.of(KnownOIDs.SHA256withRSA))));
      certInfo.set(X509CertInfo.ISSUER, new X500Name("CN=local.fusionauth.io"));
      certInfo.set(X509CertInfo.SUBJECT, new X500Name("CN=local.fusionauth.io"));
      certInfo.set(X509CertInfo.VALIDITY, new CertificateValidity(Date.from(Instant.now().minusSeconds(30)), Date.from(Instant.now().plusSeconds(10_000))));
      certInfo.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(new BigInteger(UUID.randomUUID().toString().replace("-", ""), 16)));

      X509CertImpl impl = new X509CertImpl(certInfo);
      impl.sign(privateKey, "SHA256withRSA");
      return impl;
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  @SuppressWarnings("unused")
  public static class TestListener implements ITestListener {
    @Override
    public void onTestFailure(ITestResult result) {
      result.getThrowable().printStackTrace();
    }

    @Override
    public void onTestStart(ITestResult result) {
      System.out.println("Running " + result.getTestClass().getName() + "#" + result.getName());
    }
  }
}
