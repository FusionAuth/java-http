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
package io.fusionauth.http.security;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * A toolkit for security helper methods.
 *
 * @author Brian Pontarelli
 */
public final class SecurityTools {
  public static final String CERT_END = "-----END CERTIFICATE";

  public static final String CERT_START = "BEGIN CERTIFICATE-----";

  public static final String P8_KEY_END = "-----END PRIVATE KEY";

  public static final String P8_KEY_START = "BEGIN PRIVATE KEY-----";

  // Disable SNI so that it doesn't mess up our use of JSSE with some certificates
  static {
    System.setProperty("jsse.enableSNIExtension", "false");
  }

  private SecurityTools() {
  }

  /**
   * This creates an in-memory trust store containing the certificate and initializes the SSLContext with it.
   *
   * @param certificate A Certificate object.
   * @return A SSLContext configured with the Certificate.
   */
  public static SSLContext clientContext(Certificate certificate) throws GeneralSecurityException, IOException {
    KeyStore keystore = KeyStore.getInstance("JKS");
    keystore.load(null);
    keystore.setCertificateEntry("cert-alias", certificate);

    TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
    tmf.init(keystore);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, tmf.getTrustManagers(), null);
    return context;
  }

  public static Certificate parseCertificate(String certificate) throws CertificateException {
    return parseCertificates(certificate)[0];
  }

  public static Certificate[] parseCertificates(String certificates) throws CertificateException {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    byte[] certBytes = parseDERFromPEM(certificates, CERT_START, CERT_END);
    return factory.generateCertificates(new ByteArrayInputStream(certBytes)).toArray(new Certificate[]{});
  }

  /**
   * Parses all objects in a PEM-formatted string into a byte[].
   */
  public static byte[] parseDERFromPEM(String pem, String beginDelimiter, String endDelimiter) {
    // Allocate an initial buffer to hold 2 certificates.
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream(4000);

    try {
      while (!pem.trim().isEmpty()) {
        int startIndex = pem.indexOf(beginDelimiter);
        if (startIndex < 0) {
          throw new IllegalArgumentException("Invalid PEM format");
        }

        int endIndex = pem.indexOf(endDelimiter);
        if (endIndex < 0) {
          throw new IllegalArgumentException("Invalid PEM format");
        }

        String base64 = pem.substring(startIndex + beginDelimiter.length(), endIndex);

        // Decode current chunk using the MIME decoder to strip whitespace, then skip past chunk.
        outBytes.write(Base64.getMimeDecoder().decode(base64));
        pem = pem.substring(endIndex + endDelimiter.length() + 5);
      }

    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid PEM format");
    }
    return outBytes.toByteArray();
  }

  public static RSAPrivateKey parsePrivateKey(String privateKey) throws InvalidKeySpecException, NoSuchAlgorithmException {
    byte[] keyBytes = parseDERFromPEM(privateKey, P8_KEY_START, P8_KEY_END);
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
    KeyFactory factory = KeyFactory.getInstance("RSA");
    return (RSAPrivateKey) factory.generatePrivate(spec);
  }

  /**
   * This creates an in-memory keystore containing the certificate and private key and initializes the SSLContext with the key material it
   * contains.
   *
   * @param certificate A Certificate object.
   * @param privateKey  A PrivateKey object.
   * @return A SSLContext configured with the Certificate and Private Key.
   */
  public static SSLContext serverContext(Certificate certificate, PrivateKey privateKey) throws GeneralSecurityException, IOException {
    KeyStore keystore = KeyStore.getInstance("JKS");
    keystore.load(null);
    keystore.setCertificateEntry("cert-alias", certificate);
    keystore.setKeyEntry("key-alias", privateKey, "changeit".toCharArray(), new Certificate[]{certificate});

    KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
    kmf.init(keystore, "changeit".toCharArray());

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(kmf.getKeyManagers(), null, null);
    return context;
  }

  public static SSLContext serverContext(Certificate[] certificateChain, PrivateKey privateKey)
      throws GeneralSecurityException, IOException {
    KeyStore keystore = KeyStore.getInstance("JKS");
    keystore.load(null);
    keystore.setKeyEntry("key-alias", privateKey, "changeit".toCharArray(), certificateChain);

    KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
    kmf.init(keystore, "changeit".toCharArray());

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(kmf.getKeyManagers(), null, null);
    return context;
  }
}
