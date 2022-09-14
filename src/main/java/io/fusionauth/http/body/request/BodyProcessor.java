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
package io.fusionauth.http.body.request;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * A body parser that handles request body processing based on either Content-Length or chunked data.
 *
 * @author Brian Pontarelli
 */
public interface BodyProcessor {
  /**
   * @return The current buffer that the selector should use for reading.
   */
  ByteBuffer currentBuffer();

  /**
   * @return True if the body processing is complete.
   */
  boolean isComplete();

  /**
   * Called after bytes are read into the current buffer in order to process the bytes read.
   *
   * @param consumer A consumer of finished ByteBuffers.
   */
  void processBuffer(Consumer<ByteBuffer> consumer);

  long totalBytesProcessed();
}
