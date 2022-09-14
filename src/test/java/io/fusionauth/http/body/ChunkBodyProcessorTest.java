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
package io.fusionauth.http.body;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.function.Consumer;

import io.fusionauth.http.body.request.ChunkedBodyProcessor;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Test
public class ChunkBodyProcessorTest {
  @Test
  public void multipleChunks() {
    var list = new ArrayList<ByteBuffer>();
    Consumer<ByteBuffer> consumer = list::add;
    var processor = new ChunkedBodyProcessor(1024);
    processor.currentBuffer().put(
        """
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
    processor.processBuffer(consumer);

    assertTrue(processor.isComplete());
    assertEquals(list.size(), 3);
    assertEquals(new String(list.get(0).array()), "1234567890");
    assertEquals(new String(list.get(1).array()), "12345678901234567890");
    assertEquals(new String(list.get(2).array()), "123456789012345678901234567890");
  }

  @Test
  public void partialChunks() {
    var list = new ArrayList<ByteBuffer>();
    Consumer<ByteBuffer> consumer = list::add;
    var processor = new ChunkedBodyProcessor(1024);
    processor.currentBuffer().put(
        """
            A\r
            12345678""".getBytes()
    );
    processor.processBuffer(consumer);

    assertFalse(processor.isComplete());
    assertEquals(list.size(), 1);
    assertEquals(new String(list.get(0).array()), "12345678");

    processor.currentBuffer().put(
        """
            90\r
            14\r
            12345678901234567890\r
            1E\r
            123456789012345678901234567890\r
            0\r
            \r
            """.getBytes()
    );
    processor.processBuffer(consumer);

    assertTrue(processor.isComplete());
    assertEquals(list.size(), 4);
    assertEquals(new String(list.get(0).array()), "12345678");
    assertEquals(new String(list.get(1).array()), "90");
    assertEquals(new String(list.get(2).array()), "12345678901234567890");
    assertEquals(new String(list.get(3).array()), "123456789012345678901234567890");
  }

  @Test
  public void partialChunksButPushed() {
    var list = new ArrayList<ByteBuffer>();
    Consumer<ByteBuffer> consumer = list::add;
    var processor = new ChunkedBodyProcessor(1024);
    processor.currentBuffer().put(
        """
            A\r
            1234567890""".getBytes()
    );
    processor.processBuffer(consumer);

    assertFalse(processor.isComplete());
    assertEquals(list.size(), 1);
    assertEquals(new String(list.get(0).array()), "1234567890");

    processor.currentBuffer().put(
        """
            \r
            14\r
            12345678901234567890\r
            1E\r
            123456789012345678901234567890\r
            0\r
            \r
            """.getBytes()
    );
    processor.processBuffer(consumer);

    assertTrue(processor.isComplete());
    assertEquals(list.size(), 3);
    assertEquals(new String(list.get(0).array()), "1234567890");
    assertEquals(new String(list.get(1).array()), "12345678901234567890");
    assertEquals(new String(list.get(2).array()), "123456789012345678901234567890");
  }

  @Test
  public void partialHeader() {
    var list = new ArrayList<ByteBuffer>();
    Consumer<ByteBuffer> consumer = list::add;
    var processor = new ChunkedBodyProcessor(1024);
    processor.currentBuffer().put(
        """
            A\r
            1234567890\r
            14""".getBytes()
    );
    processor.processBuffer(consumer);

    assertFalse(processor.isComplete());
    assertEquals(list.size(), 1);
    assertEquals(new String(list.get(0).array()), "1234567890");

    processor.currentBuffer().put(
        """
            \r
            12345678901234567890\r
            0\r
            \r
            """.getBytes()
    );
    processor.processBuffer(consumer);

    assertTrue(processor.isComplete());
    assertEquals(list.size(), 2);
    assertEquals(new String(list.get(0).array()), "1234567890");
    assertEquals(new String(list.get(1).array()), "12345678901234567890");
  }
}
