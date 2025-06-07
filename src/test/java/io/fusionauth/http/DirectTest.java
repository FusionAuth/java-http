/*
 * Copyright (c) 2024, FusionAuth, All Rights Reserved
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

import java.net.Socket;

/**
 * @author Brian Pontarelli
 */
public class DirectTest {
  public static void main(String[] args) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", 8080)) {
      var os = socket.getOutputStream();
      os.write("GET /css/style.css?1.51.2 HTTP/1.1\r\nHost: localhost:8080\r\nUser-Agent: curl/8.7.1\r\nAccept: */*\r\n\r\n".getBytes());
      os.flush();

      var buffer = new byte[4096];
      var is = socket.getInputStream();
      int read;
      while ((read = is.read(buffer)) != -1) {
        for (int i = 0; i < read; i++) {
          if (i != 0 && i % 16 == 0) {
            String str = new String(buffer, i - 16, 16);
            System.out.println("\"" + str.replaceAll("\n", "\\\\n").replaceAll("\r", "\\\\r") + "\"");
          }

          String hex = Integer.toString(buffer[i], 16);
          if (hex.length() == 1) {
            hex = "0" + hex;
          }
          System.out.print(hex + " ");
        }

        int tail = read % 16;
        if (tail == 0) {
          tail = 16;
        }
        String str = new String(buffer, read - tail, tail);
        System.out.println("\"" + str.replaceAll("\n", "\\\\n").replaceAll("\r", "\\\\r") + "\"");
      }
    }
  }
}
