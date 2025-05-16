/*
 * Copyright (c) 2022-2025, FusionAuth, All Rights Reserved
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.fusionauth.http.FileInfo;
import io.fusionauth.http.ParseException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * @author Brian Pontarelli
 */
public class MultipartStreamTest {
  @DataProvider(name = "badBoundary")
  public Object[][] badBoundary() {
    return new Object[][]{
        {"""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        bar----WebKitFormBoundaryTWfMVJErBoLURJIe--"""},
        {"""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        bar------WebKitFormBoundaryTWfMVJErBoLURJIe--"""}
    };
  }

  @Test(dataProvider = "badBoundary", expectedExceptions = ParseException.class, expectedExceptionsMessageRegExp = "Invalid multipart body. Ran out of data while processing.")
  public void bad_boundaryParameter(String boundary) throws IOException {
    new MultipartStream(new ByteArrayInputStream(boundary.getBytes()), "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), 1024)
        .process(new HashMap<>(), new LinkedList<>());
  }

  @Test
  public void boundaryInParameter() throws IOException {
    ByteArrayInputStream is = new ByteArrayInputStream("""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        bar------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe--""".getBytes());
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new LinkedList<>();
    MultipartStream stream = new MultipartStream(is, "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), 1024);
    stream.process(parameters, files);

    assertEquals(parameters.get("foo"), List.of("bar------WebKitFormBoundaryTWfMVJErBoLURJIe"));
  }

  @Test
  public void file() throws IOException {
    ByteArrayInputStream is = new ByteArrayInputStream("""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"; filename="foo.jpg"\r
        \r
        filecontents\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe--""".getBytes());
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new LinkedList<>();
    MultipartStream stream = new MultipartStream(is, "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), 1024);
    stream.process(parameters, files);

    assertEquals(files.size(), 1);
    assertEquals(files.get(0).contentType, "application/octet-stream");
    assertEquals(Files.readString(files.get(0).file), "filecontents");
    assertEquals(files.get(0).fileName, "foo.jpg");
    assertEquals(files.get(0).name, "foo");

    Files.delete(files.get(0).file);
  }

  @Test
  public void mixed() throws IOException {
    ByteArrayInputStream is = new ByteArrayInputStream("""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        bar\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="file"; filename="foo.jpg"\r
        \r
        filecontents\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe--""".getBytes());
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new LinkedList<>();
    MultipartStream stream = new MultipartStream(is, "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), 1024);
    stream.process(parameters, files);

    assertEquals(parameters.get("foo"), List.of("bar"));

    assertEquals(files.size(), 1);
    assertEquals(files.get(0).contentType, "application/octet-stream");
    assertEquals(Files.readString(files.get(0).file), "filecontents");
    assertEquals(files.get(0).fileName, "foo.jpg");
    assertEquals(files.get(0).name, "file");

    Files.delete(files.get(0).file);
  }

  @Test
  public void parameter() throws IOException {
    ByteArrayInputStream is = new ByteArrayInputStream("""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        bar\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe--""".getBytes());
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new LinkedList<>();
    MultipartStream stream = new MultipartStream(is, "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), 1024);
    stream.process(parameters, files);

    assertEquals(parameters.get("foo"), List.of("bar"));
  }

  @Test
  public void partialBoundaryInParameter() throws IOException {
    ByteArrayInputStream is = new ByteArrayInputStream("""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        ------WebKitFormBoundaryTWfMVJErBoLURJI\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe--""".getBytes());
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new LinkedList<>();
    MultipartStream stream = new MultipartStream(is, "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), 1024);
    stream.process(parameters, files);

    assertEquals(parameters.get("foo"), List.of("------WebKitFormBoundaryTWfMVJErBoLURJI"));
  }

  @DataProvider(name = "parts")
  public Object[][] parts() {
    String body = """
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        bar\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="file"; filename="foo.jpg"\r
        \r
        filecontents\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe--""";
    Object[][] invocations = new Object[body.length() - 1][];
    for (int i = 1; i < body.length(); i++) {
      invocations[i - 1] = new Object[]{i, new Parts(new byte[][]{body.substring(0, i).getBytes(), body.substring(i).getBytes()})};
    }
    return invocations;
  }

  @Test(dataProvider = "parts")
  public void separateParts(@SuppressWarnings("unused") int index, Parts parts) throws IOException {
    PartInputStream is = new PartInputStream(parts.parts);
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new LinkedList<>();
    MultipartStream stream = new MultipartStream(is, "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), 1024);
    stream.process(parameters, files);

    assertEquals(parameters.get("foo"), List.of("bar"));

    assertEquals(files.size(), 1);
    assertEquals(files.get(0).contentType, "application/octet-stream");
    assertEquals(Files.readString(files.get(0).file), "filecontents");
    assertEquals(files.get(0).fileName, "foo.jpg");
    assertEquals(files.get(0).name, "file");

    Files.delete(files.get(0).file);
  }

  public static class PartInputStream extends InputStream {
    private final byte[][] parts;

    private int index;

    private int partIndex;

    public PartInputStream(byte[]... parts) {
      this.parts = parts;
    }

    public int read(byte[] buffer, int start, int count) {
      if (index > parts.length) {
        return -1;
      }

      int copied = Math.min(count, parts[index].length - partIndex);
      System.arraycopy(parts[index], partIndex, buffer, start, copied);
      partIndex += copied;

      if (partIndex >= parts[index].length) {
        partIndex = 0;
        index++;
      }

      return copied;
    }

    @Override
    public int read() {
      throw new UnsupportedOperationException();
    }
  }

  public static class Parts {
    public byte[][] parts;

    public Parts(byte[][] parts) {
      this.parts = parts;
    }

    public String toString() {
      List<String> result = new ArrayList<>();
      for (byte[] part : parts) {
        result.add("" + part.length);
      }
      return "{" + String.join(",", result) + "}";
    }
  }
}
