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
package io.fusionauth.http.server.io;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Intercepts each OutputStream method and sends the number of bytes to the Throughput object.
 *
 * @author Brian Pontarelli
 */
public class ThroughputOutputStream extends OutputStream {
  private final OutputStream delegate;

  private final Throughput throughput;

  public ThroughputOutputStream(OutputStream delegate, Throughput throughput) {
    this.delegate = delegate;
    this.throughput = throughput;
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }

  @Override
  public void flush() throws IOException {
    delegate.flush();
  }

  @Override
  public void write(int b) throws IOException {
    delegate.write(b);
    throughput.wrote(1);
  }

  @Override
  public void write(byte[] b) throws IOException {
    delegate.write(b);
    throughput.wrote(b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    delegate.write(b, off, len);
    throughput.wrote(len);
  }
}
