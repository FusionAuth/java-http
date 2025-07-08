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
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.fusionauth.http.FileInfo;

/**
 * An HTTP input stream processor that can handle a <code>Content-Type: multipart/form-data</code>.
 * <p>
 * This implementation is configurable.
 *
 * @author Daniel DeGroff
 */
public class MultipartStreamProcessor {
  private MultipartConfiguration multipartConfiguration;

  private MultipartFileManager multipartFileManager;

  /**
   * @return the multipart configuration that will be used for this processor.
   */
  public MultipartConfiguration getMultiPartConfiguration() {
    if (multipartConfiguration == null) {
      multipartConfiguration = new MultipartConfiguration();
    }

    return multipartConfiguration;
  }

  public MultipartFileManager getMultipartFileManager() {
    if (multipartFileManager == null) {
      getMultiPartConfiguration();
      multipartFileManager = new DefaultMultipartFileManager(Paths.get(multipartConfiguration.getTemporaryFileLocation()), multipartConfiguration.getTemporaryFilenamePrefix(), multipartConfiguration.getTemporaryFilenameSuffix());
    }

    return multipartFileManager;
  }

  public void process(InputStream inputStream, Map<String, List<String>> parameters, List<FileInfo> files, byte[] boundary)
      throws IOException {
    // Lazily construct the file manager based upon the configuration;
    new MultipartStream(inputStream, boundary, getMultipartFileManager(), getMultiPartConfiguration()).process(parameters, files);
  }

  public void setMultipartConfiguration(MultipartConfiguration multipartConfiguration) {
    Objects.requireNonNull(multipartConfiguration);
    this.multipartConfiguration = multipartConfiguration;
  }
}
