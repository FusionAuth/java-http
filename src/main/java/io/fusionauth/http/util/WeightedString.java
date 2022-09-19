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
package io.fusionauth.http.util;

/**
 * A simple weighted string class that allows weighted headers and other values to be sorted.
 *
 * @author Brian Pontarelli
 */
public record WeightedString(String value, double weight, int position) implements Comparable<WeightedString> {
  @Override
  public int compareTo(WeightedString o) {
    if (weight == o.weight) {
      return position - o.position;
    }

    if (o.weight < weight) {
      return -1;
    }

    return 1;
  }
}
