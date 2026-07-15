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

package org.apache.fory.json.codec;

import java.lang.reflect.Type;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.ObjectCodec.AnyInfo;
import org.apache.fory.json.meta.JsonAnySetterInvoker;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonTypeUse;

/** Android generated Any metadata, isolated from the ordinary JVM Any hot path. */
final class AndroidAnyInfo extends AnyInfo {
  private final JsonAnySetterInvoker setterInvoker;
  private final boolean readEnabled;
  private final boolean fieldRead;
  private final boolean finalReadField;

  static AnyInfo create(
      JsonFieldAccessor writeAccessor,
      JsonFieldAccessor readAccessor,
      JsonAnySetterInvoker setterInvoker,
      Class<?> setterValueRawType,
      boolean fieldRead,
      boolean finalReadField,
      Type mapType,
      Class<?> mapRawType,
      Type valueType,
      Class<?> valueRawType,
      JsonTypeUse valueTypeUse,
      int writeIndex,
      int constructionIndex) {
    return new AndroidAnyInfo(
        writeAccessor,
        readAccessor,
        setterInvoker,
        setterValueRawType,
        fieldRead,
        finalReadField,
        mapType,
        mapRawType,
        valueType,
        valueRawType,
        valueTypeUse,
        writeIndex,
        constructionIndex);
  }

  private AndroidAnyInfo(
      JsonFieldAccessor writeAccessor,
      JsonFieldAccessor readAccessor,
      JsonAnySetterInvoker setterInvoker,
      Class<?> setterValueRawType,
      boolean fieldRead,
      boolean finalReadField,
      Type mapType,
      Class<?> mapRawType,
      Type valueType,
      Class<?> valueRawType,
      JsonTypeUse valueTypeUse,
      int writeIndex,
      int constructionIndex) {
    super(
        writeAccessor,
        readAccessor,
        setterValueRawType,
        mapType,
        mapRawType,
        valueType,
        valueRawType,
        valueTypeUse,
        writeIndex,
        constructionIndex);
    this.setterInvoker = setterInvoker;
    readEnabled = readAccessor != null || setterInvoker != null;
    this.fieldRead = fieldRead;
    this.finalReadField = finalReadField;
  }

  @Override
  boolean readEnabled() {
    return readEnabled;
  }

  @Override
  boolean fieldRead() {
    return fieldRead;
  }

  @Override
  boolean finalReadField() {
    return finalReadField;
  }

  @Override
  void put(Object target, String name, Object value) {
    if (value == null && setterValueRawType.isPrimitive()) {
      throw new ForyJsonException(
          "Cannot read null into primitive @JsonAnySetter parameter " + setterValueRawType);
    }
    setterInvoker.set(target, name, value);
  }
}
