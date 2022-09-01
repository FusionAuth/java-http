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
import java.util.Objects;

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

  public enum SameSite {
    Lax,
    None,
    Strict
  }
}
