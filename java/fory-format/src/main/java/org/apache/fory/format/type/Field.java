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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** A named field with type, nullability, and optional metadata. */
public class Field {
  private final String name;
  private final DataType type;
  private final boolean nullable;
  private final Map<String, String> metadata;

  public Field(String name, DataType type) {
    this(name, type, true, Collections.emptyMap());
  }

  public Field(String name, DataType type, boolean nullable) {
    this(name, type, nullable, Collections.emptyMap());
  }

  public Field(String name, DataType type, boolean nullable, Map<String, String> metadata) {
    this.name = name;
    this.type = type;
    this.nullable = nullable;
    this.metadata = metadata != null ? new HashMap<>(metadata) : Collections.emptyMap();
  }

  public String name() {
    return name;
  }

  public DataType type() {
    return type;
  }

  public boolean nullable() {
    return nullable;
  }

  public Map<String, String> metadata() {
    return Collections.unmodifiableMap(metadata);
  }

  /** Returns a new field with the same properties but a different name. */
  public Field withName(String newName) {
    return new Field(newName, type, nullable, metadata);
  }

  /** Returns a new field with the same properties but different nullability. */
  public Field withNullable(boolean newNullable) {
    return new Field(name, type, newNullable, metadata);
  }

  @Override
  public String toString() {
    return name + ": " + type.toString() + (nullable ? "" : " not null");
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    Field other = (Field) obj;
    return name.equals(other.name) && type.equals(other.type) && nullable == other.nullable;
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, type, nullable);
  }
}
