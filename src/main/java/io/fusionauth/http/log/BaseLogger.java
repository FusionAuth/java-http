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
package io.fusionauth.http.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.regex.Matcher;

/**
 * A base class for loggers.
 *
 * @author Brian Pontarelli
 */
public abstract class BaseLogger implements Logger {
  protected Level level = Level.Info;

  @Override
  public void debug(String message) {
    if (getLevelOrdinal() <= Level.Debug.ordinal()) {
      handleMessage(format(message));
    }
  }

  @Override
  public void debug(String message, Object... values) {
    if (getLevelOrdinal() <= Level.Debug.ordinal()) {
      handleMessage(format(message, values));
    }
  }

  @Override
  public void debug(String message, Throwable throwable) {
    if (getLevelOrdinal() <= Level.Debug.ordinal()) {
      handleMessage(format(message, throwable));
    }
  }

  @Override
  public void error(String message, Throwable throwable) {
    if (getLevelOrdinal() <= Level.Error.ordinal()) {
      handleMessage(format(message, throwable));
    }
  }

  @Override
  public void error(String message) {
    if (getLevelOrdinal() <= Level.Error.ordinal()) {
      handleMessage(format(message));
    }
  }

  @Override
  public void info(String message) {
    if (getLevelOrdinal() <= Level.Info.ordinal()) {
      handleMessage(format(message));
    }
  }

  @Override
  public void info(String message, Object... values) {
    if (getLevelOrdinal() <= Level.Info.ordinal()) {
      handleMessage(format(message, values));
    }
  }

  @Override
  public boolean isDebugEnabled() {
    return getLevelOrdinal() <= Level.Debug.ordinal();
  }

  @Override
  public boolean isErrorEnabled() {
    return getLevelOrdinal() <= Level.Error.ordinal();
  }

  @Override
  public boolean isInfoEnabled() {
    return getLevelOrdinal() <= Level.Info.ordinal();
  }

  @Override
  public boolean isTraceEnabled() {
    return getLevelOrdinal() <= Level.Trace.ordinal();
  }

  @Override
  public void setLevel(Level level) {
    this.level = level;
  }

  @Override
  public void trace(String message, Object... values) {
    if (getLevelOrdinal() <= Level.Trace.ordinal()) {
      handleMessage(format(message, values));
    }
  }

  @Override
  public void trace(String message) {
    if (getLevelOrdinal() <= Level.Trace.ordinal()) {
      handleMessage(format(message));
    }
  }

  protected String format(String message, Throwable t) {
    if (t == null) {
      return message;
    }

    StringWriter writer = new StringWriter();
    PrintWriter pw = new PrintWriter(writer);
    t.printStackTrace(pw);
    pw.flush();
    return timestamp() + message + "\n" + writer;
  }

  protected String format(String message, Object... values) {
    for (Object value : values) {
      String replacement = value != null ? value.toString() : "null";
      // Account for the replacement value containing a '$' or other regular expression reference.
      message = message.replaceFirst("\\{}", Matcher.quoteReplacement(replacement));
    }

    return timestamp() + message;
  }

  protected int getLevelOrdinal() {
    return level.ordinal();
  }

  protected abstract void handleMessage(String message);

  protected String timestamp() {
    return System.currentTimeMillis() + " ";
  }
}
