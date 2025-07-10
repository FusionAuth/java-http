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
@SuppressWarnings("UnusedReturnValue")
public class MultipartConfiguration {
  private boolean deleteTemporaryFiles = true;

  private MultipartFileUploadPolicy fileUploadPolicy = MultipartFileUploadPolicy.Reject;

  private long maxFileSize = 1024 * 1024; // 1 Megabyte

  private long maxRequestSize = 10 * 1024 * 1024; // 10 Megabyte

  private int multipartBufferSize = 8 * 1024; // 8 Kilobyte

  private String temporaryFileLocation = System.getProperty("java.io.tmpdir");

  private String temporaryFilenamePrefix = "java-http";

  private String temporaryFilenameSuffix = "file-upload";

  public MultipartConfiguration() {
  }

  public MultipartConfiguration(MultipartConfiguration other) {
    this.deleteTemporaryFiles = other.deleteTemporaryFiles;
    this.fileUploadPolicy = other.fileUploadPolicy;
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
    if (!(o instanceof MultipartConfiguration that)) {
      return false;
    }
    return deleteTemporaryFiles == that.deleteTemporaryFiles &&
           fileUploadPolicy == that.fileUploadPolicy &&
           maxFileSize == that.maxFileSize &&
           maxRequestSize == that.maxRequestSize &&
           multipartBufferSize == that.multipartBufferSize &&
           Objects.equals(temporaryFileLocation, that.temporaryFileLocation) &&
           Objects.equals(temporaryFilenamePrefix, that.temporaryFilenamePrefix) &&
           Objects.equals(temporaryFilenameSuffix, that.temporaryFilenameSuffix);
  }

  public MultipartFileUploadPolicy getFileUploadPolicy() {
    return fileUploadPolicy;
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
    return Objects.hash(
        deleteTemporaryFiles,
        fileUploadPolicy,
        maxFileSize,
        maxRequestSize,
        multipartBufferSize,
        temporaryFileLocation,
        temporaryFilenamePrefix,
        temporaryFilenameSuffix);
  }

  public boolean isDeleteTemporaryFiles() {
    return deleteTemporaryFiles;
  }

  /**
   * Setting this to <code>true</code> will cause the server to delete all temporary files created while processing a multipart stream after
   * the request handler has been invoked.
   * <p>
   * If you set this to <code>false</code> the request handler will need to manage cleanup of these temporary files.
   *
   * @param deleteTemporaryFiles controls if temporary files are deleted by the server.
   * @return This.
   */
  public MultipartConfiguration withDeleteTemporaryFiles(boolean deleteTemporaryFiles) {
    this.deleteTemporaryFiles = deleteTemporaryFiles;
    return this;
  }

  /**
   * This is the file upload policy for the HTTP server.
   *
   * @param fileUploadPolicy the file upload policy. Cannot be null.
   * @return This.
   */
  public MultipartConfiguration withFileUploadPolicy(MultipartFileUploadPolicy fileUploadPolicy) {
    Objects.requireNonNull(fileUploadPolicy, "You cannot set the fileUploadPolicy to null");
    this.fileUploadPolicy = fileUploadPolicy;
    return this;
  }

  /**
   * This is the maximum size for each file found within a multipart stream which may contain one to many files.
   *
   * @param maxFileSize the maximum file size in bytes
   * @return This.
   */
  public MultipartConfiguration withMaxFileSize(long maxFileSize) {
    this.maxFileSize = maxFileSize;
    return this;
  }

  /**
   * This is the maximum size of the request payload in bytes when reading a multipart stream.
   *
   * @param maxRequestSize the maximum request size in bytes
   * @return This.
   */
  public MultipartConfiguration withMaxRequestSize(long maxRequestSize) {
    if (maxRequestSize < maxFileSize) {
      // In practice the maxRequestSize should be more than just one byte larger than maxFileSize, but I am not going to require any specific amount.
      throw new IllegalArgumentException("The maximum request size must be greater than the maxFileSize");
    }

    this.maxRequestSize = maxRequestSize;
    return this;
  }

  /**
   * @param multipartBufferSize the size of the buffer used to parse a multipart stream.
   * @return This.
   */
  public MultipartConfiguration withMultipartBufferSize(int multipartBufferSize) {
    if (multipartBufferSize <= 0) {
      throw new IllegalArgumentException("The multipart buffer size must be greater than 0");
    }

    this.multipartBufferSize = multipartBufferSize;
    return this;
  }

  /**
   * A temporary file location used for creating temporary files.
   * <p>
   * The specific behavior of creating temporary files will be dependant upon the {@link MultipartFileManager} implementation.
   *
   * @param temporaryFileLocation the temporary file location. Cannot be <code>null</code>.
   * @return This.
   */
  public MultipartConfiguration withTemporaryFileLocation(String temporaryFileLocation) {
    Objects.requireNonNull(temporaryFileLocation, "You cannot set the temporaryFileLocation to null");
    this.temporaryFileLocation = temporaryFileLocation;
    return this;
  }

  /**
   * An optional filename prefix used for naming temporary files.
   * <p>
   * This parameter may be set to <code>null</code>. When set to <code>null</code> a system default such as '.tmp' may be used when naming a
   * temporary file depending upon the {@link MultipartFileManager} implementation.
   *
   * @param temporaryFilenamePrefix an optional filename prefix to be used when creating temporary files.
   * @return This.
   */
  public MultipartConfiguration withTemporaryFilenamePrefix(String temporaryFilenamePrefix) {
    this.temporaryFilenamePrefix = temporaryFilenamePrefix;
    return this;
  }

  /**
   * An optional filename suffix used for naming temporary files.
   * <p>
   * This parameter may be set to <code>null</code>. The specific file naming with or without this optional suffix may be dependant upon the
   * {@link MultipartFileManager} implementation. file depending upon the {@link MultipartFileManager} implementation.
   *
   * @param temporaryFilenameSuffix an optional filename suffix to be used when creating temporary files.
   * @return This.
   */
  public MultipartConfiguration withTemporaryFilenameSuffix(String temporaryFilenameSuffix) {
    this.temporaryFilenameSuffix = temporaryFilenameSuffix;
    return this;
  }
}
