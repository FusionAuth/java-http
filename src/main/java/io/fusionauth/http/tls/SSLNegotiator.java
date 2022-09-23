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
package io.fusionauth.http.tls;

public class SSLNegotiator {
//  private final SSLEngine engine;
//
//  private final Logger logger;
//
//  public SSLNegotiator(LoggerFactory loggerFactory, SSLEngine engine) {
//    this.logger = loggerFactory.getLogger(this.getClass());
//    this.engine = engine;
//  }
//
//  public void closeConnection(SocketChannel socketChannel, SSLEngine engine) throws IOException {
//    engine.closeOutbound();
//    doHandshake(socketChannel);
//    socketChannel.close();
//  }
//
//  public HandshakeStatus doHandshake(SocketChannel socketChannel) throws IOException {
//    logger.debug("About to do handshake...");
//
//    HandshakeStatus status = engine.getHandshakeStatus();
//    if (status == HandshakeStatus.FINISHED || status == HandshakeStatus.NOT_HANDSHAKING) {
//      return status;
//    }
//
//    switch (status) {
//      case NEED_UNWRAP -> unwrap(socketChannel);
//      case NEED_WRAP -> wrap(socketChannel);
//      case NEED_TASK -> {
//        Runnable task;
//        while ((task = engine.getDelegatedTask()) != null) {
//          new Thread(task, "TLS negotiator thread");
//        }
//
//        status = engine.getHandshakeStatus();
//      }
//    }
//
//    return status;
//  }
//
//  public long read(SocketChannel socketChannel, ByteBuffer sink) throws Exception {
//    logger.debug("About to read from a client...");
//
//    peerNetData.clear();
//    int bytesRead = socketChannel.read(peerNetData);
//    if (bytesRead == 0) {
//      return 0L;
//    } else if (bytesRead == -1) {
//      handleEndOfStream(socketChannel, engine);
//      return 0L;
//    }
//
//    peerNetData.flip();
//    while (peerNetData.hasRemaining()) {
//      peerAppData.clear();
//      SSLEngineResult result = engine.unwrap(peerNetData, peerAppData);
//      switch (result.getStatus()) {
//        case OK -> {
//          peerAppData.flip();
//          sink.put(peerAppData);
//        }
//        case BUFFER_OVERFLOW -> peerAppData = handleBufferOverflow(peerAppData);
//        case BUFFER_UNDERFLOW -> peerNetData = handleBufferUnderflow(peerNetData);
//        case CLOSED -> {
//          logger.debug("Client wants to close connection...");
//          closeConnection(socketChannel, engine);
//          logger.debug("Goodbye client!");
//          return bytesRead;
//        }
//      }
//    }
//  }
//
//  public void write(SocketChannel socketChannel, String message) throws Exception {
//    logger.debug("About to write to the server...");
//
//    myAppData.clear();
//    myAppData.put(message.getBytes());
//    myAppData.flip();
//    while (myAppData.hasRemaining()) {
//      // The loop has a meaning for (outgoing) messages larger than 16KB.
//      // Every wrap call will remove 16KB from the original message and send it to the remote peer.
//      myNetData.clear();
//      SSLEngineResult result = engine.wrap(myAppData, myNetData);
//      switch (result.getStatus()) {
//        case OK:
//          myNetData.flip();
//          while (myNetData.hasRemaining()) {
//            socketChannel.write(myNetData);
//          }
//          logger.debug("Message sent to the server: " + message);
//          break;
//        case BUFFER_OVERFLOW:
//          myNetData = enlargePacketBuffer(engine, myNetData);
//          break;
//        case BUFFER_UNDERFLOW:
//          throw new SSLException("Buffer underflow occured after a wrap. I don't think we should ever get here.");
//        case CLOSED:
//          closeConnection(socketChannel, engine);
//          return;
//        default:
//          throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
//      }
//    }
//  }
//
//  protected ByteBuffer enlargeApplicationBuffer(SSLEngine engine, ByteBuffer buffer) {
//    return enlargeBuffer(buffer, engine.getSession().getApplicationBufferSize());
//  }
//
//  protected ByteBuffer enlargeBuffer(ByteBuffer buffer, int sessionProposedCapacity) {
//    if (sessionProposedCapacity > buffer.capacity()) {
//      buffer = ByteBuffer.allocate(sessionProposedCapacity);
//    } else {
//      buffer = ByteBuffer.allocate(buffer.capacity() * 2);
//    }
//    return buffer;
//  }
//
//  protected ByteBuffer enlargePacketBuffer(SSLEngine engine, ByteBuffer buffer) {
//    return enlargeBuffer(buffer, engine.getSession().getPacketBufferSize());
//  }
//
//  protected void handleEndOfStream(SocketChannel socketChannel, SSLEngine engine) throws IOException {
//    try {
//      engine.closeInbound();
//    } catch (Exception e) {
//      logger.error("This engine was forced to close inbound, without having received the proper SSL/TLS close notification message from the peer, due to end of stream.");
//    }
//    closeConnection(socketChannel, engine);
//  }
}