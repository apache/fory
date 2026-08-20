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

import java.util.Objects;

public class TypeExtMeta {
  private final int typeId;
  private final boolean nullable;
  private final boolean trackingRef;
  private final boolean nullableWrapper;
  private final boolean covariant;

  public static TypeExtMeta of(int typeId, boolean nullable, boolean trackingRef) {
    return new TypeExtMeta(typeId, nullable, trackingRef);
  }

  public static TypeExtMeta of(
      int typeId, boolean nullable, boolean trackingRef, boolean nullableWrapper) {
    return new TypeExtMeta(typeId, nullable, trackingRef, nullableWrapper);
  }

  public static TypeExtMeta of(
      int typeId,
      boolean nullable,
      boolean trackingRef,
      boolean nullableWrapper,
      boolean covariant) {
    return new TypeExtMeta(typeId, nullable, trackingRef, nullableWrapper, covariant);
  }

  TypeExtMeta(int typeId, boolean nullable, boolean trackingRef) {
    this(typeId, nullable, trackingRef, false);
  }

  TypeExtMeta(int typeId, boolean nullable, boolean trackingRef, boolean nullableWrapper) {
    this(typeId, nullable, trackingRef, nullableWrapper, false);
  }

  TypeExtMeta(
      int typeId,
      boolean nullable,
      boolean trackingRef,
      boolean nullableWrapper,
      boolean covariant) {
    this.typeId = typeId;
    this.nullable = nullable;
    this.trackingRef = trackingRef;
    this.nullableWrapper = nullableWrapper;
    this.covariant = covariant;
  }

  public int typeId() {
    return typeId;
  }

  public boolean nullable() {
    return nullable;
  }

  public boolean trackingRef() {
    return trackingRef;
  }

  /** Whether the local source type wraps a nullable value in a language-level container. */
  public boolean nullableWrapper() {
    return nullableWrapper;
  }

  /** Whether this declared occurrence is the producer of a covariant type projection. */
  public boolean covariant() {
    return covariant;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TypeExtMeta)) {
      return false;
    }
    TypeExtMeta that = (TypeExtMeta) o;
    return typeId == that.typeId
        && nullable == that.nullable
        && trackingRef == that.trackingRef
        && nullableWrapper == that.nullableWrapper
        && covariant == that.covariant;
  }

  @Override
  public int hashCode() {
    return Objects.hash(typeId, nullable, trackingRef, nullableWrapper, covariant);
  }

  @Override
  public String toString() {
    return "TypeExtMeta{"
        + "typeId="
        + typeId
        + ", nullable="
        + nullable
        + ", trackingRef="
        + trackingRef
        + ", nullableWrapper="
        + nullableWrapper
        + ", covariant="
        + covariant
        + '}';
  }
}
