/*
 * Copyright (c) 2026, FusionAuth, All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fusionauth.http.server.internal;

import javax.net.ServerSocketFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServerConfiguration;

/** Tests for custom server socket factories. */
public class HTTPServerThreadTest {
  @Test
  public void customServerSocketFactoryIsUsedForTLSListener() throws Exception {
    assertCustomServerSocketFactoryIsUsed(true);
  }

  @Test
  public void customServerSocketFactoryIsUsedForPlainListener() throws Exception {
    assertCustomServerSocketFactoryIsUsed(false);
  }

  private void assertCustomServerSocketFactoryIsUsed(boolean tls) throws Exception {
    AtomicInteger createCalls = new AtomicInteger();
    ServerSocketFactory factory = new ServerSocketFactory() {
      @Override
      public ServerSocket createServerSocket() throws IOException {
        createCalls.incrementAndGet();
        return new ServerSocket();
      }

      @Override
      public ServerSocket createServerSocket(int port) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ServerSocket createServerSocket(int port, int backlog) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ServerSocket createServerSocket(int port, int backlog, InetAddress bindAddress) {
        throw new UnsupportedOperationException();
      }
    };

    HTTPListenerConfiguration listener = new HTTPListenerConfiguration(InetAddress.getLoopbackAddress(), 0, tls, factory);
    HTTPServerThread serverThread = new HTTPServerThread(new HTTPServerConfiguration(), listener);
    try {
      assertEquals(listener.isTLS(), tls);
      assertEquals(listener.getServerSocketFactory(), factory);
      assertEquals(createCalls.get(), 1);
    } finally {
      serverThread.shutdown();
    }
  }
}
