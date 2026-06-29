/*
 * Copyright (c) 2022-2026, FusionAuth, All Rights Reserved
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

import io.fusionauth.http.util.ThrowingFunction;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

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
    public void no_chunks_can_be_read() throws IOException {
        // Use case: After reading 1 byte (the non terminated chunk size), no carriage return is received and we are
        //           unable to read anything from the underlying pushback input stream. We should bail.

        // arrange
        String body = "3";
        PushbackInputStream pushbackInputStream = new PushbackInputStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                null);
        ChunkedInputStream chunkedInputStream = new ChunkedInputStream(pushbackInputStream, 2048);

        // act
        byte[] result = chunkedInputStream.readAllBytes();

        // assert
        assertEquals(result.length,
                0);
    }

    @Test
    public void zero_length() throws IOException {
        // arrange
        String body = "";
        PushbackInputStream pushbackInputStream = new PushbackInputStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                null);
        ChunkedInputStream chunkedInputStream = new ChunkedInputStream(pushbackInputStream, 2048);
        byte[] buffer = new byte[0];

        // act
        int result = chunkedInputStream.read(buffer, 0, 0);

        // assert
        assertEquals(result, 0,
                "contract says if len is zero, no bytes are read and zero is returned");
    }

    @Test
    public void non_zero_offset() throws IOException {
        // Use case: We have 2 chunks, a 10 byte chunk and a 5 byte chunk. If we ask for 15 bytes
        //           we should read 10 bytes from the first chunk and 5 from the second chunk.
        //           note that the offset should be independent of where the data is being read from. it
        //           only should affect the destination buffer.

        // arrange
        // two chunks: 10 bytes ("ABCDEFGHIJ") + 5 bytes ("KLMNO") = 15 bytes total
        String body = "a\r\nABCDEFGHIJ\r\n5\r\nKLMNO\r\n0\r\n\r\n";
        PushbackInputStream pushbackInputStream = new PushbackInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), null);
        ChunkedInputStream chunkedInputStream = new ChunkedInputStream(pushbackInputStream, 2048);

        byte[] dest = new byte[25];

        // act
        int read = chunkedInputStream.read(dest, 10, 15);

        // assert
        assertEquals(read,
                15,
                "We asked for 15 bytes, and those exist because the buffer from 'body' is 30 bytes, therefore we should get 15 bytes");
        assertEquals(new String(dest, 10, read, StandardCharsets.UTF_8),
                "ABCDEFGHIJKLMNO",
                "dest[10..24] must contain both chunks");
    }

    @Test
    public void bufferOverrun_zero_offset() throws IOException {
        // Use case: dLen is bigger than the buffer size with zero offset

        // arrange
        // one 20-byte chunk
        String body = "14\r\nABCDEFGHIJKLMNOPQRST\r\n0\r\n\r\n";
        PushbackInputStream pushbackInputStream = new PushbackInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), null);
        ChunkedInputStream chunkedInputStream = new ChunkedInputStream(pushbackInputStream, 2048);

        byte[] destinationBuffer = new byte[10];

        // act
        try {
            chunkedInputStream.read(destinationBuffer, 0, 20);
            fail("expected an exception");
        } catch (IndexOutOfBoundsException e) {
            assertEquals(e.getMessage(),
                    "Range [0, 0 + 20) out of bounds for length 10");
        }
    }

    @Test
    public void bufferOverrun_nonzero_offset() throws IOException {
        // Use case: dLen is bigger than the buffer size with non-zero offset

        // arrange
        // one 20-byte chunk
        String body = "14\r\nABCDEFGHIJKLMNOPQRST\r\n0\r\n\r\n";
        PushbackInputStream pushbackInputStream = new PushbackInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), null);
        ChunkedInputStream chunkedInputStream = new ChunkedInputStream(pushbackInputStream, 2048);

        byte[] destinationBuffer = new byte[10];

        // act
        try {
            chunkedInputStream.read(destinationBuffer, 1, 19);
            fail("expected an exception");
        } catch (IndexOutOfBoundsException e) {
            assertEquals(e.getMessage(),
                    "Range [1, 1 + 19) out of bounds for length 10");
        }
    }

    @Test
    public void chunk_larger_than_32_bytes() throws IOException {
        // arrange
        // 80000000 is Integer.MAX_VALUE + 1 in hex
        String body = "80000000\r\nABC\r\n0\r\n\r\n";
        PushbackInputStream pushbackInputStream = new PushbackInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), null);
        ChunkedInputStream chunkedInputStream = new ChunkedInputStream(pushbackInputStream, 2048);
        byte[] dest = new byte[5];

        // act + assert
        try {
            chunkedInputStream.read(dest, 0, 5);
            fail("expected an exception");
        } catch (ChunkException e) {
            assertEquals(e.getMessage(),
                    "Chunk size is too large");
        }
    }

    @Test
    public void cross_chunk_read_does_not_overflow_destination() throws IOException {
        // Use case: 2 chunks, 8 hex bytes total. the first chunk is 3 bytes. The second one is 5.
        //           If we ready 5 bytes, we'll need the first chunk and the 2nd chunk.

        // arrange
        String body = "3\r\nABC\r\n5\r\nDEFGH\r\n0\r\n\r\n";
        PushbackInputStream pushbackInputStream = new PushbackInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), null);
        ChunkedInputStream chunkedInputStream = new ChunkedInputStream(pushbackInputStream, 2048);

        byte[] dest = new byte[5];

        // act
        // 5 bytes requested, crossing the chunk boundary (3 from chunk 1, 2 from chunk 2)
        int read = chunkedInputStream.read(dest, 0, 5);

        // assert
        assertEquals(read, 5);
        assertEquals(new String(dest, 0, 5, StandardCharsets.UTF_8), "ABCDE");
    }

    @Test
    public void destination_smaller_than_chunk() throws IOException {
        // Use case: The destination buffer is smaller than a single chunk.

        // arrange
        // one 20-byte chunk
        String body = "14\r\nABCDEFGHIJKLMNOPQRST\r\n0\r\n\r\n";
        PushbackInputStream pushbackInputStream = new PushbackInputStream(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), null);
        ChunkedInputStream chunkedInputStream = new ChunkedInputStream(pushbackInputStream, 2048);

        byte[] destinationBuffer = new byte[10];

        // act – first read: We read 10 of the 20 bytes
        int read1 = chunkedInputStream.read(destinationBuffer, 0, 10);

        // assert
        assertEquals(read1, 10, "We asked for 10 bytes");
        StringBuilder stringBuilder = new StringBuilder();
        String string = new String(destinationBuffer, 0, 10, StandardCharsets.UTF_8);
        assertEquals(string, "ABCDEFGHIJ",
                "first read must yield the first half of the chunk");
        stringBuilder.append(string);

        // act – second read: read 5 more of the 20 bytes total
        int read2 = chunkedInputStream.read(destinationBuffer, 0, 5);

        // assert
        assertEquals(read2, 5, "second read must return the remaining chunk bytes");

        // now read the final 5 bytes in at offset 5
        int read3 = chunkedInputStream.read(destinationBuffer, 5, 5);
        assertEquals(read3, 5);
        string = new String(destinationBuffer, 0, 10, StandardCharsets.UTF_8);
        assertEquals(string, "KLMNOPQRST",
                "2nd and 3rd read must yield the second half of the chunk");
        stringBuilder.append(string);

        // act – body is exhausted
        int read4 = chunkedInputStream.read(destinationBuffer, 0, 10);

        // assert
        assertEquals(read4, -1, "third read must signal end of chunked body");
        assertEquals(stringBuilder.toString(),
                "ABCDEFGHIJKLMNOPQRST",
                "Altogether, we should read the entire thing");
    }

    @Test
    public void incompleteRequest() throws IOException {
        // Use case: After reading 1 chunk successfully, no further chunk size is received and we are
        //           unable to read anything from the underlying pushback input stream. This should be an
        //           incomplete request per RFC 9112 section 8.

        // arrange
        String body = """
                3\r
                Hi \r
                """;

        PushbackInputStream pushbackInputStream = new PushbackInputStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                null);
        ChunkedInputStream chunkedInputStream = new ChunkedInputStream(pushbackInputStream, 2048);

        // act
        byte[] result = chunkedInputStream.readAllBytes();

        // assert
        assertEquals(result.length,
                0);
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
            if (len == 0) {
                return 0;
            }

            // We may only read part way through one of the parts.
            // If we didn't read all the way through, use the subPartIndex
            byte[] part = parts[partsIndex];
            int remainingInPart = part.length - subPartIndex;
            // whichever is smaller, what's left in the actual array or what we were asked to read
            int toRead = Math.min(remainingInPart, len);
            System.arraycopy(part, subPartIndex, b, off, toRead);
            subPartIndex += toRead;

            if (subPartIndex >= part.length) {
                partsIndex++;
                subPartIndex = 0;
            }

            return toRead;
        }

        @Override
        public int read(byte[] b) {
            throw new IllegalStateException("Unexpected call to read(byte[] b)");
        }
    }
}
