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

package org.apache.fory.resolver;

import static org.apache.fory.meta.Encoders.PACKAGE_DECODER;
import static org.apache.fory.meta.Encoders.TYPE_NAME_DECODER;

import org.apache.fory.collection.Tuple2;
import org.apache.fory.meta.EncodedMetaString;
import org.apache.fory.meta.Encoders;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.type.Types;
import org.apache.fory.util.function.Functions;

/**
 * This class put together object type related information to reduce array/map loop up when
 * serialization.
 */
public class TypeInfo {
  final Class<?> type;
  final EncodedMetaString namespace;
  final EncodedMetaString typeName;
  // Fory type ID for both native and xlang modes.
  // - Types 0-30: Shared internal types (Types.BOOL, Types.STRING, etc.)
  // - Types 31-255: Native-only internal types (VOID_ID, CHAR_ID, etc.)
  final int typeId;
  // User-registered type ID stored as unsigned int. -1 (0xffffffff) means "unset".
  final int userTypeId;
  Serializer<?> serializer;
  TypeDef typeDef;
  boolean needToWriteTypeDef;

  TypeInfo(
      Class<?> type,
      EncodedMetaString namespace,
      EncodedMetaString typeName,
      Serializer<?> serializer,
      int typeId,
      int userTypeId) {
    this.type = type;
    this.namespace = namespace;
    this.typeName = typeName;
    this.typeId = typeId;
    this.userTypeId = userTypeId;
    this.serializer = serializer;
  }

  /**
   * Creates a TypeInfo for deserialization with a TypeDef. Used when reading class meta from stream
   * where the TypeDef specifies the field layout.
   *
   * @param type the class
   * @param typeDef the class definition from stream
   */
  public TypeInfo(Class<?> type, TypeDef typeDef) {
    this.type = type;
    this.typeDef = typeDef;
    this.namespace = null;
    this.typeName = null;
    this.serializer = null;
    this.typeId = typeDef == null ? Types.UNKNOWN : typeDef.getClassSpec().typeId;
    this.userTypeId = typeDef == null ? -1 : typeDef.getClassSpec().userTypeId;
  }

  TypeInfo(
      TypeResolver classResolver,
      Class<?> type,
      Serializer<?> serializer,
      int typeId,
      int userTypeId) {
    this.type = type;
    this.serializer = serializer;
    needToWriteTypeDef = serializer != null && classResolver.needToWriteTypeDef(serializer);
    // When typeId indicates a named type, we need to create classname bytes for serialization.
    // - NAMED_STRUCT: unregistered struct classes
    // - NAMED_COMPATIBLE_STRUCT: unregistered classes in compatible mode
    // - NAMED_ENUM, NAMED_EXT: other named types
    // - REPLACE_STUB_ID: for write replace class in `ClassSerializer`
    boolean isNamedType =
        typeId == Types.NAMED_STRUCT
            || typeId == Types.NAMED_COMPATIBLE_STRUCT
            || typeId == Types.NAMED_ENUM
            || typeId == Types.NAMED_EXT
            || typeId == ClassResolver.REPLACE_STUB_ID;
    if (type != null && isNamedType) {
      Tuple2<String, String> tuple2 = Encoders.encodePkgAndClass(type);
      this.namespace = classResolver.sharedRegistry.getPackageEncodedMetaString(tuple2.f0);
      this.typeName = classResolver.sharedRegistry.getTypeNameEncodedMetaString(tuple2.f1);
    } else {
      this.namespace = null;
      this.typeName = null;
    }
    if (type != null) {
      boolean isLambda = Functions.isLambda(type);
      boolean isProxy = typeId != ClassResolver.REPLACE_STUB_ID && ReflectionUtils.isJdkProxy(type);
      if (isLambda) {
        typeId = ClassResolver.LAMBDA_STUB_ID;
      }
      if (isProxy) {
        typeId = ClassResolver.JDK_PROXY_STUB_ID;
      }
    }
    this.typeId = typeId;
    this.userTypeId = userTypeId;
  }

  public TypeInfo copy(int typeId) {
    if (typeId == this.typeId) {
      return this;
    }
    return new TypeInfo(type, namespace, typeName, serializer, typeId, userTypeId);
  }

  public TypeInfo copy(int typeId, int userTypeId) {
    if (typeId == this.typeId && userTypeId == this.userTypeId) {
      return this;
    }
    return new TypeInfo(type, namespace, typeName, serializer, typeId, userTypeId);
  }

  public Class<?> getType() {
    return type;
  }

  public TypeDef getTypeDef() {
    return typeDef;
  }

  void setTypeDef(TypeDef typeDef) {
    this.typeDef = typeDef;
  }

  /** Returns the fory type ID for this class. */
  public int getTypeId() {
    return typeId;
  }

  public int getUserTypeId() {
    return userTypeId;
  }

  @SuppressWarnings("unchecked")
  public <T> Serializer<T> getSerializer() {
    return (Serializer<T>) serializer;
  }

  public void setSerializer(Serializer<?> serializer) {
    this.serializer = serializer;
  }

  void setSerializer(TypeResolver resolver, Serializer<?> serializer) {
    this.serializer = serializer;
    needToWriteTypeDef = serializer != null && resolver.needToWriteTypeDef(serializer);
  }

  public String decodeNamespace() {
    return namespace.decode(PACKAGE_DECODER);
  }

  public String decodeTypeName() {
    return typeName.decode(TYPE_NAME_DECODER);
  }

  @Override
  public String toString() {
    return "TypeInfo{"
        + "cls="
        + type
        + ", serializer="
        + serializer
        + ", typeId="
        + typeId
        + ", userTypeId="
        + userTypeId
        + '}';
  }
}
