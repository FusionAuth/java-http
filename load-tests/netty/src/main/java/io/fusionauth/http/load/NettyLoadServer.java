/*
 * Copyright (c) 2025, FusionAuth, All Rights Reserved
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
package io.fusionauth.http.load;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

public class NettyLoadServer {
  private static final Map<Integer, byte[]> Blobs = new HashMap<>();

  public static void main(String[] args) throws Exception {
    EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    EventLoopGroup workerGroup = new NioEventLoopGroup();

    try {
      ServerBootstrap b = new ServerBootstrap();
      b.group(bossGroup, workerGroup)
       .channel(NioServerSocketChannel.class)
       .option(ChannelOption.SO_BACKLOG, 200)
       .childHandler(new ChannelInitializer<SocketChannel>() {
         @Override
         protected void initChannel(SocketChannel ch) {
           ch.pipeline().addLast(
               new HttpServerCodec(),
               new HttpObjectAggregator(10 * 1024 * 1024),
               new LoadHandler()
           );
         }
       });

      var ch = b.bind(8080).sync().channel();
      System.out.println("Netty server started on port 8080");
      ch.closeFuture().sync();
    } finally {
      bossGroup.shutdownGracefully();
      workerGroup.shutdownGracefully();
    }
  }

  static class LoadHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
      String path = request.uri();
      int queryIdx = path.indexOf('?');
      String pathOnly = queryIdx >= 0 ? path.substring(0, queryIdx) : path;

      FullHttpResponse response;
      try {
        response = switch (pathOnly) {
          case "/" -> handleNoOp(request);
          case "/no-read" -> handleNoRead();
          case "/hello" -> handleHello();
          case "/file" -> handleFile(request);
          case "/load" -> handleLoad(request);
          default -> handleFailure(pathOnly);
        };
      } catch (Exception e) {
        response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
      }

      boolean keepAlive = HttpUtil.isKeepAlive(request);
      response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
      if (keepAlive) {
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
      }

      var future = ctx.writeAndFlush(response);
      if (!keepAlive) {
        future.addListener(ChannelFutureListener.CLOSE);
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      ctx.close();
    }

    private FullHttpResponse handleFailure(String path) {
      byte[] body = ("Invalid path [" + path + "]. Supported paths include [/, /no-read, /hello, /file, /load].").getBytes(StandardCharsets.UTF_8);
      ByteBuf content = Unpooled.wrappedBuffer(body);
      FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST, content);
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
      return response;
    }

    private FullHttpResponse handleFile(FullHttpRequest request) {
      int size = 1024 * 1024;
      QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
      var sizeParam = decoder.parameters().get("size");
      if (sizeParam != null && !sizeParam.isEmpty()) {
        size = Integer.parseInt(sizeParam.getFirst());
      }

      byte[] blob = Blobs.get(size);
      if (blob == null) {
        synchronized (Blobs) {
          blob = Blobs.get(size);
          if (blob == null) {
            System.out.println("Build file with size : " + size);
            String s = "Lorem ipsum dolor sit amet";
            String body = s.repeat(size / s.length() + (size % s.length()));
            assert body.length() == size;
            Blobs.put(size, body.getBytes(StandardCharsets.UTF_8));
            blob = Blobs.get(size);
            assert blob != null;
          }
        }
      }

      ByteBuf content = Unpooled.wrappedBuffer(blob);
      FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
      return response;
    }

    private FullHttpResponse handleHello() {
      byte[] body = "Hello world".getBytes(StandardCharsets.UTF_8);
      ByteBuf content = Unpooled.wrappedBuffer(body);
      FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
      return response;
    }

    private FullHttpResponse handleLoad(FullHttpRequest request) {
      byte[] body = new byte[request.content().readableBytes()];
      request.content().readBytes(body);
      byte[] result = Base64.getEncoder().encode(body);
      ByteBuf content = Unpooled.wrappedBuffer(result);
      FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
      return response;
    }

    private FullHttpResponse handleNoOp(FullHttpRequest request) {
      // Read the body (it's already aggregated by HttpObjectAggregator)
      return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    }

    private FullHttpResponse handleNoRead() {
      return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    }
  }
}
