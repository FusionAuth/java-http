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

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
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
   * This creates an in-memory keystore containing the certificate and private key and initializes the SSLContext with the key material it
   * contains.
   *
   * @param certificateString A PEM formatted Certificate.
   * @param keyString         A PKCS8 PEM formatted Private Key.
   * @return A SSLContext configured with the Certificate and Private Key.
   */
  public static SSLContext getServerContext(String certificateString, String keyString) throws GeneralSecurityException, IOException {
    byte[] certBytes = parseDERFromPEM(certificateString, CERT_START, CERT_END);
    byte[] keyBytes = parseDERFromPEM(keyString, P8_KEY_START, P8_KEY_END);

    X509Certificate cert = generateCertificateFromDER(certBytes);
    PrivateKey key = generatePrivateKeyFromPKCS8DER(keyBytes);
    KeyStore keystore = KeyStore.getInstance("JKS");
    keystore.load(null);
    keystore.setCertificateEntry("cert-alias", cert);
    keystore.setKeyEntry("key-alias", key, "changeit".toCharArray(), new Certificate[]{cert});

    KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
    kmf.init(keystore, "changeit".toCharArray());

    KeyManager[] km = kmf.getKeyManagers();

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(km, null, null);
    return context;
  }

  private static X509Certificate generateCertificateFromDER(byte[] certBytes) throws CertificateException {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(certBytes));
  }

  private static RSAPrivateKey generatePrivateKeyFromPKCS8DER(byte[] keyBytes) throws InvalidKeySpecException, NoSuchAlgorithmException {
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
    KeyFactory factory = KeyFactory.getInstance("RSA");
    return (RSAPrivateKey) factory.generatePrivate(spec);
  }

  private static byte[] parseDERFromPEM(String pem, String beginDelimiter, String endDelimiter) {
    int startIndex = pem.indexOf(beginDelimiter);
    if (startIndex < 0) {
      throw new IllegalArgumentException("Invalid PEM format");
    }

    int endIndex = pem.indexOf(endDelimiter);
    if (endIndex < 0) {
      throw new IllegalArgumentException("Invalid PEM format");
    }

    // Strip all the whitespace since the PEM and DER allow them but they aren't valid in Base 64 encoding
    String base64 = pem.substring(startIndex + beginDelimiter.length(), endIndex).replaceAll("\\s", "");
    return Base64.getDecoder().decode(base64);
  }
}
