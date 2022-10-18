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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.fusionauth.http.log.Logger;
import io.fusionauth.http.security.SecurityTools;

public class HTTPS11Processor implements HTTPProcessor {
  private static final AtomicInteger threadCount = new AtomicInteger(1);

  private static final ExecutorService executor = Executors.newCachedThreadPool(r -> new Thread(r, "TLS Handshake Thread " + threadCount.getAndIncrement()));

  private final SSLEngine engine;

  private final Logger logger;

  private final ByteBuffer[] myAppData;

  private final ByteBuffer[] myNetData;

  private final ByteBuffer peerAppData;

  private HTTP11Processor delegate;

  private volatile ProcessorState handshakeState;

  private ByteBuffer peerNetData;

  public HTTPS11Processor(HTTP11Processor delegate, HTTPServerConfiguration configuration, HTTPListenerConfiguration listenerConfiguration)
      throws GeneralSecurityException, IOException {
    this.delegate = delegate;
    this.logger = configuration.getLoggerFactory().getLogger(HTTPS11Processor.class);

    if (listenerConfiguration.isTLS()) {
      SSLContext context = SecurityTools.serverContext(listenerConfiguration.getCertificate(), listenerConfiguration.getPrivateKey());
      this.engine = context.createSSLEngine();
      this.engine.setUseClientMode(false);

      SSLSession session = engine.getSession();
      this.myAppData = new ByteBuffer[]{ByteBuffer.allocate(session.getApplicationBufferSize())};
      this.myNetData = new ByteBuffer[]{ByteBuffer.allocate(session.getPacketBufferSize())};
      this.peerAppData = ByteBuffer.allocate(session.getApplicationBufferSize());
      this.peerNetData = ByteBuffer.allocate(session.getPacketBufferSize());

      // Set the remaining on the myNetData to be 0. This is how we tell the write operation that we have nothing to write, so it can handshake/wrap
      this.myNetData[0].flip();

      engine.beginHandshake();
      HandshakeStatus tlsStatus = engine.getHandshakeStatus();
      if (tlsStatus == HandshakeStatus.NEED_UNWRAP) {
        this.handshakeState = ProcessorState.Read;
      } else if (tlsStatus == HandshakeStatus.NEED_WRAP) {
        this.handshakeState = ProcessorState.Write;
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
  public ProcessorState close(boolean endOfStream) {
    logger.trace("(HTTPS-C)");

    if (this.engine == null) {
      return delegate.close(endOfStream);
    }

    if (endOfStream) {
      try {
        engine.closeInbound();
      } catch (IOException e) {
        // Smother
      }
    }

    delegate.close(endOfStream);
    handshakeState = ProcessorState.Write;
    engine.closeOutbound();
    return handshakeState;
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

    return handshakeState == ProcessorState.Read ? SelectionKey.OP_READ : SelectionKey.OP_WRITE;
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
      logger.trace("(HTTPS-R-HS)" + tlsStatus);
      decryptBuffer = peerAppData;
    } else {
      logger.trace("(HTTPS-R-RQ)");
      handshakeState = null;
      decryptBuffer = delegate.readBuffer();
    }

    // TODO : Not sure if this is correct
    if (decryptBuffer == null) {
      logger.trace("(HTTPS-R-NULL)");
      return delegate.state();
    }

    // Unwrapping using one of these cases:
    //    Handshake Data (plain text or encrypted/signed) ---> Ignore (this side doesn't matter as it is internal to SSLEngine)
    //        * If OK - might need TASK
    //        * If Underflow - Handshake Data was not enough or there isn't enough space in the buffer
    //        * If Overflow - ??
    //    Encrypted Data ---> Plain Text Data (send to the HTTP handler)
    //        * If OK - send the decrypted data to the app
    //        * If Underflow - Encrypted Data was not enough or there isn't enough space in the network buffer
    //        * If Overflow - The encrypted data was larger than the buffer the app is using (Preamble or body buffers)
    var result = engine.unwrap(peerNetData, decryptBuffer);

    // This will always put position at limit, so if there is data in the buffer, it will always be at the start and position will be greater than 0
    // Therefore, we will need to flip this if we are resizing (i.e. for an underflow)
    peerNetData.compact();

    if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
      logger.trace("(HTTPS-R-UF)");
      peerNetData = handleBufferUnderflow(peerNetData);
      return handshakeState != null ? handshakeState : delegate.state(); // Keep reading
    } else if (result.getStatus() == Status.CLOSED) {
      logger.trace("(HTTPS-R-C)");
      return close(false);
    } else if (result.getStatus() == Status.BUFFER_OVERFLOW) {
      throw new IllegalStateException("A buffer overflow is not expected during an unwrap operation. This occurs because the preamble or body buffers are too small. Increase their sizes to avoid this issue.");
    }

    if (tlsStatus == HandshakeStatus.NOT_HANDSHAKING || tlsStatus == HandshakeStatus.FINISHED) {
      logger.trace("(HTTPS-R-RQ-R)");
      handshakeState = null;
      decryptBuffer.flip();
      return delegate.read(decryptBuffer);
    }

    var newTLSStatus = result.getHandshakeStatus();
    return handleHandshake(newTLSStatus);
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

    return handshakeState != null ? handshakeState : delegate.state();
  }

  public void updateDelegate(HTTP11Processor delegate) {
    this.delegate = delegate;
  }

  @Override
  public ByteBuffer[] writeBuffers() throws IOException {
    delegate.markUsed();

    if (engine == null) {
      return delegate.writeBuffers();
    }

    // We haven't written it all out yet, so return the existing bytes in the encrypted/handshake buffer
    if (myNetData[0].hasRemaining()) {
      return myNetData;
    }

    var tlsStatus = engine.getHandshakeStatus();
    if (tlsStatus == HandshakeStatus.NEED_UNWRAP) {
      handshakeState = ProcessorState.Read;
      return null;
    }

    // TODO : can bytes be in the handshake that clear() doesn't even provide enough protection
    ByteBuffer[] plainTextBuffers;
    if (tlsStatus == HandshakeStatus.NEED_WRAP) {
      logger.trace("(HTTPS-W-HS)");
      myAppData[0].clear(); // TODO : Always clear for the handshake??
      plainTextBuffers = myAppData;
    } else {
      handshakeState = null;
      plainTextBuffers = delegate.writeBuffers();
    }

    if (plainTextBuffers == null) {
      return null;
    }

    myNetData[0].clear();
    var result = engine.wrap(plainTextBuffers, myNetData[0]);
    if (result.getStatus() == Status.BUFFER_OVERFLOW) {
      logger.trace("(HTTPS-W-OF)");
      myNetData[0] = handleBufferOverflow(myNetData[0]);
    } else if (result.getStatus() == Status.CLOSED) {
      logger.trace("(HTTPS-W-C)");
      close(false);
      return null;
    } else if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
      throw new IllegalStateException("A buffer underflow is not expected during a wrap operation according to the Javadoc. Maybe this is something we need to fix.");
    } else {
      logger.trace("(HTTPS-W-RQ)");
      myNetData[0].flip();
    }

    return myNetData;
  }

  @Override
  public ProcessorState wrote(long num) throws IOException {
    delegate.markUsed();

    if (handshakeState == null) {
      return delegate.wrote(num);
    }

    var tlsStatus = engine.getHandshakeStatus();
    return handleHandshake(tlsStatus);
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

  private ProcessorState handleHandshake(HandshakeStatus newTLSStatus) throws IOException {
    if (newTLSStatus == HandshakeStatus.NEED_TASK) {
      logger.trace("(HTTPS-HS-T)");

      // Keep hard looping until the thread finishes (sucks but not sure what else to do here)
      // TODO : is this sucky?
      do {
        newTLSStatus = engine.getHandshakeStatus();

        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
          executor.submit(task);
        }
      } while (newTLSStatus == HandshakeStatus.NEED_TASK);
    }

    if (newTLSStatus == HandshakeStatus.NEED_UNWRAP || newTLSStatus == HandshakeStatus.NEED_UNWRAP_AGAIN) {
      logger.trace("(HTTPS-HS-R)");
      handshakeState = ProcessorState.Read;
    } else if (newTLSStatus == HandshakeStatus.NEED_WRAP) {
      logger.trace("(HTTPS-HS-W)");
      handshakeState = ProcessorState.Write;
    } else {
      logger.trace("(HTTPS-HS-DONE)" + newTLSStatus.name());

      if (!myNetData[0].hasRemaining()) {
        logger.trace("(HTTPS-HS-DONE)" + newTLSStatus.name() + "-" + delegate.state());
        handshakeState = null;

        // This indicates that the client sent along part of the HTTP request preamble with its last handshake. We need to consume that before we continue
        if (peerNetData.position() > 0) {
          peerNetData.flip();
          return read(peerNetData);
        }

        return delegate.state();
      }
    }

    return handshakeState;
  }
}
