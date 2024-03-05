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

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A simple logger that spits out messages to System.out.
 *
 * @author Brian Pontarelli
 */
public class FileLogger extends BaseLogger {
  private final Writer writer;

  public FileLogger(Path file) {
    try {
      Files.createDirectories(file.getParent());
      this.writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void flush() {
    try {
      this.writer.flush();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void handleMessage(String message) {
    try {
      writer.write(message + "\n");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
