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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.security.GeneralSecurityException;

import io.fusionauth.http.log.Logger;
import io.fusionauth.http.security.SecurityTools;

public class HTTPS11Processor implements HTTPProcessor {
  private final HTTP11Processor delegate;

  private final SSLEngine engine;

  private final Logger logger;

  private final ByteBuffer[] myAppData;

  private final ByteBuffer[] myNetData;

  private final ByteBuffer peerAppData;

  private ByteBuffer peerNetData;

  private volatile ProcessorState state;

  public HTTPS11Processor(HTTP11Processor delegate, HTTPServerConfiguration configuration, HTTPListenerConfiguration listenerConfiguration)
      throws GeneralSecurityException, IOException {
    this.delegate = delegate;
    this.logger = configuration.getLoggerFactory().getLogger(HTTPS11Processor.class);

    if (listenerConfiguration.isTLS()) {
      SSLContext context = SecurityTools.getServerContext(listenerConfiguration.getCertificate(), listenerConfiguration.getPrivateKey());
      this.engine = context.createSSLEngine();
      this.engine.setUseClientMode(false);

      SSLSession session = engine.getSession();
      this.myAppData = new ByteBuffer[]{ByteBuffer.allocate(session.getApplicationBufferSize())};
      this.myNetData = new ByteBuffer[]{ByteBuffer.allocate(session.getPacketBufferSize())};
      this.peerAppData = ByteBuffer.allocate(session.getApplicationBufferSize());
      this.peerNetData = ByteBuffer.allocate(session.getPacketBufferSize());

      engine.beginHandshake();
      HandshakeStatus tlsStatus = engine.getHandshakeStatus();
      if (tlsStatus == HandshakeStatus.NEED_UNWRAP) {
        this.state = ProcessorState.Read;
      } else if (tlsStatus == HandshakeStatus.NEED_WRAP) {
        this.state = ProcessorState.Write;
      } else {
        throw new IllegalStateException("The SSLEngine is not in a valid state. It should be in the handshake state, but it is in the state [" + tlsStatus + "]");
      }
    } else {
      this.engine = null;
      this.myAppData = null;
      this.myNetData = null;
      this.peerAppData = null;
      this.peerNetData = null;
    }
  }

  @Override
  public ProcessorState close() {
    logger.trace("(HTTPS-C)");

    if (this.engine == null) {
      return delegate.close();
    }

    throw new UnsupportedOperationException();
  }

  @Override
  public void failure(Throwable t) {
    logger.trace("(HTTPS-F)");
    delegate.failure(t);
  }

  /**
   * TLS so we need to read and write during the handshake.
   *
   * @return {@link SelectionKey#OP_READ} {@code |} {@link SelectionKey#OP_WRITE}
   */
  @Override
  public int initialKeyOps() {
    logger.trace("(HTTPS-A)");
    if (engine == null) {
      return delegate.initialKeyOps();
    }

    return SelectionKey.OP_READ | SelectionKey.OP_WRITE;
  }

  /**
   * @return The delegate's lastUsed().
   */
  @Override
  public long lastUsed() {
    return delegate.lastUsed();
  }

  @Override
  public ProcessorState read(ByteBuffer buffer) throws IOException {
    delegate.markUsed();

    if (engine == null) {
      return delegate.read(buffer);
    }

    var tlsStatus = engine.getHandshakeStatus();
    ByteBuffer decryptBuffer;
    if (tlsStatus != HandshakeStatus.NOT_HANDSHAKING && tlsStatus != HandshakeStatus.FINISHED) {
      decryptBuffer = peerAppData;
    } else {
      decryptBuffer = delegate.readBuffer();
    }

    // TODO : Not sure if this is correct
    if (decryptBuffer == null) {
      state = ProcessorState.Write;
      return state;
    }

    var result = engine.unwrap(buffer, decryptBuffer);
    if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
      peerNetData = handleBufferUnderflow(peerNetData);
      return state;
    } else if (result.getStatus() == Status.CLOSED) {
      return close();
    } else if (result.getStatus() == Status.BUFFER_OVERFLOW) {
      throw new IllegalStateException("A buffer underflow is not expected during a wrap operation according to the Javadoc. Maybe this is something we need to fix.");
    }

    var newTLSStatus = result.getHandshakeStatus();
    if (tlsStatus == HandshakeStatus.NOT_HANDSHAKING || tlsStatus == HandshakeStatus.FINISHED) {
      decryptBuffer.flip();
      state = delegate.read(decryptBuffer);
    } else if (newTLSStatus == HandshakeStatus.NEED_UNWRAP || newTLSStatus == HandshakeStatus.NEED_UNWRAP_AGAIN) {
      state = ProcessorState.Read;
    } else if (newTLSStatus == HandshakeStatus.NEED_WRAP) {
      state = ProcessorState.Write;
    }

    return state;
  }

  @Override
  public ByteBuffer readBuffer() {
    delegate.markUsed();

    if (engine == null) {
      return delegate.readBuffer();
    }

    // Always read into the peer network buffer
    return peerNetData;
  }

  @Override
  public ProcessorState state() {
    delegate.markUsed();

    if (engine == null) {
      return delegate.state();
    }

    throw new UnsupportedOperationException();
  }

  @Override
  public ByteBuffer[] writeBuffers() throws IOException {
    delegate.markUsed();

    if (engine == null) {
      return delegate.writeBuffers();
    }

    var tlsStatus = engine.getHandshakeStatus();
    ByteBuffer[] buffers;
    if (tlsStatus == HandshakeStatus.NEED_WRAP) {
      buffers = myAppData;
    } else {
      buffers = delegate.writeBuffers();
    }

    if (buffers == null) {
      return null;
    }

    var result = engine.wrap(buffers, myNetData[0]);
    if (result.getStatus() == Status.BUFFER_OVERFLOW) {
      myNetData[0] = handleBufferOverflow(myNetData[0]);
    } else if (result.getStatus() == Status.CLOSED) {
      close();
      return null;
    } else if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
      throw new IllegalStateException("A buffer underflow is not expected during a wrap operation according to the Javadoc. Maybe this is something we need to fix.");
    }

    return myNetData;
  }

  @Override
  public ProcessorState wrote(long num) {
    delegate.markUsed();

    // If we are in a handshake that needs to read or just finished the handshake, flip the selector back to Read
    var tlsStatus = engine != null ? engine.getHandshakeStatus() : null;
    if (tlsStatus == HandshakeStatus.NEED_UNWRAP || tlsStatus == HandshakeStatus.NEED_UNWRAP_AGAIN || tlsStatus == HandshakeStatus.FINISHED) {
      state = ProcessorState.Read;
      return state;
    }

    return delegate.wrote(num);
  }

  private ByteBuffer handleBufferOverflow(ByteBuffer buffer) {
    int applicationSize = engine.getSession().getApplicationBufferSize();
    ByteBuffer newBuffer = ByteBuffer.allocate(applicationSize + buffer.position());
    buffer.flip();
    newBuffer.put(buffer);
    return newBuffer;
  }

  private ByteBuffer handleBufferUnderflow(ByteBuffer buffer) {
    int networkSize = engine.getSession().getPacketBufferSize();
    if (networkSize > buffer.capacity()) {
      ByteBuffer newBuffer = ByteBuffer.allocate(networkSize);
      buffer.flip();
      newBuffer.put(buffer);
      buffer = newBuffer;
    }

    return buffer;
  }

  //  private static final ByteBuffer Empty = ByteBuffer.allocate(0);
//
//  private final SSLEngine engine;
//
//  private ByteBuffer myAppData;
//
//  private ByteBuffer myNetData;
//
//  private ByteBuffer peerAppData;
//
//  private ByteBuffer peerNetData;
//
//  private TLSState tlsState;
//
//  public HTTPS11Processor(HTTPServerConfiguration configuration, Notifier notifier, ByteBuffer preambleBuffer, ThreadPool threadPool,
//                          InetAddress ipAddress) throws GeneralSecurityException, IOException {
//    super(configuration, notifier, preambleBuffer, threadPool, ipAddress);
//
//    SSLContext context = SecurityTools.getServerContext(configuration.getCertificateString(), configuration.getPrivateKeyString());
//    this.engine = context.createSSLEngine();
//    this.engine.setUseClientMode(false);
//
//    SSLSession session = engine.getSession();
//    this.myAppData = ByteBuffer.allocate(session.getApplicationBufferSize());
//    this.myNetData = ByteBuffer.allocate(session.getPacketBufferSize());
//    this.peerAppData = ByteBuffer.allocate(session.getApplicationBufferSize());
//    this.peerNetData = ByteBuffer.allocate(session.getPacketBufferSize());
//  }
//
//  @Override
//  public void accept(SelectionKey key, SocketChannel clientChannel) throws IOException {
//    // Start in the handshake process and update the interested ops, so we can handshake in both directions
//    clientChannel.configureBlocking(false);
//    clientChannel.register(key.selector(), SelectionKey.OP_READ | SelectionKey.OP_WRITE, this);
//    tlsState = TLSState.Handshake;
//  }
//
//  @Override
//  public void close(SelectionKey key, SocketChannel client, boolean endOfStream) {
//    if (endOfStream) {
//      try {
//        engine.closeInbound();
//      } catch (SSLException e) {
//        // Smother
//      }
//    }
//
//    tlsState = TLSState.Closing;
//    engine.closeOutbound();
//    key.interestOps(SelectionKey.OP_WRITE);
//  }
//
//  @Override
//  public int read(SelectionKey key) throws IOException {
//    // If the server is closing this connection, we won't read anymore bytes
//    if (tlsState == TLSState.Closing) {
//      return 0;
//    }
//
//    if (tlsState == TLSState.Handshake) {
//      var status = engine.getHandshakeStatus();
//
//      // This means we need to write, so we should bail from this read
//      if (status == HandshakeStatus.NEED_WRAP) {
//        return 0;
//      }
//
//      // We somehow got out of the handshake but didn't update the tlsState, fixing that
//      if (status == HandshakeStatus.FINISHED || status == HandshakeStatus.NOT_HANDSHAKING) {
//        tlsState = TLSState.RequestResponse;
//      }
//    }
//
//    return super.read(key);
//  }
//
//  @Override
//  public long write(SelectionKey key) throws IOException {
//    if (tlsState == TLSState.Closing) {
//      return 0;
//    }
//
//    if (tlsState == TLSState.Handshake) {
//      var status = engine.getHandshakeStatus();
//
//      // This means we need to read, so we should bail from this write
//      if (status == HandshakeStatus.NEED_UNWRAP) {
//        return 0;
//      }
//
//      // We somehow got out of the handshake but didn't update the tlsState, fixing that
//      if (status == HandshakeStatus.FINISHED || status == HandshakeStatus.NOT_HANDSHAKING) {
//        tlsState = TLSState.RequestResponse;
//      }
//    }
//
//    return super.write(key);
//  }
//
//  /**
//   * We always read into the peerNetData buffer.
//   *
//   * @param state Not used.
//   * @return The peerNetData buffer.
//   */
//  @Override
//  protected ByteBuffer locateRequestByteBuffer(RequestState state) {
//    return peerNetData;
//  }
//
//  @Override
//  protected ByteBuffer[] locateResponseByteBuffers() {
//    if (myNetData.hasRemaining()) {
//      return new ByteBuffer[]{myNetData};
//    }
//
//    ByteBuffer[] buffers = super.locateResponseByteBuffers();
//    if (buffers == null) {
//      return null;
//    }
//
//    myNetData.clear();
//    engine.wrap(buffers, myNetData);
//    return super.locateResponseByteBuffers();
//  }
//
//  @Override
//  protected int processRequest(RequestState state, SelectionKey key, ByteBuffer buffer, int read) throws IOException {
//    buffer.flip();
//
//    if (tlsState == TLSState.Handshake) {
//
//    }
//
//    read = 0;
//    while (buffer.hasRemaining()) {
//      peerAppData.clear();
//
//      SSLEngineResult result = engine.unwrap(buffer, peerAppData);
//      switch (result.getStatus()) {
//        case OK -> {
//          peerAppData.flip();
//          read += peerAppData.remaining();
//
//          while (peerAppData.hasRemaining()) {
//            ByteBuffer requestBuffer = super.locateRequestByteBuffer(state);
//            int length = Math.min(peerAppData.remaining(), requestBuffer.remaining());
//            requestBuffer.put(requestBuffer.position(), peerAppData, peerAppData.position(), length);
//            super.processRequest(state, key, requestBuffer, read);
//          }
//        }
//
//        case BUFFER_OVERFLOW -> peerAppData = handleBufferOverflow(peerAppData);
//        case BUFFER_UNDERFLOW -> peerNetData = handleBufferUnderflow(peerNetData);
//        case CLOSED -> {
//          close(key, (SocketChannel) key.channel());
//          return read;
//        }
//      }
//    }
//
//    return read;
//  }
//
//
//  private HandshakeStatus handleHandshake(SelectionKey key, SocketChannel client) throws IOException {
//    HandshakeStatus status = engine.getHandshakeStatus();
//    if (status != HandshakeStatus.FINISHED && status != HandshakeStatus.NOT_HANDSHAKING) {
//      switch (status) {
//        case NEED_UNWRAP -> status = handshakeUnwrap(client, key);
//        case NEED_WRAP -> status = handshakeWrap(client);
//        case NEED_TASK -> {
//          Runnable task;
//          while ((task = engine.getDelegatedTask()) != null) {
//            new TLSThread(task, "TLS negotiator thread").start();
//          }
//
//          status = engine.getHandshakeStatus();
//        }
//      }
//
//      // This means the handshake failed. Close the TLS connection
//      if (status == null) {
//        close(key, client);
//        return null;
//      }
//
//      // Not quite done yet, return null, which tells the caller to exit the method. This doesn't close anything, so it should all be groovy
//      if (status != HandshakeStatus.FINISHED && status != HandshakeStatus.NOT_HANDSHAKING) {
//        return null;
//      }
//
//      // Reset all the buffers to get ready for the request/response
//      myAppData.clear();
//      myNetData.clear();
//      peerAppData.clear();
//      peerNetData.clear();
//    }
//
//    return status;
//  }
//
//  /**
//   * Unwraps bytes that are coming in from the peer during the TLS handshake process.
//   *
//   * @param client The socket to read bytes from.
//   * @param key    The key for the client.
//   * @return The handshake status if the handshake is still valid and can continue. Null if the handshake failed.
//   * @throws IOException If the read operation failed because the connection was broken.
//   */
//  private HandshakeStatus handshakeUnwrap(SocketChannel client, SelectionKey key) throws IOException {
//    peerNetData.clear();
//
//    HandshakeStatus status = engine.getHandshakeStatus();
//    int read = client.read(peerNetData);
//    if (read == 0) {
//      return status;
//    }
//
//    if (read == -1) {
//      handleEndOfStream(key, client);
//      return null;
//    }
//
//    peerNetData.flip();
//
//    SSLEngineResult result;
//    try {
//      result = engine.unwrap(peerNetData, peerAppData);
//      peerNetData.compact();
//      status = result.getHandshakeStatus();
//    } catch (SSLException sslException) {
//      logger.error("A problem was encountered while processing the data that caused the SSLEngine to abort. Will try to properly close connection...");
//      engine.closeOutbound();
//      return engine.getHandshakeStatus();
//    }
//
//    switch (result.getStatus()) {
//      case BUFFER_OVERFLOW -> peerAppData = handleBufferOverflow(peerAppData);
//      case BUFFER_UNDERFLOW -> peerNetData = handleBufferUnderflow(peerNetData);
//      case CLOSED -> {
//        // Can't close in the middle of a handshake
//        return null;
//      }
//    }
//
//    return status;
//  }
//
//  private HandshakeStatus handshakeWrap(SocketChannel socketChannel) throws IOException {
//    myNetData.clear();
//
//    SSLEngineResult result;
//    HandshakeStatus handshakeStatus;
//    try {
//      result = engine.wrap(myAppData, myNetData);
//      handshakeStatus = result.getHandshakeStatus();
//    } catch (SSLException sslException) {
//      logger.error("A problem was encountered while processing the data that caused the SSLEngine to abort. Will try to properly close connection...");
//      engine.closeOutbound();
//      return engine.getHandshakeStatus();
//    }
//
//    switch (result.getStatus()) {
//      case OK -> {
//        myNetData.flip();
//        while (myNetData.hasRemaining()) {
//          socketChannel.write(myNetData);
//        }
//      }
//      case BUFFER_OVERFLOW -> myNetData = handleBufferOverflow(myNetData);
//      case BUFFER_UNDERFLOW -> myAppData = handleBufferUnderflow(myAppData);
//      case CLOSED -> {
//        try {
//          myNetData.flip();
//          while (myNetData.hasRemaining()) {
//            socketChannel.write(myNetData);
//          }
//          // At this point the handshake status will probably be NEED_UNWRAP, so we make sure that peerNetData is clear to read.
//          peerNetData.clear();
//        } catch (Exception e) {
//          logger.error("Failed to send server's CLOSE message due to socket channel's failure.");
//          handshakeStatus = engine.getHandshakeStatus();
//        }
//      }
//    }
//
//    return handshakeStatus;
//  }
//
//  public enum TLSState {
//    Handshake,
//    RequestResponse,
//    Closing
//  }
//
//  private class TLSThread extends Thread {
//    public TLSThread(Runnable task, String name) {
//      super(task, name);
//    }
//
//    public void run() {
//      super.run();
//
//      // TODO : this is a race condition with the main thread to clear these buffers
//      HandshakeStatus status = engine.getHandshakeStatus();
//      if (status == HandshakeStatus.FINISHED || status == HandshakeStatus.NOT_HANDSHAKING) {
//        myAppData.clear();
//        myNetData.clear();
//        peerAppData.clear();
//        peerNetData.clear();
//      }
//    }
//  }
}
