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
package io.fusionauth.http.util;

import java.nio.ByteBuffer;

import io.fusionauth.http.HTTPValues.ControlBytes;
import io.fusionauth.http.HTTPValues.ProtocolBytes;
import io.fusionauth.http.io.ByteBufferOutputStream;
import io.fusionauth.http.server.HTTPResponse;

public final class HTTPTools {
  /**
   * Builds the HTTP response head section (status line, headers, etc).
   *
   * @param response  The response.
   * @param maxLength The maximum length of the complete HTTP response head section.
   * @return The bytes of the response head section.
   */
  public static ByteBuffer buildExpectResponsePreamble(HTTPResponse response, int maxLength) {
    ByteBufferOutputStream bbos = new ByteBufferOutputStream(1024, maxLength);
    writeStatusLine(response, bbos);
    if (response.getStatus() != 100) {
      bbos.write("Content-Length: 0".getBytes());
      bbos.write(ControlBytes.CRLF);
      bbos.write("Connection: close".getBytes());
      bbos.write(ControlBytes.CRLF);
    }
    bbos.write(ControlBytes.CRLF);
    return bbos.toByteBuffer();
  }

  /**
   * Builds the HTTP response head section (status line, headers, etc).
   *
   * @param response  The response.
   * @param maxLength The maximum length of the complete HTTP response head section.
   * @return The bytes of the response head section.
   */
  public static ByteBuffer buildResponsePreamble(HTTPResponse response, int maxLength) {
    ByteBufferOutputStream bbos = new ByteBufferOutputStream(1024, maxLength);
    writeStatusLine(response, bbos);
    response.getHeadersMap().forEach((key, values) ->
        values.forEach(value -> {
          bbos.write(key.getBytes());
          bbos.write(':');
          bbos.write(' ');
          bbos.write(value.getBytes());
          bbos.write(ControlBytes.CRLF);
        }));
    bbos.write(ControlBytes.CRLF);
    return bbos.toByteBuffer();
  }

  /**
   * Determines if the given character (byte) is an allowed HTTP multipart boundary character.
   * <p>
   * Covered by https://www.w3.org/Protocols/rfc1341/7_2_Multipart.html
   *
   * @param ch The character as a byte since HTTP is ASCII.
   * @return True if the character is a multipart boundary character.
   */
  public static boolean isBoundaryCharacter(byte ch) {
    return ch == '\'' || ch == '(' || ch == ')' || ch == '+' || ch == '_' || ch == ',' || ch == '-' || ch == '.' || ch == '/' || ch == ':' ||
        ch == '=' || ch == '?' || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
  }

  /**
   * Determines if the given character (byte) is a digit (i.e. 0-9)
   *
   * @param ch The character as a byte since HTTP is ASCII.
   * @return True if the character is a digit.
   */
  public static boolean isDigitCharacter(byte ch) {
    return ch >= '0' && ch <= '9';
  }

  /**
   * Determines if the given character (byte) is an allowed hexadecimal character (i.e. 0-9a-zA-Z)
   *
   * @param ch The character as a byte since HTTP is ASCII.
   * @return True if the character is a hexadecimal character.
   */
  public static boolean isHexadecimalCharacter(byte ch) {
    return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
  }

  /**
   * Determines if the given character (byte) is an allowed HTTP token character (header field names, methods, etc).
   * <p>
   * Covered by https://www.rfc-editor.org/rfc/rfc9110.html#name-fields
   *
   * @param ch The character as a byte since HTTP is ASCII.
   * @return True if the character is a token character.
   */
  public static boolean isTokenCharacter(byte ch) {
    return ch == '!' || ch == '#' || ch == '$' || ch == '%' || ch == '&' || ch == '\'' || ch == '*' || ch == '+' || ch == '-' || ch == '.' ||
        ch == '^' || ch == '_' || ch == '`' || ch == '|' || ch == '~' || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') ||
        (ch >= '0' && ch <= '9');
  }

  /**
   * Naively determines if the given character (byte) is an allowed URI character.
   *
   * @param ch The character as a byte since URIs are ASCII.
   * @return True if the character is a URI character.
   */
  public static boolean isURICharacter(byte ch) {
    // TODO : Fully implement RFC 3986 to accurate parsing
    return ch >= '!' && ch <= '~';
  }

  public static boolean isValueCharacter(byte ch) {
    return isURICharacter(ch) || ch == ' ' || ch == '\t' || ch == '\n';
  }

  /**
   * Writes out the status line to the given OutputStream.
   *
   * @param response The response to pull the status information from.
   * @param out      The OutputStream.
   */
  private static void writeStatusLine(HTTPResponse response, ByteBufferOutputStream out) {
    out.write(ProtocolBytes.HTTTP1_1);
    out.write(' ');
    out.write(Integer.toString(response.getStatus()).getBytes());
    out.write(' ');
    if (response.getStatusMessage() != null) {
      out.write(response.getStatusMessage().getBytes());
    }
    out.write(ControlBytes.CRLF);
  }
}
