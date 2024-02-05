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
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.security.GeneralSecurityException;

import io.fusionauth.http.ParseException;
import io.fusionauth.http.log.Logger;
import io.fusionauth.http.security.SecurityTools;

public class HTTPS11ProcessorNew implements HTTPProcessor {
  public static int count = 0;

  private final ByteBuffer[] encryptedDataArray;

  private final SSLEngine engine;

  private final ByteBuffer[] handshakeDataArray;

  private final Logger logger;

  private ByteBuffer decryptedData;

  private HTTP11Processor delegate;

  private ByteBuffer encryptedData;

  private ByteBuffer handshakeData;

  private volatile HTTPSState state;

  public HTTPS11ProcessorNew(HTTP11Processor delegate, HTTPServerConfiguration configuration,
                             HTTPListenerConfiguration listenerConfiguration)
      throws GeneralSecurityException, IOException {
    this.delegate = delegate;
    this.logger = configuration.getLoggerFactory().getLogger(HTTPS11ProcessorNew.class);
    this.logger.trace("Client " + (count++));

    if (listenerConfiguration.isTLS()) {
      SSLContext context = SecurityTools.serverContext(listenerConfiguration.getCertificateChain(), listenerConfiguration.getPrivateKey());
      this.engine = context.createSSLEngine();
      this.engine.setUseClientMode(false);

      SSLSession session = engine.getSession();
      this.decryptedData = ByteBuffer.allocate(session.getApplicationBufferSize());
      this.encryptedData = ByteBuffer.allocate(session.getPacketBufferSize());
      this.handshakeData = ByteBuffer.allocate(session.getPacketBufferSize());
      this.encryptedDataArray = new ByteBuffer[]{encryptedData};
      this.handshakeDataArray = new ByteBuffer[]{handshakeData};

      engine.beginHandshake();
      HandshakeStatus tlsStatus = engine.getHandshakeStatus();
      if (tlsStatus == HandshakeStatus.NEED_UNWRAP) {
        this.state = HTTPSState.HandshakeRead;
      } else if (tlsStatus == HandshakeStatus.NEED_WRAP) {
        this.state = HTTPSState.HandshakeWrite;
      } else {
        throw new IllegalStateException("The SSLEngine is not in a valid state. It should be in the handshake state, but it is in the state [" + tlsStatus + "]");
      }
    } else {
      this.engine = null;
      this.decryptedData = null;
      this.encryptedData = null;
      this.encryptedDataArray = null;
      this.handshakeData = null;
      this.handshakeDataArray = null;
    }
  }

  @Override
  public ProcessorState close(boolean endOfStream) {
    if (this.engine == null) {
      return delegate.close(endOfStream);
    }

    logger.trace(count + " (HTTPS-CLOSE) {} {}", engine.isInboundDone(), engine.isOutboundDone());

    engine.getSession().invalidate();

//    if (endOfStream) {
//      try {
//        engine.closeInbound();
//      } catch (IOException e) {
//        logger.trace(count + " (HTTPS-CLOSE) {}", e);
//        // Smother
//      }
//    }

    try {
      delegate.close(endOfStream);
      engine.closeOutbound();
      state = HTTPSState.HandshakeWrite;

      encryptedData.clear();
      decryptedData.clear();
      var result = engine.wrap(decryptedData, encryptedData);
      logger.trace(count + " (HTTPS-CLOSE) {} {} {} {} {} {} {}", engine.isInboundDone(), engine.isOutboundDone(), encryptedData, decryptedData, result.getStatus(), result.getHandshakeStatus(), state);
    } catch (SSLException e) {
      // Ignore since we are closing
    }

    return toProcessorState();
  }

  @Override
  public void failure(Throwable t) {
    logger.trace(count + " (HTTPS-FAILURE)");
    delegate.failure(t);
    state = switch (delegate.state()) {
      case Close -> HTTPSState.Close;
      case Write -> HTTPSState.BodyWrite;
      default -> throw new IllegalStateException("Unexpected failure state from the HTTP11Processor (delegate to the HTTPS11Processor)");
    };
  }

  /**
   * TLS so we need to read and write during the handshake.
   *
   * @return {@link SelectionKey#OP_READ} {@code |} {@link SelectionKey#OP_WRITE}
   */
  @Override
  public int initialKeyOps() {
    logger.trace(count + " (HTTPS-ACCEPT)");
    if (engine == null) {
      return delegate.initialKeyOps();
    }

    return toProcessorState() == ProcessorState.Read ? SelectionKey.OP_READ : SelectionKey.OP_WRITE;
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
    logger.trace(count + " (HTTPS-READ) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
    delegate.markUsed();

    // HTTPS is disabled
    if (engine == null) {
      return delegate.read(buffer);
    }

    if (state == HTTPSState.HandshakeRead || state == HTTPSState.HandshakeWrite) {
      handshake();

      if (handshakeData.hasRemaining()) {
        // This shouldn't happen, but let's resize just in case
        if (handshakeData.remaining() > encryptedData.remaining()) {
          logger.trace(count + " (HTTPS-READ-RESIZE-AFTER-HANDSHAKE-BEFORE) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
          encryptedData = resizeBuffer(encryptedData, encryptedData.capacity() + handshakeData.remaining());
          logger.trace(count + " (HTTPS-READ-RESIZE-AFTER-HANDSHAKE-AFTER) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
        }

        encryptedData.put(handshakeData);
        logger.trace(count + " (HTTPS-READ-COPY-AFTER-HANDSHAKE) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
      }

      // We've got more handshaking to do
      if (state == HTTPSState.HandshakeRead || state == HTTPSState.HandshakeWrite) {
        handshakeData.clear();
        return toProcessorState();
      }

      if (encryptedData.hasRemaining()) {
        // We are no longer handshaking, but there are bytes, which we should assume are body bytes and fall through to the code below
        state = HTTPSState.BodyRead;
        encryptedData.compact();
      } else {
        // We are done handshaking, clear the buffer to start the body read
        encryptedData.clear();
      }
    }

    decrypt();

    // Check if we are done reading
    state = switch (delegate.state()) {
      case Read -> HTTPSState.BodyRead;
      case Write -> HTTPSState.BodyWrite;
      case Close -> HTTPSState.Close;
      case Reset -> HTTPSState.Reset;
    };

    logger.trace(count + " (HTTPS-READ-DONE) {} {} {}", encryptedData, decryptedData, state);
    return toProcessorState();
  }

  @Override
  public ByteBuffer readBuffer() {
    logger.trace(count + " (HTTPS-READ-BUFFER) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
    delegate.markUsed();

    // HTTPS is disabled
    if (engine == null) {
      return delegate.readBuffer();
    }

    // Always read into the peer network buffer
    return state == HTTPSState.HandshakeRead ? handshakeData : encryptedData;
  }

  @Override
  public long readThroughput() {
    return delegate.readThroughput();
  }

  @Override
  public ProcessorState state() {
    // HTTPS is disabled
    if (engine == null) {
      return delegate.state();
    }

    return toProcessorState();
  }

  /**
   * Updates the delegate in order to reset the state of it (HTTP state machine). This also resets the TLS state back to a BodyRead
   *
   * @param delegate The new delegate.
   */
  public void updateDelegate(HTTP11Processor delegate) {
    this.delegate = delegate;
    this.state = HTTPSState.BodyRead;

    // Reset all the buffers since this is a new request/response cycle
    if (engine != null) {
      this.decryptedData.clear();
      this.encryptedData.clear();
      this.handshakeData.clear();
    }
  }

  @Override
  public ByteBuffer[] writeBuffers() throws IOException {
    logger.trace(count + " (HTTPS-WRITE-BUFFERS) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
    delegate.markUsed();

    // HTTPS is disabled
    if (engine == null) {
      return delegate.writeBuffers();
    }

    // We haven't written it all out yet, so return the existing bytes in the encrypted or handshake buffer
    if (state == HTTPSState.HandshakeWriting && handshakeData.hasRemaining()) {
      return handshakeDataArray;
    }

    if (state == HTTPSState.BodyWriting && encryptedData.hasRemaining()) {
      return encryptedDataArray;
    }

    if (state == HTTPSState.HandshakeRead || state == HTTPSState.HandshakeWrite) {
      handshake();
    } else {
      encrypt();
    }

    // If we aren't writing, bail
    if (state != HTTPSState.BodyWriting && state != HTTPSState.HandshakeWriting) {
      return null;
    }

    return state == HTTPSState.HandshakeWriting ? handshakeDataArray : encryptedDataArray;
  }

  @Override
  public long writeThroughput() {
    return delegate.writeThroughput();
  }

  @Override
  public ProcessorState wrote(long num) throws IOException {
    delegate.markUsed();

    // HTTPS is disabled
    if (engine == null) {
      return delegate.wrote(num);
    }

    logger.trace(count + " (HTTPS-WROTE) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);

    if (state == HTTPSState.HandshakeWriting && !handshakeData.hasRemaining()) {
      handshakeData.clear();

      var handshakeStatus = engine.getHandshakeStatus();
      state = switch (handshakeStatus) {
        case NEED_WRAP -> HTTPSState.HandshakeWrite;
        case NEED_UNWRAP, NEED_UNWRAP_AGAIN -> HTTPSState.HandshakeRead;
        case FINISHED, NOT_HANDSHAKING -> HTTPSState.BodyRead;
        default -> throw new IllegalStateException("Handshaking went from write to task, which was unexpected");
      };

      // This means we had body data during handshaking, and we need to handle it here
      if (state == HTTPSState.BodyRead && encryptedData.position() > 0) {
        encryptedData.flip();
        read(encryptedData);
      }
    } else {
      // Check if we are done writing
      var newState = delegate.wrote(num);
      state = switch (newState) {
        case Read -> HTTPSState.BodyRead;
        case Write -> HTTPSState.BodyWrite;
        case Close -> HTTPSState.Close;
        case Reset -> HTTPSState.Reset;
      };

      if (state == HTTPSState.BodyRead) {
        // This condition should never happen because the write-operation should have written out the entire buffer
        if (encryptedData.hasRemaining()) {
          throw new IllegalStateException("The encrypted data still has data to write, but the HTTP processor changed states.");
        }

        // Clear the encrypted side of the buffer to start the read
        encryptedData.clear();
      }
    }

    logger.trace(count + " (HTTPS-WROTE-DONE) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
    return toProcessorState();
  }

  private void copyToDelegate() throws IOException {
    decryptedData.flip();

    while (decryptedData.hasRemaining()) {
      var buf = delegate.readBuffer();
      if (buf == null) {
        logger.trace(count + " (HTTPS-DECRYPT-COPY-TO-DELEGATE-NULL)");
        throw new ParseException("Unable to complete HTTP request because the server thought the request was complete but the client sent more data");
      }

      logger.trace(count + " (HTTPS-DECRYPT-COPY-TO-DELEGATE) {} {} {}", encryptedData, decryptedData, state);

      // Copy it
      int length = Math.min(buf.remaining(), decryptedData.remaining());
      buf.put(buf.position(), decryptedData, decryptedData.position(), length);
      buf.position(buf.position() + length);
      buf.flip();
      decryptedData.position(decryptedData.position() + length);
      logger.trace(count + " (HTTPS-DECRYPT-COPY-TO-DELEGATE-COPIED) {} {} {}", encryptedData, decryptedData, state);

      // Pass it down
      var newState = delegate.read(buf);
      logger.trace(count + " (HTTPS-DECRYPT-COPY-TO-DELEGATE-DONE) {} {} {} {}", encryptedData, decryptedData, state, newState);
    }
  }

  private void decrypt() throws IOException {
//    var handshakeStatus = engine.getHandshakeStatus();
//    if (handshakeStatus != HandshakeStatus.FINISHED && handshakeStatus != HandshakeStatus.NOT_HANDSHAKING) {
//      throw new IllegalStateException("Unexpected handshake after the connection was in the body processing state.");
//    }

    if (state != HTTPSState.BodyRead) {
      throw new IllegalStateException("Somehow we got into a state of [" + state + "] but should be in BodyRead.");
    }

    boolean overflowedAlready = false;
    SSLEngineResult result;
    while (encryptedData.hasRemaining()) {
      logger.trace(count + " (HTTPS-DECRYPT-BEFORE) {} {} {}", encryptedData, decryptedData, state);
      result = engine.unwrap(encryptedData, decryptedData);
      logger.trace(count + " (HTTPS-DECRYPT-AFTER) {} {} {}", encryptedData, decryptedData, state);

      Status status = result.getStatus();
      if (status == Status.BUFFER_OVERFLOW) {
        logger.trace(count + " (HTTPS-DECRYPT-OVERFLOW) {} {} {}", encryptedData, decryptedData, state);
        if (overflowedAlready) {
          throw new IllegalStateException("We already overflowed the decryption buffer and resized it, so this is extremely unexpected.");
        }

        overflowedAlready = true;
        decryptedData = resizeBuffer(decryptedData, engine.getSession().getApplicationBufferSize());
        continue;
      }

      if (status == Status.BUFFER_UNDERFLOW) {
        logger.trace(count + " (HTTPS-DECRYPT-UNDERFLOW-BEFORE) {} {} {}", encryptedData, decryptedData, state);
        encryptedData.compact(); // Compact for good measure and then go read some more
        logger.trace(count + " (HTTPS-DECRYPT-UNDERFLOW-AFTER) {} {} {}", encryptedData, decryptedData, state);
        return;
      }

      if (status == Status.CLOSED) {
        logger.trace(count + " (HTTPS-DECRYPT-CLOSE) {} {} {}", encryptedData, decryptedData, state);
        state = HTTPSState.Close;
        return;
      }

      copyToDelegate();
      decryptedData.clear(); // Should have been fully drained to delegate
    }

    encryptedData.clear();
  }

  private void encrypt() throws SSLException {
    logger.trace(count + " (HTTPS-ENCRYPT) {} {} {}", encryptedData, decryptedData, state);
    var buffers = delegate.writeBuffers();
    if (buffers == null || buffers.length == 0) {
      return;
    }

    // This is safe because we only get here when there are no more bytes remaining to write
    encryptedData.clear();
    logger.trace(count + " (HTTPS-ENCRYPT-CLEAR) {} {} {}", encryptedData, decryptedData, state);

    Status status;
    do {
      logger.trace(count + " (HTTPS-ENCRYPT-WRAP-BEFORE) {} {} {}", encryptedData, decryptedData, state);
      var result = engine.wrap(buffers, encryptedData);
      status = result.getStatus();
      logger.trace(count + " (HTTPS-ENCRYPT-WRAP-AFTER) {} {} {} {}", encryptedData, decryptedData, state, status);

      // We don't have enough bytes from the application, let the worker thread keep processing and we'll be back
      if (status == Status.BUFFER_UNDERFLOW) {
        return;
      }

      if (status == Status.CLOSED) {
        state = HTTPSState.Close;
        return;
      }

      if (status == Status.BUFFER_OVERFLOW) {
        encryptedData = resizeBuffer(encryptedData, engine.getSession().getPacketBufferSize());
        logger.trace(count + " (HTTPS-ENCRYPT-RESIZE) {} {} {}", encryptedData, decryptedData, state);
      }
    } while (status == Status.BUFFER_OVERFLOW);

    encryptedData.flip();
    state = HTTPSState.BodyWriting;
  }

  private HandshakeStatus handleHandshakeTask(HandshakeStatus handshakeStatus) {
    if (handshakeStatus == HandshakeStatus.NEED_TASK) {
      logger.trace(count + " (HTTPS-HANDSHAKE-TASK) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
      // Keep hard looping until the thread finishes (sucks but not sure what else to do here)
      do {
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
          task.run();
        }

        handshakeStatus = engine.getHandshakeStatus();
      } while (handshakeStatus == HandshakeStatus.NEED_TASK);

      logger.trace(count + " (HTTPS-HANDSHAKE-TASK-DONE) {} {} {} {} {}", handshakeData, encryptedData, decryptedData, state, handshakeStatus);
    }

    return handshakeStatus;
  }

  private void handshake() throws SSLException {
    var handshakeStatus = engine.getHandshakeStatus();
    if (handshakeStatus == HandshakeStatus.FINISHED || handshakeStatus == HandshakeStatus.NOT_HANDSHAKING) {
      state = HTTPSState.BodyRead;
      return;
    }

    handshakeStatus = handleHandshakeTask(handshakeStatus);

    if (handshakeStatus == HandshakeStatus.NEED_UNWRAP || handshakeStatus == HandshakeStatus.NEED_UNWRAP_AGAIN) {
      logger.trace(count + " (HTTPS-HANDSHAKE-UNWRAP) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
      SSLEngineResult result = null;
      while ((handshakeStatus == HandshakeStatus.NEED_UNWRAP || handshakeStatus == HandshakeStatus.NEED_UNWRAP_AGAIN) && handshakeData.hasRemaining()) {
        logger.trace(count + " (HTTPS-HANDSHAKE-UNWRAP-BEFORE) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
        result = engine.unwrap(handshakeData, decryptedData);
        logger.trace(count + " (HTTPS-HANDSHAKE-UNWRAP-AFTER) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);

        Status status = result.getStatus();
        if (status == Status.BUFFER_OVERFLOW) {
          throw new IllegalStateException("Handshake reading should never overflow the network buffer. It is sized such that it can handle a full TLS packet.");
        }

        if (status == Status.BUFFER_UNDERFLOW) {
          logger.trace(count + " (HTTPS-HANDSHAKE-UNWRAP-UNDERFLOW) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
          handshakeData.compact(); // Compact for good measure and then go read some more
          state = HTTPSState.HandshakeRead;
          return;
        }

        if (status == Status.CLOSED) {
          logger.trace(count + " (HTTPS-HANDSHAKE-UNWRAP-CLOSE) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
          state = HTTPSState.Close;
          return;
        }

        handshakeStatus = result.getHandshakeStatus();
        handshakeStatus = handleHandshakeTask(handshakeStatus); // In case the handshake immediately went into a NEED_TASK mode
      }

      // We never had any bytes to handle, bail!
      if (result == null) {
        logger.trace(count + " (HTTPS-HANDSHAKE-UNWRAP-EMPTY) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
        return;
      }

      state = switch (handshakeStatus) {
        case NEED_WRAP -> HTTPSState.HandshakeWrite;
        case NEED_UNWRAP, NEED_UNWRAP_AGAIN -> HTTPSState.HandshakeRead;
        case FINISHED, NOT_HANDSHAKING -> HTTPSState.BodyRead;
        default -> throw new IllegalStateException("Handshaking got back into a NEED_TASK mode and should have handled that above.");
      };
    } else if (handshakeStatus == HandshakeStatus.NEED_WRAP) {
      logger.trace(count + " (HTTPS-HANDSHAKE-WRAP) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
      SSLEngineResult result;
      Status status;
      do {
        logger.trace(count + " (HTTPS-HANDSHAKE-WRAP-BEFORE) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
        result = engine.wrap(decryptedData, handshakeData);
        status = result.getStatus();
        logger.trace(count + " (HTTPS-HANDSHAKE-WRAP-AFTER) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
        if (status == Status.BUFFER_OVERFLOW) {
          logger.trace(count + " (HTTPS-HANDSHAKE-WRAP-OVERFLOW) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
          handshakeData = resizeBuffer(handshakeData, engine.getSession().getPacketBufferSize() + handshakeData.remaining());
        }
      } while (status == Status.BUFFER_OVERFLOW);

      if (status == Status.BUFFER_UNDERFLOW) {
        throw new IllegalStateException("Handshake writing should never underflow the network buffer. The engine handles generating handshake data, so this should be impossible.");
      }

      if (status == Status.CLOSED) {
        logger.trace(count + " (HTTPS-HANDSHAKE-WRAP-CLOSE) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
        state = HTTPSState.Close;
        return;
      }

      // Should have the next chunk of handshake data to write, so transition to the writing mode
      handshakeData.flip();
      state = HTTPSState.HandshakeWriting;
    }

    logger.trace(count + " (HTTPS-HANDSHAKE-DONE) {} {} {} {}", handshakeData, encryptedData, decryptedData, state);
  }

  private ByteBuffer resizeBuffer(ByteBuffer buffer, int engineSize) {
    if (engineSize > buffer.capacity()) {
      ByteBuffer newBuffer = ByteBuffer.allocate(engineSize + buffer.remaining());
      newBuffer.put(buffer);
      buffer = newBuffer;
    } else {
      buffer.compact();
    }

    return buffer;
  }

  private ProcessorState toProcessorState() {
    return switch (state) {
      case BodyRead, HandshakeRead -> ProcessorState.Read;
      case BodyWrite, BodyWriting, HandshakeWrite, HandshakeWriting -> ProcessorState.Write;
      case Close -> ProcessorState.Close;
      case Reset -> ProcessorState.Reset;
    };
  }

  public enum HTTPSState {
    BodyRead,
    BodyWrite,
    BodyWriting,
    Close,
    HandshakeRead,
    HandshakeWrite,
    HandshakeWriting,
    Reset,
  }
}
