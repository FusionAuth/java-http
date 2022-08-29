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
package io.fusionauth.http.client;

import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

/**
 * Models a pool of available Channels that are already connected to a remote server and are in a Keep-Alive state. At any point, a HTTP client
 * request might check out a Channel from the pool and return it when finished.
 *
 * @author Brian Pontarelli
 */
public class ChannelPool {
  private final Map<String, Queue<SocketChannel>> pool = new HashMap<>();

  public synchronized void checkin(String host, SocketChannel channel) {
    var available = pool.computeIfAbsent(host, k -> new LinkedList<>());
    available.offer(channel);
  }

  public synchronized SocketChannel checkout(String host) {
    var available = pool.get(host);
    if (available == null || available.isEmpty()) {
      return null;
    }

    return available.poll();
  }
}
