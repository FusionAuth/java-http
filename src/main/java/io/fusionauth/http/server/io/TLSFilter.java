/*
 * Copyright (c) 2024, FusionAuth, All Rights Reserved
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
package io.fusionauth.http.server.io;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

import io.fusionauth.http.security.SecurityTools;
import io.fusionauth.http.server.HTTPListenerConfiguration;

public class TLSFilter {
  private final SSLEngine engine;

  private final InputStream inputStream;

  private final OutputStream outputStream;

  private final TLSInputStream tlsInputStream;

  private final TLSOutputStream tlsOutputStream;

  private ByteBuffer decryptedData;

  private ByteBuffer encryptedData;

  private ByteBuffer handshakeData;

  private HandshakeStatus handshakeStatus;

  public TLSFilter(HTTPListenerConfiguration listenerConfiguration, InputStream inputStream, OutputStream outputStream)
      throws GeneralSecurityException, IOException {
    this.inputStream = inputStream;
    this.outputStream = outputStream;
    this.tlsInputStream = new TLSInputStream();
    this.tlsOutputStream = new TLSOutputStream();

    SSLContext context = SecurityTools.serverContext(listenerConfiguration.getCertificateChain(), listenerConfiguration.getPrivateKey());
    this.engine = context.createSSLEngine();
    this.engine.setUseClientMode(false);

    SSLSession session = engine.getSession();
    this.decryptedData = ByteBuffer.allocate(session.getApplicationBufferSize());
    this.encryptedData = ByteBuffer.allocate(session.getPacketBufferSize());
    this.handshakeData = ByteBuffer.allocate(session.getPacketBufferSize());

    this.engine.beginHandshake();
    this.handshakeStatus = engine.getHandshakeStatus();
  }

  public InputStream getTLSInputStream() {
    return tlsInputStream;
  }

  public OutputStream getTLSOutputStream() {
    return tlsOutputStream;
  }

  private boolean decrypt() throws IOException {
    byte[] array = encryptedData.array();
    SSLEngineResult.Status status = null;
    while (status != Status.OK) {
      int read = inputStream.read(array, encryptedData.position(), encryptedData.capacity());
      if (read == 0) {
        continue;
      }

      if (read < 0) {
        return false;
      }

      encryptedData.position(encryptedData.position() + read);
      encryptedData.flip();
      decryptedData.clear();

      var result = engine.unwrap(encryptedData, decryptedData);
      status = result.getStatus();
      if (status == Status.BUFFER_OVERFLOW) {
        decryptedData = resizeBuffer(decryptedData, engine.getSession().getApplicationBufferSize());
        continue;
      }

      if (status == Status.CLOSED) {
        return false;
      }

      encryptedData.compact();
    }

    return true;
  }

  private void encrypt() {

  }

  private void handshake() throws IOException {
    while (isHandshaking()) {
      if (handshakeStatus == HandshakeStatus.NEED_UNWRAP || handshakeStatus == HandshakeStatus.NEED_UNWRAP_AGAIN) {
        byte[] array = handshakeData.array();
        int read = inputStream.read(array, handshakeData.position(), handshakeData.capacity());
        if (read == 0) {
          continue;
        }

        if (read == -1) {
          throw new IOException("End of stream reached while TLS handshaking.");
        }

        handshakeData.position(handshakeData.position() + read);
        handshakeData.flip();
        var result = engine.unwrap(handshakeData, decryptedData);
        var status = result.getStatus();
        handshakeStatus = result.getHandshakeStatus();

        if (status == Status.BUFFER_OVERFLOW) {
          throw new IllegalStateException("Handshake reading should never overflow the network buffer. It is sized such that it can handle a full TLS packet.");
        }

        if (status == Status.BUFFER_UNDERFLOW) {
          handshakeData.position(handshakeData.limit());
          handshakeData.limit(handshakeData.capacity());
          continue;
        }

        if (status == Status.CLOSED) {
          return;
        }

        // If there are body bytes, copy them over
        if (handshakeData.hasRemaining()) {
          encryptedData.put(handshakeData);
        }

        if (handshakeStatus != HandshakeStatus.NEED_UNWRAP && handshakeStatus != HandshakeStatus.NEED_UNWRAP_AGAIN) {
          encryptedData.clear();
        }
      }

      if (handshakeStatus == HandshakeStatus.NEED_TASK) {
        // Keep hard looping until the thread finishes (sucks but not sure what else to do here)
        do {
          Runnable task;
          while ((task = engine.getDelegatedTask()) != null) {
            task.run();
          }

          handshakeStatus = engine.getHandshakeStatus();
        } while (handshakeStatus == HandshakeStatus.NEED_TASK);
      }

      if (handshakeStatus == HandshakeStatus.NEED_WRAP) {
        var result = engine.wrap(decryptedData, encryptedData);
        var status = result.getStatus();
        handshakeStatus = result.getHandshakeStatus();

        if (status == Status.BUFFER_OVERFLOW) {
          encryptedData = resizeBuffer(encryptedData, engine.getSession().getPacketBufferSize() + encryptedData.remaining());
          continue;
        }

        if (status == Status.BUFFER_UNDERFLOW) {
          throw new IllegalStateException("Handshake writing should never underflow the network buffer. The engine handles generating handshake data, so this should be impossible.");
        }

        if (status == Status.CLOSED) {
          return;
        }

        encryptedData.flip();
        outputStream.write(encryptedData.array(), encryptedData.position(), encryptedData.limit());
        encryptedData.clear();
      }
    }
  }

  private boolean isHandshaking() {
    return handshakeStatus != HandshakeStatus.FINISHED && handshakeStatus != HandshakeStatus.NOT_HANDSHAKING;
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

  private class TLSInputStream extends InputStream {
    @Override
    public void close() throws IOException {
      inputStream.close();
    }

    @Override
    public int read(byte[] b, int offset, int length) throws IOException {
      handshake();
//      if (!readEncryptedData(length)) {
//        return 0;
//      }

      if (!decrypt()) {
        return 0;
      }

      if (!decryptedData.hasRemaining() && !decrypt()) {
        return -1;
      }

      if (decryptedData.hasRemaining()) {
        int read = Math.min(length, decryptedData.remaining());
        decryptedData.get(b, offset, read);
        return read;
      }

      return 0;
    }

    @Override
    public int read(byte[] b) throws IOException {
      return read(b, 0, b.length);
    }

    @Override
    public int read() throws IOException {
      handshake();

      if (!decryptedData.hasRemaining() && !decrypt()) {
        return -1;
      }

      if (decryptedData.hasRemaining()) {
        return decryptedData.get() & 0xFF;
      }

      return 0;
    }
  }

  private class TLSOutputStream extends OutputStream {
    @Override
    public void close() throws IOException {
      outputStream.close();
    }

    @Override
    public void flush() throws IOException {
      outputStream.flush();
    }

    @Override
    public void write(byte[] b) throws IOException {
      write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int offset, int length) throws IOException {
      handshake();

      int end = offset + length;
      while (offset < end) {
        decryptedData.clear();

        int wrote = Math.min(length, decryptedData.remaining());
        decryptedData.put(b, offset, wrote);
        encrypt();

        offset += wrote;
      }
    }

    @Override
    public void write(int b) throws IOException {
      handshake();
    }
  }
}