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
package io.fusionauth.http.client;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

@SuppressWarnings("resource")
public class NIOClientThread extends Thread implements Closeable {
//  final Queue<SelectionKey> clientKeys = new ConcurrentLinkedQueue<>();

//  private final List<WorkerThread> workerThreads = new ArrayList<>();

  // TODO : clean up stale connections to servers
//  private final ClientReaperThread clientReaper;

  private final ChannelPool pool = new ChannelPool();

  private final Selector selector;

  public NIOClientThread() throws IOException {
    selector = Selector.open();
    System.out.println("Client started");
  }

  public Future<Integer> add(URI uri, String method) throws IOException {
    HTTPData data = new HTTPData();
    data.request = ByteBuffer.wrap(
        """
            GET /api/system/version HTTP/1.1\r
            \r
            """.getBytes());
    data.future = new CompletableFuture<>();
    data.host = uri.getHost();

    SocketChannel channel = pool.checkout(uri.getHost());
    if (channel == null) {
      channel = SocketChannel.open();
      channel.configureBlocking(false);
      channel.connect(new InetSocketAddress(uri.getHost(), uri.getPort()));

      SelectionKey key = channel.register(selector, SelectionKey.OP_CONNECT);
      key.attach(data);
    } else {
      SelectionKey key = channel.keyFor(selector);
      key.attach(data);
      key.interestOps(SelectionKey.OP_WRITE);
    }

    // Wakeup! Time to put on a little makeup!
    selector.wakeup();
    return data.future;
  }

  @Override
  public void close() {
//    clientReaper.shutdown();

    try {
      selector.close();
    } catch (Throwable t) {
      t.printStackTrace();
    }
  }

  public void run() {
    while (true) {
      try {
        // If the selector has been closed, shut the thread down
        if (!selector.isOpen()) {
          return;
        }

        int numberOfKeys = selector.select();
        if (numberOfKeys <= 0) {
          continue;
        }

        var keys = selector.selectedKeys();
        Iterator<SelectionKey> iterator = keys.iterator();
        while (iterator.hasNext()) {
          var key = iterator.next();
          if (key.isConnectable()) {
            System.out.println("Connecting");
            connect(key);
          } else if (key.isReadable()) {
            read(key);
          } else if (key.isWritable()) {
            write(key);
          }

          iterator.remove();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  private void connect(SelectionKey key) throws IOException {
    SocketChannel channel = (SocketChannel) key.channel();
    if (channel.finishConnect()) {
      key.interestOps(SelectionKey.OP_WRITE);
    }
  }

  @SuppressWarnings("resource")
  private void read(SelectionKey key) throws IOException {
    SocketChannel client = (SocketChannel) key.channel();
    HTTPData data = (HTTPData) key.attachment();
    long read = client.read(data.currentBuffer());
    if (read <= 0) {
      return;
    }

    if (data.isResponseComplete()) {
      data.future.complete(data.code);
      key.attach(null);
      pool.checkin(data.host, (SocketChannel) key.channel());
    }
  }

  @SuppressWarnings("resource")
  private void write(SelectionKey key) throws IOException {
    SocketChannel client = (SocketChannel) key.channel();
    HTTPData data = (HTTPData) key.attachment();
    client.write(data.request);

    if (data.request.position() == data.request.limit()) {
      key.interestOps(SelectionKey.OP_READ);
    }
  }
}
