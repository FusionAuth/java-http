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
package io.fusionauth.http.server;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

import io.fusionauth.http.security.SecurityTools;

/**
 * A listener configuration that is used to construct the HTTP server and bind various listeners.
 *
 * @author Brian Pontarelli
 */
public class HTTPListenerConfiguration {
  private final InetAddress bindAddress;

  private final Certificate[] certificateChain;

  private final int port;

  private final PrivateKey privateKey;

  private final boolean tls;

  /**
   * Stores the configuration for a single HTTP listener for the server. This constructor sets up a non-TLS based listener that binds to all
   * the network interfaces of the server.
   *
   * @param port The port of this listener.
   */
  public HTTPListenerConfiguration(int port) {
    this.bindAddress = allInterfaces();
    this.port = port;
    this.tls = false;
    this.certificateChain = null;
    this.privateKey = null;
  }

  /**
   * Stores the configuration for a single HTTP listener for the server. This constructor sets up a TLS based listener.
   *
   * @param port        The port of this listener.
   * @param certificate The certificate as a PEM encoded X.509 certificate String. May include intermediate CA certificates.
   * @param privateKey  The private key as a PKCS8 encoded DER private key.
   * @throws GeneralSecurityException If the private key or certificate Strings were not valid and could not be parsed.
   */
  public HTTPListenerConfiguration(int port, String certificate, String privateKey) throws GeneralSecurityException {
    Objects.requireNonNull(certificate);
    Objects.requireNonNull(privateKey);

    this.bindAddress = allInterfaces();
    this.port = port;
    this.tls = true;
    this.certificateChain = SecurityTools.parseCertificates(certificate);
    this.privateKey = SecurityTools.parsePrivateKey(privateKey);
  }

  /**
   * Stores the configuration for a single HTTP listener for the server. This constructor sets up a TLS based listener.
   *
   * @param port        The port of this listener.
   * @param certificate The certificate Object.
   * @param privateKey  The private key Object.
   */
  public HTTPListenerConfiguration(int port, Certificate certificate, PrivateKey privateKey) {
    Objects.requireNonNull(certificate);
    Objects.requireNonNull(privateKey);

    this.bindAddress = allInterfaces();
    this.port = port;
    this.tls = true;
    this.certificateChain = new Certificate[]{certificate};
    this.privateKey = privateKey;
  }

  /**
   * Stores the configuration for a single HTTP listener for the server. This constructor sets up a TLS based listener using the supplied
   * certificate chain.
   *
   * @param port             The port of this listener.
   * @param certificateChain The certificate Object.
   * @param privateKey       The private key Object.
   */
  public HTTPListenerConfiguration(int port, Certificate[] certificateChain, PrivateKey privateKey) {
    Objects.requireNonNull(certificateChain);
    Objects.requireNonNull(privateKey);

    this.bindAddress = allInterfaces();
    this.port = port;
    this.tls = true;
    this.certificateChain = certificateChain;
    this.privateKey = privateKey;
  }

  /**
   * Stores the configuration for a single HTTP listener for the server. This constructor sets up a non-TLS based listener.
   *
   * @param bindAddress The bind address of this listener.
   * @param port        The port of this listener.
   */
  public HTTPListenerConfiguration(InetAddress bindAddress, int port) {
    Objects.requireNonNull(bindAddress);

    this.bindAddress = bindAddress;
    this.port = port;
    this.tls = false;
    this.certificateChain = null;
    this.privateKey = null;
  }

  /**
   * Stores the configuration for a single HTTP listener for the server. This constructor sets up a TLS based listener.
   *
   * @param bindAddress The bind address of this listener.
   * @param port        The port of this listener.
   * @param certificate The certificate as a PEM ecnoded X.509 certificate String.
   * @param privateKey  The private key as a PKCS8 encoded DER private key.
   * @throws GeneralSecurityException If the private key or certificate Strings were not valid and could not be parsed.
   */
  public HTTPListenerConfiguration(InetAddress bindAddress, int port, String certificate, String privateKey)
      throws GeneralSecurityException {
    Objects.requireNonNull(bindAddress);
    Objects.requireNonNull(certificate);
    Objects.requireNonNull(privateKey);

    this.bindAddress = bindAddress;
    this.port = port;
    this.tls = true;
    this.certificateChain = SecurityTools.parseCertificates(certificate);
    this.privateKey = SecurityTools.parsePrivateKey(privateKey);
  }

  /**
   * Stores the configuration for a single HTTP listener for the server. This constructor sets up a TLS based listener.
   *
   * @param bindAddress The bind address of this listener.
   * @param port        The port of this listener.
   * @param certificate The certificate Object.
   * @param privateKey  The private key Object.
   */
  public HTTPListenerConfiguration(InetAddress bindAddress, int port, Certificate certificate, PrivateKey privateKey) {
    Objects.requireNonNull(bindAddress);
    Objects.requireNonNull(certificate);
    Objects.requireNonNull(privateKey);

    this.bindAddress = bindAddress;
    this.port = port;
    this.tls = true;
    this.certificateChain = new Certificate[]{certificate};
    this.privateKey = privateKey;
  }

  public InetAddress getBindAddress() {
    return bindAddress;
  }

  public Certificate getCertificate() {
    if (certificateChain != null && certificateChain.length > 0) {
      return certificateChain[0];
    } else {
      return null;
    }
  }

  public Certificate[] getCertificateChain() {
    return certificateChain;
  }

  public int getPort() {
    return port;
  }

  public PrivateKey getPrivateKey() {
    return privateKey;
  }

  public boolean isTLS() {
    return tls;
  }

  private InetAddress allInterfaces() {
    try {
      boolean ipv6Supported = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                                         .map(NetworkInterface::getInterfaceAddresses)
                                         .flatMap(Collection::stream)
                                         .map(InterfaceAddress::getAddress)
                                         .anyMatch(i -> !i.isLoopbackAddress() && i instanceof Inet6Address);
      if (ipv6Supported) {
        return InetAddress.getByName("::");
      }

      return InetAddress.getByName("0.0.0.0");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
