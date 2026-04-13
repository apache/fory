/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fory.collection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.util.Preconditions;

/** Minimal map builder supporting the subset used by fory-core. */
public final class MapMaker {
  private boolean weakKeys;
  private int concurrencyLevel = 4;

  public MapMaker weakKeys() {
    weakKeys = true;
    return this;
  }

  public MapMaker concurrencyLevel(int concurrencyLevel) {
    Preconditions.checkArgument(concurrencyLevel > 0, "concurrencyLevel must be positive");
    this.concurrencyLevel = concurrencyLevel;
    return this;
  }

  public <K, V> Map<K, V> makeMap() {
    if (weakKeys) {
      return new ReferenceConcurrentMap<>(true, false, concurrencyLevel);
    }
    return new ConcurrentHashMap<>(Math.max(16, concurrencyLevel));
  }
}
