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

import io.fusionauth.http.util.HTTPTools;

public enum RequestParserState {
  RequestMethod {
    public RequestParserState next(byte ch) {
      if (ch == ' ') {
        return RequestMethodSP;
      } else if (HTTPTools.isTokenCharacter(ch)) {
        return RequestMethod;
      } else {
        throw new ParseException();
      }
    }

    public boolean store() {
      return true;
    }
  },

  RequestMethodSP {
    @Override
    public RequestParserState next(byte ch) {
      if (ch == ' ') {
        return RequestMethodSP;
      } else if (HTTPTools.isURICharacter(ch)) {
        return RequestPath;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  RequestPath {
    @Override
    public RequestParserState next(byte ch) {
      if (ch == ' ') {
        return RequestPathSP;
      } else if (HTTPTools.isURICharacter(ch)) {
        return RequestPath;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return true;
    }
  },

  RequestPathSP {
    @Override
    public RequestParserState next(byte ch) {
      if (ch == ' ') {
        return RequestPathSP;
      } else if (HTTPTools.isURICharacter(ch)) {
        return RequestProtocol;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  RequestProtocol {
    @Override
    public RequestParserState next(byte ch) {
      if (ch == 'H' || ch == 'T' || ch == 'P' || ch == '/' || ch == '1' || ch == '.') {
        return RequestProtocol;
      } else if (ch == '\r') {
        return RequestCR;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return true;
    }
  },

  RequestCR {
    @Override
    public RequestParserState next(byte ch) {
      if (ch == '\n') {
        return RequestLF;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  RequestLF {
    @Override
    public RequestParserState next(byte ch) {
      if (ch == '\r') {
        return RequestMessageCR;
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

  RequestMessageCR {
    @Override
    public RequestParserState next(byte ch) {
      if (ch == '\n') {
        return RequestComplete;
      } else {
        throw new ParseException();
      }
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  RequestComplete {
    @Override
    public RequestParserState next(byte ch) {
      return null;
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  HeaderName {
    @Override
    public RequestParserState next(byte ch) {
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
    public RequestParserState next(byte ch) {
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
    public RequestParserState next(byte ch) {
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
    public RequestParserState next(byte ch) {
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
    public RequestParserState next(byte ch) {
      if (ch == '\r') {
        return RequestMessageCR;
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

  public abstract RequestParserState next(byte ch);

  public abstract boolean store();
}
