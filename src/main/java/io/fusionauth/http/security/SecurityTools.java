/*
 * Copyright (c) 2022-2024, FusionAuth, All Rights Reserved
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
import javax.security.auth.x500.X500Principal;
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
import java.util.Collection;
import java.util.HashMap;

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

  /**
   * Parses a single certificate from a PEM string.
   *
   * @param certificateString PEM-formatted certificate text.
   * @return The first {@link Certificate} encoded in the file.
   * @throws CertificateException If unable to parse PEM content.
   */
  public static Certificate parseCertificate(String certificateString) throws CertificateException {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    var is = new ByteArrayInputStream(certificateString.getBytes());
    return factory.generateCertificates(is).stream().findFirst().get();
  }

  /**
   * Parses and re-orders multiple Certificates from a PEM-formatted string into an ordered certificate chain array.
   *
   * @param certificateString the PEM-formatted content of one or more certificates in a chain.
   * @return An array of {@link Certificate}s ordered from the end-entity through each supplied issuer.
   * @throws CertificateException If unable to parse PEM content.
   */
  public static Certificate[] parseCertificates(String certificateString) throws CertificateException {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    var is = new ByteArrayInputStream(certificateString.getBytes());
    var certs = (Collection<X509Certificate>) factory.generateCertificates(is);
    return reorderCertificates(certs);
  }

  /**
   * Returns an array of Certificates ordered starting from the end-entity through each supplied issuer in the List.
   *
   * @param certs The certificates to re-order.
   * @return An array of {@link Certificate}s ordered from the end-entity through each supplied issuer.
   * @throws IllegalArgumentException if certs is empty or missing an intermediate issuer cert.
   */
  private static Certificate[] reorderCertificates(Collection<X509Certificate> certs) {
    if (certs.isEmpty()) {
      throw new IllegalArgumentException("Empty certificate list");
    }

    var orderedCerts = new X509Certificate[certs.size()];

    // Short circuit if only one cert is in collection
    if (certs.size() == 1) {
      orderedCerts[0] = certs.stream().findFirst().get();
      return orderedCerts;
    }

    // Index certificates by Issuer and Subject
    var certsByIssuer = new HashMap<X500Principal, X509Certificate>(certs.size());
    var certsBySubject = new HashMap<X500Principal, X509Certificate>(certs.size());

    // Load all certificates into maps
    for (X509Certificate cert : certs) {
      certsByIssuer.put(cert.getIssuerX500Principal(), cert);
      certsBySubject.put(cert.getSubjectX500Principal(), cert);
    }

    // Find the server certificate. It's the one that no other certificate refers to as its issuer. Store it in the first array element.
    for (var cert : certs) {
      if (!certsByIssuer.containsKey(cert.getSubjectX500Principal())) {
        orderedCerts[0] = cert;
        break;
      }
    }

    // Start at the server cert and add each issuer certificate in order.
    for (int i = 0; i < orderedCerts.length - 1; i++) {
      var issuer = certsBySubject.get(orderedCerts[i].getIssuerX500Principal());
      if (issuer == null) {
        throw new IllegalArgumentException("Missing issuer cert for " + orderedCerts[i].getIssuerX500Principal());
      }
      orderedCerts[i + 1] = issuer;
    }

    return orderedCerts;
  }


  /**
   * Parses a single object in a PEM-formatted string into a byte[].
   */
  public static byte[] parseDERFromPEM(String pem, String beginDelimiter, String endDelimiter) {
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

  /**
   * This creates an in-memory keystore containing the certificate chain and private key and initializes the SSLContext with the key
   * material it contains.
   *
   * @param certificateChain The chain of certificates to include in the TLS negotiation. Should be ordered by end-entity first.
   * @param privateKey       The PrivateKey corresponding to the end-entity certificate in the chain.
   * @return A SSLContext configured with the Certificate and Private Key.
   */
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
