/*
 * Copyright (c) 2025, FusionAuth, All Rights Reserved
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
package io.fusionauth.http.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Manage file creation for multipart streams.
 *
 * @author Daniel DeGroff
 */
public class DefaultMultipartFileManager implements MultipartFileManager {
  private final List<Path> tempFiles = new ArrayList<>(0);

  public Path createTemporaryFile(Path tempDir, String optionalPrefix, String optionalSuffix) throws IOException {
    Path tempFile = Files.createTempFile(tempDir, optionalPrefix, optionalSuffix);
    tempFiles.add(tempFile);
    return tempFile;
  }

  public List<Path> getTemporaryFiles() {
    return List.copyOf(tempFiles);
  }
}
