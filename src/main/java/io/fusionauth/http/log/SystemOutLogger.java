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
package io.fusionauth.http.log;

/**
 * A simple logger that spits out messages to System.out.
 *
 * @author Brian Pontarelli
 */
public class SystemOutLogger implements Logger {
  private Level level = Level.Info;

  @Override
  public void debug(String message) {
    if (level.ordinal() <= Level.Debug.ordinal()) {
      System.out.println(message);
      System.out.flush();
    }
  }

  @Override
  public void debug(String message, Object... values) {
    if (level.ordinal() <= Level.Debug.ordinal()) {
      System.out.println(format(message, values));
      System.out.flush();
    }
  }

  @Override
  public void error(String message, Throwable throwable) {
    if (level.ordinal() <= Level.Error.ordinal()) {
      System.out.println(message);
      throwable.printStackTrace(System.out);
      System.out.flush();
    }
  }

  @Override
  public void error(String message) {
    if (level.ordinal() <= Level.Error.ordinal()) {
      System.out.println(message);
      System.out.flush();
    }
  }

  @Override
  public void info(String message) {
    if (level.ordinal() <= Level.Info.ordinal()) {
      System.out.println(message);
      System.out.flush();
    }
  }

  @Override
  public void info(String message, Object... values) {
    if (level.ordinal() <= Level.Info.ordinal()) {
      System.out.println(format(message, values));
      System.out.flush();
    }
  }

  @Override
  public boolean isDebuggable() {
    return level.ordinal() <= Level.Debug.ordinal();
  }

  @Override
  public void setLevel(Level level) {
    this.level = level;
  }

  @Override
  public void trace(String message) {
    if (level.ordinal() == Level.Trace.ordinal()) {
      System.out.println(message);
      System.out.flush();
    }
  }

  @Override
  public void trace(String message, Object... values) {
    if (level.ordinal() == Level.Trace.ordinal()) {
      System.out.println(format(message, values));
      System.out.flush();
    }
  }

  private String format(String message, Object... values) {
    for (Object value : values) {
      String replacement = value != null ? value.toString() : "null";
      message = message.replaceFirst("\\{}", replacement);
    }
    return message;
  }
}
