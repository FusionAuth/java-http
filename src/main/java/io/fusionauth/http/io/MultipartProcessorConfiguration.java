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
package io.fusionauth.http.io;

/**
 * Provides configuration to control the behavior of the {@link MultipartStream} parser, specifically around file uploads.
 *
 * @author Daniel DeGroff
 */
public class MultipartProcessorConfiguration {
  private boolean fileUploadEnabled;

  private long maxFileSize = 1024 * 1024; // 1 Megabyte

  private long maxRequestSize = 10 * 1024 * 1024; // 10 Megabyte

  // TODO : Daniel : Review : This was 1k, that seemed really small?
  private int multipartBufferSize = 8 * 1024; // 8 Kilobyte

  private String temporaryFileLocation = System.getProperty("java.io.tmpdir");

  private String temporaryFilenamePrefix = "java-http";

  private String temporaryFilenameSuffix = "file-upload";

  public MultipartProcessorConfiguration() {
  }

  public long getMaxFileSize() {
    return maxFileSize;
  }

  public void setMaxFileSize(long maxFileSize) {
    this.maxFileSize = maxFileSize;
  }

  public long getMaxRequestSize() {
    return maxRequestSize;
  }

  public void setMaxRequestSize(long maxRequestSize) {
    this.maxRequestSize = maxRequestSize;
  }

  public int getMultipartBufferSize() {
    return multipartBufferSize;
  }

  public void setMultipartBufferSize(int multipartBufferSize) {
    this.multipartBufferSize = multipartBufferSize;
  }

  public String getTemporaryFileLocation() {
    return temporaryFileLocation;
  }

  public void setTemporaryFileLocation(String temporaryFileLocation) {
    this.temporaryFileLocation = temporaryFileLocation;
  }

  public String getTemporaryFilenamePrefix() {
    return temporaryFilenamePrefix;
  }

  public void setTemporaryFilenamePrefix(String temporaryFilenamePrefix) {
    this.temporaryFilenamePrefix = temporaryFilenamePrefix;
  }

  public String getTemporaryFilenameSuffix() {
    return temporaryFilenameSuffix;
  }

  public void setTemporaryFilenameSuffix(String temporaryFilenameSuffix) {
    this.temporaryFilenameSuffix = temporaryFilenameSuffix;
  }

  public boolean isFileUploadEnabled() {
    return fileUploadEnabled;
  }

  public void setFileUploadEnabled(boolean fileUploadEnabled) {
    this.fileUploadEnabled = fileUploadEnabled;
  }
}
