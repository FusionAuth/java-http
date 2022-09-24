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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * A listener configuration that is used to construct the HTTP server and bind various listeners.
 *
 * @author Brian Pontarelli
 */
public class HTTPListenerConfiguration {
  private final InetAddress bindAddress;

  private final String certificate;

  private final int port;

  private final String privateKey;

  private final boolean tls;

  /**
   * Stores the configuration for a single HTTP listener for the server. This constructor sets up a non-TLS based listener that binds to all
   * the network interfaces of the server.
   *
   * @param port The port of this listener.
   */
  public HTTPListenerConfiguration(int port) {
    try {
      this.bindAddress = InetAddress.getByName("::");
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }

    this.port = port;
    this.tls = false;
    this.certificate = null;
    this.privateKey = null;
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
    this.certificate = null;
    this.privateKey = null;
  }

  /**
   * Stores the configuration for a single HTTP listener for the server. This constructor sets up a TLS based listener.
   *
   * @param bindAddress The bind address of this listener.
   * @param port        The port of this listener.
   * @param certificate The certificate as a PEM ecnoded X.509 certificate String.
   * @param privateKey  The private key as a PKCS8 encoded DER private key.
   */
  public HTTPListenerConfiguration(InetAddress bindAddress, int port, String certificate, String privateKey) {
    Objects.requireNonNull(bindAddress);
    Objects.requireNonNull(certificate);
    Objects.requireNonNull(privateKey);

    this.bindAddress = bindAddress;
    this.port = port;
    this.tls = true;
    this.certificate = certificate;
    this.privateKey = privateKey;
  }

  public InetAddress getBindAddress() {
    return bindAddress;
  }

  public String getCertificate() {
    return certificate;
  }

  public int getPort() {
    return port;
  }

  public String getPrivateKey() {
    return privateKey;
  }

  public boolean isTLS() {
    return tls;
  }
}
