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

public interface MultipartFileManager {
  /**
   * Create a temporary file for use when processing files in multipart form data.
   *
   * @param tempDir the path to the temporary directory
   * @param optionalPrefix the optional prefix used to create the temporary file
   * @param optionalSuffix the optional suffix used to create the temporary file
   * @return the path to the temporary file
   */
  Path createTemporaryFile(Path tempDir, String optionalPrefix, String optionalSuffix) throws IOException;

  /**
   * @return a list of the temporary files created by this file manager.
   */
  List<Path> getTemporaryFiles();
}
