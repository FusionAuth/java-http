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

import io.fusionauth.http.ParseException;
import io.fusionauth.http.log.Logger;
import io.fusionauth.http.security.SecurityTools;

public class HTTPS11Processor implements HTTPProcessor {
  public static int count = 0;

  private final SSLEngine engine;

  private final Logger logger;

  private final ByteBuffer[] myAppData;

  private final ByteBuffer[] myNetData;

  private HTTP11Processor delegate;

  private ByteBuffer encryptedData;

  private volatile ProcessorState handshakeState;

  private ByteBuffer inboundDecryptedData;

  public HTTPS11Processor(HTTP11Processor delegate, HTTPServerConfiguration configuration, HTTPListenerConfiguration listenerConfiguration)
      throws GeneralSecurityException, IOException {
    System.out.println("Client " + (count++));
    this.delegate = delegate;
    this.logger = configuration.getLoggerFactory().getLogger(HTTPS11Processor.class);

    if (listenerConfiguration.isTLS()) {
      SSLContext context = SecurityTools.serverContext(listenerConfiguration.getCertificateChain(), listenerConfiguration.getPrivateKey());
      this.engine = context.createSSLEngine();
      this.engine.setUseClientMode(false);

      SSLSession session = engine.getSession();
      this.inboundDecryptedData = ByteBuffer.allocate(session.getApplicationBufferSize());
      this.encryptedData = ByteBuffer.allocate(session.getPacketBufferSize());
      this.myAppData = new ByteBuffer[]{inboundDecryptedData};
      this.myNetData = new ByteBuffer[]{encryptedData};

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
      this.inboundDecryptedData = null;
      this.encryptedData = null;
    }
  }

  @Override
  public ProcessorState close(boolean endOfStream) {
    if (this.engine == null) {
      return delegate.close(endOfStream);
    }

    logger.trace(count + " (HTTPS-C) {} {}", engine.isInboundDone(), engine.isOutboundDone());

    engine.getSession().invalidate();

    if (endOfStream) {
      try {
        engine.closeInbound();
      } catch (IOException e) {
        logger.trace(count + " (HTTPS-C) {}", e);
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
    logger.trace(count + " (HTTPS-F)");
    delegate.failure(t);
  }

  /**
   * TLS so we need to read and write during the handshake.
   *
   * @return {@link SelectionKey#OP_READ} {@code |} {@link SelectionKey#OP_WRITE}
   */
  @Override
  public int initialKeyOps() {
    logger.trace(count + " (HTTPS-A)");
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

  // TODO : at the end of handshake, the final read contains 90 bytes of handshake and 144 bytes of body
  //  then, we actually write the ack back to the the client of the handshake completing. During this ACK is when
  //  delete that body stuff
  @Override
  public ProcessorState read(ByteBuffer buffer) throws IOException {
    delegate.markUsed();

    if (engine == null) {
      return delegate.read(buffer);
    }

    // TODO : This likely wrong since we might have real bytes here!
//    peerAppData.clear();

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
      // TODO : This might contain handshake and preamble!!!!
      logger.trace(count + " (HTTPS-R) {} {}", encryptedData, inboundDecryptedData);
      result = engine.unwrap(encryptedData, inboundDecryptedData);
      logger.trace(count + " (HTTPS-R2) {} {}", encryptedData, inboundDecryptedData);

      encryptedData.compact();

      // If things got closed, just bail without doing any work
      status = result.getStatus();
      if (status == Status.CLOSED) {
        logger.trace(count + " (HTTPS-R-C)");
        return close(false);
      }

      if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
        logger.trace(count + " (HTTPS-R-UF)BUFFER_UNDERFLOW {} {}", encryptedData, inboundDecryptedData);
        encryptedData = resizeBuffer(encryptedData, engine.getSession().getPacketBufferSize());
        return ProcessorState.Read; // Keep reading
      }

      if (status == Status.BUFFER_OVERFLOW) {
        logger.trace(count + " (HTTPS-R-OF)BUFFER_OVERFLOW {} {}", encryptedData, inboundDecryptedData);
        inboundDecryptedData = resizeBuffer(inboundDecryptedData, engine.getSession().getApplicationBufferSize());
      }

      // If we are handshaking, then handle the handshake states
      var tlsStatus = result.getHandshakeStatus();
      if (tlsStatus != HandshakeStatus.FINISHED && tlsStatus != HandshakeStatus.NOT_HANDSHAKING) {
        logger.trace(count + " (HTTPS-R-HS) {} {}", encryptedData, inboundDecryptedData);
        var newTLSStatus = result.getHandshakeStatus();
        handshakeState = handleHandshake(newTLSStatus);
        logger.trace(count + " (HTTPS-R-HS) {} {}", encryptedData, inboundDecryptedData);
      } else {
        // Handle a normal read by passing the decrypted bytes down to the delegate
        logger.trace(count + " (HTTPS-R-RQ-R)");
        handshakeState = null;
        drainToDelegate();
        logger.trace(count + " (HTTPS-R-RQ-R2) {} {} {}", encryptedData, inboundDecryptedData, engine.getHandshakeStatus());
      }

      var newState = handshakeState != null ? handshakeState : delegate.state();
      if (newState == ProcessorState.Write) {
        // Set the position to the limit such that the write operation knows it is the first write (which clears the buffer in the write method)
        encryptedData.position(encryptedData.limit());
      }

      logger.trace(count + " (HTTPS-R-DONE) {} {} {} {}", handshakeState, newState, encryptedData, inboundDecryptedData);
    } while (encryptedData.hasRemaining());

    return null;
  }

  @Override
  public ByteBuffer readBuffer() {
    delegate.markUsed();

    if (engine == null) {
      return delegate.readBuffer();
    }

    // Always read into the peer network buffer
    return encryptedData;
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
    if (encryptedData.hasRemaining()) {
      return myNetData;
    }

    var tlsStatus = engine.getHandshakeStatus();
    if (tlsStatus == HandshakeStatus.NEED_UNWRAP) {
      handshakeState = ProcessorState.Read;
      return null;
    }

    ByteBuffer[] plainTextBuffers;
    if (tlsStatus == HandshakeStatus.NEED_WRAP) {
      logger.trace(count + " (HTTPS-W-HS)");
      plainTextBuffers = myAppData; // This buffer isn't actually used, so it can be anything
    } else {
      handshakeState = null;
      plainTextBuffers = delegate.writeBuffers();
    }

    if (plainTextBuffers == null) {
      return null;
    }

    encryptedData.clear();

    SSLEngineResult result;
    do {
      logger.trace(count + " (HTTPS-W) {} {}", encryptedData, inboundDecryptedData);
      result = engine.wrap(plainTextBuffers, encryptedData);
      logger.trace(count + " (HTTPS-W2) {} {}", encryptedData, inboundDecryptedData);

      // If things got closed, just bail without doing any work
      if (result.getStatus() == Status.CLOSED) {
        logger.trace(count + " (HTTPS-W-C) {} {}", encryptedData, inboundDecryptedData);
        close(false);
        return null;
      }

      if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
        throw new IllegalStateException("A buffer underflow is not expected during a wrap operation according to the Javadoc. Maybe this is something we need to fix.");
      }

      // If there is an overflow, resize the network buffer and retry the wrap
      if (result.getStatus() == Status.BUFFER_OVERFLOW) {
        logger.trace(count + " (HTTPS-W-OF) {} {}", encryptedData, inboundDecryptedData);
        encryptedData = resizeBuffer(encryptedData, engine.getSession().getApplicationBufferSize());
      }
    } while (result.getStatus() != Status.OK);

    logger.trace(count + " (HTTPS-W-DONE) {} {}", encryptedData, inboundDecryptedData);
    encryptedData.flip();
    return myNetData;
  }

  @Override
  public long writeThroughput() {
    return delegate.writeThroughput();
  }

  @Override
  public ProcessorState wrote(long num) throws IOException {
    delegate.markUsed();

    logger.trace(count + " (HTTPS-WROTE) {} {}", encryptedData, inboundDecryptedData);

    ProcessorState newState;
    if (handshakeState != null) {
      // TODO : What if we don't write all the handshake data to the client? I think this will screw up the newState and think we are in a Read,
      //  but really we are still in handshakeState of Write

      // TODO : Does this fix the TODO above?
      if (encryptedData.hasRemaining()) {
        throw new IllegalStateException("Oh shit!");
      }

      var tlsStatus = engine.getHandshakeStatus();
      newState = handleHandshake(tlsStatus);
      if (newState == ProcessorState.Read) {
        encryptedData.clear();
      }

      logger.trace(count + " (HTTPS-WROTE-HS)DONE {} {}", encryptedData, inboundDecryptedData);
      return newState;
    }

    newState = delegate.wrote(num);
    if (newState != ProcessorState.Write && encryptedData != null) {
      // The write-operation is done, but we still might need to read (Expect/Continue handling for example). Therefore, we need to clear the network
      // buffer to prepare for the read
      encryptedData.clear();
    }

    logger.trace(count + " (HTTPS-WROTE)DONE {} {}", encryptedData, inboundDecryptedData);
    return newState;
  }

  private void drainToDelegate() throws IOException {
    inboundDecryptedData.flip();
//    System.out.print(new String(peerAppData.array(), peerAppData.position(), peerAppData.limit()));

    while (inboundDecryptedData.hasRemaining()) {
      var buf = delegate.readBuffer();
      if (buf == null) {
        logger.trace(count + " (HTTPS-R-NULL)");
        throw new ParseException("Unable to complete HTTP request because the server thought the request was complete but the client sent more data");
      }

      logger.trace(count + " (HTTPS-R-DELEGATE) {} {}", buf, inboundDecryptedData);

      // Copy it
      int length = Math.min(buf.remaining(), inboundDecryptedData.remaining());
      buf.put(buf.position(), inboundDecryptedData, inboundDecryptedData.position(), length);
      buf.position(buf.position() + length);
      buf.flip();
      inboundDecryptedData.position(inboundDecryptedData.position() + length);
      logger.trace(count + " (HTTPS-R-DELEGATE)COPY {} {}", buf, inboundDecryptedData);

      // Pass it down
      var newState = delegate.read(buf);
      logger.trace(count + " (HTTPS-R-DELEGATE)DONE {}", newState);
    }

    inboundDecryptedData.clear();
  }

  private ProcessorState handleHandshake(HandshakeStatus newTLSStatus) {
    logger.trace(count + " (HTTPS-HS-{}) ", newTLSStatus);

    if (newTLSStatus == HandshakeStatus.NEED_TASK) {
      // Keep hard looping until the thread finishes (sucks but not sure what else to do here)
      do {
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
          task.run();
        }
        newTLSStatus = engine.getHandshakeStatus();
      } while (newTLSStatus == HandshakeStatus.NEED_TASK);
    }

    logger.trace(count + " (HTTPS-HS-{}) ", newTLSStatus);
    if (newTLSStatus == HandshakeStatus.NEED_UNWRAP || newTLSStatus == HandshakeStatus.NEED_UNWRAP_AGAIN) {
      handshakeState = ProcessorState.Read;
    } else if (newTLSStatus == HandshakeStatus.NEED_WRAP) {
      handshakeState = ProcessorState.Write;
    } else {
      if (!encryptedData.hasRemaining()) {
        logger.trace(count + " (HTTPS-HS-DONE-DATA) {}", newTLSStatus.name() + "-" + delegate.state());
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
