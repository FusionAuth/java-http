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

import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Test
public class ContentLengthBodyProcessorTest {
  @Test
  public void partialChunks() {
    var list = new ArrayList<ByteBuffer>();
    Consumer<ByteBuffer> consumer = list::add;
    var processor = new ContentLengthBodyProcessor(30, 60);
    processor.currentBuffer().put("123456789012345678901234567890".getBytes());
    processor.processBuffer(consumer);

    assertFalse(processor.isComplete());

    processor.currentBuffer().put("123456789012345678901234567890".getBytes());
    processor.processBuffer(consumer);

    assertTrue(processor.isComplete());
    assertEquals(list.size(), 2);
    assertEquals(new String(list.get(0).array(), 0, list.get(0).limit()), "123456789012345678901234567890");
    assertEquals(new String(list.get(1).array(), 0, list.get(1).limit()), "123456789012345678901234567890");
  }

  @Test
  public void partialChunksBufferReused() {
    var list = new ArrayList<ByteBuffer>();
    Consumer<ByteBuffer> consumer = list::add;
    var processor = new ContentLengthBodyProcessor(30, 60);
    processor.currentBuffer().put("1234567890123456789012345".getBytes());
    processor.processBuffer(consumer);

    assertFalse(processor.isComplete());

    processor.currentBuffer().put("67890".getBytes());
    processor.processBuffer(consumer);

    assertFalse(processor.isComplete());

    processor.currentBuffer().put("123456789012345678901234567890".getBytes());
    processor.processBuffer(consumer);

    assertTrue(processor.isComplete());
    assertEquals(list.size(), 2);
    assertEquals(new String(list.get(0).array(), 0, list.get(0).limit()), "123456789012345678901234567890");
    assertEquals(new String(list.get(1).array(), 0, list.get(1).limit()), "123456789012345678901234567890");
  }

  @Test
  public void singleChunk() {
    var list = new ArrayList<ByteBuffer>();
    Consumer<ByteBuffer> consumer = list::add;
    var processor = new ContentLengthBodyProcessor(1024, 60);
    processor.currentBuffer().put("123456789012345678901234567890123456789012345678901234567890".getBytes());
    processor.processBuffer(consumer);

    assertTrue(processor.isComplete());
    assertEquals(list.size(), 1);
    assertEquals(new String(list.get(0).array(), 0, list.get(0).limit()), "123456789012345678901234567890123456789012345678901234567890");
  }
}
