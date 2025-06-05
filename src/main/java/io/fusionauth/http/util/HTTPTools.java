/*
 * Copyright (c) 2022-2025, FusionAuth, All Rights Reserved
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.fusionauth.http.server.io.ConnectionClosedException;
import io.fusionauth.http.HTTPMethod;
import io.fusionauth.http.HTTPValues.ControlBytes;
import io.fusionauth.http.HTTPValues.HeaderBytes;
import io.fusionauth.http.HTTPValues.ProtocolBytes;
import io.fusionauth.http.ParseException;
import io.fusionauth.http.io.PushbackInputStream;
import io.fusionauth.http.log.Logger;
import io.fusionauth.http.log.LoggerFactory;
import io.fusionauth.http.server.HTTPRequest;
import io.fusionauth.http.server.HTTPResponse;
import io.fusionauth.http.server.Instrumenter;

public final class HTTPTools {
  private static Logger logger;

  /**
   * Statically sets up the logger, mostly for trace logging.
   *
   * @param loggerFactory The logger factory.
   */
  public static void initialize(LoggerFactory loggerFactory) {
    HTTPTools.logger = loggerFactory.getLogger(HTTPTools.class);
  }

  /**
   * @param ch The character as a since HTTP is ASCII
   * @return True if the character is an ASCII control character.
   */
  public static boolean isControlCharacter(byte ch) {
    return ch >= 0 && ch <= 31;
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
   * Covered by <a
   * href="https://www.rfc-editor.org/rfc/rfc9110.html#name-fields">https://www.rfc-editor.org/rfc/rfc9110.html#name-fields</a>
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
    return ch >= '!' && ch <= '~';
  }

  // RFC9110 section-5.5 allows for "obs-text", which includes 0x80-0xFF, but really shouldn't be used.
  public static boolean isValueCharacter(byte ch) {
    int intVal = ch & 0xFF;  // Convert the value into an integer without extending the sign bit.
    return isURICharacter(ch) || intVal == ' ' || intVal == '\t' || intVal == '\n' || intVal >= 0x80;
  }

  /**
   * Build a {@link ParseException} that can be thrown.
   *
   * @param b     the byte that caused the exception
   * @param state the current parser state
   * @return a throwable exception
   */
  public static ParseException makeParseException(byte b, Enum<? extends Enum<?>> state) {
    // Trying to print a control characters can mess up the logging format.
    var hex = HexFormat.of().withUpperCase().formatHex(new byte[]{b});
    String message = HTTPTools.isControlCharacter(b)
        ? "Unexpected character. Dec [" + b + "] Hex [" + hex + "]"
        : "Unexpected character. Dec [" + b + "] Hex [" + hex + "] Symbol [" + ((char) b) + "]";
    return new ParseException(message + " Parse state [" + state + "]", state.name());
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

    if (parameters == null) {
      parameters = Map.of();
    }

    return new HeaderValue(headerValue, parameters);
  }

  /**
   * Parses the request preamble directly from the given InputStream.
   *
   * @param inputStream   The input stream to read the preamble from.
   * @param request       The HTTP request to populate.
   * @param requestBuffer A buffer used for reading to help reduce memory thrashing.
   * @param instrumenter  The Instrumenter that is informed of bytes read.
   * @param readObserver  An observer that is called once one byte has been read.
   * @throws IOException If the read fails.
   */
  public static void parseRequestPreamble(PushbackInputStream inputStream, HTTPRequest request, byte[] requestBuffer,
                                          Instrumenter instrumenter,
                                          Runnable readObserver)
      throws IOException {
    RequestPreambleState state = RequestPreambleState.RequestMethod;
    var valueBuffer = new ByteArrayOutputStream(512);
    String headerName = null;

    int read = 0;
    int index = 0;
    while (state != RequestPreambleState.Complete) {
      long start = System.currentTimeMillis();
      read = inputStream.read(requestBuffer);

      // We have not yet reached the end of the preamble. If there are no more bytes to read, the connection must have been closed by the client.
      if (read < 0) {
        long waited = System.currentTimeMillis() - start;
        throw new ConnectionClosedException(String.format("Read returned [%d] after waiting [%d] ms", read, waited));
      }

      logger.trace("Read [{}] from client for preamble.", read);

      if (instrumenter != null) {
        instrumenter.readFromClient(read);
      }

      // Tell the callback that we've read at least one byte
      readObserver.run();

      for (index = 0; index < read && state != RequestPreambleState.Complete; index++) {
        // If there is a state transition, store the value properly and reset the builder (if needed)
        byte ch = requestBuffer[index];
        RequestPreambleState nextState = state.next(ch);
        if (nextState != state) {
          switch (state) {
            case RequestMethod -> request.setMethod(HTTPMethod.of(valueBuffer.toString(StandardCharsets.UTF_8)));
            case RequestPath -> request.setPath(valueBuffer.toString(StandardCharsets.UTF_8));
            case RequestProtocol -> request.setProtocol(valueBuffer.toString(StandardCharsets.UTF_8));
            case HeaderName -> headerName = valueBuffer.toString(StandardCharsets.UTF_8);
            case HeaderValue -> request.addHeader(headerName, valueBuffer.toString(StandardCharsets.UTF_8));
          }

          // If the next state is storing, reset the builder
          if (nextState.store()) {
            valueBuffer.reset();
            valueBuffer.write(ch);
          }
        } else if (state.store()) {
          // If the current state is storing, store the character
          valueBuffer.write(ch);
        }

        state = nextState;
      }
    }

    // Push back the leftover bytes
    if (index < read) {
      inputStream.push(requestBuffer, index, read - index);
    }
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

    // Write the headers (minus the cookies)
    for (var headers : response.getHeadersMap().entrySet()) {
      String name = headers.getKey();
      for (String value : headers.getValue()) {
        outputStream.write(name.getBytes());
        outputStream.write(':');
        outputStream.write(' ');
        outputStream.write(value.getBytes());
        outputStream.write(ControlBytes.CRLF);
      }
    }

    // Write the cookies
    for (var cookie : response.getCookies()) {
      outputStream.write(HeaderBytes.SetCookie);
      outputStream.write(':');
      outputStream.write(' ');
      outputStream.write(cookie.toResponseHeader().getBytes());
      outputStream.write(ControlBytes.CRLF);
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
    String value = URLDecoder.decode(encodedValue, Objects.requireNonNullElse(charset, StandardCharsets.UTF_8));

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
