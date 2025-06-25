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
import java.nio.file.Path;
import java.util.List;

/**
 * A file manager for multipart files.
 *
 * @author Daniel DeGroff
 */
public interface MultipartFileManager {
  /**
   * Create a temporary file for use when processing files in multipart form data.
   *
   * @return the path to the temporary file
   */
  Path createTemporaryFile() throws IOException;

  /**
   * @return a list of the temporary files created by this file manager.
   */
  List<Path> getTemporaryFiles();
}
