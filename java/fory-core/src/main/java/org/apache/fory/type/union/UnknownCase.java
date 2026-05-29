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

package org.apache.fory.type.union;

import org.apache.fory.annotation.Internal;
import org.apache.fory.type.Types;

/**
 * Runtime-owned carrier for a generated typed ADT union case that is unknown to the local schema.
 */
public final class UnknownCase {
  private final int caseId;
  // Keep resolver TypeInfo out of the carrier. TypeInfo is bound to one Fory resolver and cannot
  // safely cross threads or runtimes; the stable wire type id plus the polymorphic value are enough
  // for the runtime serializer to rebuild Any metadata on write.
  private final int typeId;
  private final Object value;

  public UnknownCase(int caseId, Object value) {
    this(caseId, Types.UNKNOWN, value);
  }

  private UnknownCase(int caseId, int typeId, Object value) {
    this.caseId = caseId;
    this.typeId = typeId;
    this.value = value;
  }

  @Internal
  public static UnknownCase ofRuntime(int caseId, int typeId, Object value) {
    return new UnknownCase(caseId, typeId, value);
  }

  public int caseId() {
    return caseId;
  }

  public int getCaseId() {
    return caseId;
  }

  public Object value() {
    return value;
  }

  public Object getValue() {
    return value;
  }

  public <T> T downcast(Class<T> type) {
    return type.cast(value);
  }

  @Internal
  public int typeId() {
    return typeId;
  }

  @Override
  public String toString() {
    return "UnknownCase{caseId="
        + caseId
        + ", valueType="
        + (value == null ? "null" : value.getClass().getName())
        + '}';
  }
}
