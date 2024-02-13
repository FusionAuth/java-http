/*
 * Copyright (c) 2022-2023, FusionAuth, All Rights Reserved
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import io.fusionauth.http.HTTPMethod;
import io.fusionauth.http.HTTPValues.ControlBytes;
import io.fusionauth.http.HTTPValues.ProtocolBytes;
import io.fusionauth.http.ParseException;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPResponse;
import io.fusionauth.http.server.RequestPreambleState;

public final class HTTPTools {
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
   * Parses URL encoded data either from a URL parameter list in the query string or the form body.
   *
   * @param data   The data as a character array.
   * @param start  The start index to start parsing from.
   * @param length The length to parse.
   * @param result The result Map to put the value into.
   */
  public static void parseEncodedData(byte[] data, int start, int length, Map<String, List<String>> result) {
    boolean inName = true;
    String name = null;
    String value;
    for (int i = start; i < length; i++) {
      if (data[i] == '=' && inName) {
        // Names can't start with an equal sign
        if (i == start) {
          start++;
          continue;
        }

        inName = false;

        try {
          name = URLDecoder.decode(new String(data, start, i - start), StandardCharsets.UTF_8);
        } catch (Exception e) {
          name = null; // Malformed
        }

        start = i + 1;
      } else if (data[i] == '&' && !inName) {
        inName = true;

        if (name == null || start > i) {
          continue; // Malformed
        }

        //noinspection DuplicatedCode
        try {
          if (start < i) {
            value = URLDecoder.decode(new String(data, start, i - start), StandardCharsets.UTF_8);
          } else {
            value = "";
          }

          result.computeIfAbsent(name, key -> new LinkedList<>()).add(value);
        } catch (Exception ignore) {
          // Ignore
        }

        start = i + 1;
        name = null;
      }
    }

    if (name != null && !inName) {
      //noinspection DuplicatedCode
      try {
        if (start < length) {
          value = URLDecoder.decode(new String(data, start, length - start), StandardCharsets.UTF_8);
        } else {
          value = "";
        }

        result.computeIfAbsent(name, key -> new LinkedList<>()).add(value);
      } catch (Exception ignore) {
        // Ignore
      }
    }
  }

  /**
   * Parses an HTTP header value that is a standard semicolon separated list of values.
   *
   * @param value The header value.
   * @return The HeaderValue record.
   */
  public static HeaderValue parseHeaderValue(String value) {
    String headerValue = null;
    Map<String, String> parameters = null;
    char[] chars = value.toCharArray();
    boolean inQuote = false;
    int start = 0;
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      if (!inQuote && c == ';') {
        if (headerValue == null) {
          headerValue = new String(chars, start, i - start);
        } else {
          if (parameters == null) {
            parameters = new HashMap<>();
          }

          parseHeaderParameter(chars, start, i, parameters);
        }

        start = -1;
      } else if (!inQuote && !Character.isWhitespace(c) && start == -1) {
        start = i;
      } else if (!inQuote && c == '"') {
        inQuote = true;
      } else if (inQuote && c == '\\' && i < chars.length - 2 && chars[i + 1] == '"') {
        i++; // Skip the next quote character since it is escaped
      } else if (inQuote && c == '"') {
        inQuote = false;
      }
    }

    // Add any final part
    if (start != -1) {
      if (headerValue == null) {
        headerValue = new String(chars, start, chars.length - start);
      } else {
        if (parameters == null) {
          parameters = new HashMap<>();
        }

        parseHeaderParameter(chars, start, chars.length, parameters);
      }
    }

    if (headerValue == null) {
      throw new ParseException("Unable to parse a parameterized HTTP header [" + value + "]");
    }

    if (parameters == null) {
      parameters = Map.of();
    }

    return new HeaderValue(headerValue, parameters);
  }

  /**
   * Parses the request preamble directly from the given InputStream.
   *
   * @param inputStream The input stream to read the preamble from.
   * @param request     The HTTP request to populate.
   * @return Any leftover body bytes from the last read from the InputStream.
   * @throws IOException If the read fails.
   */
  public static byte[] parseRequestPreamble(InputStream inputStream, HTTPRequest request) throws IOException {
    RequestPreambleState state = RequestPreambleState.RequestMethod;
    StringBuilder builder = new StringBuilder();
    String headerName = null;

    byte[] buffer = new byte[1024];
    int read = 0;
    int index = 0;
    while (state != RequestPreambleState.Complete) {
      read = inputStream.read(buffer);
      if (read < 0) {
        throw new IOException("Client closed the socket.");
      }

      for (index = 0; index < read && state != RequestPreambleState.Complete; index++) {
        // If there is a state transition, store the value properly and reset the builder (if needed)
        byte ch = buffer[index];
        RequestPreambleState nextState = state.next(ch);
        if (nextState != state) {
          switch (state) {
            case RequestMethod -> request.setMethod(HTTPMethod.of(builder.toString()));
            case RequestPath -> request.setPath(builder.toString());
            case RequestProtocol -> request.setProtocol(builder.toString());
            case HeaderName -> headerName = builder.toString();
            case HeaderValue -> request.addHeader(headerName, builder.toString());
          }

          // If the next state is storing, reset the builder
          if (nextState.store()) {
            builder.delete(0, builder.length());
            builder.appendCodePoint(ch);
          }
        } else if (state.store()) {
          // If the current state is storing, store the character
          builder.appendCodePoint(ch);
        }

        state = nextState;
      }
    }

    byte[] leftover = null;
    if (index < read) {
      leftover = Arrays.copyOfRange(buffer, index, read);
    }

    return leftover;
  }

  /**
   * Writes the HTTP response head section (status line, headers, etc).
   *
   * @param response     The response.
   * @param outputStream The output stream to write the preamble to.
   * @throws IOException If the stream threw an exception.
   */
  public static void writeExpectResponsePreamble(HTTPResponse response, OutputStream outputStream) throws IOException {
    writeStatusLine(response, outputStream);
    if (response.getStatus() != 100) {
      outputStream.write("Content-Length: 0".getBytes());
      outputStream.write(ControlBytes.CRLF);
      outputStream.write("Connection: close".getBytes());
      outputStream.write(ControlBytes.CRLF);
    }
    outputStream.write(ControlBytes.CRLF);
  }

  /**
   * Writes the HTTP response head section (status line, headers, etc).
   *
   * @param response     The response.
   * @param outputStream The output stream to write the preamble to.
   * @throws IOException If the stream threw an exception.
   */
  public static void writeResponsePreamble(HTTPResponse response, OutputStream outputStream) throws IOException {
    writeStatusLine(response, outputStream);
    for (Entry<String, List<String>> headers : response.getHeadersMap().entrySet()) {
      String name = headers.getKey();
      for (String value : headers.getValue()) {
        outputStream.write(name.getBytes());
        outputStream.write(':');
        outputStream.write(' ');
        outputStream.write(value.getBytes());
        outputStream.write(ControlBytes.CRLF);
      }
    }
    outputStream.write(ControlBytes.CRLF);
  }

  private static void parseHeaderParameter(char[] chars, int start, int end, Map<String, String> parameters) {
    boolean encoded = false;
    Charset charset = null;
    String name = null;
    for (int i = start; i < end; i++) {
      if (name == null && chars[i] == '*') {
        encoded = true;
        name = new String(chars, start, i - start).toLowerCase();
        start = i + 2;
      } else if (name == null && chars[i] == '=') {
        name = new String(chars, start, i - start).toLowerCase();
        start = i + 1;
      } else if (name != null && encoded && charset == null && chars[i] == '\'') {
        String charsetName = new String(chars, start, i - start);
        try {
          charset = Charset.forName(charsetName);
        } catch (IllegalCharsetNameException e) {
          charset = StandardCharsets.UTF_8; // Fallback to UTF-8
        }
        start = i + 1;
      } else if (name != null && encoded && charset != null && chars[i] == '\'') {
        start = i + 1;
      }
    }

    // This is an invalid parameter, but we won't fail here
    if (start >= end) {
      if (name != null) {
        parameters.put(name, "");
      }

      return;
    }

    if (chars[start] == '"') {
      start++;
    }

    if (chars[end - 1] == '"') {
      end--;
    }

    String encodedValue = new String(chars, start, end - start);
    String value;
    if (charset != null) {
      value = URLDecoder.decode(encodedValue, charset);
    } else {
      value = URLDecoder.decode(encodedValue, StandardCharsets.UTF_8);
    }

    if (name == null) {
      name = value;
      value = "";
    }

    // Prefer the encoded version
    if (!parameters.containsKey(name) || encoded) {
      parameters.put(name, value);
    }
  }

  /**
   * Writes out the status line to the given OutputStream.
   *
   * @param response The response to pull the status information from.
   * @param out      The OutputStream.
   * @throws IOException If the stream threw an exception.
   */
  private static void writeStatusLine(HTTPResponse response, OutputStream out) throws IOException {
    out.write(ProtocolBytes.HTTTP1_1);
    out.write(' ');
    out.write(Integer.toString(response.getStatus()).getBytes());
    out.write(' ');
    if (response.getStatusMessage() != null) {
      out.write(response.getStatusMessage().getBytes());
    }
    out.write(ControlBytes.CRLF);
  }

  /**
   * A record that stores a parameterized header value.
   *
   * @param value      The initial value of the header.
   * @param parameters The parameters.
   */
  public record HeaderValue(String value, Map<String, String> parameters) {
  }
}
