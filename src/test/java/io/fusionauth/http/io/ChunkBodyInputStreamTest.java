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
package io.fusionauth.http.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

@Test
public class ChunkBodyInputStreamTest {
  @Test
  public void multipleChunks() throws IOException {
    ByteArrayInputStream bais = new ByteArrayInputStream("""
        A\r
        1234567890\r
        14\r
        12345678901234567890\r
        1E\r
        123456789012345678901234567890\r
        0\r
        \r
        """.getBytes()
    );
    var inputStream = new ChunkedInputStream(bais, 1024, null);
    var result = new String(inputStream.readAllBytes());
    assertEquals(result, "123456789012345678901234567890123456789012345678901234567890");
    assertEquals(inputStream.read(), -1);
  }

  @Test
  public void partialChunks() throws IOException {
    var pmis = new PieceMealInputStream("""
        A\r
        12345678""",
        """
            90\r
            14\r
            12345678901234567890\r
            1E\r
            123456789012345678901234567890\r
            0\r
            \r
            """);
    var buf = new byte[1024];
    var inputStream = new ChunkedInputStream(pmis, 1024, null);
    assertEquals(inputStream.read(buf), 8);
    var result = new String(buf, 0, 8);
    assertEquals(result, "12345678");

    assertEquals(inputStream.read(buf), 2);
    result = new String(buf, 0, 2);
    assertEquals(result, "90");

    assertEquals(inputStream.read(buf), 20);
    result = new String(buf, 0, 20);
    assertEquals(result, "12345678901234567890");

    assertEquals(inputStream.read(buf), 30);
    result = new String(buf, 0, 30);
    assertEquals(result, "123456789012345678901234567890");
    assertEquals(inputStream.read(), 0);
    assertEquals(inputStream.read(), -1);
  }

  @Test
  public void partialHeader() throws IOException {
    var pmis = new PieceMealInputStream("""
        A\r
        1234567890\r
        14""",
        """
            \r
            12345678901234567890\r
            0\r
            \r
            """);
    var buf = new byte[1024];
    var inputStream = new ChunkedInputStream(pmis, 1024, null);
    assertEquals(inputStream.read(buf), 10);
    var result = new String(buf, 0, 10);
    assertEquals(result, "1234567890");

    assertEquals(inputStream.read(buf), 0);
    assertEquals(inputStream.read(buf), 20);
    result = new String(buf, 0, 20);
    assertEquals(result, "12345678901234567890");
    assertEquals(inputStream.read(), 0);
    assertEquals(inputStream.read(), -1);
  }

  private class PieceMealInputStream extends InputStream {
    private final byte[][] parts;

    private int partsIndex;

    public PieceMealInputStream(String... parts) {
      this.parts = new byte[parts.length][];
      for (int i = 0; i < parts.length; i++) {
        String part = parts[i];
        this.parts[i] = part.getBytes();
      }
    }

    @Override
    public int read(byte[] b) {
      if (partsIndex >= parts.length) {
        return -1;
      }

      int read = parts[partsIndex].length;
      System.arraycopy(parts[partsIndex], 0, b, 0, read);
      partsIndex++;
      return read;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      throw new IOException("Should not be called");
    }

    @Override
    public int read() throws IOException {
      throw new IOException("Should not be called");
    }
  }
}
