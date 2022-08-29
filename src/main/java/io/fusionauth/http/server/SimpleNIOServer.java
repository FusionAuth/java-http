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

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SimpleNIOServer extends Thread implements Closeable {
  private static final byte[] Response = """
      HTTP/1.1 200 OK\r
      Content-Type: text/plain\r
      Content-Length: 16\r
      \r
      {"version":"42"}""".getBytes();

  final Queue<SelectionKey> clientKeys = new ConcurrentLinkedQueue<>();

//  private final List<WorkerThread> workerThreads = new ArrayList<>();

  private final ClientReaperThread clientReaper;

  private final Selector selector;

  public ServerSocketChannel channel;

  public SimpleNIOServer() throws IOException {
    clientReaper = new ClientReaperThread(this);
    clientReaper.start();

    selector = Selector.open();
    channel = ServerSocketChannel.open();
    channel.configureBlocking(false);
    channel.bind(new InetSocketAddress(InetAddress.getByName("localhost"), 9011));
    channel.register(selector, SelectionKey.OP_ACCEPT);

    System.out.println("Server started");
  }

  @Override
  public void close() {
    clientReaper.shutdown();

    try {
      selector.close();
    } catch (Throwable t) {
      t.printStackTrace();
    }

    try {
      channel.close();
    } catch (Throwable t) {
      t.printStackTrace();
    }
  }

  public void run() {
//    // Start the threads
//    for (int i = 0; i < 50; i++) {
//      var workerThread = new WorkerThread("HTTPServer thread " + i);
//      workerThread.start();
//      workerThreads.add(workerThread);
//    }

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
          if (key.isAcceptable()) {
            System.out.println("Accepting");
            accept();
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

  private void accept() throws IOException {
    var clientChannel = channel.accept();
    clientChannel.configureBlocking(false);

    var clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
    clientKey.attach(new HTTPData());
    clientKeys.add(clientKey);
  }

  @SuppressWarnings("resource")
  private void read(SelectionKey key) throws IOException {
    SocketChannel client = (SocketChannel) key.channel();
    HTTPData data = (HTTPData) key.attachment();
    data.markUsed();

    long read = client.read(data.currentBuffer());
    if (read <= 0) {
      return;
    }

    if (data.isRequestComplete()) {
      data.response = ByteBuffer.wrap(Response);
      key.interestOps(SelectionKey.OP_WRITE);
    }
  }

  @SuppressWarnings("resource")
  private void write(SelectionKey key) throws IOException {
    SocketChannel client = (SocketChannel) key.channel();
    HTTPData data = (HTTPData) key.attachment();
    data.markUsed();

    client.write(data.response);

    if (data.response.position() == data.response.limit()) {
      key.interestOps(SelectionKey.OP_READ);
      data.reset();
    }
  }
}
