/*
 * Copyright (c) 2024-2025, FusionAuth, All Rights Reserved
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
import java.io.InputStream;

/**
 * An InputStream that updates a Throughput as it is read from.
 *
 * @author Brian Pontarelli
 */
public class ThroughputInputStream extends InputStream {
  private final InputStream delegate;

  private final Throughput throughput;

  public ThroughputInputStream(InputStream delegate, Throughput throughput) {
    this.delegate = delegate;
    this.throughput = throughput;
  }

  @Override
  public int available() throws IOException {
    return delegate.available();
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }

  @Override
  public void mark(@SuppressWarnings("SpellCheckingInspection") int readlimit) {
    delegate.mark(readlimit);
  }

  @Override
  public boolean markSupported() {
    return delegate.markSupported();
  }

  @Override
  public int read() throws IOException {
    int read = delegate.read();
    if (read != -1) {
      throughput.read(read);
    }
    return read;
  }

  @Override
  public int read(byte[] b) throws IOException {
    int read = delegate.read(b);
    if (read != -1) {
      throughput.read(read);
    }
    return read;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int read = delegate.read(b, off, len);
    if (read != -1) {
      throughput.read(read);
    }
    return read;
  }

  @Override
  public void reset() throws IOException {
    delegate.reset();
  }

  @Override
  public long skip(long n) throws IOException {
    long skipped = delegate.skip(n);
    throughput.read(skipped);
    return skipped;
  }
}
