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

import java.nio.ByteBuffer;

import io.fusionauth.http.HTTPResponse;
import io.fusionauth.http.HTTPValues;
import io.fusionauth.http.util.HTTPTools;

/**
 * A processor that handles incoming bytes that form the HTTP request.
 *
 * @author Brian Pontarelli
 */
public class HTTPResponseProcessor {
  public static final ByteBuffer Last = ByteBuffer.allocate(0);

  private final int maxHeadLength;

  private final HTTPResponse response;

  private ByteBuffer headBuffer;

  public HTTPResponseProcessor(HTTPResponse response, int maxHeadLength) {
    this.response = response;
    this.maxHeadLength = maxHeadLength;
  }

  public ByteBuffer currentBuffer() {
    HTTPOutputStream outputStream = (HTTPOutputStream) response.getOutputStream();
    boolean closed = outputStream.isClosed();
    if (!outputStream.hasBytes() && !closed) {
      return null;
    }

    if (headBuffer == null) {
      fillInHeaders();
      headBuffer = HTTPTools.buildResponseHead(response, maxHeadLength);
    }

    if (headBuffer.hasRemaining()) {
      return headBuffer;
    }

    ByteBuffer buffer = outputStream.currentReadBuffer();
    if (buffer == null && closed) {
      return Last;
    }

    return buffer;
  }

  private void fillInHeaders() {
    if (!response.containsHeader(HTTPValues.Headers.Connection)) {
      response.setHeader(HTTPValues.Headers.Connection, HTTPValues.Connections.KeepAlive);
    }
  }
}
