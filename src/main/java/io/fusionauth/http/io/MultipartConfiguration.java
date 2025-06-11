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

import java.util.Objects;

/**
 * Provides configuration to control the behavior of the {@link MultipartStream} parser, specifically around file uploads.
 *
 * @author Daniel DeGroff
 */
public class MultipartConfiguration {
  private boolean deleteTemporaryFiles = true;

  private boolean fileUploadEnabled;

  private long maxFileSize = 1024 * 1024; // 1 Megabyte

  private long maxRequestSize = 10 * 1024 * 1024; // 10 Megabyte

  // TODO : Daniel : Review : This was 1k, that seemed really small?
  private int multipartBufferSize = 8 * 1024; // 8 Kilobyte

  private String temporaryFileLocation = System.getProperty("java.io.tmpdir");

  private String temporaryFilenamePrefix = "java-http";

  private String temporaryFilenameSuffix = "file-upload";

  public MultipartConfiguration() {
  }

  public MultipartConfiguration(MultipartConfiguration other) {
    this.fileUploadEnabled = other.fileUploadEnabled;
    this.maxFileSize = other.maxFileSize;
    this.maxRequestSize = other.maxRequestSize;
    this.multipartBufferSize = other.multipartBufferSize;
    this.temporaryFileLocation = other.temporaryFileLocation;
    this.temporaryFilenamePrefix = other.temporaryFilenamePrefix;
    this.temporaryFilenameSuffix = other.temporaryFilenameSuffix;
  }

  public boolean deleteTemporaryFiles() {
    return deleteTemporaryFiles;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    MultipartConfiguration that = (MultipartConfiguration) o;
    return fileUploadEnabled == that.fileUploadEnabled && maxFileSize == that.maxFileSize && maxRequestSize == that.maxRequestSize && multipartBufferSize == that.multipartBufferSize && Objects.equals(temporaryFileLocation, that.temporaryFileLocation) && Objects.equals(temporaryFilenamePrefix, that.temporaryFilenamePrefix) && Objects.equals(temporaryFilenameSuffix, that.temporaryFilenameSuffix);
  }

  public long getMaxFileSize() {
    return maxFileSize;
  }

  public long getMaxRequestSize() {
    return maxRequestSize;
  }

  public int getMultipartBufferSize() {
    return multipartBufferSize;
  }

  public String getTemporaryFileLocation() {
    return temporaryFileLocation;
  }

  public String getTemporaryFilenamePrefix() {
    return temporaryFilenamePrefix;
  }

  public String getTemporaryFilenameSuffix() {
    return temporaryFilenameSuffix;
  }

  @Override
  public int hashCode() {
    return Objects.hash(fileUploadEnabled, maxFileSize, maxRequestSize, multipartBufferSize, temporaryFileLocation, temporaryFilenamePrefix, temporaryFilenameSuffix);
  }

  public boolean isFileUploadEnabled() {
    return fileUploadEnabled;
  }

  public MultipartConfiguration withDeleteTemporaryFiles(boolean deleteTemporaryFiles) {
    this.deleteTemporaryFiles = deleteTemporaryFiles;
    return this;
  }

  public MultipartConfiguration withFileUploadEnabled(boolean fileUploadEnabled) {
    this.fileUploadEnabled = fileUploadEnabled;
    return this;
  }

  public MultipartConfiguration withMaxFileSize(long maxFileSize) {
    this.maxFileSize = maxFileSize;
    return this;
  }

  public MultipartConfiguration withMaxRequestSize(long maxRequestSize) {
    this.maxRequestSize = maxRequestSize;
    return this;
  }

  public MultipartConfiguration withMultipartBufferSize(int multipartBufferSize) {
    this.multipartBufferSize = multipartBufferSize;
    return this;
  }

  public MultipartConfiguration withTemporaryFileLocation(String temporaryFileLocation) {
    this.temporaryFileLocation = temporaryFileLocation;
    return this;
  }

  public MultipartConfiguration withTemporaryFilenamePrefix(String temporaryFilenamePrefix) {
    this.temporaryFilenamePrefix = temporaryFilenamePrefix;
    return this;
  }

  public MultipartConfiguration withTemporaryFilenameSuffix(String temporaryFilenameSuffix) {
    this.temporaryFilenameSuffix = temporaryFilenameSuffix;
    return this;
  }
}
