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

package org.apache.fory.json.resolver;

import java.lang.reflect.Type;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.reflect.TypeRef;

/** Immutable JSON type binding resolved and owned by {@link JsonTypeResolver}. */
public final class JsonTypeInfo {
  private final Type type;
  private final TypeRef<?> typeRef;
  private final Class<?> rawType;
  private final JsonFieldKind kind;
  private final JsonCodec codec;
  private final boolean primitive;

  JsonTypeInfo(
      Type type, TypeRef<?> typeRef, Class<?> rawType, JsonFieldKind kind, JsonCodec codec) {
    this.type = type;
    this.typeRef = typeRef;
    this.rawType = rawType;
    this.kind = kind;
    this.codec = codec;
    primitive = rawType.isPrimitive();
  }

  public Type type() {
    return type;
  }

  public TypeRef<?> typeRef() {
    return typeRef;
  }

  public Class<?> rawType() {
    return rawType;
  }

  public JsonFieldKind kind() {
    return kind;
  }

  public JsonCodec codec() {
    return codec;
  }

  public boolean primitive() {
    return primitive;
  }
}
