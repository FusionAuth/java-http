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

  private final ByteBuffer[] decryptedDataArray;

  private final ByteBuffer[] encryptedDataArray;

  private final SSLEngine engine;

  private final Logger logger;

  private ByteBuffer decryptedData;

  private HTTP11Processor delegate;

  private ByteBuffer encryptedData;

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
      this.decryptedDataArray = new ByteBuffer[]{decryptedData};
      this.encryptedDataArray = new ByteBuffer[]{encryptedData};

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
      this.decryptedDataArray = null;
      this.encryptedDataArray = null;
      this.decryptedData = null;
      this.encryptedData = null;
    }
  }

  @Override
  public ProcessorState close(boolean endOfStream) {
    if (this.engine == null) {
      return delegate.close(endOfStream);
    }

    logger.trace(count + " (HTTPS-CLOSE) {} {}", engine.isInboundDone(), engine.isOutboundDone());

    engine.getSession().invalidate();

    if (endOfStream) {
      try {
        engine.closeInbound();
      } catch (IOException e) {
        logger.trace(count + " (HTTPS-CLOSE) {}", e);
        // Smother
      }
    }

    delegate.close(endOfStream);
    engine.closeOutbound();
    state = HTTPSState.HandshakeWrite;
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
    logger.trace(count + " (HTTPS-READ) {} {} {}", encryptedData, decryptedData, state);
    delegate.markUsed();

    if (engine == null) {
      return delegate.read(buffer);
    }

    if (state == HTTPSState.HandshakeRead || state == HTTPSState.HandshakeWrite) {
      handshake();

      // We just got done handshaking and there weren't body bytes mixed in with the handshake data, which means we are done. If there were
      // bytes, we will fall through and might actually decrypt those bytes and pass them down
      if (state != HTTPSState.HandshakeRead && state != HTTPSState.HandshakeWrite && !decryptedData.hasRemaining()) {
        return toProcessorState();
      }
    }

    decrypt();

    return toProcessorState();
  }

  @Override
  public ByteBuffer readBuffer() {
    logger.trace(count + " (HTTPS-READ-BUFFER) {} {} {}", encryptedData, decryptedData, state);
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

    return toProcessorState();
  }

  public void updateDelegate(HTTP11Processor delegate) {
    this.delegate = delegate;
  }

  @Override
  public ByteBuffer[] writeBuffers() throws IOException {
    logger.trace(count + " (HTTPS-WRITE-BUFFERS) {} {} {}", encryptedData, decryptedData, state);
    delegate.markUsed();

    if (engine == null) {
      return delegate.writeBuffers();
    }

    // We haven't written it all out yet, so return the existing bytes in the encrypted/handshake buffer
    if (encryptedData.hasRemaining()) {
      return encryptedDataArray;
    }

    if (state == HTTPSState.HandshakeRead || state == HTTPSState.HandshakeWrite) {
      handshake();
    } else {
      encrypt();
    }

    return encryptedDataArray;
  }

  @Override
  public long writeThroughput() {
    return delegate.writeThroughput();
  }

  @Override
  public ProcessorState wrote(long num) throws IOException {
    delegate.markUsed();
    logger.trace(count + " (HTTPS-WROTE) {} {} {}", encryptedData, decryptedData, state);
    logger.trace(count + " (HTTPS-WROTE)DONE {} {} {}", encryptedData, decryptedData, state);
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
    var handshakeStatus = engine.getHandshakeStatus();
    if (handshakeStatus != HandshakeStatus.FINISHED && handshakeStatus != HandshakeStatus.NOT_HANDSHAKING) {
      throw new IllegalStateException("Unexpected handshake after the connection was in the body processing state.");
    }

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
        logger.trace(count + " (HTTPS-DECRYPT-UNDERFLOW) {} {} {}", encryptedData, decryptedData, state);
        encryptedData.compact(); // Compact for good measure and then go read some more
        return;
      }

      if (status == Status.CLOSED) {
        logger.trace(count + " (HTTPS-DECRYPT-CLOSE) {} {} {}", encryptedData, decryptedData, state);
        state = HTTPSState.Close;
        return;
      }

      copyToDelegate();
      decryptedData.clear(); // Should have been fully drained to delegate
      encryptedData.compact(); // Might have more bytes, so let's compact it
    }

    // Check if we are done reading
    state = switch (delegate.state()) {
      case Read -> HTTPSState.BodyRead;
      case Write -> HTTPSState.BodyWrite;
      case Close -> HTTPSState.Close;
      case Reset -> HTTPSState.Reset;
    };
  }

  private void handshake() throws SSLException {
    var handshakeStatus = engine.getHandshakeStatus();
    if (handshakeStatus == HandshakeStatus.FINISHED || handshakeStatus == HandshakeStatus.NOT_HANDSHAKING) {
      state = HTTPSState.BodyRead;
      return;
    }

    if (handshakeStatus == HandshakeStatus.NEED_TASK) {
      logger.trace(count + " (HTTPS-HANDSHAKE-TASK) {} {} {}", encryptedData, decryptedData, state);
      // Keep hard looping until the thread finishes (sucks but not sure what else to do here)
      do {
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
          task.run();
        }

        handshakeStatus = engine.getHandshakeStatus();
      } while (handshakeStatus == HandshakeStatus.NEED_TASK);
    }

    if (handshakeStatus == HandshakeStatus.NEED_UNWRAP || handshakeStatus == HandshakeStatus.NEED_UNWRAP_AGAIN) {
      logger.trace(count + " (HTTPS-HANDSHAKE-UNWRAP) {} {} {}", encryptedData, decryptedData, state);
      SSLEngineResult result = null;
      while (encryptedData.hasRemaining()) {
        logger.trace(count + " (HTTPS-HANDSHAKE-UNWRAP-BEFORE) {} {} {}", encryptedData, decryptedData, state);
        result = engine.unwrap(encryptedData, decryptedData);
        logger.trace(count + " (HTTPS-HANDSHAKE-UNWRAP-AFTER) {} {} {}", encryptedData, decryptedData, state);

        Status status = result.getStatus();
        if (status == Status.BUFFER_OVERFLOW) {
          throw new IllegalStateException("Handshake reading should never overflow the network buffer. It is sized such that it can handle a full TLS packet.");
        }

        if (status == Status.BUFFER_UNDERFLOW) {
          logger.trace(count + " (HTTPS-HANDSHAKE-UNWRAP-UNDERFLOW) {} {} {}", encryptedData, decryptedData, state);
          encryptedData.compact(); // Compact for good measure and then go read some more
          state = HTTPSState.HandshakeRead;
          return;
        }

        if (status == Status.CLOSED) {
          logger.trace(count + " (HTTPS-HANDSHAKE-UNWRAP-CLOSE) {} {} {}", encryptedData, decryptedData, state);
          state = HTTPSState.Close;
          return;
        }
      }

      // We never had any bytes to handle, bail!
      if (result == null) {
        logger.trace(count + " (HTTPS-HANDSHAKE-UNWRAP-EMPTY) {} {} {}", encryptedData, decryptedData, state);
        return;
      }

      state = switch (result.getHandshakeStatus()) {
        case NEED_WRAP -> HTTPSState.HandshakeWrite;
        case NEED_UNWRAP, NEED_UNWRAP_AGAIN -> HTTPSState.HandshakeRead;
        case FINISHED, NOT_HANDSHAKING -> HTTPSState.BodyRead;
        default -> throw new IllegalStateException("Handshaking went from read to task, which was unexpected");
      };
    } else if (handshakeStatus == HandshakeStatus.NEED_WRAP) {
      logger.trace(count + " (HTTPS-HANDSHAKE-WRAP) {} {} {}", encryptedData, decryptedData, state);
      SSLEngineResult result;
      do {
        logger.trace(count + " (HTTPS-HANDSHAKE-WRAP-BEFORE) {} {} {}", encryptedData, decryptedData, state);
        result = engine.wrap(decryptedData, encryptedData);
        logger.trace(count + " (HTTPS-HANDSHAKE-WRAP-AFTER) {} {} {}", encryptedData, decryptedData, state);
        if (result.getStatus() == Status.BUFFER_OVERFLOW) {
          logger.trace(count + " (HTTPS-HANDSHAKE-WRAP-OVERFLOW) {} {} {}", encryptedData, decryptedData, state);
          encryptedData = resizeBuffer(encryptedData, engine.getSession().getPacketBufferSize() + encryptedData.remaining());
        }
      } while (result.getStatus() == Status.BUFFER_OVERFLOW);

      if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
        throw new IllegalStateException("Handshake writing should never underflow the network buffer. The engine handles generating handshake data, so this should be impossible.");
      }

      if (result.getStatus() == Status.CLOSED) {
        logger.trace(count + " (HTTPS-HANDSHAKE-WRAP-CLOSE) {} {} {}", encryptedData, decryptedData, state);
        state = HTTPSState.Close;
        return;
      }

      state = switch (result.getHandshakeStatus()) {
        case NEED_WRAP -> HTTPSState.HandshakeWrite;
        case NEED_UNWRAP, NEED_UNWRAP_AGAIN -> HTTPSState.HandshakeRead;
        case FINISHED, NOT_HANDSHAKING -> HTTPSState.BodyRead;
        default -> throw new IllegalStateException("Handshaking went from read to task, which was unexpected");
      };
    }

    logger.trace(count + " (HTTPS-HANDSHAKE-DONE) {} {} {}", encryptedData, decryptedData, state);
  }

  private ByteBuffer resizeBuffer(ByteBuffer buffer, int engineSize) {
    if (engineSize > buffer.capacity()) {
      ByteBuffer newBuffer = ByteBuffer.allocate(engineSize + buffer.remaining());
      newBuffer.put(buffer);
      buffer = newBuffer;
    }

    return buffer;
  }

  private ProcessorState toProcessorState() {
    return switch (state) {
      case BodyRead, HandshakeRead -> ProcessorState.Read;
      case BodyWrite, HandshakeWrite -> ProcessorState.Write;
      case Close -> ProcessorState.Close;
      case Reset -> ProcessorState.Reset;
    };
  }

  public enum HTTPSState {
    BodyRead,
    BodyWrite,
    Close,
    HandshakeRead,
    HandshakeWrite,
    Reset,
  }
}
