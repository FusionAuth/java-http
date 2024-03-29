/*
 * Copyright (c) 2021-2022, FusionAuth, All Rights Reserved
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

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.fusionauth.http.util.DateTools;

public class Cookie implements Buildable<Cookie> {
  public String domain;

  public ZonedDateTime expires;

  public boolean httpOnly;

  public Long maxAge;

  public String name;

  public String path;

  public SameSite sameSite;

  public boolean secure;

  public String value;

  public Cookie() {
  }

  public Cookie(String name, String value) {
    this.name = name;
    this.value = value;
  }

  public Cookie(Cookie other) {
    if (other == null) {
      return;
    }

    this.domain = other.domain;
    this.expires = other.expires;
    this.httpOnly = other.httpOnly;
    this.maxAge = other.maxAge;
    this.name = other.name;
    this.path = other.path;
    this.sameSite = other.sameSite;
    this.secure = other.secure;
    this.value = other.value;
  }

  /**
   * Parses cookies from a request header (cookie).
   *
   * @param header The header value.
   * @return The list of cookies.
   */
  public static List<Cookie> fromRequestHeader(String header) {
    List<Cookie> cookies = new ArrayList<>();
    boolean inName = false, inValue = false;
    char[] chars = header.toCharArray();
    int start = 0;
    String name = null;
    String value = null;
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      if (!inName && !inValue && (Character.isWhitespace(c) || c == ';')) {
        start++;
        continue;
      }

      if (c == '=' && inName) {
        name = new String(chars, start, i - start);
        value = "";
        inValue = true;
        inName = false;
        start = i + 1;
      } else if (c == ';' && inValue) {
        value = new String(chars, start, i - start);
        if (name.trim().length() > 0 && value.trim().length() > 0) {
          cookies.add(new Cookie(name, value));
        }

        inValue = false;
        name = null;
        value = null;
        start = 0;
      } else if (!inName && !inValue) {
        inName = true;
        start = i;
      }
    }

    // Handle what's left
    if (inName && start > 0) {
      name = header.substring(start);
    }

    if (inValue && start > 0) {
      value = header.substring(start);
    }

    if (name != null && value != null && name.trim().length() > 0 && value.trim().length() > 0) {
      cookies.add(new Cookie(name, value));
    }

    return cookies;
  }

  /**
   * Parses a cookie from a response header (set-cookie).
   *
   * @param header The header value.
   * @return The Cookie.
   */
  public static Cookie fromResponseHeader(String header) {
    // Note we could use the JDK java.net.HttpCookie.parse(header) instead.
    // - However, they still don't support SameSite. Super lame.
    Cookie cookie = new Cookie();
    boolean inName = false, inValue = false, inAttributes = false;
    char[] chars = header.toCharArray();
    int start = 0;
    String name = null;
    String value = null;
    for (int i = 0; i < header.length(); i++) {
      char c = chars[i];
      if (!inName && !inValue && (Character.isWhitespace(c) || c == ';')) {
        start++;
        continue;
      }

      if (c == '=' && inName) {
        name = header.substring(start, i);
        if (!inAttributes && name.trim().isEmpty()) {
          return null;
        }

        value = "";
        inValue = true;
        inName = false;
        // Values may be double-quoted
        // https://www.rfc-editor.org/rfc/rfc6265#section-4.1.1
        // cookie-value = *cookie-octet / ( DQUOTE *cookie-octet DQUOTE )
        start = (i < (header.length() - 1)) && chars[i + 1] == '"'
            ? i + 2
            : i + 1;
      } else if (c == ';') {
        if (inName) {
          if (!inAttributes) {
            return null;
          }

          name = header.substring(start, i);
          value = null;
        } else {
          // Values may be double-quoted
          int end = chars[i - 1] == '"'
              ? i - 1
              : i;
          value = header.substring(start, end);
        }

        if (inAttributes) {
          cookie.addAttribute(name, value);
        } else {
          cookie.name = name;
          cookie.value = value;
        }

        inName = false;
        inValue = false;
        inAttributes = true;
        name = null;
        value = null;
      } else {
        if (!inName && !inValue) {
          inName = true;
          start = i;
        }
      }
    }

    // Handle what's left
    if (inName && start > 0) {
      name = header.substring(start);
    }

    if (inValue && start > 0) {
      value = header.substring(start);
    }

    if (inAttributes) {
      cookie.addAttribute(name, value);
    } else {
      if (name == null || value == null || name.trim().length() == 0) {
        return null;
      }

      cookie.name = name;
      cookie.value = value;
    }

    return cookie;
  }

  public void addAttribute(String name, String value) {
    if (name == null) {
      return;
    }

    switch (name.toLowerCase()) {
      case HTTPValues.CookieAttributes.DomainLower:
        domain = value;
        break;
      case HTTPValues.CookieAttributes.ExpiresLower:
        expires = DateTools.parse(value);
        break;
      case HTTPValues.CookieAttributes.HttpOnlyLower:
        httpOnly = true;
        break;
      case HTTPValues.CookieAttributes.MaxAgeLower:
        try {
          maxAge = Long.parseLong(value);
        } catch (Exception e) {
          // Ignore
        }
        break;
      case HTTPValues.CookieAttributes.PathLower:
        path = value;
        break;
      case HTTPValues.CookieAttributes.SameSiteLower:
        try {
          sameSite = SameSite.valueOf(value);
        } catch (Exception e) {
          // Ignore
        }
        break;
      case HTTPValues.CookieAttributes.SecureLower:
        secure = true;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Cookie)) {
      return false;
    }
    Cookie cookie = (Cookie) o;
    return httpOnly == cookie.httpOnly &&
        secure == cookie.secure &&
        Objects.equals(domain, cookie.domain) &&
        Objects.equals(expires, cookie.expires) &&
        Objects.equals(maxAge, cookie.maxAge) &&
        Objects.equals(name, cookie.name) &&
        Objects.equals(path, cookie.path) &&
        Objects.equals(value, cookie.value);
  }

  public String getDomain() {
    return domain;
  }

  public void setDomain(String domain) {
    this.domain = domain;
  }

  public ZonedDateTime getExpires() {
    return expires;
  }

  public void setExpires(ZonedDateTime expires) {
    this.expires = expires;
  }

  public Long getMaxAge() {
    return maxAge;
  }

  public void setMaxAge(Long maxAge) {
    this.maxAge = maxAge;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public SameSite getSameSite() {
    return sameSite;
  }

  public void setSameSite(SameSite sameSite) {
    this.sameSite = sameSite;
  }

  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }

  @Override
  public int hashCode() {
    return Objects.hash(domain, expires, httpOnly, maxAge, name, path, secure, value);
  }

  public boolean isHttpOnly() {
    return httpOnly;
  }

  public void setHttpOnly(boolean httpOnly) {
    this.httpOnly = httpOnly;
  }

  public boolean isSecure() {
    return secure;
  }

  public void setSecure(boolean secure) {
    this.secure = secure;
  }

  public String toRequestHeader() {
    return name + "=" + value;
  }

  public String toResponseHeader() {
    return name + "=" + value
        + (domain != null ? ("; " + HTTPValues.CookieAttributes.Domain + "=" + domain) : "")
        + (expires != null ? ("; " + HTTPValues.CookieAttributes.Expires + "=" + DateTools.format(expires)) : "")
        + (httpOnly ? ("; " + HTTPValues.CookieAttributes.HttpOnly) : "")
        + (maxAge != null ? ("; " + HTTPValues.CookieAttributes.MaxAge + "=" + maxAge) : "")
        + (path != null ? ("; " + HTTPValues.CookieAttributes.Path + "=" + path) : "")
        + (sameSite != null ? ("; " + HTTPValues.CookieAttributes.SameSite + "=" + sameSite.name()) : "")
        + (secure ? ("; " + HTTPValues.CookieAttributes.Secure) : "");
  }

  public enum SameSite {
    Lax,
    None,
    Strict
  }
}
