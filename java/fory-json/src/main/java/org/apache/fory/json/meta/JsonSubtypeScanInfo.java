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

package org.apache.fory.json.meta;

import org.apache.fory.annotation.Internal;

/** Immutable metadata for inline-discriminator scans and wrapper logical-name lookup. */
@Internal
public final class JsonSubtypeScanInfo {
  private final String property;
  private final long propertyHash;
  private final String[] names;
  private final long[] nameHashes;

  public JsonSubtypeScanInfo(String property, String[] names) {
    this.property = property;
    propertyHash = JsonFieldNameHash.hash(property);
    this.names = names.clone();
    nameHashes = new long[names.length];
    for (int i = 0; i < names.length; i++) {
      nameHashes[i] = JsonFieldNameHash.hash(names[i]);
    }
  }

  public String property() {
    return property;
  }

  public long propertyHash() {
    return propertyHash;
  }

  public String name(int index) {
    return names[index];
  }

  public int size() {
    return names.length;
  }

  public int nameIndex(long hash) {
    for (int i = 0; i < nameHashes.length; i++) {
      if (nameHashes[i] == hash) {
        return i;
      }
    }
    return -1;
  }
}
