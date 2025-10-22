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

import static org.apache.fory.meta.Encoders.GENERIC_ENCODER;
import static org.apache.fory.meta.Encoders.PACKAGE_DECODER;
import static org.apache.fory.meta.Encoders.TYPE_NAME_DECODER;

import org.apache.fory.collection.Tuple2;
import org.apache.fory.meta.ClassDef;
import org.apache.fory.meta.Encoders;
import org.apache.fory.meta.MetaString.Encoding;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.function.Functions;

/**
 * This class put together object type related information to reduce array/map loop up when
 * serialization.
 */
public class ClassInfo {
  final Class<?> cls;
  final MetaStringBytes fullNameBytes;
  final MetaStringBytes namespaceBytes;
  final MetaStringBytes typeNameBytes;
  final boolean isDynamicGeneratedClass;
  int xtypeId;
  Serializer<?> serializer;
  // use primitive to avoid boxing
  // class id must be less than Integer.MAX_VALUE/2 since we use bit 0 as class id flag.
  short classId;
  ClassDef classDef;
  boolean needToWriteClassDef;

  ClassInfo(
      Class<?> cls,
      MetaStringBytes fullNameBytes,
      MetaStringBytes namespaceBytes,
      MetaStringBytes typeNameBytes,
      boolean isDynamicGeneratedClass,
      Serializer<?> serializer,
      short classId,
      int xtypeId) {
    this.cls = cls;
    this.fullNameBytes = fullNameBytes;
    this.namespaceBytes = namespaceBytes;
    this.typeNameBytes = typeNameBytes;
    this.isDynamicGeneratedClass = isDynamicGeneratedClass;
    this.xtypeId = xtypeId;
    this.serializer = serializer;
    this.classId = classId;
    if (cls != null && classId == TypeResolver.NO_CLASS_ID) {
      Preconditions.checkArgument(typeNameBytes != null);
    }
  }

  ClassInfo(
      TypeResolver classResolver,
      Class<?> cls,
      Serializer<?> serializer,
      short classId,
      short xtypeId) {
    this.cls = cls;
    this.serializer = serializer;
    needToWriteClassDef = serializer != null && classResolver.needToWriteClassDef(serializer);
    MetaStringResolver metaStringResolver = classResolver.getMetaStringResolver();
    if (cls != null && classResolver.getFory().isCrossLanguage()) {
      this.fullNameBytes =
          metaStringResolver.getOrCreateMetaStringBytes(
              GENERIC_ENCODER.encode(cls.getName(), Encoding.UTF_8));
    } else {
      this.fullNameBytes = null;
    }
    // When `classId == ClassResolver.REPLACE_STUB_ID` was established,
    // means only classes are serialized, not the instance. If we
    // serialize such class only, we need to write classname bytes.
    if (cls != null
        && (classId == ClassResolver.NO_CLASS_ID || classId == ClassResolver.REPLACE_STUB_ID)) {
      // REPLACE_STUB_ID for write replace class in `ClassSerializer`.
      Tuple2<String, String> tuple2 = Encoders.encodePkgAndClass(cls);
      this.namespaceBytes =
          metaStringResolver.getOrCreateMetaStringBytes(Encoders.encodePackage(tuple2.f0));
      this.typeNameBytes =
          metaStringResolver.getOrCreateMetaStringBytes(Encoders.encodeTypeName(tuple2.f1));
    } else {
      this.namespaceBytes = null;
      this.typeNameBytes = null;
    }
    this.xtypeId = xtypeId;
    this.classId = classId;
    if (cls != null) {
      boolean isLambda = Functions.isLambda(cls);
      boolean isProxy = classId != ClassResolver.REPLACE_STUB_ID && ReflectionUtils.isJdkProxy(cls);
      this.isDynamicGeneratedClass = isLambda || isProxy;
      if (isLambda) {
        this.classId = ClassResolver.LAMBDA_STUB_ID;
      }
      if (isProxy) {
        this.classId = ClassResolver.JDK_PROXY_STUB_ID;
      }
    } else {
      this.isDynamicGeneratedClass = false;
    }
  }

  public Class<?> getCls() {
    return cls;
  }

  public short getClassId() {
    return classId;
  }

  public int getXtypeId() {
    return xtypeId;
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
    needToWriteClassDef = serializer != null && resolver.needToWriteClassDef(serializer);
  }

  public String decodeNamespace() {
    return namespaceBytes.decode(PACKAGE_DECODER);
  }

  public String decodeTypeName() {
    return typeNameBytes.decode(TYPE_NAME_DECODER);
  }

  @Override
  public String toString() {
    return "ClassInfo{"
        + "cls="
        + cls
        + ", fullClassNameBytes="
        + fullNameBytes
        + ", isDynamicGeneratedClass="
        + isDynamicGeneratedClass
        + ", serializer="
        + serializer
        + ", classId="
        + classId
        + '}';
  }
}
