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

package org.apache.fory.meta;

import org.apache.fory.type.TypeUtils;

public class ClassSpec {
  public final String entireClassName;

  /** Whether current class is enum of component is enum if current class is array. */
  public final boolean isEnum;

  public final boolean isArray;
  public final int dimension;
  public final int typeId;
  public final int userTypeId;
  public Class<?> type;

  public ClassSpec(Class<?> cls) {
    this(
        cls.getName(),
        cls.isEnum(),
        cls.isArray(),
        cls.isArray() ? TypeUtils.getArrayDimensions(cls) : 0,
        -1,
        -1);
    type = cls;
  }

  public ClassSpec(Class<?> cls, int typeId) {
    this(
        cls.getName(),
        cls.isEnum(),
        cls.isArray(),
        cls.isArray() ? TypeUtils.getArrayDimensions(cls) : 0,
        typeId,
        -1);
  }

  public ClassSpec(Class<?> cls, int typeId, int userTypeId) {
    this(
        cls.getName(),
        cls.isEnum(),
        cls.isArray(),
        cls.isArray() ? TypeUtils.getArrayDimensions(cls) : 0,
        typeId,
        userTypeId);
  }

  public ClassSpec(String entireClassName, boolean isEnum, boolean isArray, int dimension) {
    this(entireClassName, isEnum, isArray, dimension, -1, -1);
  }

  public ClassSpec(
      String entireClassName,
      boolean isEnum,
      boolean isArray,
      int dimension,
      int typeId,
      int userTypeId) {
    this.entireClassName = entireClassName;
    this.isEnum = isEnum;
    this.isArray = isArray;
    this.dimension = dimension;
    this.typeId = typeId;
    this.userTypeId = userTypeId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ClassSpec classSpec = (ClassSpec) o;
    return entireClassName.equals(classSpec.entireClassName);
  }

  @Override
  public int hashCode() {
    return entireClassName.hashCode();
  }
}
