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
import java.util.List;

/** Base class for all data types in the Fory type system. */
public abstract class DataType {
  protected final int typeId;

  protected DataType(int typeId) {
    this.typeId = typeId;
  }

  public int typeId() {
    return typeId;
  }

  public abstract String name();

  public int bitWidth() {
    return -1;
  }

  public int byteWidth() {
    int bits = bitWidth();
    return bits > 0 ? (bits + 7) / 8 : -1;
  }

  public int numFields() {
    return 0;
  }

  public Field field(int i) {
    return null;
  }

  public List<Field> fields() {
    return Collections.emptyList();
  }

  @Override
  public String toString() {
    return name();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    DataType other = (DataType) obj;
    return typeId == other.typeId;
  }

  @Override
  public int hashCode() {
    return typeId;
  }
}
