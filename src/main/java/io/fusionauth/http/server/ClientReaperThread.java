/*
 * Copyright (c) 2022, FusionAuth, All Rights Reserved
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
package io.fusionauth.http.server;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.Iterator;

public class ClientReaperThread extends Thread {
  private final HTTPServer HTTPServer;

  private boolean running;

  public ClientReaperThread(HTTPServer HTTPServer) {
    this.HTTPServer = HTTPServer;
    setDaemon(true);
  }

  public synchronized void run() {
    while (running) {
      Iterator<SelectionKey> iterator = HTTPServer.clientKeys.iterator();
      while (iterator.hasNext()) {
        SelectionKey key = iterator.next();
        HTTPData data = (HTTPData) key.attachment();
        if (data.lastUsed > System.currentTimeMillis() + 10_000) {
          iterator.remove();

          try {
            key.channel().close();
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      }

      try {
        wait(20_000);
      } catch (InterruptedException e) {
        // Ignore
      }
    }
  }

  public synchronized void shutdown() {
    running = false;
    this.notify();
  }
}
