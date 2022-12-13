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

import java.nio.file.Files;
import java.nio.file.Path;

import io.fusionauth.http.BaseTest;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

public class SecurityToolsTest extends BaseTest {

  static Path projectDir;

  @BeforeClass
  public static void setUp() {
    projectDir = Path.of("");
  }

  @Test
  public void testParseCertificateChain() throws Exception {
    // Test that a combined server and intermediate certificate parse from the same file
    var combinedPem = Files.readString(projectDir.resolve("src/test/resources/test-intermediate-server-combined.pem"));
    var rootPem = Files.readString(projectDir.resolve("src/test/resources/test-root-ca.pem"));

    var certs = SecurityTools.parseCertificates(combinedPem);
    var rootCert = SecurityTools.parseCertificate(rootPem);
    assertEquals(certs.length, 2);

    validateCertPath(rootCert, certs);
  }
}
