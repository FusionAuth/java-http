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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import io.fusionauth.http.BaseTest;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * Tests parsing of certificate chains and keys.
 *
 * @author Mark Manes
 */
public class SecurityToolsTest extends BaseTest {
  private static Path projectDir;

  @BeforeClass
  public static void setUp() {
    projectDir = java.nio.file.Path.of("");
  }

  @Test
  public void securityContextMangling() throws Exception {
    setupCertificates();
    SecurityTools.clientContext(certificate);
    SecurityTools.serverContext(certificate, keyPair.getPrivate());

    try (var client = HttpClient.newHttpClient()) {
      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(URI.create("https://fusionauth.io"))
                                       .GET()
                                       .build();
      var response = client.send(request, r -> BodySubscribers.ofString(StandardCharsets.UTF_8));
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void testParseCertificateChain() throws Exception {
    // Test that a combined server and intermediate certificate parse from the same file
    var combinedPem = Files.readString(projectDir.resolve("src/test/resources/test-intermediate-server-combined.pem"));
    var rootPem = Files.readString(projectDir.resolve("src/test/resources/test-root-ca.pem"));

    var certs = SecurityTools.parseCertificates(combinedPem);
    var rootCert = SecurityTools.parseCertificate(rootPem);
    assertEquals(certs.length, 3);

    // Ensure that the combined server certificate chain validate up to the root. This will throw an exception on validation failure.
    validateCertPath(rootCert, certs);
  }
}
