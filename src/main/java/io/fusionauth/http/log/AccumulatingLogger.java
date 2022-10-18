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

import java.util.ArrayList;
import java.util.List;

/**
 * A logger that accumulates the log messages into an ArrayList that can later be output to a file or output to the console. Great for
 * testing when sysout logging would be way too spammy.
 *
 * @author Brian Pontarelli
 */
public class AccumulatingLogger extends BaseLogger {
  private final List<String> messages = new ArrayList<>();

  public void reset() {
    messages.clear();
  }

  @Override
  public String toString() {
    return String.join("\n", messages);
  }

  @Override
  protected void handleMessage(String message) {
    messages.add(message);
  }
}
