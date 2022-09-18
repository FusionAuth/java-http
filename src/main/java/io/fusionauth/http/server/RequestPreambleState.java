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

import io.fusionauth.http.ParseException;
import io.fusionauth.http.util.HTTPTools;

/**
 * Finite state machine parser for an HTTP 1.1 request preamble. This is the start-line and headers.
 *
 * @author Brian Pontarelli
 */
public enum RequestPreambleState {
  RequestMethod {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == ' ') {
        return RequestMethodSP;
      } else if (HTTPTools.isTokenCharacter(ch)) {
        return RequestMethod;
      }

      throw makeParseException((char) ch);
    }

    @Override
    public boolean store() {
      return true;
    }
  },

  RequestMethodSP {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == ' ') {
        return RequestMethodSP;
      } else if (HTTPTools.isURICharacter(ch)) {
        return RequestPath;
      }

      throw makeParseException((char) ch);
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  RequestPath {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == ' ') {
        return RequestPathSP;
      } else if (HTTPTools.isURICharacter(ch)) {
        return RequestPath;
      }

      throw makeParseException((char) ch);
    }

    @Override
    public boolean store() {
      return true;
    }
  },

  RequestPathSP {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == ' ') {
        return RequestPathSP;
      } else if (HTTPTools.isURICharacter(ch)) {
        return RequestProtocol;
      }

      throw makeParseException((char) ch);
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  RequestProtocol {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == 'H' || ch == 'T' || ch == 'P' || ch == '/' || ch == '1' || ch == '.') {
        return RequestProtocol;
      } else if (ch == '\r') {
        return RequestCR;
      }

      throw makeParseException((char) ch);
    }

    @Override
    public boolean store() {
      return true;
    }
  },

  RequestCR {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == '\n') {
        return RequestLF;
      }

      throw makeParseException((char) ch);
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  RequestLF {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == '\r') {
        return PreambleCR;
      } else if (HTTPTools.isTokenCharacter(ch)) {
        return HeaderName;
      }

      throw makeParseException((char) ch);
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  HeaderName {
    @Override
    public RequestPreambleState next(byte ch) {
      if (HTTPTools.isTokenCharacter(ch)) {
        return HeaderName;
      } else if (ch == ':') {
        return HeaderColon;
      }

      throw makeParseException((char) ch);
    }

    @Override
    public boolean store() {
      return true;
    }
  },

  HeaderColon {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == ' ') {
        return HeaderColon; // Re-using this state because HeaderSP would be the same
      } else if (ch == '\r') {
        return HeaderCR; // Empty header
      }

      return HeaderValue;
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  HeaderValue {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == '\r') {
        return HeaderCR;
      } else if (HTTPTools.isValueCharacter(ch)) {
        return HeaderValue;
      }

      throw makeParseException((char) ch);
    }

    @Override
    public boolean store() {
      return true;
    }
  },

  HeaderCR {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == '\n') {
        return HeaderLF;
      }

      throw makeParseException((char) ch);
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  HeaderLF {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == '\r') {
        return PreambleCR;
      } else if (HTTPTools.isTokenCharacter(ch)) {
        return HeaderName;
      }

      throw makeParseException((char) ch);
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  PreambleCR {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == '\n') {
        return Complete;
      }

      throw makeParseException((char) ch);
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  Complete {
    @Override
    public RequestPreambleState next(byte ch) {
      return null;
    }

    @Override
    public boolean store() {
      return false;
    }
  };

  public abstract RequestPreambleState next(byte ch);

  public abstract boolean store();

  ParseException makeParseException(char ch) {
    return new ParseException("Invalid character [" + ch + "] in state [" + this + "]");
  }
}
