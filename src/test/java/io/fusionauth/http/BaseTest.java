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
package io.fusionauth.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.CookieHandler;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertPath;
import java.security.cert.CertPathParameters;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Collectors;

import io.fusionauth.http.log.Level;
import io.fusionauth.http.log.SystemOutLogger;
import io.fusionauth.http.log.SystemOutLoggerFactory;
import io.fusionauth.http.security.SecurityTools;
import io.fusionauth.http.server.ExpectValidator;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServer;
import io.fusionauth.http.server.Instrumenter;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.DataProvider;
import sun.security.util.KnownOIDs;
import sun.security.util.ObjectIdentifier;
import sun.security.x509.AlgorithmId;
import sun.security.x509.BasicConstraintsExtension;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateExtensions;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateValidity;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.DNSName;
import sun.security.x509.GeneralName;
import sun.security.x509.GeneralNames;
import sun.security.x509.SubjectAlternativeNameExtension;
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
  /**
   * This timeout is used for the HttpClient during each test. If you are in a debugger, you will need to change this timeout to be much
   * larger, otherwise, the client might truncate the request to the server.
   */
  public static final Duration ClientTimeout = Duration.ofSeconds(2);

  /**
   * This timeout is used for the HTTPServer during each test. If you are in a debugger, you will need to change this timeout to be much
   * larger, otherwise, the server will toss out the request.
   */
  public static final Duration ServerTimeout = Duration.ofSeconds(2);

  private static final ZonedDateTime TestStarted = ZonedDateTime.now();

  private static final DateTimeFormatter hh_mm_ss_SSS = DateTimeFormatter.ofPattern("hh:mm:ss.SSS");

  public static SystemOutLogger logger = (SystemOutLogger) SystemOutLoggerFactory.FACTORY.getLogger(BaseTest.class);

  /*
   * Keypairs and certificates for a 3-level CA chain (root->intermediate->server).
   */
  public static Certificate certificate;

  public static Certificate intermediateCertificate;

  public static KeyPair intermediateKeyPair;

  public static KeyPair keyPair;

  public static Certificate rootCertificate;

  public static KeyPair rootKeyPair;

  static {
    logger.setLevel(Level.Trace);
    System.setProperty("sun.net.http.retryPost", "false");
    System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection");
  }

  public HttpClient makeClient(String scheme, CookieHandler cookieHandler) throws GeneralSecurityException, IOException {
    var builder = HttpClient.newBuilder();
    if (scheme.equals("https")) {
      builder.sslContext(SecurityTools.clientContext(rootCertificate));
    }

    if (cookieHandler != null) {
      builder.cookieHandler(cookieHandler);
    }

    return builder.connectTimeout(ClientTimeout).build();
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
      var certChain = new Certificate[]{certificate, intermediateCertificate};
      listenerConfiguration = new HTTPListenerConfiguration(4242, certChain, keyPair.getPrivate());
    } else {
      listenerConfiguration = new HTTPListenerConfiguration(4242);
    }

    return new HTTPServer().withHandler(handler)
                           .withKeepAliveTimeoutDuration(ServerTimeout)
                           .withInitialReadTimeout(ServerTimeout)
                           .withProcessingTimeoutDuration(ServerTimeout)
                           .withExpectValidator(expectValidator)
                           .withInstrumenter(instrumenter)
                           .withLoggerFactory(SystemOutLoggerFactory.FACTORY)
                           .withMinimumReadThroughput(200 * 1024)
                           .withMinimumWriteThroughput(200 * 1024)
                           .withListener(listenerConfiguration)
                           .withReadThroughputCalculationDelayDuration(Duration.ofSeconds(1))
                           .withWriteThroughputCalculationDelayDuration(Duration.ofSeconds(1));
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

  @AfterSuite(groups = "acceptance")
  public void tearDown() {
    System.out.println("\nTests began : " + hh_mm_ss_SSS.format(TestStarted));
    System.out.println("Tests ended : " + hh_mm_ss_SSS.format(ZonedDateTime.now()));
    System.out.println("Total test time in minutes : " + Duration.between(TestStarted, ZonedDateTime.now()).toMinutes());
  }

  protected static X509CertInfo generateCertInfo(PublicKey publicKey, String commonName) {
    try {
      X509CertInfo certInfo = new X509CertInfo();
      CertificateX509Key certKey = new CertificateX509Key(publicKey);
      certInfo.setKey(certKey);
      // X.509 Certificate version 3 (0 based)
      certInfo.setVersion(new CertificateVersion(2));
      certInfo.setAlgorithmId(new CertificateAlgorithmId(new AlgorithmId(ObjectIdentifier.of(KnownOIDs.SHA256withRSA))));
      certInfo.setSubject(new X500Name("CN=" + commonName));
      certInfo.setValidity(new CertificateValidity(Date.from(Instant.now().minusSeconds(30)), Date.from(Instant.now().plusSeconds(10_000))));
      certInfo.setSerialNumber(new CertificateSerialNumber(new BigInteger(UUID.randomUUID().toString().replace("-", ""), 16)));

      return certInfo;
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  protected static KeyPair generateNewRSAKeyPair() {
    try {
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(4096);
      return keyPairGenerator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  protected static Certificate generateRootCA(PublicKey publicKey, PrivateKey privateKey)
      throws IllegalArgumentException {
    try {
      // Generate the standard CertInfo, but set Issuer and Subject to the same value.
      X509CertInfo certInfo = generateCertInfo(publicKey, "root-ca.fusionauth.io");
      certInfo.setIssuer(new X500Name("CN=root-ca.fusionauth.io"));

      // Self-sign certificate
//      return signCertificate(new X509CertImpl(certInfo.getEncodedInfo()), privateKey, certInfo, true);
      return X509CertImpl.newSigned(certInfo, privateKey, "SHA256withRSA");
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * Generates keypairs and certificates for Root CA -> Intermediate -> Server Certificate.
   */
  @BeforeSuite
  public static void setupCertificates() {
    rootKeyPair = generateNewRSAKeyPair();
    intermediateKeyPair = generateNewRSAKeyPair();
    keyPair = generateNewRSAKeyPair();

    // Build root and intermediate CAs
    rootCertificate = generateRootCA(rootKeyPair.getPublic(), rootKeyPair.getPrivate());
    X509CertInfo intermediateCertInfo = generateCertInfo(intermediateKeyPair.getPublic(), "intermediate.fusionauth.io");
    intermediateCertificate = signCertificate((X509Certificate) rootCertificate, rootKeyPair.getPrivate(), intermediateCertInfo, true);

    // Build server cert
    X509CertInfo serverCertInfo = generateCertInfo(keyPair.getPublic(), "local.fusionauth.io");
    certificate = signCertificate((X509Certificate) intermediateCertificate, intermediateKeyPair.getPrivate(), serverCertInfo, false);
  }

  protected static X509Certificate signCertificate(X509Certificate issuer, PrivateKey issuerPrivateKey, X509CertInfo signingRequest, boolean isCa)
      throws IllegalArgumentException {

    try {
      X509CertInfo issuerInfo = new X509CertInfo(issuer.getTBSCertificate());
      signingRequest.setIssuer(issuerInfo.getSubject());

      CertificateExtensions certExtensions = new CertificateExtensions();
      if (isCa) {
        certExtensions.setExtension(BasicConstraintsExtension.NAME, new BasicConstraintsExtension(true, true, 1));
      }

      // Set the Subject Alternate Names field to the DNS hostname.
      X500Name subject = signingRequest.getSubject();
      String hostname = subject.getCommonName();
      GeneralNames altNames = new GeneralNames();
      altNames.add(new GeneralName(new DNSName(hostname)));
      certExtensions.setExtension(SubjectAlternativeNameExtension.NAME, new SubjectAlternativeNameExtension(false, altNames));
      signingRequest.setExtensions(certExtensions);

      // Sign it
      return X509CertImpl.newSigned(signingRequest, issuerPrivateKey, "SHA256withRSA");
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * Verifies that the chain certificates can be validated up to the supplied root certificate. See
   * {@link CertPathValidator#validate(CertPath, CertPathParameters)} for details.
   */
  protected void validateCertPath(Certificate root, Certificate[] chain)
      throws CertPathValidatorException, InvalidAlgorithmParameterException {

    CertPathValidator validator;
    CertPath certPath;
    PKIXParameters pkixParameters;

    try {
      var certificateFactory = CertificateFactory.getInstance("X.509");
      certPath = certificateFactory.generateCertPath(Arrays.asList(chain));

      // Create a trustStore with only the root installed
      var trustStore = KeyStore.getInstance("JKS");
      trustStore.load(null);
      trustStore.setCertificateEntry("root-ca", root);

      pkixParameters = new PKIXParameters(trustStore);
      pkixParameters.setRevocationEnabled(false);
      validator = CertPathValidator.getInstance("PKIX");
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
    // validate() will throw an exception if any check fails.
    validator.validate(certPath, pkixParameters);
  }

  @SuppressWarnings("unused")
  public static class TestListener implements ITestListener {
    private int counter = 0;

    private String lastTestMethod;

    private int lastTestMethodCounter = 0;

    @Override
    public void onTestFailure(ITestResult result) {
      Throwable throwable = result.getThrowable();

      // Intentionally leaving empty lines here
      System.out.println("""
                                 
                
                                 
          Test failure
          -----------------
          Exception: {{exception}}
          Message: {{message}}
                               
          HTTP Trace:
          {{trace}}
          -----------------
           """.replace("{{exception}}", throwable != null ? throwable.getClass().getSimpleName() : "-")
              .replace("{{message}}", throwable != null ? (throwable.getMessage() != null ? throwable.getMessage() : "-") : "-"));
    }

    @Override
    public void onTestStart(ITestResult result) {
      Object[] dataProvider = result.getParameters();
      String iteration = dataProvider != null && dataProvider.length > 0
          ? " [" + serializeDataProviderArgs(dataProvider) + "]"
          : "";

      // Still missing the factory data provider, for example when we re-run tests as GraalJS or Nashorn, I don't yet have a way to show that in this output.
      // - But TestNG can do it - so we can too! Just need to figure it out.
      String testMethod = result.getTestClass().getName() + "." + result.getName();
      if (lastTestMethod != null && !lastTestMethod.equals(testMethod)) {
        lastTestMethodCounter = 0;
      }

      if (!iteration.isEmpty()) {
        iteration += " (" + ++lastTestMethodCounter + ")";
      }

      lastTestMethod = testMethod;
      // Trying to replicate the name of the test in the IJ TestNG runner.
      System.out.println("[" + ++counter + "] " + hh_mm_ss_SSS.format(ZonedDateTime.now()) + " " + testMethod + iteration);
    }

    private String serializeDataProviderArgs(Object[] dataProvider) {
      String result = Arrays.stream(dataProvider)
                            .map(o -> (o == null ? "null" : o.toString()).replace("\n", " "))
                            .collect(Collectors.joining(", "));

      int maxLength = 128;
      if (result.length() > maxLength) {
        if (result.charAt(maxLength) == ',') {
          maxLength -= 1;
        }

        //noinspection UnnecessaryUnicodeEscape
        result = result.substring(0, maxLength) + "\u2026";
      }

      return result;
    }
  }
}
