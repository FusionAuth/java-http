/*
 * Copyright (c) 2021-2025, FusionAuth, All Rights Reserved
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
package io.fusionauth.http;

/**
 * All the HTTP constants you might need. Okay, maybe not everything, but the ones you'll likely need. :)
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("unused")
public final class HTTPValues {
  private HTTPValues() {
  }

  public static final class CacheControl {
    public static final String NoCache = "no-cache";

    public static final String NoStore = "no-store";

    public static final String OnlyIfCached = "only-if-cached";
  }

  public static final class Connections {
    public static final String Close = "close";

    public static final String KeepAlive = "keep-alive";

    private Connections() {
      super();
    }
  }

  /**
   * Content encodings
   */
  public static final class ContentEncodings {
    public static final String Deflate = "deflate";

    public static final String Gzip = "gzip";

    public static final String XGzip = "x-gzip";

    private ContentEncodings() {
    }
  }

  /**
   * Content types.
   */
  public static final class ContentTypes {
    public static final String ApplicationJson = "application/json";

    public static final String ApplicationXml = "application/xml";

    public static final String BoundaryParameter = "boundary";

    public static final String CharsetParameter = "charset";

    public static final String Form = "application/x-www-form-urlencoded";

    public static final String MultipartPrefix = "multipart/";

    public static final String Octet = "application/octet-stream";

    public static final String Text = "text/plain";

    private ContentTypes() {
    }
  }

  public static final class ControlBytes {
    public static final byte CR = '\r';

    public static final byte Dash = '-';

    public static final byte LF = '\n';

    public static final byte[] CRLF = {CR, LF};

    public static final byte[] ChunkedTerminator = {'0', CR, LF, CR, LF};

    public static final byte[] MultipartBoundaryPrefix = {CR, LF, Dash, Dash};

    public static final byte[] MultipartTerminator = {Dash, Dash};

    public static final byte Zero = '0';

    public static final byte[] MultipartFinalChunkBytes = {Zero, CR, LF, CR, LF};

    private ControlBytes() {
    }
  }

  /**
   * Named cookie attributes (in the specs). This includes upper and lower versions since some implementations are not case-sensitive.
   */
  public static final class CookieAttributes {
    public static final String Domain = "Domain";

    public static final String DomainLower = "domain";

    public static final String Expires = "Expires";

    public static final String ExpiresLower = "expires";

    public static final String HttpOnly = "HttpOnly";

    public static final String HttpOnlyLower = "httponly";

    public static final String MaxAge = "Max-Age";

    public static final String MaxAgeLower = "max-age";

    public static final String Path = "Path";

    public static final String PathLower = "path";

    public static final String SameSite = "SameSite";

    public static final String SameSiteLower = "samesite";

    public static final String Secure = "Secure";

    public static final String SecureLower = "secure";

    private CookieAttributes() {
    }
  }

  public static final class DispositionParameters {
    public static final String filename = "filename";

    public static final String name = "name";
  }

  public static final class HeaderBytes {
    public static final byte[] SetCookie = Headers.SetCookie.getBytes();

    private HeaderBytes() {
    }
  }

  /**
   * Header names.
   */
  public static final class Headers {
    public static final String AcceptEncoding = "Accept-Encoding";

    public static final String AcceptEncodingLower = "accept-encoding";

    public static final String AcceptLanguage = "Accept-Language";

    public static final String AcceptLanguageLower = "accept-language";

    /**
     * The Access-Control-Allow-Credentials header indicates whether the response to request can be exposed when the omit credentials flag
     * is unset. When part of the response to a preflight request it indicates that the actual request can include user credentials.
     */
    public static final String AccessControlAllowCredentials = "Access-Control-Allow-Credentials";

    /**
     * The Access-Control-Allow-Headers header indicates, as part of the response to a preflight request, which header field names can be
     * used during the actual request.
     */
    public static final String AccessControlAllowHeaders = "Access-Control-Allow-Headers";

    /**
     * The Access-Control-Allow-Methods header indicates, as part of the response to a preflight request, which methods can be used during
     * the actual request.
     */
    public static final String AccessControlAllowMethods = "Access-Control-Allow-Methods";

    /**
     * The Access-Control-Allow-Origin header indicates whether a resource can be shared based by returning the value of the Origin request
     * header in the response.
     */
    public static final String AccessControlAllowOrigin = "Access-Control-Allow-Origin";

    /**
     * The Access-Control-Expose-Headers header indicates which headers are safe to expose to the API of a CORS API specification
     */
    public static final String AccessControlExposeHeaders = "Access-Control-Expose-Headers";

    /**
     * The Access-Control-Max-Age header indicates how long the results of a preflight request can be cached in a preflight result cache.
     */
    public static final String AccessControlMaxAge = "Access-Control-Max-Age";

    /**
     * The Access-Control-Request-Headers header indicates which headers will be used in the actual request as part of the preflight
     * request.
     */
    public static final String AccessControlRequestHeaders = "Access-Control-Request-Headers";

    /**
     * The Access-Control-Request-Method header indicates which method will be used in the actual request as part of the preflight request.
     */
    public static final String AccessControlRequestMethod = "Access-Control-Request-Method";

    public static final String CacheControl = "Cache-Control";

    public static final String Connection = "Connection";

    public static final String ContentDispositionLower = "content-disposition";

    public static final String ContentEncoding = "Content-Encoding";

    public static final String ContentEncodingLower = "content-encoding";

    public static final String ContentLength = "Content-Length";

    public static final String ContentLengthLower = "content-length";

    public static final String ContentType = "Content-Type";

    public static final String ContentTypeLower = "content-type";

    public static final String Cookie = "Cookie";

    public static final String CookieLower = "cookie";

    public static final String Date = "Date";

    public static final String Expect = "Expect";

    public static final String Expires = "Expires";

    public static final String Host = "Host";

    public static final String HostLower = "host";

    public static final String IfModifiedSince = "If-Modified-Since";

    public static final String LastModified = "Last-Modified";

    public static final String Location = "Location";

    public static final String MethodOverride = "X-HTTP-Method-Override";

    /**
     * The Origin header indicates where the cross-origin request or preflight request originates from.
     */
    public static final String Origin = "Origin";

    public static final String Referer = "Referer";

    public static final String RetryAfter = "Retry-After";

    public static final String SetCookie = "Set-Cookie";

    public static final String TransferEncoding = "Transfer-Encoding";

    public static final String UserAgent = "User-Agent";

    public static final String Vary = "Vary";

    public static final String XForwardedFor = "X-Forwarded-For";

    public static final String XForwardedHost = "X-Forwarded-Host";

    public static final String XForwardedPort = "X-Forwarded-Port";

    public static final String XForwardedProto = "X-Forwarded-Proto";

    private Headers() {
    }
  }

  public static final class Methods {
    public static final String CONNECT = "CONNECT";

    public static final String DELETE = "DELETE";

    public static final String GET = "GET";

    public static final String HEAD = "HEAD";

    public static final String OPTIONS = "OPTIONS";

    public static final String PATCH = "PATCH";

    public static final String POST = "POST";

    public static final String PUT = "PUT";

    public static final String TRACE = "TRACE";

    private Methods() {
    }
  }

  public static final class ProtocolBytes {
    public static final byte[] HTTTP1_1 = Protocols.HTTTP1_1.getBytes();

    private ProtocolBytes() {
    }
  }

  public static final class Protocols {
    public static final String HTTTP1_0 = "HTTP/1.0";

    public static final String HTTTP1_1 = "HTTP/1.1";

    private Protocols() {
    }
  }

  public static final class Status {
    public static final String ContinueRequest = "100-continue";

    public static final int MovedPermanently = 301;

    public static final int MovedTemporarily = 302;

    public static final int NotModified = 304;

    private Status() {
    }
  }

  public static final class TransferEncodings {
    public static final String Chunked = "chunked";

    public static final String Compress = "compress";

    public static final String Deflate = "deflate";

    public static final String Gzip = "gzip";

    private TransferEncodings() {
    }
  }
}
