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
import java.net.SocketException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import io.fusionauth.http.HTTPValues.ControlBytes;
import io.fusionauth.http.HTTPValues.Protocols;
import io.fusionauth.http.io.ByteBufferOutputStream;

@SuppressWarnings("resource")
public class HTTPClientThread extends Thread implements Closeable {
//  final Queue<SelectionKey> clientKeys = new ConcurrentLinkedQueue<>();

  public static AtomicInteger counter = new AtomicInteger(0);
  // TODO : clean up stale connections to servers
//  private final ClientReaperThread clientReaper;

  private final HTTPClientChannelPool pool = new HTTPClientChannelPool();

  private final Selector selector;

  public HTTPClientThread() throws IOException {
    selector = Selector.open();
    System.out.println("Client started");
  }
  //  private final List<WorkerThread> workerThreads = new ArrayList<>();

  public CompletableFuture<HTTPClientResponse> add(URI url, String method, HTTPClientConfiguration configuration) throws IOException {
    // Just do enough to set up the data to attach and open the socket.
    HTTP1Processor data = new HTTP1Processor();
//    data.logger = configuration.getLoggerFactory();
    data.future = new CompletableFuture<>();
    data.host = url.getHost();
    data.method = method;
    data.protocol = url.getScheme();
    data.port = url.getPort() == -1
        ? (data.protocol.equals("http") ? 80 : 443)
        : url.getPort();
    data.url = url;

    for (String key : configuration.headers.keySet()) {
      data.headers.put(key, new ArrayList<>(configuration.headers.get(key)));
    }

    // No channel, open a new socket w/out blocking, connect and then register for the OP_CONNECT state.
    SocketChannel channel = pool.checkout(url.getHost());
    if (channel == null) {
      System.out.println("[" + counter.incrementAndGet() + "] open socket.");
      channel = SocketChannel.open();
      channel.configureBlocking(false);
      channel.connect(new InetSocketAddress(data.host, data.port));
      channel.register(selector, SelectionKey.OP_CONNECT, data);
    } else {
      System.out.println("[" + counter.incrementAndGet() + "] re-use socket.");
      // Use the existing socket, and attach our data and register for OP_WRITE
//      channel.register(selector, SelectionKey.OP_WRITE, data);
//      SelectionKey key =channel.register(selector, SelectionKey.OP_WRITE, data);
      // TODO : Is this safe?
      SelectionKey key = channel.keyFor(selector);
      key.attach(data);
      key.interestOps(SelectionKey.OP_WRITE);
    }

    // Time to get up!
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

        // TODO : Note This wil block, we should add a timeout for cleanup.
        // TODO : Configuration
        int numberOfKeys = selector.select(1_000L);
        if (numberOfKeys <= 0) {
          continue;
        }

        var keys = selector.selectedKeys();
        Iterator<SelectionKey> iterator = keys.iterator();
        while (iterator.hasNext()) {
          var key = iterator.next();
          if (key.isConnectable()) {
//            System.out.println("Connecting");
            connect(key);
          } else if (key.isReadable()) {
            if (key.attachment() == null) {
              System.out.println("Should we be here if we don't have an attachment? Seems like we should be in a write state?\n\n");
            }
            try {
              read(key);
            } catch (SocketException e) {
              // Try to recover from a connection reset
              if (e.getMessage().equals("Connection reset")) {
                System.out.println("[" + counter + "] Handle connection reset.");
                HTTP1Processor data = (HTTP1Processor) key.attachment();
                data.reset();
                SocketChannel channel = SocketChannel.open();
                channel.configureBlocking(false);
                channel.connect(new InetSocketAddress(data.host, data.port));
                channel.register(selector, SelectionKey.OP_CONNECT, data);
              } else {
                throw e;
              }

            }

          } else if (key.isWritable()) {
            if (key.attachment() == null) {
              System.out.println("Should we be here if we don't have an attachment? Seems like we should be in a different state?\n\n");
            }
            write(key);
          }

          iterator.remove();
        }
      } catch (IOException e) {
        System.out.println("Whoa!!!\n\n");
        e.printStackTrace();
      } finally {
//        cleanup();
      }
    }
  }

  private void cancelAndCloseKey(SelectionKey key) {
    if (key != null) {
      HTTP1Processor data = (HTTP1Processor) key.attachment();

      try (var client = key.channel()) {
        key.cancel();
      } catch (Throwable t) {
        // TODO : Logger
      }

      // TODO : Tracer
    }
  }

  private void cleanup() {
    long now = System.currentTimeMillis();
    // TODO : Logger
    selector.keys()
            .stream()
            .filter(key -> key.attachment() != null)
            // TODO : Configuration
            .filter(key -> ((HTTP1Processor) key.attachment()).lastUsed() < now - 20_000)
            // TODO : Logger
            .forEach(this::cancelAndCloseKey);
  }

  private void connect(SelectionKey key) throws IOException {
    SocketChannel channel = (SocketChannel) key.channel();
    if (channel.finishConnect()) {
      key.interestOps(SelectionKey.OP_WRITE);
    }
  }

  private void parsePreamble(HTTP1Processor data) {
    StringBuilder builder = new StringBuilder(512);

    String url = "/";
    if (data.url.getPath() != null) {
      url = data.url.getPath();
    }

    if (data.url.getQuery() != null) {
      url += data.url.getQuery();
    }


    if ("".equals(url)) {
      url = "/";
    }

    ByteBufferOutputStream bbos = new ByteBufferOutputStream(1024, 8 * 1024);

    // TODO : Note : Things like Host, etc we could just add headers and then use the common path of code.

    // Method Path Protocol
    bbos.write(data.method.getBytes(StandardCharsets.UTF_8));
    bbos.write(" ".getBytes(StandardCharsets.UTF_8));
    bbos.write(url.getBytes(StandardCharsets.UTF_8));
    bbos.write(" ".getBytes(StandardCharsets.UTF_8));
    bbos.write(Protocols.HTTTP1_1.getBytes(StandardCharsets.UTF_8));
    bbos.write(ControlBytes.CRLF);

    // Host
    bbos.write("Host: ".getBytes(StandardCharsets.UTF_8));
    bbos.write(data.url.getHost().getBytes(StandardCharsets.UTF_8));
    bbos.write(ControlBytes.CRLF);

    // User-Agent
    bbos.write("User-Agent: java-http-client/1.0.0".getBytes(StandardCharsets.UTF_8));
    bbos.write(ControlBytes.CRLF);

//    bbos.write("Accept: */*".getBytes(StandardCharsets.UTF_8));
//    bbos.write(ControlBytes.CRLF);

//    builder.append(data.method)
//           .append(" ")
//           .append(url)
//           .append(" ")
//           .append(Protocols.HTTTP1_1)
//           .append((byte[]) ControlBytes.CRLF)
//           .append("Host:")
//           .append(data.url.getHost())
//           .append((byte[]) ControlBytes.CRLF)
//           .append("User-Agent: java-http-client/1.0.0")
//           .append((byte[]) ControlBytes.CRLF)
//           .append("Accept: */*")
//           .append((byte[]) ControlBytes.CRLF);


    // Headers
    if (!data.headers.isEmpty()) {


      for (String key : data.headers.keySet()) {
        StringBuilder valueString = new StringBuilder();
        bbos.write((key + ": ").getBytes(StandardCharsets.UTF_8));
        for (String value : data.headers.get(key)) {
          // TODO : Daniel : Need escaping?
          valueString.append(value);
          valueString.append("; ");
        }

        int index = valueString.indexOf("; ");
        if (index == valueString.length() - 2) {
          bbos.write(valueString.substring(0, valueString.length() - 2).getBytes(StandardCharsets.UTF_8));
        } else {
          bbos.write(valueString.toString().getBytes(StandardCharsets.UTF_8));
        }

        bbos.write(ControlBytes.CRLF);
      }
    }

    // Close the preamble
    bbos.write(ControlBytes.CRLF);

//    data.request = ByteBuffer.wrap(builder.toString().getBytes(StandardCharsets.UTF_8));
    ByteBuffer buff = bbos.toByteBuffer();
//    System.out.println("\n---Preamble Start");
//    System.out.println(new String(buff.array(), 0, buff.limit(), StandardCharsets.UTF_8));
//    System.out.println("---Preamble End\n\n");
    data.request = buff;
  }

  @SuppressWarnings("resource")
  private void read(SelectionKey key) throws IOException {
    SocketChannel client = (SocketChannel) key.channel();
    HTTP1Processor data = (HTTP1Processor) key.attachment();
    // TODO : Daniel : is this safe? Why do we have a read op if we are complete and we
    //                 set the data attachment to null.
    long read = data != null ? client.read(data.currentBuffer()) : 0;
    if (read <= 0) {
      return;
    }

    if (data.isResponseComplete()) {
      HTTPClientResponse httpResponse = new HTTPClientResponse();
      httpResponse.setStatus(data.code);
      int expected = 0;
      for (ByteBuffer buffer : data.buffers) {
        expected += buffer.position();
      }

      ByteBuffer total = ByteBuffer.allocate(expected);
      for (ByteBuffer buffer : data.buffers) {
        buffer.flip();
        byte[] actual = buffer.array();
        byte[] used = Arrays.copyOfRange(actual, 0, buffer.limit());
        total.put(used);
      }
      byte[] bytes = total.array();
//      System.out.println(bytes.length);
//      System.out.println(data.read);
      httpResponse.setBody(bytes);


      key.attach(null);

      // TODO : Hacking - If using keep-alive, check back in... if not, don't
      //        Once we parse the response we should know if this is keep-alive from a boolean.
      String stringBody = new String(bytes);
      // TODO : is closing the channel enough, or should I cancel the keys as well?
      if (stringBody.contains("connection: close")) {
//        System.out.println("close the connection");
        key.channel().close();
//        key.interestOps(0);
//        key.cancel();
      } else {
//        key.interestOps(0);
//        key.cancel();
        key.interestOps(0);
        pool.checkin(data.host, (SocketChannel) key.channel());

//        System.out.println(stringBody);
      }


      data.future.complete(httpResponse);

//      key.attach(null);


    }

  }

  @SuppressWarnings("resource")
  private void write(SelectionKey key) throws IOException {
    // TODO : Daniel : We can write the preamble here
    //        The data object could manage the state. n
    SocketChannel client = (SocketChannel) key.channel();
    HTTP1Processor data = (HTTP1Processor) key.attachment();

    // TODO : Daniel : we'll need to keep track of the state to know what we are writing.
    parsePreamble(data);
    client.write(data.request);

    if (data.request.position() == data.request.limit()) {
      key.interestOps(SelectionKey.OP_READ);
    }
  }
}
