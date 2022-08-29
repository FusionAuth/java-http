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
package io.fusionauth.http.client;

import java.util.List;
import java.util.Map;

import io.fusionauth.http.util.HTTPTools;

public enum ResponseParserState {
  ResponseProtocol {
    @Override
    public ResponseParserState next(byte ch, Map<String, List<String>> headers) {
      if (ch == 'H' || ch == 'T' || ch == 'P' || ch == '/' || ch == '1' || ch == '.') {
        return ResponseProtocol;
      } else if (ch == ' ') {
        return ResponseProtocolSP;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return true;
    }
  },

  ResponseProtocolSP {
    @Override
    public ResponseParserState next(byte ch, Map<String, List<String>> headers) {
      if (ch == ' ') {
        return ResponseProtocolSP;
      } else if (HTTPTools.isDigitCharacter(ch)) {
        return ResponseStatusCode;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  ResponseStatusCode {
    public ResponseParserState next(byte ch, Map<String, List<String>> headers) {
      if (ch == ' ') {
        return ResponseStatusCodeSP;
      } else if (HTTPTools.isDigitCharacter(ch)) {
        return ResponseStatusCode;
      } else {
        throw new ParseException();
      }
    }

    public boolean store() {
      return true;
    }
  },

  ResponseStatusCodeSP {
    @Override
    public ResponseParserState next(byte ch, Map<String, List<String>> headers) {
      if (ch == ' ') {
        return ResponseStatusCodeSP;
      } else if (HTTPTools.isValueCharacter(ch)) {
        return ResponseStatusMessage;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  ResponseStatusMessage {
    @Override
    public ResponseParserState next(byte ch, Map<String, List<String>> headers) {
      if (ch == '\r') {
        return ResponseStatusMessageCR;
      } else if (HTTPTools.isValueCharacter(ch)) {
        return ResponseStatusMessage;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return true;
    }
  },

  ResponseStatusMessageCR {
    @Override
    public ResponseParserState next(byte ch, Map<String, List<String>> headers) {
      if (ch == '\n') {
        return ResponseStatusMessageLF;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  ResponseStatusMessageLF {
    @Override
    public ResponseParserState next(byte ch, Map<String, List<String>> headers) {
      if (ch == '\r') {
        return ResponseMessageCR;
      } else if (HTTPTools.isTokenCharacter(ch)) {
        return HeaderName;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  ResponseMessageCR {
    @Override
    public ResponseParserState next(byte ch, Map<String, List<String>> headers) {
      if (ch == '\n') {
        return ResponseComplete;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  ResponseComplete {
    @Override
    public ResponseParserState next(byte ch, Map<String, List<String>> headers) {
      return ResponseComplete;
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  HeaderName {
    @Override
    public ResponseParserState next(byte ch, Map<String, List<String>> headers) {
      if (HTTPTools.isTokenCharacter(ch)) {
        return HeaderName;
      } else if (ch == ':') {
        return HeaderColon;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return true;
    }
  },

  HeaderColon {
    @Override
    public ResponseParserState next(byte ch, Map<String, List<String>> headers) {
      if (ch == ' ') {
        return HeaderColon; // Re-using this state because HeaderSP would be the same
      } else if (HTTPTools.isTokenCharacter(ch)) {
        return HeaderValue;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  HeaderValue {
    @Override
    public ResponseParserState next(byte ch, Map<String, List<String>> headers) {
      if (ch == '\r') {
        return HeaderCR;
      } else if (HTTPTools.isValueCharacter(ch)) {
        return HeaderValue;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return true;
    }
  },

  HeaderCR {
    @Override
    public ResponseParserState next(byte ch, Map<String, List<String>> headers) {
      if (ch == '\n') {
        return HeaderLF;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  HeaderLF {
    @Override
    public ResponseParserState next(byte ch, Map<String, List<String>> headers) {
      if (ch == '\r') {
        return ResponseMessageCR;
      } else if (HTTPTools.isTokenCharacter(ch)) {
        return HeaderName;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return false;
    }
  };

  public abstract ResponseParserState next(byte ch, Map<String, List<String>> headers);

  public abstract boolean store();
}
