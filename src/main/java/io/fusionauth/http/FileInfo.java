/*
 * Copyright (c) 2012-2022, FusionAuth, All Rights Reserved
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

import java.nio.file.Path;

/**
 * This class provides file info for multipart requests.
 *
 * @author Brian Pontarelli
 */
public class FileInfo {
  public final String contentType;

  public final Path file;

  public final String fileName;

  public final String name;

  public FileInfo(Path file, String fileName, String name, String contentType) {
    this.file = file;
    this.fileName = fileName;
    this.name = name;
    this.contentType = contentType;
  }

  public String getContentType() {
    return contentType;
  }

  public Path getFile() {
    return file;
  }

  public String getName() {
    return name;
  }
}
