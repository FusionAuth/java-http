/*
 * Copyright (c) 2022-2025, FusionAuth, All Rights Reserved
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
import java.nio.charset.StandardCharsets;

import io.fusionauth.http.util.ThrowingFunction;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * @author Brian Pontarelli
 */
@Test
public class ChunkedInputStreamTest {
  @SuppressWarnings("GrazieInspection")
  @Test
  public void chunkExtensions() throws Exception {
    // Test extensions
    // - We do not support these, but we need to be able to ignore them w/out puking.
    //
    // ;foo=bar          Single extension
    // ;foo=             Single extension, no value
    // ;foo              Single extension, no value, no equals
    // ;foo;bar          Two extensions, no values, no equals
    // ;foo;bar=         Two extensions, no values
    // ;foo;bar=baz      Two extensions, one value, one equals
    // ;foo=;bar=baz     Two extensions, one value, one equals
    // ;foo=bar;bar=baz  Two extensions, two values
    // ;                 No extension, only a separator. Not sure if this is valid, but we should be able to ignore it.
    // 0;foo=bar;bar     Extensions on the final 0 chunk
    withBody(
        """
            3;foo=bar\r
            Hi \r
            4;foo=\r
            mom!\r
            3;foo\r
             Lo\r
            2;foo;bar\r
            ok\r
            1;foo;bar=\r
             \r
            1;foo;bar=baz\r
            n\r
            2;foo=bar;baz\r
            o \r
            3;foo=bar;bar=baz\r
            ext\r
            2;\r
            en\r
            4\r
            sion\r
            2;\r
            s!\r
            0;foo=bar;bar\r
            \r
            """)
        .assertResult("Hi mom! Look no extensions!")
        .assertLeftOverBytes(0);
  }

  @Test
  public void multipleChunks() throws Exception {
    withBody("""
        A\r
        1234567890\r
        14\r
        12345678901234567890\r
        1E\r
        123456789012345678901234567890\r
        0\r
        \r
        """
    ).assertResult("123456789012345678901234567890123456789012345678901234567890")
     .assertLeftOverBytes(0)
     .assertNextRead(ChunkedInputStream::read, -1);
  }

  @Test
  public void ok() throws Exception {
    withBody(
        """
            3\r
            Hi \r
            4\r
            mom!\r
            0\r
            \r
            """
    ).assertResult("Hi mom!")
     .assertLeftOverBytes(0);
  }

  @Test
  public void partialChunks() throws IOException {
    var buf = new byte[1024];
    var inputStream = new ChunkedInputStream(withParts(
        """
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
            """), 1024);
    // All chunks will be read on the first attempt because the buffer is large enough
    assertEquals(inputStream.read(buf), 60);
    var result = new String(buf, 0, 60);
    assertEquals(result, "123456789012345678901234567890123456789012345678901234567890");
    assertEquals(inputStream.read(), -1);
  }

  @Test
  public void partialHeader() throws IOException {
    var buf = new byte[1024];
    var inputStream = new ChunkedInputStream(withParts(
        """
            A\r
            1234567890\r
            14""",
        """
            \r
            12345678901234567890\r
            0\r
            \r
            """), 1024);

    // All chunks will be read on the first attempt because the buffer is large enough
    assertEquals(inputStream.read(buf), 30);
    var result = new String(buf, 0, 30);
    assertEquals(result, "123456789012345678901234567890");
    assertEquals(inputStream.read(buf), -1);
  }

  @Test
  public void trailers() throws Exception {
    // It isn't clear if any HTTP server actually users or supports trailers. But, the spec indicates we should at least ignore them.
    // - https://www.rfc-editor.org/rfc/rfc2616.html#section-3.6.1
    withBody(
        """
            30\r
            There is no fate but what we make for ourselves.\r
            12\r
            
              - Sarah Connor
            \r
            0\r
            Judgement-Day: August 29, 1997 2:14 AM EDT\r
            \r
            """)
        .assertResult("""
            There is no fate but what we make for ourselves.
              - Sarah Connor
            """)
        // If we correctly read to the end of the InputStream we should not have any bytes left over in the PushbackInputStream
        .assertLeftOverBytes(0);
  }

  private Builder withBody(String body) {
    return new Builder().withBody(body);
  }

  private PushbackInputStream withParts(String... parts) {
    return new PushbackInputStream(new PieceMealInputStream(parts), null);
  }

  @SuppressWarnings("UnusedReturnValue")
  private static class Builder {
    public String body;

    public ChunkedInputStream chunkedInputStream;

    public PushbackInputStream pushbackInputStream;

    /**
     * Used to ensure the parser worked correctly and was able to read to the end of the encoded body.
     *
     * @param expected the number of expected bytes that were over-read.
     * @return this.
     */
    public Builder assertLeftOverBytes(int expected) throws IOException {
      int actual = pushbackInputStream.getAvailableBufferedBytesRemaining();
      if (actual != expected) {
        if (actual > 0) {
          byte[] leftOverBytes = new byte[actual];
          int leftOverRead = pushbackInputStream.read(leftOverBytes);
          // No reason to think these would not be equal... but they better be.
          assertEquals(leftOverBytes.length, leftOverRead);
          assertEquals(actual, expected, "\nHere is what was left over in the buffer\n[" + new String(leftOverBytes) + "]");
        }
      }

      return this;
    }

    public Builder assertNextRead(ThrowingFunction<ChunkedInputStream, Integer> function, int expected) throws Exception {
      var result = function.apply(chunkedInputStream);
      assertEquals(result, expected);
      return this;
    }

    public Builder assertResult(String expected) throws IOException {
      pushbackInputStream = new PushbackInputStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), null);
      chunkedInputStream = new ChunkedInputStream(pushbackInputStream, 2048);

      String actual = new String(chunkedInputStream.readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(actual, expected);
      return this;
    }

    public Builder withBody(String body) {
      this.body = body;
      return this;
    }
  }

  private static class PieceMealInputStream extends InputStream {
    private final byte[][] parts;

    private int partsIndex;

    private int subPartIndex = 0;

    public PieceMealInputStream(String... parts) {
      this.parts = new byte[parts.length][];
      for (int i = 0; i < parts.length; i++) {
        String part = parts[i];
        this.parts[i] = part.getBytes();
      }
    }

    @Override
    public int read() {
      throw new IllegalStateException("Unexpected call to read()");
    }

    @Override
    public int read(byte[] b, int off, int len) {
      if (partsIndex >= parts.length) {
        return -1;
      }

      // We may only read part way through one of the parts.
      // If we didn't read all the way through, use the subPartIndex
      int read = Math.min(parts[partsIndex].length - subPartIndex, b.length);
      System.arraycopy(parts[partsIndex], 0, b, 0, read);
      if (read < parts[partsIndex].length - subPartIndex) {
        subPartIndex = read;
      } else {
        partsIndex++;
      }

      return read;
    }

    @Override
    public int read(byte[] b) {
      throw new IllegalStateException("Unexpected call to read(byte[] b)");
    }
  }
}
