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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.JsonAnyGetter;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonAnySetter;
import org.apache.fory.json.annotation.JsonByteArray;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonFormat;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.annotation.JsonRawValue;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.json.annotation.JsonValidator;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.codec.JsonUnwrappedInfo.Declaration;
import org.apache.fory.json.codec.JsonUnwrappedInfo.WriteSpec;
import org.apache.fory.json.codec.ObjectCodec.AnyInfo;
import org.apache.fory.json.meta.JsonAnySetterAccessor;
import org.apache.fory.json.meta.JsonCreatorDeclaration;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.meta.JsonValidatorInfo;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.reflect.ObjectInstantiator;
import org.apache.fory.reflect.ObjectInstantiators;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.util.record.RecordComponent;
import org.apache.fory.util.record.RecordUtils;

/** Builds immutable object-codec metadata from one Java object type. */
final class ObjectCodecBuilder {
  private ObjectCodecBuilder() {}

  static <T> ObjectCodec<T> build(
      TypeRef<T> ownerType,
      boolean propertyDiscoveryEnabled,
      PropertyNamingStrategy propertyNamingStrategy,
      boolean writeNullFields,
      JsonSharedRegistry sharedRegistry,
      GeneratedJsonCodec<?> generatedCodec) {
    return build(
        ownerType,
        propertyDiscoveryEnabled,
        propertyNamingStrategy,
        writeNullFields,
        sharedRegistry,
        generatedCodec,
        null);
  }

  static <T> ObjectCodec<T> build(
      TypeRef<T> ownerType,
      boolean propertyDiscoveryEnabled,
      PropertyNamingStrategy propertyNamingStrategy,
      boolean writeNullFields,
      JsonSharedRegistry sharedRegistry,
      GeneratedJsonCodec<?> generatedCodec,
      JsonObjectModel objectModel) {
    Class<?> type = ownerType.getRawType();
    Annotations annotations = new Annotations(type, sharedRegistry);
    boolean record =
        generatedCodec == null ? RecordUtils.isRecord(type) : generatedCodec.validatedRecord();
    boolean hasAnyField =
        validateMemberAnnotations(
            type, propertyDiscoveryEnabled, record, generatedCodec, annotations, objectModel);
    JsonValidatorInfo validatorInfo =
        JsonValidatorInfo.create(type, findValidators(type, annotations), generatedCodec);
    if (objectModel != null && objectModel.fixedInstance() != null) {
      validateFixedObjectModel(type, hasAnyField, generatedCodec, annotations, objectModel);
      return ObjectCodec.createCodec(
          ownerType,
          new JsonFieldInfo[0],
          new JsonFieldInfo[0],
          JsonCreatorInfo.fixedInstance(type, objectModel.fixedInstance()),
          null,
          null,
          null,
          null,
          validatorInfo);
    }
    LinkedHashMap<String, FieldBuilder> builders = new LinkedHashMap<>();
    addFields(type, record, propertyDiscoveryEnabled, hasAnyField, builders, annotations, null);
    if (record) {
      addRecordAccessors(type, builders, generatedCodec);
    } else if (objectModel != null) {
      addObjectModelAccessors(type, builders, annotations, objectModel);
    }
    Method anySetter =
        addJsonMethods(
            type,
            propertyDiscoveryEnabled,
            record,
            builders,
            generatedCodec,
            annotations,
            objectModel);
    if (generatedCodec != null && generatedCodec.hasAnySetter() && anySetter == null) {
      throw new ForyJsonException(
          "Generated JSON Any setter does not match runtime annotations on " + type.getName());
    }
    FieldBuilder anyBuilder = findAnyBuilder(type, builders);
    if (anyBuilder != null && anyBuilder.unwrappedAnnotation != null) {
      throw new ForyJsonException(
          "@JsonUnwrapped cannot share a JSON Any logical property " + anyBuilder.name);
    }
    if (anyBuilder != null && anyBuilder.anyField != null) {
      if (anyBuilder.anyGetter != null || anySetter != null) {
        throw new ForyJsonException(
            "Field-backed and method-backed JSON Any declarations cannot be mixed on "
                + type.getName());
      }
    }
    if (anyBuilder != null && anyBuilder.hasJsonProperty) {
      throw new ForyJsonException(
          "@JsonProperty is not supported on JSON Any logical property "
              + anyBuilder.name
              + " on "
              + type.getName());
    }
    if (anyBuilder != null && anyBuilder.formatAnnotation != null) {
      throw new ForyJsonException(
          "@JsonFormat is not supported on JSON Any logical property "
              + anyBuilder.name
              + " on "
              + type.getName());
    }
    List<Declaration> creatorOnlyUnwrapped = new ArrayList<>();
    JsonCreatorInfo creatorInfo =
        record
            ? buildRecordCreatorInfo(
                type, ownerType, builders, propertyNamingStrategy, generatedCodec, annotations)
            : buildCreatorInfo(
                type,
                ownerType,
                builders,
                propertyNamingStrategy,
                creatorOnlyUnwrapped,
                generatedCodec,
                annotations,
                objectModel);
    if (objectModel != null) {
      validateObjectModelProperties(type, objectModel, builders, creatorInfo);
    }
    if (anySetter != null && (record || creatorInfo != null)) {
      throw new ForyJsonException(
          "@JsonAnySetter is not supported on constructor-backed type " + type.getName());
    }
    JsonPropertyOrder propertyOrder = findPropertyOrder(type, annotations);
    boolean hasAny = anyBuilder != null || anySetter != null;
    boolean hasUnwrapped = !creatorOnlyUnwrapped.isEmpty() || hasUnwrappedProperty(builders);
    boolean anyWrites = anyBuilder != null && anyBuilder.anyWriteEnabled();
    boolean orderWrites = propertyOrder != null || hasIndexedProperty(builders) || anyWrites;
    List<JsonFieldInfo> writes = new ArrayList<>();
    List<FieldBuilder> writeBuilders = orderWrites ? new ArrayList<>(builders.size()) : null;
    List<UnwrappedWriteBuilder> unwrappedWrites =
        hasUnwrapped ? new ArrayList<>(builders.size() + 1) : null;
    List<Declaration> unwrappedDeclarations =
        hasUnwrapped ? new ArrayList<>(builders.size() + creatorOnlyUnwrapped.size()) : null;
    List<JsonFieldInfo> reads = new ArrayList<>();
    List<JsonFieldInfo> deferredFields = objectModel == null ? null : new ArrayList<>();
    List<JsonFieldInfo> directDeferredFields = objectModel == null ? null : new ArrayList<>();
    List<Boolean> deferredRequired = objectModel == null ? null : new ArrayList<>();
    List<String> skippedNames = hasAny ? new ArrayList<>() : null;
    Map<String, FieldBuilder> canonicalNames = new LinkedHashMap<>();
    Map<Long, String> canonicalHashes = new LinkedHashMap<>();
    int anyOriginalIndex = -1;
    int anyConstructionIndex = -1;
    for (FieldBuilder builder : builders.values()) {
      if (builder == anyBuilder) {
        if (anyWrites) {
          anyOriginalIndex = writes.size();
          if (hasUnwrapped) {
            unwrappedWrites.add(UnwrappedWriteBuilder.any(builder));
          }
        }
        if (objectModel != null && builder.anyReadEnabled() && builder.creatorArgumentIndex < 0) {
          JsonFieldInfo field =
              builder.build(
                  record, ownerType, propertyNamingStrategy, writeNullFields, generatedCodec);
          anyConstructionIndex = creatorInfo.argumentCount() + deferredFields.size();
          deferredFields.add(field);
          deferredRequired.add(builder.requiredDeferred);
        }
        continue;
      }
      if (hasAny && builder.hasLogicalMember() && builder.unwrappedAnnotation == null) {
        String name = builder.jsonName(propertyNamingStrategy);
        if (name.isEmpty()) {
          throw new ForyJsonException("JSON property name must not be empty for " + builder.name);
        }
        FieldBuilder priorProperty = canonicalNames.put(name, builder);
        if (priorProperty != null) {
          throw new ForyJsonException(
              "Duplicate canonical JSON property name "
                  + name
                  + " for "
                  + priorProperty.nameDescription(propertyNamingStrategy)
                  + " and "
                  + builder.nameDescription(propertyNamingStrategy)
                  + " on "
                  + type.getName());
        }
        long hash = JsonFieldNameHash.hash(name);
        String priorHashName = canonicalHashes.put(hash, name);
        if (priorHashName != null && !priorHashName.equals(name)) {
          throw new ForyJsonException(
              "JSON property name hash collision between " + priorHashName + " and " + name);
        }
        boolean creatorInput = creatorInfo != null && builder.creatorArgumentIndex >= 0;
        boolean readableFixed =
            builder.hasReadSink()
                && (creatorInfo == null || objectModel != null && builder.creatorArgumentIndex < 0);
        if (!creatorInput && !readableFixed) {
          skippedNames.add(name);
        }
      }
      if (builder.hasIndex() && !builder.hasWriteSource()) {
        throw new ForyJsonException(
            "JSON property index requires a write source for property "
                + builder.name
                + " on "
                + type.getName()
                + " from "
                + builder.explicitIndexSource);
      }
      boolean creatorDirection =
          creatorInfo != null && builder.creatorArgumentIndex >= 0 && builder.creatorReadAllowed();
      if (!builder.hasWriteSource() && !builder.hasReadSink() && !creatorDirection) {
        if (objectModel != null
            && builder.creatorArgumentIndex >= 0
            && creatorInfo.hasDefault(builder.creatorArgumentIndex)) {
          continue;
        }
        if (builder.hasConfiguration()) {
          throw new ForyJsonException(
              "JSON property annotation has no readable or writable direction for " + builder.name);
        }
        continue;
      }
      if (builder.unwrappedAnnotation != null) {
        builder.validateUnwrapped(type, creatorInfo);
        JsonFieldInfo property =
            builder.build(
                record, ownerType, propertyNamingStrategy, writeNullFields, generatedCodec);
        markRequiredWrite(property, builder, creatorInfo, objectModel);
        int unwrappedConstructionIndex = -1;
        if (creatorInfo != null && builder.creatorArgumentIndex >= 0) {
          unwrappedConstructionIndex = builder.creatorArgumentIndex;
        } else if (objectModel != null && builder.hasReadSink()) {
          unwrappedConstructionIndex = creatorInfo.argumentCount() + deferredFields.size();
          deferredFields.add(property);
          deferredRequired.add(builder.requiredDeferred);
        }
        Declaration declaration =
            builder.buildUnwrappedDeclaration(
                ownerType, property, unwrappedConstructionIndex, creatorInfo != null);
        unwrappedDeclarations.add(declaration);
        if (builder.hasWriteSource()) {
          unwrappedWrites.add(UnwrappedWriteBuilder.group(builder, declaration));
        }
        continue;
      }
      if (creatorInfo != null && objectModel == null && !builder.hasWriteSource()) {
        if (builder.explicitInclude != JsonProperty.Include.DEFAULT) {
          throw new ForyJsonException(
              "JSON inclusion policy requires a write source for property " + builder.name);
        }
        if (builder.creatorArgumentIndex < 0 && builder.hasConfiguration()) {
          throw new ForyJsonException(
              "JSON property configuration is outside the creator read schema for " + builder.name);
        }
        continue;
      }
      JsonFieldInfo field =
          builder.build(record, ownerType, propertyNamingStrategy, writeNullFields, generatedCodec);
      markRequiredWrite(field, builder, creatorInfo, objectModel);
      if (!hasAny) {
        FieldBuilder priorProperty = canonicalNames.put(field.name(), builder);
        if (priorProperty != null) {
          throw new ForyJsonException(
              "Duplicate canonical JSON property name "
                  + field.name()
                  + " for "
                  + priorProperty.nameDescription(propertyNamingStrategy)
                  + " and "
                  + builder.nameDescription(propertyNamingStrategy)
                  + " on "
                  + type.getName());
        }
      }
      if (builder.hasWriteSource()) {
        writes.add(field);
        if (hasUnwrapped) {
          unwrappedWrites.add(UnwrappedWriteBuilder.direct(builder, field));
        }
        if (writeBuilders != null) {
          writeBuilders.add(builder);
        }
      }
      if (builder.hasReadSink()
          && (creatorInfo == null || objectModel != null && builder.creatorArgumentIndex < 0)) {
        if (!hasAny) {
          String priorHashName = canonicalHashes.put(field.nameHash(), field.name());
          if (priorHashName != null && !priorHashName.equals(field.name())) {
            throw new ForyJsonException(
                "JSON property name hash collision between "
                    + priorHashName
                    + " and "
                    + field.name());
          }
        }
        reads.add(field);
        if (objectModel != null) {
          deferredFields.add(field);
          directDeferredFields.add(field);
          deferredRequired.add(builder.requiredDeferred);
        }
      }
    }
    if (hasUnwrapped) {
      unwrappedDeclarations.addAll(creatorOnlyUnwrapped);
    }
    if (hasAny && creatorInfo != null) {
      for (JsonCreatorFieldInfo field : creatorInfo.fields()) {
        String priorName = canonicalHashes.get(field.nameHash());
        if (priorName != null && !priorName.equals(field.name())) {
          throw new ForyJsonException(
              "JSON property name hash collision between " + priorName + " and " + field.name());
        }
      }
    }
    int anyWriteIndex = -1;
    JsonFieldInfo[] writeArray;
    WriteSpec[] unwrappedWriteSpecs = null;
    if (hasUnwrapped) {
      writeArray = writes.toArray(new JsonFieldInfo[0]);
      unwrappedWriteSpecs =
          orderUnwrappedWrites(type, propertyOrder, unwrappedWrites).toArray(new WriteSpec[0]);
    } else if (anyWrites) {
      anyWriteIndex =
          orderAnyWriteFields(
              type, propertyOrder, writeBuilders, writes, anyBuilder, anyOriginalIndex);
      writeArray = writes.toArray(new JsonFieldInfo[0]);
    } else {
      writeArray =
          writeBuilders == null
              ? writes.toArray(new JsonFieldInfo[0])
              : orderWriteFields(type, propertyOrder, writeBuilders, writes);
    }
    JsonFieldInfo[] readArray = reads.toArray(new JsonFieldInfo[0]);
    if (objectModel != null && !deferredFields.isEmpty()) {
      creatorInfo =
          creatorInfo.withDeferredFields(
              deferredFields.toArray(new JsonFieldInfo[0]),
              directDeferredFields.toArray(new JsonFieldInfo[0]),
              requiredFlags(deferredRequired));
    }
    for (int i = 0; i < readArray.length; i++) {
      readArray[i].setReadIndex(i);
    }
    int constructionIndex = -1;
    if (anyBuilder != null && anyBuilder.anyReadEnabled()) {
      if (creatorInfo != null) {
        constructionIndex =
            anyBuilder.creatorArgumentIndex >= 0
                ? anyBuilder.creatorArgumentIndex
                : objectModel == null ? -1 : anyConstructionIndex;
      }
    }
    AnyInfo anyInfo =
        hasAny
            ? buildAnyInfo(
                ownerType,
                anyBuilder,
                anySetter,
                anyWriteIndex,
                constructionIndex,
                generatedCodec,
                annotations)
            : null;
    ObjectInstantiator<?> instantiator =
        creatorInfo == null
            ? GraalvmSupport.isGraalRuntime()
                ? ObjectInstantiators.getObjectInstantiator(type)
                : ObjectInstantiators.createObjectInstantiator(type)
            : null;
    String[] skipped = hasAny ? skippedNames.toArray(new String[0]) : null;
    JsonUnwrappedInfo unwrappedInfo =
        hasUnwrapped
            ? new JsonUnwrappedInfo(
                unwrappedDeclarations.toArray(new Declaration[0]), unwrappedWriteSpecs, skipped)
            : null;
    return ObjectCodec.createCodec(
        ownerType,
        writeArray,
        readArray,
        creatorInfo,
        anyInfo,
        skipped,
        unwrappedInfo,
        instantiator,
        validatorInfo);
  }

  private static void markRequiredWrite(
      JsonFieldInfo field,
      FieldBuilder builder,
      JsonCreatorInfo creatorInfo,
      JsonObjectModel objectModel) {
    if (objectModel != null && field.requiresUnboxedBinding()) {
      // The logical codec is bound only after the recursive parent shell is published. Its exact
      // transparent-null action and physical carrier are normalized in JsonFieldInfo.resolveTypes.
      return;
    }
    if (objectModel != null && field.hasOccurrenceNullability()) {
      if (field.occurrenceNullable()) {
        if (builder.explicitInclude == JsonProperty.Include.NON_NULL) {
          throw new ForyJsonException(
              "Nullable reconstructible JSON property "
                  + field.name()
                  + " cannot omit an explicit null value");
        }
        field.includeNullWrite();
      } else if (builder.hasWriteSource()
          && !field.occurrenceWrapsNull()
          && field.writeRawType() != null
          && !field.writeRawType().isPrimitive()) {
        field.requireNonNullWrite();
      }
      return;
    }
    int argumentIndex = builder.creatorArgumentIndex;
    if (objectModel != null
        && creatorInfo != null
        && argumentIndex >= 0
        && !creatorInfo.hasDefault(argumentIndex)
        && builder.hasWriteSource()
        && !field.writeNull()
        && !field.writeRawType().isPrimitive()) {
      field.requireNonNullWrite();
    }
  }

  private static boolean[] requiredFlags(List<Boolean> required) {
    boolean[] flags = new boolean[required.size()];
    for (int i = 0; i < flags.length; i++) {
      flags[i] = required.get(i);
    }
    return flags;
  }

  private static Method[] findValidators(Class<?> type, Annotations annotations) {
    List<Method> validators = null;
    for (Method method : type.getMethods()) {
      if (!method.isSynthetic()
          && !method.isBridge()
          && annotations.has(method, JsonValidator.class)) {
        if (validators == null) {
          validators = new ArrayList<>();
        }
        validators.add(method);
      }
    }
    return validators == null ? null : validators.toArray(new Method[0]);
  }

  private static boolean hasUnwrappedProperty(Map<String, FieldBuilder> builders) {
    for (FieldBuilder builder : builders.values()) {
      if (builder.unwrappedAnnotation != null) {
        return true;
      }
    }
    return false;
  }

  private static JsonPropertyOrder findPropertyOrder(Class<?> type, Annotations annotations) {
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      JsonPropertyOrder order = annotations.get(current, JsonPropertyOrder.class);
      if (order != null) {
        return order;
      }
    }
    return null;
  }

  private static boolean hasIndexedProperty(Map<String, FieldBuilder> builders) {
    for (FieldBuilder builder : builders.values()) {
      if (builder.hasIndex()) {
        return true;
      }
    }
    return false;
  }

  private static JsonFieldInfo[] orderWriteFields(
      Class<?> type,
      JsonPropertyOrder propertyOrder,
      List<FieldBuilder> builders,
      List<JsonFieldInfo> fields) {
    int size = fields.size();
    assert builders.size() == size;
    JsonFieldInfo[] ordered = new JsonFieldInfo[size];
    boolean[] selected = new boolean[size];
    int outputIndex = 0;

    if (propertyOrder != null) {
      String[] names = propertyOrder.value();
      if (names.length == 0 && !propertyOrder.alphabetic()) {
        throw new ForyJsonException("Empty @JsonPropertyOrder on " + type.getName());
      }
      for (String name : names) {
        if (name.isEmpty()) {
          throw new ForyJsonException("Empty @JsonPropertyOrder property on " + type.getName());
        }
        int propertyIndex = findOrderedProperty(name, builders, fields);
        if (propertyIndex < 0) {
          throw new ForyJsonException(
              "Unknown @JsonPropertyOrder property " + name + " on " + type.getName());
        }
        if (selected[propertyIndex]) {
          throw new ForyJsonException(
              "Duplicate @JsonPropertyOrder property " + name + " on " + type.getName());
        }
        selected[propertyIndex] = true;
        ordered[outputIndex++] = fields.get(propertyIndex);
      }
    }

    int indexedCount = 0;
    for (FieldBuilder builder : builders) {
      if (builder.hasIndex()) {
        indexedCount++;
      }
    }
    if (indexedCount != 0) {
      long[] indexed = new long[indexedCount];
      int next = 0;
      for (int i = 0; i < size; i++) {
        int index = builders.get(i).explicitIndex;
        if (index != JsonProperty.INDEX_UNKNOWN) {
          indexed[next++] = ((long) index << 32) | (i & 0xffffffffL);
        }
      }
      Arrays.sort(indexed);
      rejectDuplicateIndexes(type, builders, indexed);
      for (long indexedProperty : indexed) {
        int propertyIndex = (int) indexedProperty;
        if (!selected[propertyIndex]) {
          selected[propertyIndex] = true;
          ordered[outputIndex++] = fields.get(propertyIndex);
        }
      }
    }

    int unorderedStart = outputIndex;
    for (int i = 0; i < size; i++) {
      if (!selected[i]) {
        ordered[outputIndex++] = fields.get(i);
      }
    }
    if (propertyOrder != null && propertyOrder.alphabetic() && outputIndex - unorderedStart > 1) {
      Arrays.sort(
          ordered,
          unorderedStart,
          outputIndex,
          (left, right) -> left.name().compareTo(right.name()));
    }
    assert outputIndex == size;
    return ordered;
  }

  private static List<WriteSpec> orderUnwrappedWrites(
      Class<?> type, JsonPropertyOrder propertyOrder, List<UnwrappedWriteBuilder> entries) {
    int size = entries.size();
    boolean[] selected = new boolean[size];
    List<WriteSpec> ordered = new ArrayList<>(size);
    if (propertyOrder != null) {
      String[] names = propertyOrder.value();
      if (names.length == 0 && !propertyOrder.alphabetic()) {
        throw new ForyJsonException("Empty @JsonPropertyOrder on " + type.getName());
      }
      for (String name : names) {
        if (name.isEmpty()) {
          throw new ForyJsonException("Empty @JsonPropertyOrder property on " + type.getName());
        }
        int match = findUnwrappedWrite(name, entries);
        if (match < 0) {
          throw new ForyJsonException(
              "Unknown @JsonPropertyOrder property " + name + " on " + type.getName());
        }
        if (selected[match]) {
          throw new ForyJsonException(
              "Duplicate @JsonPropertyOrder property " + name + " on " + type.getName());
        }
        selected[match] = true;
        ordered.add(entries.get(match).spec);
      }
    }

    int indexedCount = 0;
    for (UnwrappedWriteBuilder entry : entries) {
      if (entry.builder.hasIndex()) {
        indexedCount++;
      }
    }
    if (indexedCount != 0) {
      long[] indexed = new long[indexedCount];
      int next = 0;
      for (int i = 0; i < size; i++) {
        int index = entries.get(i).builder.explicitIndex;
        if (index != JsonProperty.INDEX_UNKNOWN) {
          indexed[next++] = ((long) index << 32) | (i & 0xffffffffL);
        }
      }
      Arrays.sort(indexed);
      for (int i = 1; i < indexed.length; i++) {
        int previousIndex = (int) (indexed[i - 1] >>> 32);
        int index = (int) (indexed[i] >>> 32);
        if (previousIndex == index) {
          UnwrappedWriteBuilder previous = entries.get((int) indexed[i - 1]);
          UnwrappedWriteBuilder current = entries.get((int) indexed[i]);
          throw new ForyJsonException(
              "Duplicate JSON property index "
                  + index
                  + " for "
                  + previous.builder.name
                  + " and "
                  + current.builder.name
                  + " on "
                  + type.getName());
        }
      }
      for (long indexedEntry : indexed) {
        int entryIndex = (int) indexedEntry;
        if (!selected[entryIndex]) {
          selected[entryIndex] = true;
          ordered.add(entries.get(entryIndex).spec);
        }
      }
    }

    List<UnwrappedWriteBuilder> remaining = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      if (!selected[i]) {
        remaining.add(entries.get(i));
      }
    }
    if (propertyOrder != null && propertyOrder.alphabetic()) {
      remaining.sort((left, right) -> left.sortName().compareTo(right.sortName()));
    }
    for (UnwrappedWriteBuilder entry : remaining) {
      ordered.add(entry.spec);
    }
    assert ordered.size() == size;
    return ordered;
  }

  private static final class UnwrappedWriteBuilder {
    private final FieldBuilder builder;
    private final WriteSpec spec;
    private final String finalName;

    private UnwrappedWriteBuilder(FieldBuilder builder, WriteSpec spec, String finalName) {
      this.builder = builder;
      this.spec = spec;
      this.finalName = finalName;
    }

    private static UnwrappedWriteBuilder direct(FieldBuilder builder, JsonFieldInfo field) {
      return new UnwrappedWriteBuilder(builder, WriteSpec.direct(field), field.name());
    }

    private static UnwrappedWriteBuilder group(FieldBuilder builder, Declaration declaration) {
      return new UnwrappedWriteBuilder(builder, WriteSpec.group(declaration), null);
    }

    private static UnwrappedWriteBuilder any(FieldBuilder builder) {
      return new UnwrappedWriteBuilder(builder, WriteSpec.any(), null);
    }

    private String sortName() {
      return finalName == null ? builder.name : finalName;
    }
  }

  private static int findUnwrappedWrite(String name, List<UnwrappedWriteBuilder> entries) {
    for (int i = 0; i < entries.size(); i++) {
      if (name.equals(entries.get(i).finalName)) {
        return i;
      }
    }
    for (int i = 0; i < entries.size(); i++) {
      if (name.equals(entries.get(i).builder.name)) {
        return i;
      }
    }
    return -1;
  }

  private static int orderAnyWriteFields(
      Class<?> type,
      JsonPropertyOrder propertyOrder,
      List<FieldBuilder> builders,
      List<JsonFieldInfo> fields,
      FieldBuilder anyBuilder,
      int anyOriginalIndex) {
    int fixedCount = fields.size();
    int anyId = fixedCount;
    int[] ordered = new int[fixedCount + 1];
    boolean[] selected = new boolean[fixedCount + 1];
    int outputIndex = 0;
    if (propertyOrder != null) {
      String[] names = propertyOrder.value();
      if (names.length == 0 && !propertyOrder.alphabetic()) {
        throw new ForyJsonException("Empty @JsonPropertyOrder on " + type.getName());
      }
      for (String name : names) {
        if (name.isEmpty()) {
          throw new ForyJsonException("Empty @JsonPropertyOrder property on " + type.getName());
        }
        int id = findAnyOrderedProperty(name, builders, fields, anyBuilder, anyId);
        if (id < 0) {
          throw new ForyJsonException(
              "Unknown @JsonPropertyOrder property " + name + " on " + type.getName());
        }
        if (selected[id]) {
          throw new ForyJsonException(
              "Duplicate @JsonPropertyOrder property " + name + " on " + type.getName());
        }
        selected[id] = true;
        ordered[outputIndex++] = id;
      }
    }
    int indexedCount = 0;
    for (FieldBuilder builder : builders) {
      if (builder.hasIndex()) {
        indexedCount++;
      }
    }
    if (indexedCount != 0) {
      long[] indexed = new long[indexedCount];
      int next = 0;
      for (int i = 0; i < fixedCount; i++) {
        int index = builders.get(i).explicitIndex;
        if (index != JsonProperty.INDEX_UNKNOWN) {
          indexed[next++] = ((long) index << 32) | (i & 0xffffffffL);
        }
      }
      Arrays.sort(indexed);
      rejectDuplicateIndexes(type, builders, indexed);
      for (long indexedProperty : indexed) {
        int id = (int) indexedProperty;
        if (!selected[id]) {
          selected[id] = true;
          ordered[outputIndex++] = id;
        }
      }
    }
    int unorderedStart = outputIndex;
    for (int position = 0; position <= fixedCount; position++) {
      int id;
      if (position == anyOriginalIndex) {
        id = anyId;
      } else {
        id = position < anyOriginalIndex ? position : position - 1;
      }
      if (!selected[id]) {
        ordered[outputIndex++] = id;
      }
    }
    if (propertyOrder != null && propertyOrder.alphabetic()) {
      sortAnySuffix(ordered, unorderedStart, outputIndex, fields, anyBuilder, anyId);
    }
    JsonFieldInfo[] original = fields.toArray(new JsonFieldInfo[0]);
    int fixedOutput = 0;
    int writeIndex = -1;
    for (int i = 0; i < outputIndex; i++) {
      int id = ordered[i];
      if (id == anyId) {
        writeIndex = fixedOutput;
      } else {
        fields.set(fixedOutput++, original[id]);
      }
    }
    assert fixedOutput == fixedCount;
    assert writeIndex >= 0;
    return writeIndex;
  }

  private static int findAnyOrderedProperty(
      String name,
      List<FieldBuilder> builders,
      List<JsonFieldInfo> fields,
      FieldBuilder anyBuilder,
      int anyId) {
    for (int i = 0; i < fields.size(); i++) {
      if (name.equals(fields.get(i).name())) {
        return i;
      }
    }
    for (int i = 0; i < builders.size(); i++) {
      if (name.equals(builders.get(i).name)) {
        return i;
      }
    }
    return name.equals(anyBuilder.name) ? anyId : -1;
  }

  private static void rejectDuplicateIndexes(
      Class<?> type, List<FieldBuilder> builders, long[] indexed) {
    for (int i = 1; i < indexed.length; i++) {
      int previousIndex = (int) (indexed[i - 1] >>> 32);
      int index = (int) (indexed[i] >>> 32);
      if (previousIndex == index) {
        int previousProperty = (int) indexed[i - 1];
        int property = (int) indexed[i];
        throw new ForyJsonException(
            "Duplicate JSON property index "
                + index
                + " for "
                + builders.get(previousProperty).name
                + " from "
                + builders.get(previousProperty).explicitIndexSource
                + " and "
                + builders.get(property).name
                + " from "
                + builders.get(property).explicitIndexSource
                + " on "
                + type.getName());
      }
    }
  }

  private static void sortAnySuffix(
      int[] ordered,
      int start,
      int end,
      List<JsonFieldInfo> fields,
      FieldBuilder anyBuilder,
      int anyId) {
    for (int i = start + 1; i < end; i++) {
      int id = ordered[i];
      String name = id == anyId ? anyBuilder.name : fields.get(id).name();
      int position = i;
      while (position > start) {
        int previousId = ordered[position - 1];
        String previousName = previousId == anyId ? anyBuilder.name : fields.get(previousId).name();
        if (previousName.compareTo(name) <= 0) {
          break;
        }
        ordered[position] = previousId;
        position--;
      }
      ordered[position] = id;
    }
  }

  private static int findOrderedProperty(
      String name, List<FieldBuilder> builders, List<JsonFieldInfo> fields) {
    for (int i = 0; i < fields.size(); i++) {
      if (name.equals(fields.get(i).name())) {
        return i;
      }
    }
    for (int i = 0; i < builders.size(); i++) {
      if (name.equals(builders.get(i).name)) {
        return i;
      }
    }
    return -1;
  }

  private static void addFields(
      Class<?> type,
      boolean record,
      boolean propertyDiscoveryEnabled,
      boolean hasAnyField,
      LinkedHashMap<String, FieldBuilder> builders,
      Annotations annotations,
      Field[] nonPropertyFields) {
    List<Class<?>> hierarchy = new ArrayList<>();
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      hierarchy.add(current);
    }
    // Field mode normally drops fully ignored fields. An Any field still needs their logical names
    // to classify input as skipped and reject conflicting dynamic output.
    boolean retainIgnoredFields = propertyDiscoveryEnabled || hasAnyField;
    for (int i = hierarchy.size() - 1; i >= 0; i--) {
      Class<?> current = hierarchy.get(i);
      for (Field field : current.getDeclaredFields()) {
        if (containsField(nonPropertyFields, field)) {
          continue;
        }
        if (annotations.has(field, JsonUnwrapped.class) && !isEligibleField(field)) {
          throw new ForyJsonException("@JsonUnwrapped is not supported on JSON field: " + field);
        }
        int modifiers = field.getModifiers();
        if (!isEligibleField(field)) {
          continue;
        }
        JsonIgnore ignore = annotations.get(field, JsonIgnore.class);
        boolean write = ignore == null || !ignore.ignoreWrite();
        boolean readAllowed = ignore == null || !ignore.ignoreRead();
        boolean any = annotations.has(field, JsonAnyProperty.class);
        boolean read = (any || record || !Modifier.isFinal(modifiers)) && readAllowed;
        if (!retainIgnoredFields && !write && !read && !any) {
          continue;
        }
        FieldBuilder builder = builders.get(field.getName());
        if (builder == null) {
          builder = new FieldBuilder(field.getName(), annotations);
          builders.put(field.getName(), builder);
        }
        builder.setField(type, field, write, read, write, readAllowed);
      }
    }
  }

  private static boolean containsField(Field[] fields, Field target) {
    if (fields == null) {
      return false;
    }
    for (Field field : fields) {
      if (field.equals(target)) {
        return true;
      }
    }
    return false;
  }

  private static Method addJsonMethods(
      Class<?> type,
      boolean propertyDiscoveryEnabled,
      boolean record,
      LinkedHashMap<String, FieldBuilder> builders,
      GeneratedJsonCodec<?> generatedCodec,
      Annotations annotations,
      JsonObjectModel objectModel) {
    Method anyGetter = null;
    Method anySetter = null;
    for (Method method : type.getMethods()) {
      // javac copies runtime annotations to generic bridge methods. Those generated methods do not
      // own JSON declarations and processing them would reject an otherwise valid concrete method.
      if (method.isSynthetic() || method.isBridge()) {
        continue;
      }
      if (method.getDeclaringClass().isInterface() && annotations.has(method, JsonProperty.class)) {
        validatePropertyMethod(type, method, propertyDiscoveryEnabled, record, generatedCodec);
      }
      JsonAnyGetter getter = annotations.get(method, JsonAnyGetter.class);
      JsonAnySetter setter = annotations.get(method, JsonAnySetter.class);
      if (getter != null || setter != null) {
        if (!propertyDiscoveryEnabled) {
          throw new ForyJsonException(
              "JSON Any method annotations require property discovery: " + method);
        }
        if (getter != null && setter != null) {
          throw new ForyJsonException("Conflicting JSON Any method annotations on " + method);
        }
        if (getter != null) {
          validateAnyGetter(method);
          if (anyGetter != null) {
            throw new ForyJsonException("Multiple @JsonAnyGetter methods on " + type.getName());
          }
          anyGetter = method;
        } else {
          validateAnySetter(method, annotations);
          if (anySetter != null) {
            throw new ForyJsonException("Multiple @JsonAnySetter methods on " + type.getName());
          }
          anySetter = method;
        }
      }
      if (!propertyDiscoveryEnabled || record || !isEligibleAccessor(method)) {
        continue;
      }
      // Language metadata has already installed this exact accessor under its logical source name.
      // Bean-name discovery must not create a second property from a mangled JVM method name.
      if (containsObjectModelMethod(objectModel, method)) {
        continue;
      }
      String propertyName = getterPropertyName(method);
      if (propertyName != null) {
        FieldBuilder builder = builders.get(propertyName);
        if (builder == null) {
          builder = new FieldBuilder(propertyName, annotations);
          builders.put(propertyName, builder);
        }
        builder.setWriteGetter(type, method);
        continue;
      }
      propertyName = setterPropertyName(method);
      if (propertyName != null) {
        FieldBuilder builder = builders.get(propertyName);
        if (builder == null) {
          builder = new FieldBuilder(propertyName, annotations);
          builders.put(propertyName, builder);
        }
        builder.setReadSetter(type, method);
      }
    }
    if (anyGetter != null) {
      String propertyName = getterPropertyName(anyGetter);
      if (propertyName == null) {
        propertyName = anyGetter.getName();
      }
      FieldBuilder builder = builders.get(propertyName);
      if (builder == null) {
        builder = new FieldBuilder(propertyName, annotations);
        builders.put(propertyName, builder);
      }
      builder.setAnyGetter(type, anyGetter);
    }
    return anySetter;
  }

  // Native Image hosted discovery must stay aligned with this builder without adding duplicate
  // property-name parsing or allocation to ordinary JVM metadata construction.
  static boolean usesJsonMetadata(Method method, boolean record) {
    // javac copies runtime annotations to generic bridge methods. Those generated methods do not
    // own JSON declarations and processing them would reject an otherwise valid concrete method.
    if (method.isSynthetic() || method.isBridge()) {
      return false;
    }
    if (method.getDeclaringClass().isInterface()
        && method.isAnnotationPresent(JsonProperty.class)) {
      return true;
    }
    if (method.isAnnotationPresent(JsonAnyGetter.class)
        || method.isAnnotationPresent(JsonAnySetter.class)
        || method.isAnnotationPresent(JsonValue.class)
        || method.isAnnotationPresent(JsonRawValue.class)
        || method.isAnnotationPresent(JsonByteArray.class)
        || method.isAnnotationPresent(JsonValidator.class)) {
      return true;
    }
    return !record
        && isEligibleAccessor(method)
        && (usesJsonReturn(method) || usesJsonParameters(method));
  }

  static boolean usesJsonReturn(Method method) {
    // Java rejects type-use annotations on void, and setter returns are not JSON value owners.
    // Keep return and parameter roles separate so hosted metadata follows the same ownership.
    return method.isAnnotationPresent(JsonAnyGetter.class)
        || method.isAnnotationPresent(JsonValue.class)
        || method.isAnnotationPresent(JsonRawValue.class)
        || method.isAnnotationPresent(JsonByteArray.class)
        || getterPropertyName(method) != null;
  }

  static boolean usesJsonParameters(Method method) {
    return method.isAnnotationPresent(JsonAnySetter.class) || setterPropertyName(method) != null;
  }

  private static FieldBuilder findAnyBuilder(
      Class<?> type, LinkedHashMap<String, FieldBuilder> builders) {
    FieldBuilder anyBuilder = null;
    for (FieldBuilder builder : builders.values()) {
      if (!builder.isAny()) {
        continue;
      }
      if (anyBuilder != null && anyBuilder != builder) {
        throw new ForyJsonException("Multiple JSON Any properties on " + type.getName());
      }
      anyBuilder = builder;
    }
    return anyBuilder;
  }

  private static void addRecordAccessors(
      Class<?> type,
      LinkedHashMap<String, FieldBuilder> builders,
      GeneratedJsonCodec<?> generatedCodec) {
    if (generatedCodec != null) {
      String[] names = generatedCodec.validatedCreatorParameterNames();
      Class<?>[] parameterTypes = generatedCodec.validatedCreatorParameterTypes();
      for (int i = 0; i < names.length; i++) {
        Method accessor;
        try {
          accessor = type.getDeclaredMethod(names[i]);
        } catch (NoSuchMethodException e) {
          throw new ForyJsonException(
              "Missing generated JSON record accessor " + names[i] + " on " + type.getName(), e);
        }
        if (accessor.getParameterCount() != 0 || accessor.getReturnType() != parameterTypes[i]) {
          throw new ForyJsonException("Invalid JSON record accessor " + accessor);
        }
        FieldBuilder builder = builders.get(names[i]);
        if (builder == null) {
          throw new ForyJsonException("Missing JSON record field " + names[i]);
        }
        builder.setWriteGetter(type, accessor);
      }
      return;
    }
    RecordComponent[] components = RecordUtils.getRecordComponents(type);
    for (RecordComponent component : components) {
      FieldBuilder builder = builders.get(component.getName());
      if (builder == null) {
        throw new ForyJsonException("Missing JSON record field " + component.getName());
      }
      // Component accessors are the Record value source on every platform. This preserves an
      // explicitly implemented accessor and keeps native and Android-desugared Records identical.
      builder.setWriteGetter(type, component.getAccessor());
    }
  }

  private static void addObjectModelAccessors(
      Class<?> type,
      LinkedHashMap<String, FieldBuilder> builders,
      Annotations annotations,
      JsonObjectModel objectModel) {
    String[] names = objectModel.propertyNames();
    Method[] accessors = objectModel.propertyGetters();
    Method[] setters = objectModel.propertySetters();
    TypeRef<?>[] propertyTypes = objectModel.propertyTypes();
    boolean[] reconstructible = objectModel.propertyReconstructible();
    boolean[] required = objectModel.propertyRequired();
    Set<String> creatorProperties = new HashSet<>();
    for (String name : objectModel.parameterNames()) {
      creatorProperties.add(name);
    }
    for (int i = 0; i < names.length; i++) {
      Method accessor = accessors[i];
      FieldBuilder builder =
          builders.computeIfAbsent(names[i], name -> new FieldBuilder(name, annotations));
      builder.setObjectModelType(propertyTypes[i]);
      builder.objectModelReconstructible = reconstructible[i];
      builder.requiredDeferred = required[i];
      if (accessor != null) {
        builder.setWriteGetter(type, accessor);
      }
      if (setters[i] != null) {
        builder.setReadSetter(type, setters[i]);
      }
      builder.restrictObjectModelField(creatorProperties.contains(names[i]));
    }
  }

  private static void validateFixedObjectModel(
      Class<?> type,
      boolean hasAnyField,
      GeneratedJsonCodec<?> generatedCodec,
      Annotations annotations,
      JsonObjectModel objectModel) {
    LinkedHashMap<String, FieldBuilder> builders = new LinkedHashMap<>();
    // Effective field, getter, setter, and setter-parameter annotations are merged by the same
    // property owner used for ordinary objects. A singleton candidate is accepted only when that
    // merge removes every instance property in both directions.
    addFields(
        type, false, true, hasAnyField, builders, annotations, objectModel.nonPropertyFields());
    addObjectModelAccessors(type, builders, annotations, objectModel);
    Method anySetter =
        addJsonMethods(type, true, false, builders, generatedCodec, annotations, objectModel);
    if (anySetter != null) {
      throw new ForyJsonException(
          "Singleton JSON model has an effective @JsonAnySetter on " + type.getName());
    }
    validateObjectModelProperties(type, objectModel, builders, null);
    Set<String> candidates = new HashSet<>(Arrays.asList(objectModel.propertyNames()));
    for (FieldBuilder builder : builders.values()) {
      if (!candidates.contains(builder.name)
          && builder.hasLogicalMember()
          && (!builder.ignoreRead || !builder.ignoreWrite)) {
        throw new ForyJsonException(
            "Singleton JSON model has an effective instance property "
                + builder.name
                + " on "
                + type.getName());
      }
    }
  }

  private static void validateObjectModelProperties(
      Class<?> type,
      JsonObjectModel model,
      LinkedHashMap<String, FieldBuilder> builders,
      JsonCreatorInfo creator) {
    String[] names = model.propertyNames();
    boolean[] reconstructible = model.propertyReconstructible();
    for (int i = 0; i < names.length; i++) {
      String name = names[i];
      FieldBuilder builder = builders.get(name);
      if (builder == null) {
        throw new ForyJsonException("Missing JSON object-model property " + name);
      }
      int argumentIndex = builder.creatorArgumentIndex;
      if (argumentIndex >= 0) {
        if (builder.ignoreRead != builder.ignoreWrite) {
          throw new ForyJsonException(
              "Constructor property " + name + " cannot be ignored in one direction on " + type);
        }
        if (builder.ignoreRead && !creator.hasDefault(argumentIndex)) {
          throw new ForyJsonException(
              "Ignored constructor property " + name + " requires a language default on " + type);
        }
        continue;
      }
      if (builder.ignoreRead && builder.ignoreWrite) {
        if (builder.requiredDeferred) {
          throw new ForyJsonException(
              "Required deferred property " + name + " cannot be ignored on " + type);
        }
        continue;
      }
      if (model.fixedInstance() != null) {
        throw new ForyJsonException(
            "Singleton JSON model has effective instance property " + name + " on " + type);
      }
      if (!reconstructible[i]) {
        throw new ForyJsonException(
            "JSON object-model property " + name + " is not reconstructible on " + type);
      }
      if (!builder.hasWriteSource() || !builder.hasReadSink()) {
        throw new ForyJsonException(
            "Deferred JSON property " + name + " must be readable and writable on " + type);
      }
      if (builder.ignoreRead || builder.ignoreWrite) {
        throw new ForyJsonException(
            "Deferred JSON property " + name + " cannot be ignored in one direction on " + type);
      }
      if (builder.requiredDeferred
          && (builder.objectModelType == null
              || builder.objectModelType.getTypeExtMeta() == null
              || builder.objectModelType.getTypeExtMeta().nullable()
              || builder.objectModelType.getTypeExtMeta().nullableWrapper())) {
        throw new ForyJsonException(
            "Required deferred JSON property " + name + " must be non-null on " + type);
      }
    }
  }

  private static void rejectRecordCreator(Class<?> type, Annotations annotations) {
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (annotations.has(constructor, JsonCreator.class)) {
        throw new ForyJsonException("@JsonCreator is not supported on record " + type.getName());
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      if (annotations.has(method, JsonCreator.class)) {
        throw new ForyJsonException("@JsonCreator is not supported on record " + type.getName());
      }
    }
  }

  private static JsonCreatorInfo buildRecordCreatorInfo(
      Class<?> type,
      TypeRef<?> ownerType,
      LinkedHashMap<String, FieldBuilder> builders,
      PropertyNamingStrategy namingStrategy,
      GeneratedJsonCodec<?> generatedCodec,
      Annotations annotations) {
    rejectRecordCreator(type, annotations);
    String[] names;
    Class<?>[] rawTypes;
    Constructor<?> constructor;
    if (generatedCodec == null) {
      RecordComponent[] components = RecordUtils.getRecordComponents(type);
      names = new String[components.length];
      rawTypes = new Class<?>[components.length];
      for (int i = 0; i < components.length; i++) {
        names[i] = components[i].getName();
        rawTypes[i] = components[i].getType();
      }
      constructor = RecordUtils.getRecordConstructor(type).f0;
    } else {
      names = generatedCodec.validatedCreatorParameterNames();
      rawTypes = generatedCodec.validatedCreatorParameterTypes();
      if (names == null || !generatedCodec.validatedRecord()) {
        throw new ForyJsonException(
            "Generated JSON record creator metadata is missing for " + type);
      }
      constructor = (Constructor<?>) generatedCodec.validatedCreator();
    }
    Type[] parameterTypes = generatedCodec == null ? constructor.getGenericParameterTypes() : null;
    // Fields and accessors already carry the annotations propagated from Record components. The
    // generated companion owns the canonical parameter order on Android, where Android 8 ART can
    // crash while reading annotations from desugared Record constructor parameters.
    Parameter[] parameters = generatedCodec == null ? constructor.getParameters() : null;
    List<JsonCreatorFieldInfo> fields = new ArrayList<>(names.length);
    for (int i = 0; i < names.length; i++) {
      FieldBuilder builder = builders.get(names[i]);
      if (builder == null || !builder.hasLogicalMember()) {
        throw new ForyJsonException("Unknown JSON record component " + names[i]);
      }
      if (parameters != null) {
        builder.mergeAnnotation(type, parameters[i]);
      }
      if (!builder.creatorReadAllowed()) {
        continue;
      }
      if (parameters == null) {
        builder.creatorArgumentIndex = i;
      } else {
        bindCreatorType(ownerType, constructor, i, parameterTypes[i], builder);
      }
      if (builder.isAny() || builder.unwrappedAnnotation != null) {
        continue;
      }
      TypeRef<?> resolved =
          parameterTypes == null
              ? builder.logicalTypeRef(ownerType)
              : ownerType.resolveType(parameterTypes[i]);
      fields.add(
          new JsonCreatorFieldInfo(
              builder.jsonName(namingStrategy),
              i,
              resolved,
              rawTypes[i],
              builder.codecAnnotation(),
              builder.valueCodecClass(),
              builder.formatAnnotation(),
              builder.creatorUnboxedRequired));
    }
    JsonCreatorFieldInfo[] fieldArray = fields.toArray(new JsonCreatorFieldInfo[0]);
    rejectCreatorHashCollisions(fieldArray);
    return new JsonCreatorInfo(
        type, constructor, fieldArray, creatorDefaults(rawTypes), generatedCodec);
  }

  private static JsonCreatorInfo buildCreatorInfo(
      Class<?> type,
      TypeRef<?> ownerType,
      LinkedHashMap<String, FieldBuilder> builders,
      PropertyNamingStrategy namingStrategy,
      List<Declaration> creatorOnlyUnwrapped,
      GeneratedJsonCodec<?> generatedCodec,
      Annotations annotations,
      JsonObjectModel objectModel) {
    JsonCreatorDeclaration declaration =
        JsonCreatorDeclaration.find(type, annotations.registry, objectModel);
    if (declaration == null) {
      if (objectModel == null
          && generatedCodec != null
          && generatedCodec.validatedCreatorParameterNames() != null) {
        throw new ForyJsonException(
            "Generated JSON creator does not match runtime annotations on " + type.getName());
      }
      if (objectModel == null) {
        return null;
      }
      validateGeneratedObjectModel(type, objectModel, generatedCodec);
      return buildObjectModelCreatorInfo(
          type,
          ownerType,
          builders,
          namingStrategy,
          generatedCodec,
          annotations,
          objectModel,
          null);
    }
    Executable creator = declaration.executable();
    JsonCreator annotation = declaration.annotation();
    if (objectModel != null) {
      if (!creator.equals(objectModel.creator())) {
        throw new ForyJsonException(
            "Language JSON object model does not describe selected @JsonCreator " + creator);
      }
      validateObjectModelCreatorAnnotation(
          declaration.annotationSource(), annotation, objectModel, annotations);
      validateGeneratedObjectModel(type, objectModel, generatedCodec);
      return buildObjectModelCreatorInfo(
          type,
          ownerType,
          builders,
          namingStrategy,
          generatedCodec,
          annotations,
          objectModel,
          declaration);
    }
    validateGeneratedCreator(type, creator, annotation, generatedCodec, annotations);

    Map<String, FieldBuilder> jsonProperties = new LinkedHashMap<>();
    for (FieldBuilder builder : builders.values()) {
      if (!builder.hasLogicalMember()) {
        continue;
      }
      String jsonName = builder.jsonName(namingStrategy);
      FieldBuilder prior = jsonProperties.put(jsonName, builder);
      if (prior != null) {
        throw new ForyJsonException(
            "Duplicate canonical JSON property name "
                + jsonName
                + " for "
                + prior.nameDescription(namingStrategy)
                + " and "
                + builder.nameDescription(namingStrategy)
                + " on "
                + type.getName());
      }
    }

    Type[] parameterTypes = creator.getGenericParameterTypes();
    Class<?>[] rawTypes = creator.getParameterTypes();
    Parameter[] parameters = creator.getParameters();
    List<JsonCreatorFieldInfo> fields = new ArrayList<>(parameterTypes.length);
    String[] propertyNames = annotation.value();
    if (propertyNames.length != 0) {
      if (propertyNames.length != parameterTypes.length) {
        throw new ForyJsonException(
            "@JsonCreator property count does not match parameter count on " + creator);
      }
      Set<String> names = new HashSet<>();
      for (int i = 0; i < propertyNames.length; i++) {
        String javaName = propertyNames[i];
        if (javaName.isEmpty() || !names.add(javaName)) {
          throw new ForyJsonException("Invalid @JsonCreator property name " + javaName);
        }
        if (annotations.has(parameters[i], JsonProperty.class)) {
          throw new ForyJsonException(
              "Property-list @JsonCreator parameters cannot declare @JsonProperty: " + creator);
        }
        FieldBuilder builder = builders.get(javaName);
        if (builder == null || !builder.hasLogicalMember()) {
          throw new ForyJsonException("Unknown @JsonCreator Java property " + javaName);
        }
        bindCreatorType(ownerType, creator, i, parameterTypes[i], builder);
        builder.mergeCodec(parameters[i]);
        builder.mergeUnwrapped(parameters[i]);
        if (builder.isAny() && !builder.anyReadEnabled()) {
          throw new ForyJsonException(
              "JSON Any creator property has no read-enabled field: " + javaName);
        }
        if (!builder.creatorReadAllowed()) {
          throw new ForyJsonException("@JsonCreator property is ignored for reading: " + javaName);
        }
        if (!builder.isAny() && builder.unwrappedAnnotation == null) {
          TypeRef<?> resolved = ownerType.resolveType(parameterTypes[i]);
          JsonCodec codecAnnotation = builder.codecAnnotation();
          Class<? extends JsonValueCodec<?>> valueCodecClass = builder.valueCodecClass();
          fields.add(
              new JsonCreatorFieldInfo(
                  builder.jsonName(namingStrategy),
                  i,
                  resolved,
                  rawTypes[i],
                  codecAnnotation,
                  valueCodecClass,
                  builder.formatAnnotation(),
                  builder.creatorUnboxedRequired));
        }
      }
    } else {
      Set<String> names = new HashSet<>();
      for (int i = 0; i < parameters.length; i++) {
        JsonProperty property = annotations.get(parameters[i], JsonProperty.class);
        if (property == null || property.value().isEmpty()) {
          throw new ForyJsonException(
              "Parameter-local @JsonCreator requires a non-empty @JsonProperty on every parameter: "
                  + creator);
        }
        String jsonName = property.value();
        if (!names.add(jsonName)) {
          throw new ForyJsonException("Duplicate @JsonCreator JSON property " + jsonName);
        }
        FieldBuilder builder = jsonProperties.get(jsonName);
        if (builder != null) {
          if (builder.isAny()) {
            throw new ForyJsonException(
                "Parameter-local @JsonCreator cannot bind JSON Any property " + builder.name);
          }
          bindCreatorType(ownerType, creator, i, parameterTypes[i], builder);
          if (!builder.creatorReadAllowed()) {
            throw new ForyJsonException(
                "@JsonCreator property is ignored for reading: " + builder.name);
          }
          builder.mergeCreatorParameter(type, parameters[i]);
          if (property.include() != JsonProperty.Include.DEFAULT && !builder.hasWriteSource()) {
            throw new ForyJsonException(
                "Creator parameter inclusion requires a write source for " + jsonName);
          }
        } else {
          validatePropertyIndex(property.index(), jsonName, type, parameters[i]);
          if (property.index() != JsonProperty.INDEX_UNKNOWN) {
            throw new ForyJsonException(
                "Creator-only property "
                    + jsonName
                    + " cannot declare serialization index "
                    + property.index()
                    + " on "
                    + type.getName()
                    + " from "
                    + parameters[i]);
          }
          if (property.include() != JsonProperty.Include.DEFAULT) {
            throw new ForyJsonException(
                "Creator-only property cannot declare an inclusion policy: " + jsonName);
          }
        }
        TypeRef<?> resolved = ownerType.resolveType(parameterTypes[i]);
        JsonCodec codecAnnotation =
            builder == null
                ? annotations.get(parameters[i], JsonCodec.class)
                : builder.codecAnnotation();
        Class<? extends JsonValueCodec<?>> valueCodecClass =
            builder == null ? null : builder.valueCodecClass();
        JsonFormat formatAnnotation = builder == null ? null : builder.formatAnnotation();
        JsonUnwrapped unwrapped =
            builder == null
                ? annotations.get(parameters[i], JsonUnwrapped.class)
                : builder.unwrappedAnnotation;
        if (unwrapped != null) {
          if (codecAnnotation != null || valueCodecClass != null || formatAnnotation != null) {
            throw new ForyJsonException(
                "Value codecs are not supported on @JsonUnwrapped creator property " + jsonName);
          }
          if (builder == null) {
            creatorOnlyUnwrapped.add(
                new Declaration(
                    jsonName,
                    unwrapped.prefix(),
                    unwrapped.suffix(),
                    resolved.getType(),
                    rawTypes[i],
                    null,
                    null,
                    false,
                    true,
                    i));
          }
        } else {
          fields.add(
              new JsonCreatorFieldInfo(
                  jsonName,
                  i,
                  resolved,
                  rawTypes[i],
                  codecAnnotation,
                  valueCodecClass,
                  formatAnnotation,
                  false));
        }
      }
    }
    JsonCreatorFieldInfo[] fieldArray = fields.toArray(new JsonCreatorFieldInfo[0]);
    rejectCreatorHashCollisions(fieldArray);
    return new JsonCreatorInfo(
        type, creator, fieldArray, creatorDefaults(rawTypes), generatedCodec);
  }

  private static void validateObjectModelCreatorAnnotation(
      Executable annotationSource,
      JsonCreator annotation,
      JsonObjectModel objectModel,
      Annotations annotations) {
    int logicalCount = objectModel.parameterNames().length;
    String[] declaredNames = annotation.value();
    if (declaredNames.length != 0) {
      if (declaredNames.length != logicalCount) {
        throw new ForyJsonException(
            "@JsonCreator property count does not match language object model on "
                + annotationSource);
      }
      Parameter[] parameters = annotationSource.getParameters();
      for (int i = 0; i < logicalCount; i++) {
        if (annotations.has(parameters[i], JsonProperty.class)) {
          throw new ForyJsonException(
              "Property-list @JsonCreator parameters cannot declare @JsonProperty: "
                  + annotationSource);
        }
      }
      return;
    }
    Parameter[] parameters = annotationSource.getParameters();
    for (int i = 0; i < logicalCount; i++) {
      JsonProperty property = annotations.get(parameters[i], JsonProperty.class);
      if (property == null || property.value().isEmpty()) {
        throw new ForyJsonException(
            "Parameter-local @JsonCreator requires a non-empty @JsonProperty on every parameter: "
                + annotationSource);
      }
    }
  }

  private static JsonCreatorInfo buildObjectModelCreatorInfo(
      Class<?> type,
      TypeRef<?> ownerType,
      LinkedHashMap<String, FieldBuilder> builders,
      PropertyNamingStrategy namingStrategy,
      GeneratedJsonCodec<?> generatedCodec,
      Annotations annotations,
      JsonObjectModel objectModel,
      JsonCreatorDeclaration declaration) {
    if (objectModel.fixedInstance() != null) {
      return JsonCreatorInfo.fixedInstance(type, objectModel.fixedInstance());
    }
    Executable creator = objectModel.creator();
    String[] names = objectModel.parameterNames();
    Method[] defaultMethods = objectModel.defaultMethods();
    int[] defaultMaskBits = objectModel.defaultMaskBits();
    TypeRef<?>[] logicalParameterTypes = objectModel.parameterTypes();
    Type[] parameterTypes = creator.getGenericParameterTypes();
    Class<?>[] rawTypes = creator.getParameterTypes();
    Executable annotationSource = declaration == null ? creator : declaration.annotationSource();
    Parameter[] parameters = annotationSource.getParameters();
    JsonCreator creatorAnnotation = declaration == null ? null : declaration.annotation();
    String[] declaredProperties = creatorAnnotation == null ? null : creatorAnnotation.value();
    Map<String, FieldBuilder> jsonProperties = null;
    if (creatorAnnotation != null && declaredProperties.length == 0) {
      jsonProperties = new LinkedHashMap<>();
      for (FieldBuilder builder : builders.values()) {
        if (!builder.hasLogicalMember()) {
          continue;
        }
        String jsonName = builder.jsonName(namingStrategy);
        FieldBuilder prior = jsonProperties.put(jsonName, builder);
        if (prior != null) {
          throw new ForyJsonException(
              "Duplicate canonical JSON property name " + jsonName + " on " + type.getName());
        }
      }
    }
    List<JsonCreatorFieldInfo> fields = new ArrayList<>(parameterTypes.length);
    for (int i = 0; i < parameterTypes.length; i++) {
      FieldBuilder builder;
      if (creatorAnnotation == null) {
        builder = builders.get(names[i]);
      } else if (declaredProperties.length != 0) {
        builder = builders.get(declaredProperties[i]);
      } else {
        JsonProperty property = annotations.get(parameters[i], JsonProperty.class);
        builder = property == null ? null : jsonProperties.get(property.value());
      }
      if (builder == null || !builder.hasLogicalMember()) {
        throw new ForyJsonException(
            "Unknown JSON object-model property for creator parameter "
                + names[i]
                + " on "
                + creator);
      }
      if (builder.creatorArgumentIndex >= 0) {
        throw new ForyJsonException(
            "Multiple creator parameters map to JSON object-model property "
                + builder.name
                + " on "
                + creator);
      }
      bindCreatorType(ownerType, creator, i, parameterTypes[i], builder);
      builder.mergeCreatorParameter(type, parameters[i]);
      if (!builder.creatorReadAllowed()) {
        if (defaultMethods[i] == null && defaultMaskBits[i] < 0) {
          throw new ForyJsonException(
              "Ignored constructor property " + names[i] + " requires a language default");
        }
        continue;
      }
      if (!builder.isAny() && builder.unwrappedAnnotation == null) {
        TypeRef<?> resolved = logicalParameterTypes[i];
        fields.add(
            new JsonCreatorFieldInfo(
                builder.jsonName(namingStrategy),
                i,
                resolved,
                rawTypes[i],
                builder.codecAnnotation(),
                builder.valueCodecClass(),
                builder.formatAnnotation(),
                builder.creatorUnboxedRequired));
      }
    }
    JsonCreatorFieldInfo[] fieldArray = fields.toArray(new JsonCreatorFieldInfo[0]);
    rejectCreatorHashCollisions(fieldArray);
    return new JsonCreatorInfo(
        type,
        creator,
        objectModel.invocationCreator(),
        fieldArray,
        creatorDefaults(rawTypes),
        generatedCodec,
        defaultMethods,
        objectModel.defaultsReceiver(),
        names,
        objectModel.defaultConstructor(),
        defaultMaskBits,
        objectModel.parameterNullable());
  }

  private static void validateGeneratedCreator(
      Class<?> type,
      Executable creator,
      JsonCreator annotation,
      GeneratedJsonCodec<?> generatedCodec,
      Annotations annotations) {
    if (generatedCodec == null) {
      return;
    }
    String[] names = generatedCodec.validatedCreatorParameterNames();
    Class<?>[] parameterTypes = generatedCodec.validatedCreatorParameterTypes();
    String factoryName = generatedCodec.validatedCreatorFactoryName();
    if (names == null
        || !Arrays.equals(parameterTypes, creator.getParameterTypes())
        || creator instanceof Method != (factoryName != null)
        || creator instanceof Method && !creator.getName().equals(factoryName)) {
      throw new ForyJsonException(
          "Generated JSON creator signature does not match " + creator + " on " + type.getName());
    }
    String[] runtimeNames = annotation.value();
    if (runtimeNames.length == 0) {
      runtimeNames = new String[creator.getParameterCount()];
      Parameter[] parameters = creator.getParameters();
      for (int i = 0; i < parameters.length; i++) {
        JsonProperty property = annotations.get(parameters[i], JsonProperty.class);
        runtimeNames[i] = property == null ? null : property.value();
      }
    }
    if (!Arrays.equals(names, runtimeNames)) {
      throw new ForyJsonException(
          "Generated JSON creator names do not match " + creator + " on " + type.getName());
    }
  }

  private static void validateGeneratedObjectModel(
      Class<?> type, JsonObjectModel objectModel, GeneratedJsonCodec<?> generatedCodec) {
    if (generatedCodec == null) {
      return;
    }
    String[] names = generatedCodec.validatedCreatorParameterNames();
    Class<?>[] parameterTypes = generatedCodec.validatedCreatorParameterTypes();
    String factoryName = generatedCodec.validatedCreatorFactoryName();
    Executable creator = objectModel.creator();
    String expectedFactory = creator instanceof Method ? creator.getName() : null;
    if (names == null
        || !Arrays.equals(names, objectModel.parameterNames())
        || !Arrays.equals(parameterTypes, creator.getParameterTypes())
        || (factoryName == null ? expectedFactory != null : !factoryName.equals(expectedFactory))) {
      throw new ForyJsonException(
          "Generated JSON creator metadata does not match language object model on "
              + type.getName());
    }
  }

  private static void validatePropertyIndex(
      int index, String propertyName, Class<?> type, AnnotatedElement source) {
    if (index < JsonProperty.INDEX_UNKNOWN) {
      throw new ForyJsonException(
          "Invalid JSON property index "
              + index
              + " for property "
              + propertyName
              + " on "
              + type.getName()
              + " from "
              + source);
    }
  }

  private static void bindCreatorType(
      TypeRef<?> ownerType,
      Executable creator,
      int parameterIndex,
      Type parameterType,
      FieldBuilder builder) {
    TypeRef<?> resolvedParameterRef = ownerType.resolveType(parameterType);
    Type resolvedParameter = resolvedParameterRef.getType();
    Type propertyType = builder.logicalType(ownerType);
    Class<?> parameterCarrier = creator.getParameterTypes()[parameterIndex];
    boolean compatible =
        resolvedParameter.equals(propertyType)
            || builder.objectModelType != null
                && JsonObjectModel.compatibleType(resolvedParameterRef, builder.objectModelType);
    boolean requiresCarrier =
        builder.objectModelType != null
            && (parameterCarrier == builder.objectModelType.getRawType() || !compatible)
            && UnboxedValueCodec.requiresCarrier(parameterCarrier, builder.objectModelType);
    if (requiresCarrier) {
      builder.creatorUnboxedRequired = true;
    }
    if (!compatible && !requiresCarrier) {
      throw new ForyJsonException(
          "@JsonCreator parameter type "
              + resolvedParameter
              + " does not match property "
              + builder.name
              + " type "
              + propertyType
              + " on "
              + creator
              + " parameter "
              + parameterIndex);
    }
    builder.creatorArgumentIndex = parameterIndex;
  }

  private static void rejectCreatorHashCollisions(JsonCreatorFieldInfo[] fields) {
    Map<Long, String> names = new LinkedHashMap<>();
    for (JsonCreatorFieldInfo field : fields) {
      String prior = names.put(field.nameHash(), field.name());
      if (prior != null) {
        throw new ForyJsonException(
            "JSON creator property hash collision between " + prior + " and " + field.name());
      }
    }
  }

  private static Object[] creatorDefaults(Class<?>[] types) {
    Object[] defaults = new Object[types.length];
    for (int i = 0; i < types.length; i++) {
      Class<?> type = types[i];
      if (type == boolean.class) {
        defaults[i] = Boolean.FALSE;
      } else if (type == byte.class) {
        defaults[i] = Byte.valueOf((byte) 0);
      } else if (type == short.class) {
        defaults[i] = Short.valueOf((short) 0);
      } else if (type == int.class) {
        defaults[i] = Integer.valueOf(0);
      } else if (type == long.class) {
        defaults[i] = Long.valueOf(0L);
      } else if (type == float.class) {
        defaults[i] = Float.valueOf(0F);
      } else if (type == double.class) {
        defaults[i] = Double.valueOf(0D);
      } else if (type == char.class) {
        defaults[i] = Character.valueOf((char) 0);
      }
    }
    return defaults;
  }

  private static boolean validateMemberAnnotations(
      Class<?> type,
      boolean propertyDiscoveryEnabled,
      boolean record,
      GeneratedJsonCodec<?> generatedCodec,
      Annotations annotations,
      JsonObjectModel objectModel) {
    boolean hasAnyField = false;
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        if (annotations.has(field, JsonFormat.class)) {
          validateFormatField(field, annotations);
        }
        if (annotations.has(field, JsonByteArray.class)) {
          validateByteArrayField(field, annotations);
        }
        if (annotations.has(field, JsonRawValue.class)) {
          validateRawField(field, annotations);
        }
        if (annotations.has(field, JsonCodec.class) && !isEligibleField(field)) {
          throw new ForyJsonException("@JsonCodec is not supported on JSON field: " + field);
        }
        JsonIgnore ignore = annotations.get(field, JsonIgnore.class);
        if (annotations.has(field, JsonUnwrapped.class)
            && ignore != null
            && ignore.ignoreRead()
            && ignore.ignoreWrite()) {
          throw new ForyJsonException(
              "@JsonUnwrapped has no JSON read or write direction: " + field);
        }
        if (annotations.has(field, JsonCodec.class)
            && ignore != null
            && ignore.ignoreRead()
            && ignore.ignoreWrite()) {
          throw new ForyJsonException("@JsonCodec has no JSON read or write direction: " + field);
        }
        if (annotations.has(field, JsonProperty.class) && !isEligibleField(field)) {
          throw new ForyJsonException("@JsonProperty is not supported on JSON field: " + field);
        }
        if (annotations.has(field, JsonAnyProperty.class)) {
          if (!isEligibleField(field)) {
            throw new ForyJsonException(
                "@JsonAnyProperty is not supported on JSON field: " + field);
          }
          hasAnyField = true;
        }
      }
      for (Method method : current.getDeclaredMethods()) {
        if (method.isSynthetic() || method.isBridge()) {
          continue;
        }
        // Validation follows the effective public method set used by property discovery. An
        // unannotated override removes the inherited JSON declaration from that set.
        if (isOverridden(type, method)) {
          continue;
        }
        if (annotations.has(method, JsonCodec.class)) {
          validateCodecMethod(
              type,
              method,
              propertyDiscoveryEnabled,
              record,
              generatedCodec,
              annotations,
              objectModel);
        }
        validateCodecParameters(method, propertyDiscoveryEnabled, record, objectModel, annotations);
        if (annotations.has(method, JsonRawValue.class)) {
          validateRawMethod(
              type, method, propertyDiscoveryEnabled, record, generatedCodec, annotations);
        }
        if (annotations.has(method, JsonByteArray.class)) {
          validateByteArrayMethod(
              type, method, propertyDiscoveryEnabled, record, generatedCodec, annotations);
        }
        if (annotations.has(method, JsonUnwrapped.class)) {
          validateUnwrappedMethod(
              type, method, propertyDiscoveryEnabled, record, generatedCodec, annotations);
        }
        validateUnwrappedParameters(
            type, method, propertyDiscoveryEnabled, record, objectModel, annotations);
        if (annotations.has(method, JsonProperty.class)) {
          validatePropertyMethod(type, method, propertyDiscoveryEnabled, record, generatedCodec);
        }
        if (annotations.has(method, JsonIgnore.class)) {
          validateIgnoreMethod(
              type, method, propertyDiscoveryEnabled, record, generatedCodec, objectModel);
        }
        validateIgnoreParameters(
            type, method, propertyDiscoveryEnabled, record, objectModel, annotations);
        if (annotations.has(method, JsonAnyGetter.class)) {
          if (!propertyDiscoveryEnabled) {
            throw new ForyJsonException(
                "JSON Any method annotations require property discovery: " + method);
          }
          validateAnyGetter(method);
        }
        if (annotations.has(method, JsonAnySetter.class)) {
          if (!propertyDiscoveryEnabled) {
            throw new ForyJsonException(
                "JSON Any method annotations require property discovery: " + method);
          }
          validateAnySetter(method, annotations);
        }
        if (annotations.has(method, JsonValidator.class)) {
          validateValidator(method);
        }
      }
    }
    // Generated Record parameter annotations are checked by the source processor against fields
    // and accessors. Do not re-read desugared constructor parameters: Android 8 ART may crash.
    if (!record || generatedCodec == null) {
      for (Constructor<?> constructor : type.getDeclaredConstructors()) {
        validateCodecParameters(type, constructor, record, objectModel, annotations);
        validateUnwrappedParameters(type, constructor, record, objectModel, annotations);
        validateIgnoreParameters(type, constructor, record, objectModel, annotations);
      }
    }
    for (Method method : type.getMethods()) {
      if (!method.getDeclaringClass().isInterface()) {
        continue;
      }
      if (annotations.has(method, JsonCodec.class)) {
        // getMethods exposes only the effective inherited declaration. A class or child-interface
        // override therefore suppresses an annotation from the overridden interface method.
        validateCodecMethod(
            type,
            method,
            propertyDiscoveryEnabled,
            record,
            generatedCodec,
            annotations,
            objectModel);
      }
      validateCodecParameters(method, propertyDiscoveryEnabled, record, objectModel, annotations);
      if (annotations.has(method, JsonRawValue.class)) {
        validateRawMethod(
            type, method, propertyDiscoveryEnabled, record, generatedCodec, annotations);
      }
      if (annotations.has(method, JsonByteArray.class)) {
        validateByteArrayMethod(
            type, method, propertyDiscoveryEnabled, record, generatedCodec, annotations);
      }
      if (annotations.has(method, JsonUnwrapped.class)) {
        validateUnwrappedMethod(
            type, method, propertyDiscoveryEnabled, record, generatedCodec, annotations);
      }
      validateUnwrappedParameters(
          type, method, propertyDiscoveryEnabled, record, objectModel, annotations);
      if (annotations.has(method, JsonIgnore.class)) {
        validateIgnoreMethod(
            type, method, propertyDiscoveryEnabled, record, generatedCodec, objectModel);
      }
      validateIgnoreParameters(
          type, method, propertyDiscoveryEnabled, record, objectModel, annotations);
      if (annotations.has(method, JsonValidator.class)) {
        validateValidator(method);
      }
    }
    return hasAnyField;
  }

  private static void validateIgnoreMethod(
      Class<?> type,
      Method method,
      boolean propertyDiscoveryEnabled,
      boolean record,
      GeneratedJsonCodec<?> generatedCodec,
      JsonObjectModel objectModel) {
    if (record && isRecordAccessor(type, method, generatedCodec)) {
      return;
    }
    if (containsObjectModelMethod(objectModel, method)) {
      return;
    }
    if (propertyDiscoveryEnabled
        && isEligibleAccessor(method)
        && (getterPropertyName(method) != null || setterPropertyName(method) != null)) {
      return;
    }
    throw new ForyJsonException(
        "@JsonIgnore requires an effective JSON getter or setter: " + method);
  }

  private static void validateIgnoreParameters(
      Class<?> type,
      Method method,
      boolean propertyDiscoveryEnabled,
      boolean record,
      JsonObjectModel objectModel,
      Annotations annotations) {
    Parameter[] parameters = method.getParameters();
    for (int i = 0; i < parameters.length; i++) {
      if (!annotations.has(parameters[i], JsonIgnore.class)) {
        continue;
      }
      if (annotations.has(method, JsonCreator.class)) {
        continue;
      }
      if (objectModel != null && !containsObjectModelSetter(objectModel, method)) {
        continue;
      }
      if (i == 0
          && (containsObjectModelSetter(objectModel, method)
              || !record
                  && propertyDiscoveryEnabled
                  && isEligibleAccessor(method)
                  && setterPropertyName(method) != null)) {
        continue;
      }
      throw new ForyJsonException(
          "@JsonIgnore parameter requires a JSON setter or creator value: " + method);
    }
  }

  private static void validateIgnoreParameters(
      Class<?> type,
      Constructor<?> constructor,
      boolean record,
      JsonObjectModel objectModel,
      Annotations annotations) {
    Parameter[] parameters = constructor.getParameters();
    boolean selected =
        annotations.has(constructor, JsonCreator.class)
            || record && isRecordConstructor(type, constructor)
            || objectModel != null && constructor.equals(objectModel.creator());
    for (Parameter parameter : parameters) {
      if (annotations.has(parameter, JsonIgnore.class) && !selected && objectModel == null) {
        throw new ForyJsonException(
            "@JsonIgnore parameter requires a selected JSON constructor: " + constructor);
      }
    }
  }

  private static boolean containsObjectModelMethod(JsonObjectModel model, Method method) {
    if (model == null) {
      return false;
    }
    return containsMethod(model.propertyGetters(), method)
        || containsMethod(model.propertySetters(), method);
  }

  private static boolean containsObjectModelSetter(JsonObjectModel model, Method method) {
    return model != null && containsMethod(model.propertySetters(), method);
  }

  private static boolean containsMethod(Method[] methods, Method target) {
    for (Method method : methods) {
      if (target.equals(method)) {
        return true;
      }
    }
    return false;
  }

  private static void validateValidator(Method method) {
    if (!JsonValidatorInfo.isValidatorMethod(method)) {
      throw new ForyJsonException("Invalid @JsonValidator method " + method);
    }
  }

  private static void validateUnwrappedMethod(
      Class<?> type,
      Method method,
      boolean propertyDiscoveryEnabled,
      boolean record,
      GeneratedJsonCodec<?> generatedCodec,
      Annotations annotations) {
    if (annotations.has(method, JsonAnyGetter.class)
        || annotations.has(method, JsonAnySetter.class)) {
      throw new ForyJsonException("@JsonUnwrapped cannot annotate a JSON Any method: " + method);
    }
    if (record) {
      if (isPropagatedRecordUnwrapped(type, method, generatedCodec, annotations)) {
        return;
      }
      throw new ForyJsonException("@JsonUnwrapped requires a record component accessor: " + method);
    }
    if (!propertyDiscoveryEnabled
        || !isEligibleAccessor(method)
        || getterPropertyName(method) == null && setterPropertyName(method) == null) {
      throw new ForyJsonException(
          "@JsonUnwrapped requires an effective JSON getter or setter: " + method);
    }
  }

  private static void validateUnwrappedParameters(
      Class<?> type,
      Method method,
      boolean propertyDiscoveryEnabled,
      boolean record,
      JsonObjectModel objectModel,
      Annotations annotations) {
    Parameter[] parameters = method.getParameters();
    for (int i = 0; i < parameters.length; i++) {
      if (!annotations.has(parameters[i], JsonUnwrapped.class)) {
        continue;
      }
      if (annotations.has(method, JsonCreator.class)) {
        continue;
      }
      if (objectModel != null && !containsObjectModelSetter(objectModel, method)) {
        continue;
      }
      if (!record
          && propertyDiscoveryEnabled
          && isEligibleAccessor(method)
          && setterPropertyName(method) != null
          && i == 0) {
        continue;
      }
      throw new ForyJsonException(
          "@JsonUnwrapped parameter requires a JSON setter or creator value: " + method);
    }
  }

  private static void validateUnwrappedParameters(
      Class<?> type,
      Constructor<?> constructor,
      boolean record,
      JsonObjectModel objectModel,
      Annotations annotations) {
    Parameter[] parameters = constructor.getParameters();
    for (int i = 0; i < parameters.length; i++) {
      JsonUnwrapped annotation = annotations.get(parameters[i], JsonUnwrapped.class);
      if (annotation == null
          || annotations.has(constructor, JsonCreator.class)
          || objectModel != null) {
        continue;
      }
      if (record && isPropagatedRecordUnwrapped(type, constructor, i, annotation, annotations)) {
        continue;
      }
      throw new ForyJsonException(
          "@JsonUnwrapped parameter requires a @JsonCreator: " + constructor);
    }
  }

  private static void validateCodecMethod(
      Class<?> type,
      Method method,
      boolean propertyDiscoveryEnabled,
      boolean record,
      GeneratedJsonCodec<?> generatedCodec,
      Annotations annotations,
      JsonObjectModel objectModel) {
    if (annotations.has(method, JsonAnyGetter.class)) {
      if (!propertyDiscoveryEnabled) {
        throw new ForyJsonException(
            "JSON Any method annotations require property discovery: " + method);
      }
      validateAnyGetter(method);
      return;
    }
    if (record) {
      if (isRecordAccessor(type, method, generatedCodec)) {
        return;
      }
      throw new ForyJsonException(
          "@JsonCodec requires an effective ordinary JSON getter: " + method);
    }
    if (containsObjectModelMethod(objectModel, method)) {
      return;
    }
    if (!propertyDiscoveryEnabled
        || !isEligibleAccessor(method)
        || getterPropertyName(method) == null) {
      throw new ForyJsonException(
          "@JsonCodec requires an effective ordinary JSON getter: " + method);
    }
  }

  private static void validateCodecParameters(
      Method method,
      boolean propertyDiscoveryEnabled,
      boolean record,
      JsonObjectModel objectModel,
      Annotations annotations) {
    Parameter[] parameters = method.getParameters();
    for (int i = 0; i < parameters.length; i++) {
      if (!annotations.has(parameters[i], JsonCodec.class)) {
        continue;
      }
      if (annotations.has(method, JsonCreator.class)) {
        continue;
      }
      if (objectModel != null && !containsObjectModelSetter(objectModel, method)) {
        continue;
      }
      if (annotations.has(method, JsonAnySetter.class)) {
        if (propertyDiscoveryEnabled && i == 1) {
          continue;
        }
        throw new ForyJsonException(
            "@JsonCodec is not supported on JSON Any setter key: " + method);
      }
      if (!record
          && propertyDiscoveryEnabled
          && isEligibleAccessor(method)
          && setterPropertyName(method) != null
          && i == 0) {
        continue;
      }
      throw new ForyJsonException(
          "@JsonCodec parameter requires a JSON setter or creator value: " + method);
    }
  }

  private static void validateCodecParameters(
      Class<?> type,
      Constructor<?> constructor,
      boolean record,
      JsonObjectModel objectModel,
      Annotations annotations) {
    Parameter[] parameters = constructor.getParameters();
    for (int i = 0; i < parameters.length; i++) {
      JsonCodec annotation = annotations.get(parameters[i], JsonCodec.class);
      if (annotation == null || annotations.has(constructor, JsonCreator.class)) {
        continue;
      }
      if (record && isRecordConstructor(type, constructor)) {
        continue;
      }
      if (objectModel != null && constructor.equals(objectModel.creator())) {
        continue;
      }
      if (objectModel != null) {
        continue;
      }
      throw new ForyJsonException("@JsonCodec parameter requires a @JsonCreator: " + constructor);
    }
  }

  private static void validateRawField(Field field, Annotations annotations) {
    if (!isEligibleField(field) || field.getType() != String.class) {
      throw new ForyJsonException("Invalid @JsonRawValue field " + field);
    }
    if (annotations.has(field, JsonCodec.class)
        || annotations.has(field, JsonByteArray.class)
        || annotations.has(field, JsonAnyProperty.class)) {
      throw new ForyJsonException("Conflicting JSON annotations on @JsonRawValue field " + field);
    }
    JsonIgnore ignore = annotations.get(field, JsonIgnore.class);
    if (ignore != null && ignore.ignoreRead() && ignore.ignoreWrite()) {
      throw new ForyJsonException("@JsonRawValue has no JSON read or write direction: " + field);
    }
  }

  private static void validateRawMethod(
      Class<?> type,
      Method method,
      boolean propertyDiscoveryEnabled,
      boolean record,
      GeneratedJsonCodec<?> generatedCodec,
      Annotations annotations) {
    if ((!propertyDiscoveryEnabled
            && !(record
                && isPropagatedRecordAnnotation(
                    type, method, JsonRawValue.class, generatedCodec, annotations)))
        || !isEligibleAccessor(method)
        || method.isVarArgs()
        || method.getTypeParameters().length != 0
        || method.getParameterCount() != 0
        || method.getReturnType() != String.class
        || (!annotations.has(method, JsonValue.class)
            && ((!record && getterPropertyName(method) == null)
                || (record && !isRecordAccessor(type, method, generatedCodec))))) {
      throw new ForyJsonException("Invalid @JsonRawValue method " + method);
    }
    if (annotations.has(method, JsonCodec.class)
        || annotations.has(method, JsonByteArray.class)
        || annotations.has(method, JsonAnyGetter.class)) {
      throw new ForyJsonException("Conflicting JSON annotations on @JsonRawValue method " + method);
    }
  }

  private static void validateByteArrayField(Field field, Annotations annotations) {
    if (!isEligibleField(field) || field.getType() != byte[].class) {
      throw new ForyJsonException("Invalid @JsonByteArray field " + field);
    }
    if (annotations.has(field, JsonCodec.class)
        || annotations.has(field, JsonRawValue.class)
        || annotations.has(field, JsonAnyProperty.class)) {
      throw new ForyJsonException("Conflicting JSON annotations on @JsonByteArray field " + field);
    }
    JsonIgnore ignore = annotations.get(field, JsonIgnore.class);
    if (ignore != null && ignore.ignoreRead() && ignore.ignoreWrite()) {
      throw new ForyJsonException("@JsonByteArray has no JSON read or write direction: " + field);
    }
  }

  private static void validateFormatField(Field field, Annotations annotations) {
    if (!isEligibleField(field)) {
      throw new ForyJsonException("Invalid @JsonFormat field " + field);
    }
    if (annotations.has(field, JsonCodec.class)
        || annotations.has(field, JsonByteArray.class)
        || annotations.has(field, JsonRawValue.class)
        || annotations.has(field, JsonAnyProperty.class)
        || annotations.has(field, JsonUnwrapped.class)
        || annotations.has(field, JsonValue.class)) {
      throw new ForyJsonException("Conflicting JSON annotations on @JsonFormat field " + field);
    }
    JsonIgnore ignore = annotations.get(field, JsonIgnore.class);
    if (ignore != null && ignore.ignoreRead() && ignore.ignoreWrite()) {
      throw new ForyJsonException("@JsonFormat has no JSON read or write direction: " + field);
    }
  }

  private static void validateByteArrayMethod(
      Class<?> type,
      Method method,
      boolean propertyDiscoveryEnabled,
      boolean record,
      GeneratedJsonCodec<?> generatedCodec,
      Annotations annotations) {
    if ((!propertyDiscoveryEnabled
            && !(record
                && isPropagatedRecordAnnotation(
                    type, method, JsonByteArray.class, generatedCodec, annotations)))
        || !isEligibleAccessor(method)
        || method.isVarArgs()
        || method.getTypeParameters().length != 0
        || method.getParameterCount() != 0
        || method.getReturnType() != byte[].class
        || ((!record && getterPropertyName(method) == null)
            || (record && !isRecordAccessor(type, method, generatedCodec)))) {
      throw new ForyJsonException("Invalid @JsonByteArray method " + method);
    }
    if (annotations.has(method, JsonCodec.class)
        || annotations.has(method, JsonRawValue.class)
        || annotations.has(method, JsonAnyGetter.class)) {
      throw new ForyJsonException(
          "Conflicting JSON annotations on @JsonByteArray method " + method);
    }
  }

  private static boolean isRecordConstructor(Class<?> type, Constructor<?> constructor) {
    RecordComponent[] components = RecordUtils.getRecordComponents(type);
    Class<?>[] parameterTypes = constructor.getParameterTypes();
    if (components.length != parameterTypes.length) {
      return false;
    }
    for (int i = 0; i < components.length; i++) {
      if (components[i].getType() != parameterTypes[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean isPropagatedRecordAnnotation(
      Class<?> type,
      Method method,
      Class<? extends Annotation> annotationType,
      GeneratedJsonCodec<?> generatedCodec,
      Annotations annotations) {
    if (!isRecordAccessor(type, method, generatedCodec)) {
      return false;
    }
    try {
      return annotations.has(type.getDeclaredField(method.getName()), annotationType);
    } catch (NoSuchFieldException e) {
      return false;
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException(
          "Cannot read record-component @" + annotationType.getSimpleName() + " for " + method, e);
    }
  }

  private static boolean isPropagatedRecordUnwrapped(
      Class<?> type, Method method, GeneratedJsonCodec<?> generatedCodec, Annotations annotations) {
    if (!isRecordAccessor(type, method, generatedCodec)) {
      return false;
    }
    try {
      JsonUnwrapped fieldAnnotation =
          annotations.get(type.getDeclaredField(method.getName()), JsonUnwrapped.class);
      JsonUnwrapped methodAnnotation = annotations.get(method, JsonUnwrapped.class);
      return sameUnwrapped(fieldAnnotation, methodAnnotation);
    } catch (NoSuchFieldException e) {
      return false;
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException("Cannot read record-component @JsonUnwrapped for " + method, e);
    }
  }

  private static boolean isPropagatedRecordUnwrapped(
      Class<?> type,
      Constructor<?> constructor,
      int parameterIndex,
      JsonUnwrapped annotation,
      Annotations annotations) {
    if (!isRecordConstructor(type, constructor)) {
      return false;
    }
    RecordComponent[] components = RecordUtils.getRecordComponents(type);
    if (parameterIndex >= components.length) {
      return false;
    }
    try {
      JsonUnwrapped fieldAnnotation =
          annotations.get(
              type.getDeclaredField(components[parameterIndex].getName()), JsonUnwrapped.class);
      return sameUnwrapped(annotation, fieldAnnotation);
    } catch (NoSuchFieldException e) {
      return false;
    }
  }

  private static boolean sameUnwrapped(JsonUnwrapped left, JsonUnwrapped right) {
    return left != null
        && right != null
        && left.prefix().equals(right.prefix())
        && left.suffix().equals(right.suffix());
  }

  private static boolean isOverridden(Class<?> type, Method method) {
    int modifiers = method.getModifiers();
    if (method.getDeclaringClass() == type
        || !Modifier.isPublic(modifiers)
        || Modifier.isStatic(modifiers)) {
      return false;
    }
    try {
      return !method.equals(type.getMethod(method.getName(), method.getParameterTypes()));
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  private static void validateAnyGetter(Method method) {
    int modifiers = method.getModifiers();
    if (!Modifier.isPublic(modifiers)
        || Modifier.isStatic(modifiers)
        || method.isSynthetic()
        || method.isBridge()
        || method.isVarArgs()
        || method.getTypeParameters().length != 0
        || method.getParameterCount() != 0
        || !Map.class.isAssignableFrom(method.getReturnType())) {
      throw new ForyJsonException("Invalid @JsonAnyGetter method " + method);
    }
  }

  private static void validateAnySetter(Method method, Annotations annotations) {
    int modifiers = method.getModifiers();
    if (!Modifier.isPublic(modifiers)
        || Modifier.isStatic(modifiers)
        || method.isSynthetic()
        || method.isBridge()
        || method.isVarArgs()
        || method.getTypeParameters().length != 0
        || method.getReturnType() != void.class
        || method.getParameterCount() != 2
        || method.getParameterTypes()[0] != String.class
        || annotations.has(method, JsonProperty.class)) {
      throw new ForyJsonException("Invalid @JsonAnySetter method " + method);
    }
  }

  private static void validatePropertyMethod(
      Class<?> type,
      Method method,
      boolean propertyDiscoveryEnabled,
      boolean record,
      GeneratedJsonCodec<?> generatedCodec) {
    if (!propertyDiscoveryEnabled
        || !isEligibleAccessor(method)
        || record && !isRecordAccessor(type, method, generatedCodec)) {
      throw new ForyJsonException("@JsonProperty is not supported on JSON method: " + method);
    }
    if (!record && getterPropertyName(method) == null && setterPropertyName(method) == null) {
      throw new ForyJsonException("@JsonProperty requires a JSON getter or setter: " + method);
    }
  }

  private static boolean isRecordAccessor(
      Class<?> type, Method method, GeneratedJsonCodec<?> generatedCodec) {
    if (generatedCodec != null) {
      String[] names = generatedCodec.validatedCreatorParameterNames();
      Class<?>[] types = generatedCodec.validatedCreatorParameterTypes();
      for (int i = 0; i < names.length; i++) {
        if (method.getName().equals(names[i])
            && method.getParameterCount() == 0
            && method.getReturnType() == types[i]) {
          return true;
        }
      }
      return false;
    }
    RecordComponent[] components = RecordUtils.getRecordComponents(type);
    for (RecordComponent component : components) {
      if (component.getAccessor().equals(method)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isEligibleField(Field field) {
    int modifiers = field.getModifiers();
    return !Modifier.isStatic(modifiers)
        && !Modifier.isTransient(modifiers)
        && field.getType() != Class.class
        && !field.isSynthetic();
  }

  private static boolean isEligibleAccessor(Method method) {
    int modifiers = method.getModifiers();
    return Modifier.isPublic(modifiers)
        && !Modifier.isStatic(modifiers)
        && !method.isSynthetic()
        && !method.isBridge();
  }

  private static String getterPropertyName(Method method) {
    if (method.getParameterCount() != 0
        || method.getReturnType() == void.class
        || method.getReturnType() == Class.class) {
      return null;
    }
    String name = method.getName();
    if (name.equals("getClass")) {
      return null;
    }
    if (name.length() > 3 && name.startsWith("get")) {
      return decapitalize(name.substring(3));
    }
    if (name.length() > 2
        && name.startsWith("is")
        && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
      return decapitalize(name.substring(2));
    }
    return null;
  }

  private static String setterPropertyName(Method method) {
    if (method.getParameterCount() != 1
        || method.getReturnType() != void.class
        || method.getParameterTypes()[0] == Class.class) {
      return null;
    }
    String name = method.getName();
    if (name.length() > 3 && name.startsWith("set")) {
      return decapitalize(name.substring(3));
    }
    return null;
  }

  private static String decapitalize(String name) {
    if (name.length() > 1
        && Character.isUpperCase(name.charAt(0))
        && Character.isUpperCase(name.charAt(1))) {
      return name;
    }
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  private static AnyInfo buildAnyInfo(
      TypeRef<?> ownerType,
      FieldBuilder builder,
      Method anySetter,
      int writeIndex,
      int constructionIndex,
      GeneratedJsonCodec<?> generatedCodec,
      Annotations annotations) {
    Field anyField = builder == null ? null : builder.anyField;
    Method writeGetter = builder == null || !builder.anyWriteEnabled() ? null : builder.writeGetter;
    Field writeField =
        writeGetter == null && anyField != null && builder.anyWriteEnabled() ? anyField : null;
    Field readField = anyField != null && builder.anyReadEnabled() ? anyField : null;
    Type mapType = null;
    Class<?> mapRawType = null;
    Type valueType = null;
    Class<?> valueRawType = null;
    Class<? extends JsonValueCodec<?>> valueCodecClass = null;
    JsonCodec valueCodecAnnotation = null;
    if (anyField != null || writeGetter != null) {
      Type declaredMapType =
          writeGetter == null ? anyField.getGenericType() : writeGetter.getGenericReturnType();
      mapType = ownerType.resolveType(declaredMapType).getType();
      mapRawType = CodecUtils.rawType(mapType, null);
      valueType =
          anyMapValueType(mapType, mapRawType, writeGetter == null ? anyField : writeGetter);
      valueRawType = CodecUtils.rawType(valueType, Object.class);
      validateAnyLogicalTypes(ownerType, builder, mapType);
      if (builder.valueCodecClass() != null) {
        throw new ForyJsonException(
            "A complete-value codec cannot configure JSON Any property " + builder.name);
      }
      JsonCodec annotation = builder.codecAnnotation();
      if (annotation != null) {
        valueCodecClass = anyValueCodec(annotation, "JSON Any property " + builder.name);
      }
    }
    if (anySetter != null) {
      Type setterType = ownerType.resolveType(anySetter.getGenericParameterTypes()[1]).getType();
      Class<?> setterRawType = CodecUtils.rawType(setterType, Object.class);
      if (valueType != null && !boxedType(valueType).equals(boxedType(setterType))) {
        throw new ForyJsonException(
            "Conflicting JSON Any value types "
                + valueType
                + " and "
                + setterType
                + " on "
                + ownerType.getRawType().getName());
      }
      if (valueType == null) {
        valueType = setterType;
        valueRawType = setterRawType;
      }
      if (annotations.has(anySetter.getParameters()[0], JsonCodec.class)) {
        throw new ForyJsonException("@JsonCodec is not supported on a JSON Any setter key");
      }
      valueCodecAnnotation = annotations.get(anySetter.getParameters()[1], JsonCodec.class);
      if (valueCodecClass != null && valueCodecAnnotation != null) {
        if (!isCompleteValueCodec(valueCodecAnnotation)
            || valueCodecAnnotation.value() != valueCodecClass) {
          throw new ForyJsonException(
              "Conflicting @JsonCodec declarations for JSON Any value on " + ownerType);
        }
        valueCodecClass = null;
      }
    }
    JsonAnySetterAccessor generatedAnySetter =
        anySetter == null || generatedCodec == null ? null : generatedCodec.anySetter(anySetter);
    return new AnyInfo(
        writeField,
        writeGetter,
        readField,
        anySetter,
        writeGetter != null
            ? getterAccessor(generatedCodec, writeGetter)
            : writeField == null ? null : fieldAccessor(generatedCodec, writeField),
        readField == null || constructionIndex >= 0
            ? null
            : fieldAccessor(generatedCodec, readField),
        generatedAnySetter,
        mapType,
        mapRawType,
        valueType,
        valueRawType,
        valueCodecAnnotation,
        valueCodecClass,
        writeIndex,
        constructionIndex);
  }

  private static JsonFieldAccessor generatedAccessor(
      GeneratedJsonCodec<?> generatedCodec, Member member) {
    return generatedCodec == null ? null : generatedCodec.validatedAccessor(member);
  }

  private static JsonFieldAccessor fieldAccessor(
      GeneratedJsonCodec<?> generatedCodec, Field field) {
    JsonFieldAccessor accessor = generatedAccessor(generatedCodec, field);
    return accessor == null ? JsonFieldAccessor.forField(field) : accessor;
  }

  private static JsonFieldAccessor getterAccessor(
      GeneratedJsonCodec<?> generatedCodec, Method getter) {
    JsonFieldAccessor accessor = generatedAccessor(generatedCodec, getter);
    return accessor == null ? JsonFieldAccessor.forGetter(getter) : accessor;
  }

  private static JsonFieldAccessor setterAccessor(
      GeneratedJsonCodec<?> generatedCodec, Method setter) {
    JsonFieldAccessor accessor = generatedAccessor(generatedCodec, setter);
    return accessor == null ? JsonFieldAccessor.forSetter(setter) : accessor;
  }

  private static Class<? extends JsonValueCodec<?>> anyValueCodec(
      JsonCodec annotation, String source) {
    if (annotation.value() != JsonCodec.NoJsonValueCodec.class
        || annotation.elementCodec() != JsonCodec.NoJsonValueCodec.class
        || annotation.contentCodec() != JsonCodec.NoJsonValueCodec.class
        || annotation.keyCodec() != JsonCodec.NoMapKeyCodec.class
        || annotation.valueCodec() == JsonCodec.NoJsonValueCodec.class) {
      throw new ForyJsonException(source + " supports only @JsonCodec.valueCodec");
    }
    return annotation.valueCodec();
  }

  private static boolean isCompleteValueCodec(JsonCodec annotation) {
    return annotation.value() != JsonCodec.NoJsonValueCodec.class
        && annotation.elementCodec() == JsonCodec.NoJsonValueCodec.class
        && annotation.contentCodec() == JsonCodec.NoJsonValueCodec.class
        && annotation.keyCodec() == JsonCodec.NoMapKeyCodec.class
        && annotation.valueCodec() == JsonCodec.NoJsonValueCodec.class;
  }

  private static Type anyMapValueType(Type mapType, Class<?> mapRawType, AnnotatedElement source) {
    if (mapRawType == null || !Map.class.isAssignableFrom(mapRawType)) {
      throw new ForyJsonException("JSON Any accessor must use Map<String, V>: " + source);
    }
    Tuple2<TypeRef<?>, TypeRef<?>> types = CodecUtils.mapKeyValueTypeRefs(TypeRef.of(mapType));
    if (!types.f0.getType().equals(String.class)) {
      throw new ForyJsonException("JSON Any map key must be String: " + source);
    }
    return types.f1.getType();
  }

  private static void validateAnyLogicalTypes(
      TypeRef<?> ownerType, FieldBuilder builder, Type anyMapType) {
    if (builder.field != null) {
      validateAnyLogicalType(ownerType, builder.field.getGenericType(), anyMapType, builder.field);
    }
    if (builder.writeGetter != null) {
      validateAnyLogicalType(
          ownerType, builder.writeGetter.getGenericReturnType(), anyMapType, builder.writeGetter);
    }
    if (builder.ordinaryWriteGetter != null) {
      validateAnyLogicalType(
          ownerType,
          builder.ordinaryWriteGetter.getGenericReturnType(),
          anyMapType,
          builder.ordinaryWriteGetter);
    }
    if (builder.readSetter != null) {
      validateAnyLogicalType(
          ownerType,
          builder.readSetter.getGenericParameterTypes()[0],
          anyMapType,
          builder.readSetter);
    }
  }

  private static void validateAnyLogicalType(
      TypeRef<?> ownerType, Type declaredType, Type anyMapType, AnnotatedElement source) {
    Type resolved = ownerType.resolveType(declaredType).getType();
    if (!resolved.equals(anyMapType)) {
      throw new ForyJsonException(
          "Conflicting JSON Any logical property type "
              + resolved
              + " from "
              + source
              + "; expected "
              + anyMapType);
    }
  }

  private static Type boxedType(Type type) {
    if (!(type instanceof Class) || !((Class<?>) type).isPrimitive()) {
      return type;
    }
    Class<?> rawType = (Class<?>) type;
    if (rawType == boolean.class) {
      return Boolean.class;
    }
    if (rawType == byte.class) {
      return Byte.class;
    }
    if (rawType == short.class) {
      return Short.class;
    }
    if (rawType == int.class) {
      return Integer.class;
    }
    if (rawType == long.class) {
      return Long.class;
    }
    if (rawType == float.class) {
      return Float.class;
    }
    if (rawType == double.class) {
      return Double.class;
    }
    return Character.class;
  }

  /** Target-bound cold annotation view; never retained by published codec metadata. */
  private static final class Annotations {
    private final Class<?> targetType;
    private final JsonSharedRegistry registry;

    private Annotations(Class<?> targetType, JsonSharedRegistry registry) {
      this.targetType = targetType;
      this.registry = registry;
    }

    private <A extends Annotation> A get(AnnotatedElement element, Class<A> annotationType) {
      return registry.annotation(targetType, element, annotationType);
    }

    private boolean has(AnnotatedElement element, Class<? extends Annotation> annotationType) {
      return get(element, annotationType) != null;
    }
  }

  private static final class FieldBuilder {
    private final String name;
    private final Annotations annotations;
    private Field field;
    private boolean fieldWriteAllowed;
    private boolean fieldReadAllowed;
    private Field writeField;
    private Field readField;
    private Method writeGetter;
    private Method ordinaryWriteGetter;
    private Method readSetter;
    private Field anyField;
    private Method anyGetter;
    private JsonFieldAccessor writeAccessor;
    private JsonFieldAccessor readAccessor;
    private String explicitName;
    private AnnotatedElement explicitNameSource;
    private int explicitIndex = JsonProperty.INDEX_UNKNOWN;
    private AnnotatedElement explicitIndexSource;
    private JsonProperty.Include explicitInclude = JsonProperty.Include.DEFAULT;
    private AnnotatedElement explicitIncludeSource;
    private AnnotatedElement rawValueSource;
    private boolean hasJsonProperty;
    private int creatorArgumentIndex = -1;
    private boolean creatorUnboxedRequired;
    private JsonCodec codecAnnotation;
    private Class<? extends JsonValueCodec<?>> valueCodecClass;
    private JsonFormat formatAnnotation;
    private AnnotatedElement formatSource;
    private AnnotatedElement codecSource;
    private JsonUnwrapped unwrappedAnnotation;
    private AnnotatedElement unwrappedSource;
    private boolean ignoreRead;
    private boolean ignoreWrite;
    private TypeRef<?> objectModelType;
    private boolean objectModelReconstructible = true;
    private boolean requiredDeferred;

    private FieldBuilder(String name, Annotations annotations) {
      this.name = name;
      this.annotations = annotations;
    }

    private void setField(
        Class<?> type,
        Field field,
        boolean writeSource,
        boolean readSink,
        boolean writeAllowed,
        boolean readAllowed) {
      if (this.field != null) {
        throw new ForyJsonException("Duplicate JSON field " + name);
      }
      this.field = field;
      mergeIgnore(field);
      fieldWriteAllowed = writeAllowed && !ignoreWrite;
      fieldReadAllowed = readAllowed && !ignoreRead;
      if (writeSource && !ignoreWrite) {
        writeField = field;
      }
      if (readSink && !ignoreRead) {
        readField = field;
      }
      mergeFormat(field);
      mergeAnnotation(type, field);
      if (annotations.has(field, JsonAnyProperty.class)) {
        if (!writeSource && !readSink) {
          throw new ForyJsonException("@JsonAnyProperty must enable reading or writing: " + field);
        }
        anyField = field;
      }
    }

    private void setWriteGetter(Class<?> type, Method getter) {
      // A language object model installs the exact source getter before ordinary bean discovery.
      // Seeing that same Method again is one declaration, not a competing accessor.
      if (getter.equals(writeGetter)) {
        return;
      }
      mergeIgnore(getter);
      mergeAnnotation(type, getter);
      if (ignoreWrite || field != null && !fieldWriteAllowed) {
        return;
      }
      if (writeGetter != null) {
        throw new ForyJsonException("Duplicate JSON getter for property " + name);
      }
      writeGetter = getter;
      writeField = null;
    }

    private void setObjectModelType(TypeRef<?> type) {
      if (objectModelType != null && !objectModelType.equals(type)) {
        throw new ForyJsonException("Conflicting JSON object-model types for property " + name);
      }
      objectModelType = type;
    }

    private void setReadSetter(Class<?> type, Method setter) {
      if (setter.equals(readSetter)) {
        return;
      }
      mergeIgnore(setter);
      mergeAnnotation(type, setter);
      Parameter parameter = setter.getParameters()[0];
      mergeIgnore(parameter);
      mergeCodec(parameter);
      mergeUnwrapped(parameter);
      if (ignoreRead || field != null && !fieldReadAllowed) {
        return;
      }
      if (readSetter != null) {
        throw new ForyJsonException("Duplicate JSON setter for property " + name);
      }
      readSetter = setter;
      readField = null;
    }

    private void restrictObjectModelField(boolean constructorProperty) {
      if (writeGetter == null
          && writeField != null
          && !Modifier.isPublic(writeField.getModifiers())) {
        writeField = null;
      }
      if (constructorProperty || !objectModelReconstructible) {
        readField = null;
        return;
      }
      if (readSetter == null
          && readField != null
          && (!Modifier.isPublic(readField.getModifiers())
              || Modifier.isFinal(readField.getModifiers()))) {
        readField = null;
      }
    }

    private void setAnyGetter(Class<?> type, Method getter) {
      mergeAnnotation(type, getter);
      if (field != null && !fieldWriteAllowed) {
        throw new ForyJsonException(
            "@JsonIgnore disables the same-name @JsonAnyGetter on " + getter);
      }
      if (anyGetter != null && !anyGetter.equals(getter)) {
        throw new ForyJsonException("Multiple @JsonAnyGetter methods for property " + name);
      }
      if (writeGetter != null && !writeGetter.equals(getter)) {
        ordinaryWriteGetter = writeGetter;
      }
      anyGetter = getter;
      writeGetter = getter;
      writeField = null;
    }

    private boolean isAny() {
      return anyField != null || anyGetter != null;
    }

    private boolean anyWriteEnabled() {
      return anyGetter != null || anyField != null && fieldWriteAllowed;
    }

    private boolean anyReadEnabled() {
      return anyField != null && fieldReadAllowed;
    }

    private boolean hasWriteSource() {
      return writeGetter != null || writeField != null;
    }

    private boolean hasReadSink() {
      return readSetter != null || readField != null;
    }

    private boolean hasConfiguration() {
      return explicitName != null
          || explicitIndex != JsonProperty.INDEX_UNKNOWN
          || explicitInclude != JsonProperty.Include.DEFAULT
          || codecAnnotation != null
          || rawValueSource != null
          || valueCodecClass != null
          || formatAnnotation != null
          || unwrappedAnnotation != null;
    }

    private boolean hasIndex() {
      return explicitIndex != JsonProperty.INDEX_UNKNOWN;
    }

    private boolean hasLogicalMember() {
      return field != null || writeGetter != null || readSetter != null;
    }

    private boolean creatorReadAllowed() {
      return !ignoreRead && (field == null || fieldReadAllowed);
    }

    private String jsonName(PropertyNamingStrategy strategy) {
      return explicitName == null ? translateName(name, strategy) : explicitName;
    }

    private String nameDescription(PropertyNamingStrategy strategy) {
      return explicitName == null
          ? "Java property " + name + " transformed by " + strategy
          : "Java property " + name + " explicitly named by " + explicitNameSource;
    }

    private TypeRef<?> logicalTypeRef(TypeRef<?> ownerType) {
      if (objectModelType != null) {
        return objectModelType;
      }
      Type type;
      if (writeGetter != null) {
        type = writeGetter.getGenericReturnType();
      } else if (writeField != null) {
        type = writeField.getGenericType();
      } else if (readSetter != null) {
        type = readSetter.getGenericParameterTypes()[0];
      } else if (field != null) {
        // Final fields and ignored ordinary read sinks may still be creator-bound properties.
        type = field.getGenericType();
      } else {
        throw new ForyJsonException("JSON property has no type source " + name);
      }
      return ownerType.resolveType(type);
    }

    private Type logicalType(TypeRef<?> ownerType) {
      return logicalTypeRef(ownerType).getType();
    }

    private JsonFieldInfo build(
        boolean record,
        TypeRef<?> ownerType,
        PropertyNamingStrategy propertyNamingStrategy,
        boolean defaultWriteNull,
        GeneratedJsonCodec<?> generatedCodec) {
      validateTypes(ownerType);
      if (explicitInclude != JsonProperty.Include.DEFAULT && !hasWriteSource()) {
        throw new ForyJsonException(
            "JSON inclusion policy requires a write source for property " + name);
      }
      String jsonName = jsonName(propertyNamingStrategy);
      if (jsonName.isEmpty()) {
        throw new ForyJsonException("JSON property name must not be empty for " + name);
      }
      Class<?> rawWriteType = hasWriteSource() ? writeRawType() : null;
      boolean writeNull =
          rawWriteType != null
              && (rawWriteType.isPrimitive()
                  || explicitInclude == JsonProperty.Include.ALWAYS
                  || explicitInclude == JsonProperty.Include.DEFAULT && defaultWriteNull);
      if (writeGetter != null) {
        writeAccessor = getterAccessor(generatedCodec, writeGetter);
      } else if (writeField != null) {
        writeAccessor = fieldAccessor(generatedCodec, writeField);
      }
      if (readSetter != null) {
        readAccessor = setterAccessor(generatedCodec, readSetter);
      } else if (readField != null && !record) {
        readAccessor = fieldAccessor(generatedCodec, readField);
      }
      boolean rawValue = rawValueSource != null;
      if (rawValue) {
        if (rawWriteType != null && rawWriteType != String.class) {
          throw new ForyJsonException(
              "@JsonRawValue requires an exact String write source for property " + name);
        }
        if (codecAnnotation != null || valueCodecClass != null || formatAnnotation != null) {
          throw new ForyJsonException(
              "@JsonRawValue cannot coexist with a value codec for property " + name);
        }
      }
      return new JsonFieldInfo(
          jsonName,
          writeNull,
          writeField,
          writeGetter,
          readField,
          readSetter,
          writeAccessor,
          readAccessor,
          ownerType,
          objectModelType,
          codecAnnotation,
          valueCodecClass,
          formatAnnotation,
          rawValue);
    }

    private void validateUnwrapped(Class<?> type, JsonCreatorInfo creatorInfo) {
      if (isAny()) {
        throw new ForyJsonException(
            "@JsonUnwrapped cannot share a JSON Any logical property " + name);
      }
      if (explicitName != null) {
        throw new ForyJsonException(
            "@JsonUnwrapped property has no wrapper name for @JsonProperty.value on "
                + type.getName()
                + "."
                + name);
      }
      if (explicitInclude != JsonProperty.Include.DEFAULT) {
        throw new ForyJsonException(
            "@JsonUnwrapped property cannot declare an inclusion policy: "
                + type.getName()
                + "."
                + name);
      }
      if (codecAnnotation != null
          || valueCodecClass != null
          || formatAnnotation != null
          || rawValueSource != null) {
        throw new ForyJsonException(
            "Value representation annotations are not supported on @JsonUnwrapped property "
                + type.getName()
                + "."
                + name);
      }
      if (!hasWriteSource() && !hasReadSink()) {
        throw new ForyJsonException(
            "@JsonUnwrapped property has no JSON read or write direction: "
                + type.getName()
                + "."
                + name);
      }
      if (hasIndex() && !hasWriteSource()) {
        throw new ForyJsonException(
            "@JsonUnwrapped property index requires a write source: "
                + type.getName()
                + "."
                + name);
      }
    }

    private Declaration buildUnwrappedDeclaration(
        TypeRef<?> ownerType,
        JsonFieldInfo property,
        int constructionIndex,
        boolean creatorParent) {
      Type resolvedType = logicalType(ownerType);
      Class<?> fallback =
          property.writeRawType() == null ? property.readRawType() : property.writeRawType();
      Class<?> rawType = CodecUtils.rawType(resolvedType, fallback);
      return new Declaration(
          name,
          unwrappedAnnotation.prefix(),
          unwrappedAnnotation.suffix(),
          resolvedType,
          rawType,
          property.writeAccessor(),
          property.readAccessor(),
          hasWriteSource(),
          creatorParent ? constructionIndex >= 0 : hasReadSink(),
          constructionIndex);
    }

    private JsonCodec codecAnnotation() {
      return codecAnnotation;
    }

    private Class<? extends JsonValueCodec<?>> valueCodecClass() {
      return valueCodecClass;
    }

    private JsonFormat formatAnnotation() {
      return formatAnnotation;
    }

    private void mergeAnnotation(Class<?> type, AnnotatedElement source) {
      mergeCodec(source);
      if (annotations.has(source, JsonRawValue.class)) {
        if (formatAnnotation != null) {
          throw formatConflict(source, "@JsonRawValue");
        }
        if (rawValueSource == null) {
          rawValueSource = source;
        }
      }
      mergeUnwrapped(source);
      JsonProperty property = annotations.get(source, JsonProperty.class);
      if (property == null) {
        return;
      }
      hasJsonProperty = true;
      int declaredIndex = property.index();
      validatePropertyIndex(declaredIndex, name, type, source);
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
      String declaredName = property.value();
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
      JsonProperty.Include declaredInclude = property.include();
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

    private void mergeCreatorParameter(Class<?> type, Parameter parameter) {
      mergeIgnore(parameter);
      mergeCodec(parameter);
      mergeUnwrapped(parameter);
      JsonProperty property = annotations.get(parameter, JsonProperty.class);
      if (unwrappedAnnotation == null) {
        mergeAnnotation(type, parameter);
        return;
      }
      if (property == null) {
        return;
      }
      hasJsonProperty = true;
      int declaredIndex = property.index();
      validatePropertyIndex(declaredIndex, name, type, parameter);
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
                  + parameter);
        }
        explicitIndex = declaredIndex;
        if (explicitIndexSource == null) {
          explicitIndexSource = parameter;
        }
      }
      if (property.include() != JsonProperty.Include.DEFAULT) {
        throw new ForyJsonException(
            "@JsonUnwrapped property cannot declare an inclusion policy: " + name);
      }
    }

    private void mergeIgnore(AnnotatedElement source) {
      JsonIgnore ignore = annotations.get(source, JsonIgnore.class);
      if (ignore == null) {
        return;
      }
      if (ignore.ignoreWrite() && !ignoreWrite) {
        ignoreWrite = true;
        writeField = null;
        writeGetter = null;
      }
      if (ignore.ignoreRead() && !ignoreRead) {
        ignoreRead = true;
        readField = null;
        readSetter = null;
      }
    }

    private void mergeUnwrapped(AnnotatedElement source) {
      JsonUnwrapped declared = annotations.get(source, JsonUnwrapped.class);
      if (declared == null) {
        return;
      }
      if (formatAnnotation != null) {
        throw formatConflict(source, "@JsonUnwrapped");
      }
      if (unwrappedAnnotation != null
          && (!unwrappedAnnotation.prefix().equals(declared.prefix())
              || !unwrappedAnnotation.suffix().equals(declared.suffix()))) {
        throw new ForyJsonException(
            "Conflicting @JsonUnwrapped declarations for property "
                + name
                + " from "
                + unwrappedSource
                + " and "
                + source);
      }
      if (unwrappedAnnotation == null) {
        unwrappedAnnotation = declared;
        unwrappedSource = source;
      }
    }

    private void mergeCodec(AnnotatedElement source) {
      JsonCodec declared = annotations.get(source, JsonCodec.class);
      JsonByteArray byteArray = annotations.get(source, JsonByteArray.class);
      if (byteArray != null) {
        if (formatAnnotation != null) {
          throw formatConflict(source, "@JsonByteArray");
        }
        if (declared != null || codecAnnotation != null) {
          throw new ForyJsonException(
              "@JsonByteArray cannot coexist with @JsonCodec for property " + name);
        }
        Class<? extends JsonValueCodec<?>> codecClass =
            byteArray.value() == JsonByteArray.Format.ARRAY
                ? ArrayCodec.SignedByteArrayCodec.class
                : Base64ByteArrayCodec.class;
        if (valueCodecClass != null && valueCodecClass != codecClass) {
          throw new ForyJsonException(
              "Conflicting @JsonByteArray declarations for property " + name);
        }
        valueCodecClass = codecClass;
        codecSource = source;
        return;
      }
      if (declared != null && formatAnnotation != null) {
        throw formatConflict(source, "@JsonCodec");
      }
      if (declared != null && valueCodecClass != null) {
        throw new ForyJsonException(
            "@JsonByteArray cannot coexist with @JsonCodec for property " + name);
      }
      if (declared == null) {
        return;
      }
      if (codecAnnotation != null && !codecAnnotation.equals(declared)) {
        throw new ForyJsonException(
            "Conflicting @JsonCodec declarations for property "
                + name
                + " from "
                + codecSource
                + " and "
                + source);
      }
      if (codecAnnotation == null) {
        codecAnnotation = declared;
        codecSource = source;
      }
    }

    private void mergeFormat(AnnotatedElement source) {
      JsonFormat declared = annotations.get(source, JsonFormat.class);
      if (declared == null) {
        return;
      }
      if (codecAnnotation != null || valueCodecClass != null) {
        throw formatConflict(source, "a value codec");
      }
      if (rawValueSource != null) {
        throw formatConflict(source, "@JsonRawValue");
      }
      if (unwrappedAnnotation != null) {
        throw formatConflict(source, "@JsonUnwrapped");
      }
      if (formatAnnotation != null && !formatAnnotation.equals(declared)) {
        throw new ForyJsonException(
            "Conflicting @JsonFormat declarations for property "
                + name
                + " from "
                + formatSource
                + " and "
                + source);
      }
      if (formatAnnotation == null) {
        formatAnnotation = declared;
        formatSource = source;
      }
    }

    private ForyJsonException formatConflict(AnnotatedElement source, String annotation) {
      return new ForyJsonException(
          "@JsonFormat cannot coexist with "
              + annotation
              + " for property "
              + name
              + " from "
              + formatSource
              + " and "
              + source);
    }

    private void validateTypes(TypeRef<?> ownerType) {
      Type writeType =
          writeGetter == null ? fieldType(writeField) : writeGetter.getGenericReturnType();
      Type readType =
          readSetter == null ? fieldType(readField) : readSetter.getGenericParameterTypes()[0];
      if (writeType != null) {
        writeType = ownerType.resolveType(writeType).getType();
      }
      if (readType != null) {
        readType = ownerType.resolveType(readType).getType();
      }
      if (objectModelType != null) {
        Type modelType = objectModelType.getType();
        if (writeType == void.class) {
          writeType = modelType;
        }
        boolean writeMismatch =
            writeType != null
                && !JsonObjectModel.compatibleType(
                    ownerType.resolveType(writeType), objectModelType)
                && !UnboxedValueCodec.requiresCarrier(writeRawType(), objectModelType);
        Class<?> readRawType =
            readSetter != null
                ? readSetter.getParameterTypes()[0]
                : readField == null ? null : readField.getType();
        boolean readMismatch =
            readType != null
                && !JsonObjectModel.compatibleType(ownerType.resolveType(readType), objectModelType)
                && !UnboxedValueCodec.requiresCarrier(readRawType, objectModelType);
        if (writeMismatch || readMismatch) {
          throw new ForyJsonException(
              "JSON object-model type " + modelType + " does not match property " + name);
        }
        return;
      }
      if (writeType != null && readType != null && !writeType.equals(readType)) {
        throw new ForyJsonException(
            "Conflicting JSON property types for " + name + ": " + writeType + " and " + readType);
      }
    }

    private static Type fieldType(Field field) {
      return field == null ? null : field.getGenericType();
    }

    private Class<?> writeRawType() {
      return writeGetter != null ? writeGetter.getReturnType() : writeField.getType();
    }
  }

  private static String translateName(String name, PropertyNamingStrategy strategy) {
    if (strategy == PropertyNamingStrategy.LOWER_CAMEL_CASE) {
      return name;
    }
    StringBuilder builder = new StringBuilder(name.length() + 4);
    int previous = -1;
    boolean previousUpper = false;
    for (int offset = 0; offset < name.length(); ) {
      int codePoint = name.codePointAt(offset);
      int width = Character.charCount(codePoint);
      int nextOffset = offset + width;
      int next = nextOffset < name.length() ? name.codePointAt(nextOffset) : -1;
      boolean upper = Character.isUpperCase(codePoint) || Character.isTitleCase(codePoint);
      boolean previousLower = previous >= 0 && Character.isLowerCase(previous);
      boolean previousDigit = previous >= 0 && Character.isDigit(previous);
      boolean nextLower = next >= 0 && Character.isLowerCase(next);
      if (upper && (previousLower || previousDigit || previousUpper && nextLower)) {
        builder.append('_');
      }
      builder.appendCodePoint(Character.toLowerCase(codePoint));
      if (!Character.isLetterOrDigit(codePoint)) {
        previous = -1;
        previousUpper = false;
      } else {
        previous = codePoint;
        previousUpper = upper;
      }
      offset = nextOffset;
    }
    return builder.toString();
  }
}
