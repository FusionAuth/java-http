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
import javax.net.ssl.SSLEngineResult;
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

import io.fusionauth.http.ParseException;
import io.fusionauth.http.log.Logger;
import io.fusionauth.http.security.SecurityTools;

public class HTTPS11Processor implements HTTPProcessor {
  private static final AtomicInteger threadCount = new AtomicInteger(1);

  private static final ExecutorService executor = Executors.newCachedThreadPool(r -> new Thread(r, "TLS Handshake Thread " + threadCount.getAndIncrement()));

  private final SSLEngine engine;

  private final Logger logger;

  private final ByteBuffer[] myAppData;

  private final ByteBuffer[] myNetData;

  private HTTP11Processor delegate;

  private volatile ProcessorState handshakeState;

  private ByteBuffer peerAppData;

  private ByteBuffer peerNetData;

  public HTTPS11Processor(HTTP11Processor delegate, HTTPServerConfiguration configuration, HTTPListenerConfiguration listenerConfiguration)
      throws GeneralSecurityException, IOException {
    this.delegate = delegate;
    this.logger = configuration.getLoggerFactory().getLogger(HTTPS11Processor.class);

    if (listenerConfiguration.isTLS()) {
      SSLContext context = SecurityTools.serverContext(listenerConfiguration.getCertificateChain(), listenerConfiguration.getPrivateKey());
      this.engine = context.createSSLEngine();
      this.engine.setUseClientMode(false);

      SSLSession session = engine.getSession();
      this.peerAppData = ByteBuffer.allocate(session.getApplicationBufferSize());
      this.peerNetData = ByteBuffer.allocate(session.getPacketBufferSize());
      this.myAppData = new ByteBuffer[]{peerAppData};
      this.myNetData = new ByteBuffer[]{peerNetData};

      // Set the remaining on the myNetData to be 0. This is how we tell the write operation that we have nothing to write, so it can handshake/wrap
//      this.myNetData[0].flip();

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
    handshakeState = ProcessorState.Write; // TODO : is this correct?
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

    peerAppData.clear();

    // Unwrapping using one of these cases:
    //    Handshake Data (plain text or encrypted/signed) ---> Ignore (this side doesn't matter as it is internal to SSLEngine)
    //        * If OK - might need TASK
    //        * If Underflow - Handshake Data was not enough or there isn't enough space in the buffer
    //        * If Overflow - ??
    //    Encrypted Data ---> Plain Text Data (send to the HTTP handler)
    //        * If OK - send the decrypted data to the app
    //        * If Underflow - Encrypted Data was not enough or there isn't enough space in the network buffer
    //        * If Overflow - The encrypted data was larger than the buffer the app is using (Preamble or body buffers)
    SSLEngineResult result;
    Status status;
    do {
      logger.trace("(HTTPS-R) {} {}", peerNetData, peerAppData);
      result = engine.unwrap(peerNetData, peerAppData);
      logger.trace("(HTTPS-R2) {} {}", peerNetData, peerAppData);

      // If things got closed, just bail without doing any work
      status = result.getStatus();
      if (status == Status.CLOSED) {
        logger.trace("(HTTPS-R-C)");
        return close(false);
      }

      if (status == Status.BUFFER_OVERFLOW) {
        logger.trace("(HTTPS-R-OF)BUFFER_OVERFLOW {} {}", peerNetData, peerAppData);
        peerAppData = resizeBuffer(peerAppData, engine.getSession().getApplicationBufferSize());
      }
    } while (status == Status.BUFFER_OVERFLOW);

    peerNetData.compact();

    if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
      logger.trace("(HTTPS-R-UF)BUFFER_UNDERFLOW {} {}", peerNetData, peerAppData);
      peerNetData = resizeBuffer(peerNetData, engine.getSession().getPacketBufferSize());
//      return handshakeState != null ? handshakeState : delegate.state(); // Keep reading
      return ProcessorState.Read; // Keep reading
    }

    // If we are handshaking, then handle the handshake states
    var tlsStatus = result.getHandshakeStatus();
    if (tlsStatus != HandshakeStatus.FINISHED && tlsStatus != HandshakeStatus.NOT_HANDSHAKING) {
      logger.trace("(HTTPS-R-HS) {} {}", peerNetData, peerAppData);
      var newTLSStatus = result.getHandshakeStatus();
      handshakeState = handleHandshake(newTLSStatus);
      logger.trace("(HTTPS-R-HS) {} {}", peerNetData, peerAppData);
    } else {
      // Handle a normal read by passing the decrypted bytes down to the delegate
      logger.trace("(HTTPS-R-RQ-R)");
      handshakeState = null;
      drainToDelegate();
      logger.trace("(HTTPS-R-RQ-R2) {} {} {}", peerNetData, peerAppData, engine.getHandshakeStatus());
    }

    var newState = handshakeState != null ? handshakeState : delegate.state();
    if (newState == ProcessorState.Write) {
      // Set the position to the limit such that the write operation knows it is the first write (which clears the buffer in the write method)
      peerNetData.position(peerNetData.limit());
    }

    logger.trace("(HTTPS-R-DONE) {} {} {} {}", handshakeState, newState, peerNetData, peerAppData);
    return newState;
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
  public long readThroughput() {
    return delegate.readThroughput();
  }

  @Override
  public ProcessorState state() {
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
    if (peerNetData.hasRemaining()) {
      return myNetData;
    }

    var tlsStatus = engine.getHandshakeStatus();
    if (tlsStatus == HandshakeStatus.NEED_UNWRAP) {
      handshakeState = ProcessorState.Read;
      return null;
    }

    ByteBuffer[] plainTextBuffers;
    if (tlsStatus == HandshakeStatus.NEED_WRAP) {
      logger.trace("(HTTPS-W-HS)");
      plainTextBuffers = myAppData; // This buffer isn't actually used, so it can be anything
    } else {
      handshakeState = null;
      plainTextBuffers = delegate.writeBuffers();
    }

    if (plainTextBuffers == null) {
      return null;
    }

    peerNetData.clear();

    SSLEngineResult result;
    do {
      logger.trace("(HTTPS-W) {} {}", peerNetData, peerAppData);
      result = engine.wrap(plainTextBuffers, peerNetData);
      logger.trace("(HTTPS-W2) {} {}", peerNetData, peerAppData);

      // If things got closed, just bail without doing any work
      if (result.getStatus() == Status.CLOSED) {
        logger.trace("(HTTPS-W-C) {} {}", peerNetData, peerAppData);
        close(false);
        return null;
      }

      if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
        throw new IllegalStateException("A buffer underflow is not expected during a wrap operation according to the Javadoc. Maybe this is something we need to fix.");
      }

      // If there is an overflow, resize the network buffer and retry the wrap
      if (result.getStatus() == Status.BUFFER_OVERFLOW) {
        logger.trace("(HTTPS-W-OF)");
        peerNetData = resizeBuffer(peerNetData, engine.getSession().getApplicationBufferSize());
      }
    } while (result.getStatus() == Status.BUFFER_OVERFLOW);

    logger.trace("(HTTPS-W-DONE)");
    peerNetData.flip();
    return myNetData;
  }

  @Override
  public long writeThroughput() {
    return delegate.writeThroughput();
  }

  @Override
  public ProcessorState wrote(long num) throws IOException {
    delegate.markUsed();

    logger.trace("(HTTPS-WROTE) {} {}", peerNetData, peerAppData);

    ProcessorState newState;
    if (handshakeState != null) {
      var tlsStatus = engine.getHandshakeStatus();
      newState = handleHandshake(tlsStatus);
      if (newState == ProcessorState.Read) {
        peerNetData.clear();
      }

      return newState;
    }

    newState = delegate.wrote(num);
    if (newState != ProcessorState.Write && peerNetData != null) {
      // The write-operation is done, but we still might need to read (Expect/Continue handling for example). Therefore, we need to clear the network
      // buffer to prepare for the read
      peerNetData.clear();
    }

    return newState;
  }

  private void drainToDelegate() throws IOException {
    peerAppData.flip();
//    System.out.print(new String(peerAppData.array(), peerAppData.position(), peerAppData.limit()));

    while (peerAppData.hasRemaining()) {
      var buf = delegate.readBuffer();
      if (buf == null) {
        logger.trace("(HTTPS-R-NULL)");
        throw new ParseException("Unable to complete HTTP request because the server thought the request was complete but the client sent more data");
      }

      logger.trace("(HTTPS-R-DELEGATE) {} {}", buf, peerAppData);

      // Copy it
      int length = Math.min(buf.remaining(), peerAppData.remaining());
      buf.put(buf.position(), peerAppData, peerAppData.position(), length);
      buf.position(buf.position() + length);
      buf.flip();
      peerAppData.position(peerAppData.position() + length);
      logger.trace("(HTTPS-R-DELEGATE)COPY {} {}", buf, peerAppData);

      // Pass it down
      var newState = delegate.read(buf);
      logger.trace("(HTTPS-R-DELEGATE)DONE {}", newState);
    }

    peerAppData.clear();
  }

  private ProcessorState handleHandshake(HandshakeStatus newTLSStatus) {
    if (newTLSStatus == HandshakeStatus.NEED_UNWRAP_AGAIN) {
      handshakeState = ProcessorState.Read;
      return handshakeState;
    }

    if (newTLSStatus == HandshakeStatus.NEED_TASK) {
      logger.trace("(HTTPS-HS-TASK)");

      // Keep hard looping until the thread finishes (sucks but not sure what else to do here)
      do {
        newTLSStatus = engine.getHandshakeStatus();

        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
          executor.submit(task);
        }
      } while (newTLSStatus == HandshakeStatus.NEED_TASK);
    }

    if (newTLSStatus == HandshakeStatus.NEED_UNWRAP) {
      logger.trace("(HTTPS-HS-UNWRAP)");
      handshakeState = ProcessorState.Read;
    } else if (newTLSStatus == HandshakeStatus.NEED_WRAP) {
      logger.trace("(HTTPS-HS-WRAP)");
      handshakeState = ProcessorState.Write;
    } else {
      logger.trace("(HTTPS-HS-DONE) {}", newTLSStatus.name());

      if (!peerNetData.hasRemaining()) {
        logger.trace("(HTTPS-HS-DONE-DATA) {}", newTLSStatus.name() + "-" + delegate.state());
        handshakeState = null;
        return delegate.state();
      }
    }

    return handshakeState;
  }

  private ByteBuffer resizeBuffer(ByteBuffer buffer, int engineSize) {
    if (engineSize > buffer.capacity()) {
      ByteBuffer newBuffer = ByteBuffer.allocate(engineSize + buffer.remaining());
      newBuffer.put(buffer);
      buffer = newBuffer;
    }

    return buffer;
  }
}
