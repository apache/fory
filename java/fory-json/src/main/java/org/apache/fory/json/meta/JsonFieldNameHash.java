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

public final class JsonFieldNameHash {
  public static final long MAGIC_HASH_CODE = 0xcbf29ce484222325L;
  public static final long MAGIC_PRIME = 0x100000001b3L;

  private JsonFieldNameHash() {}

  public static long hash(String name) {
    int length = name.length();
    if (length > 0 && length <= Long.BYTES) {
      boolean latin1 = true;
      long value = 0;
      for (int i = 0; i < length; i++) {
        char ch = name.charAt(i);
        if (ch > 0xFF || ch == 0) {
          latin1 = false;
          break;
        }
        value |= ((long) ch) << (i << 3);
      }
      if (latin1 && value != 0) {
        return value;
      }
    }
    long hash = MAGIC_HASH_CODE;
    for (int i = 0; i < length; i++) {
      hash = update(hash, name.charAt(i));
    }
    return hash;
  }

  public static long update(long hash, char ch) {
    return (hash ^ ch) * MAGIC_PRIME;
  }

  public static long value(long value, int length, char ch) {
    return value | (((long) ch) << (length << 3));
  }

  public static long hashPacked(long value, int length) {
    long hash = MAGIC_HASH_CODE;
    for (int i = 0; i < length; i++) {
      hash = update(hash, (char) ((value >>> (i << 3)) & 0xFF));
    }
    return hash;
  }

  public static long finish(long hash, long value, int length, boolean latin1) {
    return latin1 && length > 0 && length <= Long.BYTES && value != 0 ? value : hash;
  }
}
