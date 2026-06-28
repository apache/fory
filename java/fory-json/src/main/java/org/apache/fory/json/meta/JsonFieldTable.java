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

import org.apache.fory.json.ForyJsonException;

public final class JsonFieldTable {
  private final String[] tableNames;
  private final long[] tableHashes;
  private final JsonFieldInfo[] tableFields;
  private final int[] tableIndexes;
  private final int tableMask;

  public JsonFieldTable(JsonFieldInfo[] readFields) {
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
}
