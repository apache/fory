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
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.meta.JsonDecodedMetadata;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonMetadataFormat;
import org.apache.fory.json.meta.JsonTypeUse;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.reflect.TypeRef;

/** Android-generated member metadata kept off the ordinary JVM field-builder layout. */
final class GeneratedFieldBuilder extends ObjectCodecBuilder.FieldBuilder {
  private boolean generatedField;
  private boolean generatedAnyField;
  private boolean generatedAnyGetter;
  private int generatedFieldNode = -1;
  private int generatedGetterNode = -1;
  private int generatedSetterNode = -1;
  private int generatedRecordNode = -1;
  private int generatedFieldOperation = -1;
  private int generatedFieldModifiers;
  private int generatedGetterOperation = -1;
  private int generatedSetterOperation = -1;
  private GeneratedObjectAccess.ResolvedType resolvedFieldType;
  private GeneratedObjectAccess.ResolvedType resolvedGetterType;
  private GeneratedObjectAccess.ResolvedType resolvedSetterType;
  private GeneratedObjectAccess.ResolvedType resolvedRecordType;
  private GeneratedObjectAccess.ResolvedType resolvedCreatorType;

  GeneratedFieldBuilder(String name) {
    super(name);
  }

  static ObjectCodecBuilder.FieldBuilder create(String name) {
    return new GeneratedFieldBuilder(name);
  }

  void setGeneratedField(
      Class<?> type,
      JsonDecodedMetadata.Field field,
      boolean writeSource,
      boolean readSink,
      boolean writeAllowed,
      boolean readAllowed,
      boolean record) {
    if (generatedField) {
      throw new ForyJsonException("Duplicate JSON field " + name);
    }
    generatedField = true;
    generatedFieldNode = field.typeNode();
    generatedFieldOperation = field.operation();
    generatedFieldModifiers = field.modifiers();
    fieldWriteAllowed = writeAllowed;
    fieldReadAllowed = readAllowed;
    if (writeSource) {
      generatedGetterOperation = field.operation();
    }
    if (readSink && !record) {
      generatedSetterOperation = field.operation();
    }
    mergeGeneratedProperty(type, field.property(), "field " + field.name());
    if (hasFlag(field.flags(), JsonMetadataFormat.JSON_ANY_PROPERTY)) {
      if (!writeSource && !readSink) {
        throw new ForyJsonException(
            "@JsonAnyProperty must enable reading or writing: field " + field.name());
      }
      generatedAnyField = true;
    }
  }

  void setGeneratedWriteGetter(Class<?> type, JsonDecodedMetadata.Method getter) {
    mergeGeneratedProperty(type, getter.property(), methodSource(getter));
    if (hasField() && !fieldWriteAllowed) {
      return;
    }
    generatedGetterNode = getter.returnNode();
    if (generatedGetterOperation >= 0 && generatedGetterOperation != generatedFieldOperation) {
      throw new ForyJsonException("Duplicate JSON getter for property " + name);
    }
    generatedGetterOperation = getter.operation();
  }

  void setGeneratedReadSetter(Class<?> type, JsonDecodedMetadata.Method setter) {
    mergeGeneratedProperty(type, setter.property(), methodSource(setter));
    if (hasField() && !fieldReadAllowed) {
      return;
    }
    generatedSetterNode = setter.parameterNode(0);
    if (generatedSetterOperation >= 0 && generatedSetterOperation != generatedFieldOperation) {
      throw new ForyJsonException("Duplicate JSON setter for property " + name);
    }
    generatedSetterOperation = setter.operation();
  }

  void setGeneratedAnyGetter(Class<?> type, JsonDecodedMetadata.Method getter) {
    mergeGeneratedProperty(type, getter.property(), methodSource(getter));
    generatedGetterNode = getter.returnNode();
    if (hasField() && !fieldWriteAllowed) {
      throw new ForyJsonException(
          "@JsonIgnore disables the same-name @JsonAnyGetter on " + methodSource(getter));
    }
    if (hasAnyGetter()) {
      throw new ForyJsonException("Multiple @JsonAnyGetter methods for property " + name);
    }
    generatedAnyGetter = true;
    generatedGetterOperation = getter.operation();
  }

  void setGeneratedRecordComponent(Class<?> type, JsonDecodedMetadata.RecordComponent component) {
    mergeGeneratedProperty(type, component.property(), "record component " + component.name());
    generatedRecordNode = component.typeNode();
  }

  void setGeneratedRecordAccessor(Class<?> type, JsonDecodedMetadata.Method accessor) {
    // Record access remains field-owned. The accessor contributes annotation metadata only.
    mergeGeneratedProperty(type, accessor.property(), methodSource(accessor));
  }

  void resolveGeneratedTypes(GeneratedObjectAccess access) {
    boolean fieldSelected =
        generatedFieldNode >= 0
            && (generatedGetterOperation == generatedFieldOperation
                || generatedSetterOperation == generatedFieldOperation);
    if (fieldSelected) {
      resolvedFieldType = access.resolveType(generatedFieldNode, "field " + name);
    }
    if (generatedGetterNode >= 0
        && generatedGetterOperation >= 0
        && generatedGetterOperation != generatedFieldOperation) {
      resolvedGetterType = access.resolveType(generatedGetterNode, "getter " + name);
    }
    if (generatedSetterNode >= 0
        && generatedSetterOperation >= 0
        && generatedSetterOperation != generatedFieldOperation) {
      resolvedSetterType = access.resolveType(generatedSetterNode, "setter " + name);
    }
    if (generatedRecordNode >= 0) {
      resolvedRecordType = access.resolveType(generatedRecordNode, "record component " + name);
    }
  }

  void resolveGeneratedCreatorTypes(GeneratedObjectAccess access) {
    resolveGeneratedTypes(access);
    if (resolvedFieldType == null
        && resolvedGetterType == null
        && resolvedSetterType == null
        && generatedFieldNode >= 0) {
      resolvedFieldType = access.resolveType(generatedFieldNode, "creator field " + name);
    }
  }

  void setGeneratedCreatorType(GeneratedObjectAccess.ResolvedType parameterType) {
    resolvedCreatorType = parameterType;
  }

  int generatedFieldModifiers() {
    return generatedFieldModifiers;
  }

  @Override
  boolean hasAnyField() {
    return generatedAnyField;
  }

  @Override
  boolean hasAnyGetter() {
    return generatedAnyGetter;
  }

  @Override
  boolean hasWriteSource() {
    return generatedGetterOperation >= 0;
  }

  @Override
  boolean hasReadSink() {
    return generatedSetterOperation >= 0;
  }

  @Override
  boolean hasReadInput(boolean record) {
    return hasReadSink() || record && fieldReadAllowed && generatedRecordNode >= 0;
  }

  @Override
  boolean hasLogicalMember() {
    return generatedField || generatedGetterOperation >= 0 || generatedSetterOperation >= 0;
  }

  @Override
  boolean hasField() {
    return generatedField;
  }

  @Override
  Type logicalType(TypeRef<?> ownerType) {
    if (resolvedGetterType != null) {
      return resolvedGetterType.javaType;
    }
    if (generatedGetterOperation >= 0 && resolvedFieldType != null) {
      return resolvedFieldType.javaType;
    }
    if (resolvedSetterType != null) {
      return resolvedSetterType.javaType;
    }
    if (resolvedFieldType != null) {
      return resolvedFieldType.javaType;
    }
    throw new ForyJsonException("JSON property has no type source " + name);
  }

  @Override
  JsonFieldInfo build(
      boolean record,
      TypeRef<?> ownerType,
      JsonTypeUse ownerTypeUse,
      PropertyNamingStrategy namingStrategy,
      boolean defaultWriteNull,
      GeneratedObjectAccess generatedAccess) {
    resolveGeneratedTypes(generatedAccess);
    validateTypes(ownerType);
    if (explicitInclude != JsonProperty.Include.DEFAULT && !hasWriteSource()) {
      throw new ForyJsonException(
          "JSON inclusion policy requires a write source for property " + name);
    }
    String jsonName = jsonName(namingStrategy);
    if (jsonName.isEmpty()) {
      throw new ForyJsonException("JSON property name must not be empty for " + name);
    }
    Class<?> rawWriteType = hasWriteSource() ? writeRawType() : null;
    boolean writeNull =
        rawWriteType != null
            && (rawWriteType.isPrimitive()
                || explicitInclude == JsonProperty.Include.ALWAYS
                || explicitInclude == JsonProperty.Include.DEFAULT && defaultWriteNull);
    resolveGeneratedAccessors(record, generatedAccess);
    JsonTypeUse typeUse = resolveTypeUse(ownerType, ownerTypeUse, "JSON property " + name);
    Type writeType = generatedWriteType();
    Type readType = generatedReadType(record);
    return JsonFieldInfo.fromGenerated(
        jsonName,
        writeNull,
        writeType,
        writeType == null ? null : generatedWriteRawType(),
        readType,
        readType == null ? null : generatedReadRawType(record),
        writeAccessor,
        readAccessor,
        typeUse);
  }

  private void resolveGeneratedAccessors(boolean record, GeneratedObjectAccess access) {
    int writeOperation = hasWriteSource() ? generatedGetterOperation : -1;
    int readOperation = !record && hasReadSink() ? generatedSetterOperation : -1;
    if (writeOperation >= 0
        && readOperation == writeOperation
        && writeOperation == generatedFieldOperation) {
      JsonFieldAccessor accessor =
          access.fieldAccessor(writeOperation, FieldAccessor.READ_WRITE_ACCESS);
      writeAccessor = accessor;
      readAccessor = accessor;
      return;
    }
    if (writeOperation >= 0) {
      writeAccessor =
          writeOperation == generatedFieldOperation
              ? access.fieldAccessor(writeOperation, FieldAccessor.READ_ACCESS)
              : access.getter(writeOperation);
    }
    if (readOperation >= 0) {
      readAccessor =
          readOperation == generatedFieldOperation
              ? access.fieldAccessor(readOperation, FieldAccessor.WRITE_ACCESS)
              : access.setter(readOperation);
    }
  }

  GeneratedObjectAccess.ResolvedType generatedAnyMapType() {
    GeneratedObjectAccess.ResolvedType type =
        generatedAnyGetter ? resolvedGetterType : resolvedFieldType;
    if (type == null) {
      throw new ForyJsonException("JSON Any property has no map type source " + name);
    }
    return type;
  }

  void validateGeneratedAnyTypes(Type mapType) {
    if (resolvedFieldType != null && !resolvedFieldType.javaType.equals(mapType)) {
      throw generatedAnyTypeConflict(resolvedFieldType.javaType, mapType);
    }
    if (resolvedGetterType != null && !resolvedGetterType.javaType.equals(mapType)) {
      throw generatedAnyTypeConflict(resolvedGetterType.javaType, mapType);
    }
    if (resolvedSetterType != null && !resolvedSetterType.javaType.equals(mapType)) {
      throw generatedAnyTypeConflict(resolvedSetterType.javaType, mapType);
    }
  }

  void resolveGeneratedAnyAccess(GeneratedObjectAccess access) {
    int writeOperation = anyWriteEnabled() ? generatedGetterOperation : -1;
    int readOperation = anyReadEnabled() ? generatedFieldOperation : -1;
    if (writeOperation >= 0 && writeOperation == readOperation) {
      JsonFieldAccessor accessor =
          access.fieldAccessor(writeOperation, FieldAccessor.READ_WRITE_ACCESS);
      writeAccessor = accessor;
      readAccessor = accessor;
      return;
    }
    if (writeOperation >= 0) {
      writeAccessor =
          writeOperation == generatedFieldOperation
              ? access.fieldAccessor(writeOperation, FieldAccessor.READ_ACCESS)
              : access.getter(writeOperation);
    }
    if (readOperation >= 0) {
      readAccessor = access.fieldAccessor(readOperation, FieldAccessor.WRITE_ACCESS);
    }
  }

  @Override
  JsonTypeUse resolveTypeUse(TypeRef<?> ownerType, JsonTypeUse ownerTypeUse, String path) {
    JsonTypeUse resolved = null;
    if (resolvedFieldType != null) {
      resolved = resolvedFieldType.type;
    }
    if (resolvedGetterType != null) {
      resolved = JsonTypeUse.merge(resolved, resolvedGetterType.type, path);
    }
    if (resolvedSetterType != null) {
      resolved = JsonTypeUse.merge(resolved, resolvedSetterType.type, path);
    }
    if (resolvedRecordType != null) {
      resolved = JsonTypeUse.merge(resolved, resolvedRecordType.type, path);
    }
    if (resolvedCreatorType != null) {
      resolved = JsonTypeUse.merge(resolved, resolvedCreatorType.type, path);
    }
    return resolved;
  }

  @Override
  void validateTypes(TypeRef<?> ownerType) {
    Type expected = null;
    if (resolvedFieldType != null) {
      expected = resolvedFieldType.javaType;
    }
    expected = requireGeneratedType(expected, resolvedGetterType);
    expected = requireGeneratedType(expected, resolvedSetterType);
    expected = requireGeneratedType(expected, resolvedRecordType);
    requireGeneratedType(expected, resolvedCreatorType);
  }

  @Override
  Class<?> writeRawType() {
    if (resolvedGetterType != null) {
      return resolvedGetterType.rawType;
    }
    if (generatedGetterOperation >= 0 && resolvedFieldType != null) {
      return resolvedFieldType.rawType;
    }
    throw new ForyJsonException("JSON property has no write type source " + name);
  }

  private Type generatedWriteType() {
    if (!hasWriteSource()) {
      return null;
    }
    return resolvedGetterType != null ? resolvedGetterType.javaType : resolvedFieldType.javaType;
  }

  private Type generatedReadType(boolean record) {
    if (record) {
      return resolvedRecordType == null ? null : resolvedRecordType.javaType;
    }
    if (!hasReadSink()) {
      return null;
    }
    return resolvedSetterType != null ? resolvedSetterType.javaType : resolvedFieldType.javaType;
  }

  private Class<?> generatedWriteRawType() {
    return resolvedGetterType != null ? resolvedGetterType.rawType : resolvedFieldType.rawType;
  }

  private Class<?> generatedReadRawType(boolean record) {
    if (record) {
      return resolvedRecordType == null ? null : resolvedRecordType.rawType;
    }
    return resolvedSetterType != null ? resolvedSetterType.rawType : resolvedFieldType.rawType;
  }

  void mergeGeneratedProperty(Class<?> type, JsonDecodedMetadata.Property property, String source) {
    if (!property.present()) {
      return;
    }
    hasJsonProperty = true;
    int declaredIndex = property.index();
    ObjectCodecBuilder.validatePropertyIndex(declaredIndex, name, type, source);
    if (declaredIndex != JsonProperty.INDEX_UNKNOWN) {
      if (explicitIndex != JsonProperty.INDEX_UNKNOWN && explicitIndex != declaredIndex) {
        throw new ForyJsonException(
            "Conflicting JSON property indexes for property "
                + name
                + " on "
                + type.getName()
                + ": "
                + explicitIndex
                + " from "
                + explicitIndexSource
                + " and "
                + declaredIndex
                + " from "
                + source);
      }
      explicitIndex = declaredIndex;
      if (explicitIndexSource == null) {
        explicitIndexSource = source;
      }
    }
    String declaredName = property.name();
    if (!declaredName.isEmpty()) {
      if (explicitName != null && !explicitName.equals(declaredName)) {
        throw new ForyJsonException(
            "Conflicting JSON names for property "
                + name
                + ": "
                + explicitName
                + " from "
                + explicitNameSource
                + " and "
                + declaredName
                + " from "
                + source);
      }
      explicitName = declaredName;
      if (explicitNameSource == null) {
        explicitNameSource = source;
      }
    }
    JsonProperty.Include declaredInclude = generatedInclude(property.include(), source);
    if (declaredInclude != JsonProperty.Include.DEFAULT) {
      if (explicitInclude != JsonProperty.Include.DEFAULT && explicitInclude != declaredInclude) {
        throw new ForyJsonException(
            "Conflicting JSON inclusion policies for property "
                + name
                + ": "
                + explicitInclude
                + " from "
                + explicitIncludeSource
                + " and "
                + declaredInclude
                + " from "
                + source);
      }
      explicitInclude = declaredInclude;
      if (explicitIncludeSource == null) {
        explicitIncludeSource = source;
      }
    }
  }

  private Type requireGeneratedType(Type expected, GeneratedObjectAccess.ResolvedType candidate) {
    if (candidate == null) {
      return expected;
    }
    if (expected != null && !expected.equals(candidate.javaType)) {
      throw new ForyJsonException(
          "Conflicting JSON property types for "
              + name
              + ": "
              + expected
              + " and "
              + candidate.javaType);
    }
    return candidate.javaType;
  }

  private ForyJsonException generatedAnyTypeConflict(Type type, Type expected) {
    return new ForyJsonException(
        "Conflicting JSON Any logical property type "
            + type
            + " for "
            + name
            + "; expected "
            + expected);
  }

  private static boolean hasFlag(int flags, int flag) {
    return (flags & flag) != 0;
  }

  private static String methodSource(JsonDecodedMetadata.Method method) {
    return "method " + method.name() + method.descriptor();
  }

  private static JsonProperty.Include generatedInclude(int ordinal, String source) {
    JsonProperty.Include[] values = JsonProperty.Include.values();
    if (ordinal < 0 || ordinal >= values.length) {
      throw new ForyJsonException("Invalid JSON inclusion policy from " + source);
    }
    return values[ordinal];
  }
}
