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
package io.fusionauth.http.load;

import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class SocketHammer {
  public static void main(String[] args) throws Exception {
    List<Socket> sockets = new ArrayList<>();

    for (int i = 0; i < 10_000; i++) {
      try {
        Socket socket = new Socket("localhost", 8080);
        sockets.add(socket);
        System.out.println(i);

        OutputStream outputStream = socket.getOutputStream();
        outputStream.write("GET".getBytes());
        outputStream.flush();
      } catch (Exception e) {
        //Smother
//        System.out.println("Failed");
      }
    }

    Thread.sleep(10_000);
    for (Socket socket : sockets) {
      socket.close();
    }

    System.out.println("Done");
  }
}
