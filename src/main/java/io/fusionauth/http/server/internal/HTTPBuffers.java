/*
 * Copyright (c) 2024, FusionAuth, All Rights Reserved
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
package io.fusionauth.http.server.internal;

import io.fusionauth.http.io.FastByteArrayOutputStream;
import io.fusionauth.http.server.HTTPServerConfiguration;

/**
 * A class that lazily creates and caches the buffers for a single worker thread. This is only used by the worker thread, so no
 * synchronization is required.
 *
 * @author Brian Pontarelli
 */
public class HTTPBuffers {
  private final HTTPServerConfiguration configuration;

  private final FastByteArrayOutputStream preambleOutputStream;

  private final byte[] requestBuffer;

  private byte[] chunkBuffer;

  private FastByteArrayOutputStream chunkOutputStream;

  public HTTPBuffers(HTTPServerConfiguration configuration) {
    this.configuration = configuration;
    this.preambleOutputStream = new FastByteArrayOutputStream(1024, 1024);
    this.requestBuffer = new byte[configuration.getRequestBufferSize()];
  }

  /**
   * @return An output stream that can be used for chunking responses. This uses the configuration's
   *     {@link HTTPServerConfiguration#getMaxResponseChunkSize()} value plus 64 bytes of padding for the header and footer.  This is lazily
   *     created.
   */
  public FastByteArrayOutputStream chuckedOutputStream() {
    if (chunkOutputStream == null) {
      chunkOutputStream = new FastByteArrayOutputStream(configuration.getMaxResponseChunkSize() + 64, 64);
    }

    return chunkOutputStream;
  }

  /**
   * @return A byte array that can be used for chunking responses. This uses the configuration's
   *     {@link HTTPServerConfiguration#getMaxResponseChunkSize()} value for the size. This is lazily created.
   */
  public byte[] chunkBuffer() {
    if (chunkBuffer == null) {
      chunkBuffer = new byte[configuration.getMaxResponseChunkSize()];
    }

    return chunkBuffer;
  }

  /**
   * @return An output stream used for the preamble in the response. This is created in the constructor since it is always needed. It starts
   *     at 1k and grows in 1k increments.
   */
  public FastByteArrayOutputStream preambleOutputStream() {
    return preambleOutputStream;
  }

  /**
   * @return A byte array used to read the request preamble and body. This uses the configuration's
   *     {@link HTTPServerConfiguration#getRequestBufferSize()} value for the size. It is created in the constructor since it is always
   *     needed.
   */
  public byte[] requestBuffer() {
    return requestBuffer;
  }
}
