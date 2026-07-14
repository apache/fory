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
import org.apache.fory.json.ForyJsonException;

/**
 * Immutable open-addressed lookup table for readable object fields and Any-only skip names.
 *
 * <p>The table is built at a low load factor during object metadata construction and stores both
 * the field object and its ordered read index. Any-enabled metadata may also store declared fixed
 * names that have no read sink in a separate hash table so those names are skipped rather than
 * captured as dynamic members. Concrete readers probe by the hash computed while reading the member
 * name, avoiding String materialization. Hash collisions between declared fields are rejected
 * during construction because runtime lookup deliberately performs no secondary name comparison.
 */
public final class JsonFieldTable {
  @Internal public static final int UNKNOWN = -1;

  @Internal public static final int SKIP = -2;

  private final String[] tableNames;
  private final long[] tableHashes;
  private final JsonFieldInfo[] tableFields;
  private final int[] tableIndexes;
  private final int tableMask;
  private final String[] skipNames;
  private final long[] skipHashes;
  private final int skipMask;

  public JsonFieldTable(JsonFieldInfo[] readFields) {
    this(readFields, null);
  }

  @Internal
  public JsonFieldTable(JsonFieldInfo[] readFields, String[] skippedNames) {
    int tableSize = 1;
    while (tableSize < readFields.length * 4) {
      tableSize <<= 1;
    }
    tableNames = new String[tableSize];
    tableHashes = new long[tableSize];
    tableFields = new JsonFieldInfo[tableSize];
    tableIndexes = new int[tableSize];
    tableMask = tableSize - 1;
    for (int i = 0; i < readFields.length; i++) {
      JsonFieldInfo field = readFields[i];
      put(field, i);
    }
    if (skippedNames == null || skippedNames.length == 0) {
      skipNames = null;
      skipHashes = null;
      skipMask = 0;
      return;
    }
    int skipTableSize = 1;
    while (skipTableSize < skippedNames.length * 4) {
      skipTableSize <<= 1;
    }
    skipNames = new String[skipTableSize];
    skipHashes = new long[skipTableSize];
    skipMask = skipTableSize - 1;
    for (String name : skippedNames) {
      putSkip(name);
    }
  }

  private JsonFieldTable(JsonFieldTable source, String skippedName) {
    tableNames = source.tableNames;
    tableHashes = source.tableHashes;
    tableFields = source.tableFields;
    tableIndexes = source.tableIndexes;
    tableMask = source.tableMask;
    int skipCount = 1;
    if (source.skipNames != null) {
      for (String name : source.skipNames) {
        if (name != null) {
          skipCount++;
        }
      }
    }
    int skipTableSize = 1;
    while (skipTableSize < skipCount * 4) {
      skipTableSize <<= 1;
    }
    skipNames = new String[skipTableSize];
    skipHashes = new long[skipTableSize];
    skipMask = skipTableSize - 1;
    if (source.skipNames != null) {
      for (String name : source.skipNames) {
        if (name != null) {
          putSkip(name);
        }
      }
    }
    putSkip(skippedName);
  }

  /** Returns an immutable table that additionally classifies {@code name} as skipped. */
  @Internal
  public JsonFieldTable withSkippedName(String name) {
    long hash = JsonFieldNameHash.hash(name);
    JsonFieldInfo field = get(hash);
    if (field != null) {
      throw new ForyJsonException(
          "JSON field hash collision between " + field.name() + " and " + name);
    }
    if (skipNames != null) {
      int index = index(hash, skipMask);
      while (skipNames[index] != null) {
        if (skipHashes[index] == hash) {
          if (skipNames[index].equals(name)) {
            return this;
          }
          throw new ForyJsonException(
              "JSON field hash collision between " + skipNames[index] + " and " + name);
        }
        index = (index + 1) & skipMask;
      }
    }
    return new JsonFieldTable(this, name);
  }

  public JsonFieldInfo get(long hash) {
    JsonFieldInfo[] localFields = tableFields;
    long[] localHashes = tableHashes;
    int mask = tableMask;
    int index = index(hash, mask);
    while (true) {
      JsonFieldInfo field = localFields[index];
      if (field == null) {
        return null;
      }
      if (localHashes[index] == hash) {
        return field;
      }
      index = (index + 1) & mask;
    }
  }

  public int index(long hash) {
    long[] localHashes = tableHashes;
    int[] localIndexes = tableIndexes;
    JsonFieldInfo[] localFields = tableFields;
    int mask = tableMask;
    int index = index(hash, mask);
    while (true) {
      if (localFields[index] == null) {
        return -1;
      }
      if (localHashes[index] == hash) {
        return localIndexes[index];
      }
      index = (index + 1) & mask;
    }
  }

  @Internal
  public int match(long hash) {
    int fieldIndex = index(hash);
    if (fieldIndex >= 0) {
      return fieldIndex;
    }
    return containsSkip(hash) ? SKIP : UNKNOWN;
  }

  /** Returns whether {@code hash} belongs to the declared child schema reserved from Any input. */
  @Internal
  public boolean containsHash(long hash) {
    return index(hash) >= 0 || containsSkip(hash);
  }

  private static int index(long hash, int mask) {
    long spread = hash ^ (hash >>> 32);
    return ((int) spread) & mask;
  }

  private void put(JsonFieldInfo field, int fieldIndex) {
    String name = field.name();
    long hash = field.nameHash();
    int index = index(hash, tableMask);
    while (tableFields[index] != null) {
      if (tableHashes[index] == hash) {
        throw new ForyJsonException(
            "JSON field hash collision between " + tableNames[index] + " and " + name);
      }
      index = (index + 1) & tableMask;
    }
    tableNames[index] = name;
    tableHashes[index] = hash;
    tableFields[index] = field;
    tableIndexes[index] = fieldIndex;
  }

  private void putSkip(String name) {
    long hash = JsonFieldNameHash.hash(name);
    JsonFieldInfo field = get(hash);
    if (field != null) {
      throw new ForyJsonException(
          "JSON field hash collision between " + field.name() + " and " + name);
    }
    int index = index(hash, skipMask);
    while (skipNames[index] != null) {
      if (skipHashes[index] == hash) {
        throw new ForyJsonException(
            "JSON field hash collision between " + skipNames[index] + " and " + name);
      }
      index = (index + 1) & skipMask;
    }
    skipNames[index] = name;
    skipHashes[index] = hash;
  }

  private boolean containsSkip(long hash) {
    String[] localNames = skipNames;
    if (localNames == null) {
      return false;
    }
    long[] localHashes = skipHashes;
    int mask = skipMask;
    int index = index(hash, mask);
    while (true) {
      if (localNames[index] == null) {
        return false;
      }
      if (localHashes[index] == hash) {
        return true;
      }
      index = (index + 1) & mask;
    }
  }
}
