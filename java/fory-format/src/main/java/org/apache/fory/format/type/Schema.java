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

package org.apache.fory.format.type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Schema: a collection of named fields defining a row structure. */
public class Schema {
  private final List<Field> fields;
  private final Map<String, Integer> nameToIndex;
  private final Map<String, String> metadata;

  public Schema(List<Field> fields) {
    this(fields, Collections.emptyMap());
  }

  public Schema(List<Field> fields, Map<String, String> metadata) {
    this.fields = new ArrayList<>(fields);
    this.nameToIndex = new HashMap<>();
    for (int i = 0; i < fields.size(); i++) {
      nameToIndex.put(fields.get(i).name(), i);
    }
    this.metadata = metadata != null ? new HashMap<>(metadata) : Collections.emptyMap();
  }

  public int numFields() {
    return fields.size();
  }

  public Field field(int i) {
    return (i >= 0 && i < fields.size()) ? fields.get(i) : null;
  }

  public List<Field> fields() {
    return Collections.unmodifiableList(fields);
  }

  /** Returns the list of field names in order. */
  public List<String> fieldNames() {
    List<String> names = new ArrayList<>(fields.size());
    for (Field f : fields) {
      names.add(f.name());
    }
    return names;
  }

  /** Returns the field with the given name, or null if not found. */
  public Field getFieldByName(String name) {
    Integer idx = nameToIndex.get(name);
    return idx != null ? fields.get(idx) : null;
  }

  /** Returns the index of the field with the given name, or -1 if not found. */
  public int getFieldIndex(String name) {
    Integer idx = nameToIndex.get(name);
    return idx != null ? idx : -1;
  }

  public Map<String, String> metadata() {
    return Collections.unmodifiableMap(metadata);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < fields.size(); i++) {
      if (i > 0) {
        sb.append("\n");
      }
      sb.append(fields.get(i).toString());
    }
    return sb.toString();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    Schema other = (Schema) obj;
    if (fields.size() != other.fields.size()) {
      return false;
    }
    for (int i = 0; i < fields.size(); i++) {
      if (!fields.get(i).equals(other.fields.get(i))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    return Objects.hash(fields);
  }
}
