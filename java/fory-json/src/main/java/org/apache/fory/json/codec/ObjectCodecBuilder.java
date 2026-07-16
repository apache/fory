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
import org.apache.fory.json.annotation.JsonBase64;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.annotation.JsonRawValue;
import org.apache.fory.json.annotation.JsonUnwrapped;
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
      GeneratedJsonCodec<?> generatedCodec) {
    Class<?> type = ownerType.getRawType();
    boolean record =
        generatedCodec == null ? RecordUtils.isRecord(type) : generatedCodec.validatedRecord();
    boolean hasAnyField =
        validateMemberAnnotations(type, propertyDiscoveryEnabled, record, generatedCodec);
    LinkedHashMap<String, FieldBuilder> builders = new LinkedHashMap<>();
    addFields(type, record, propertyDiscoveryEnabled, hasAnyField, builders);
    if (record) {
      addRecordAccessors(type, builders, generatedCodec);
    }
    Method anySetter =
        addJsonMethods(type, propertyDiscoveryEnabled, record, builders, generatedCodec);
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
    List<Declaration> creatorOnlyUnwrapped = new ArrayList<>();
    JsonCreatorInfo creatorInfo =
        record
            ? buildRecordCreatorInfo(
                type, ownerType, builders, propertyNamingStrategy, generatedCodec)
            : buildCreatorInfo(
                type,
                ownerType,
                builders,
                propertyNamingStrategy,
                creatorOnlyUnwrapped,
                generatedCodec);
    if (anySetter != null && (record || creatorInfo != null)) {
      throw new ForyJsonException(
          "@JsonAnySetter is not supported on constructor-backed type " + type.getName());
    }
    if (creatorInfo != null
        && anyBuilder != null
        && anyBuilder.anyReadEnabled()
        && anyBuilder.creatorArgumentIndex < 0) {
      throw new ForyJsonException(
          "Read-enabled @JsonAnyProperty must bind one @JsonCreator argument on " + type.getName());
    }
    JsonPropertyOrder propertyOrder = findPropertyOrder(type);
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
    List<String> skippedNames = hasAny ? new ArrayList<>() : null;
    Map<String, FieldBuilder> canonicalNames = new LinkedHashMap<>();
    Map<Long, String> canonicalHashes = new LinkedHashMap<>();
    int anyOriginalIndex = -1;
    for (FieldBuilder builder : builders.values()) {
      if (builder == anyBuilder) {
        if (anyWrites) {
          anyOriginalIndex = writes.size();
          if (hasUnwrapped) {
            unwrappedWrites.add(UnwrappedWriteBuilder.any(builder));
          }
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
        boolean readableFixed = creatorInfo == null && builder.hasReadSink();
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
      if (!builder.hasWriteSource() && !builder.hasReadSink()) {
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
        int unwrappedConstructionIndex = -1;
        if (creatorInfo != null && builder.creatorArgumentIndex >= 0) {
          unwrappedConstructionIndex = builder.creatorArgumentIndex;
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
      if (creatorInfo != null && !builder.hasWriteSource()) {
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
      if (creatorInfo == null && builder.hasReadSink()) {
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
    for (int i = 0; i < readArray.length; i++) {
      readArray[i].setReadIndex(i);
    }
    int constructionIndex = -1;
    if (anyBuilder != null && anyBuilder.anyReadEnabled()) {
      if (creatorInfo != null) {
        constructionIndex = anyBuilder.creatorArgumentIndex;
      }
    }
    AnyInfo anyInfo =
        hasAny
            ? buildAnyInfo(
                ownerType, anyBuilder, anySetter, anyWriteIndex, constructionIndex, generatedCodec)
            : null;
    ObjectInstantiator<?> instantiator =
        creatorInfo == null ? ObjectInstantiators.createObjectInstantiator(type) : null;
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
        instantiator);
  }

  private static boolean hasUnwrappedProperty(Map<String, FieldBuilder> builders) {
    for (FieldBuilder builder : builders.values()) {
      if (builder.unwrappedAnnotation != null) {
        return true;
      }
    }
    return false;
  }

  private static JsonPropertyOrder findPropertyOrder(Class<?> type) {
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      JsonPropertyOrder order = current.getDeclaredAnnotation(JsonPropertyOrder.class);
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
      LinkedHashMap<String, FieldBuilder> builders) {
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
        if (field.isAnnotationPresent(JsonUnwrapped.class) && !isEligibleField(field)) {
          throw new ForyJsonException("@JsonUnwrapped is not supported on JSON field: " + field);
        }
        int modifiers = field.getModifiers();
        if (!isEligibleField(field)) {
          continue;
        }
        JsonIgnore ignore = field.getAnnotation(JsonIgnore.class);
        boolean write = ignore == null || !ignore.ignoreWrite();
        boolean readAllowed = ignore == null || !ignore.ignoreRead();
        boolean any = field.isAnnotationPresent(JsonAnyProperty.class);
        boolean read = (any || record || !Modifier.isFinal(modifiers)) && readAllowed;
        if (!retainIgnoredFields && !write && !read && !any) {
          continue;
        }
        FieldBuilder builder = builders.computeIfAbsent(field.getName(), FieldBuilder::new);
        builder.setField(type, field, write, read, write, readAllowed);
      }
    }
  }

  private static Method addJsonMethods(
      Class<?> type,
      boolean propertyDiscoveryEnabled,
      boolean record,
      LinkedHashMap<String, FieldBuilder> builders,
      GeneratedJsonCodec<?> generatedCodec) {
    Method anyGetter = null;
    Method anySetter = null;
    for (Method method : type.getMethods()) {
      // javac copies runtime annotations to generic bridge methods. Those generated methods do not
      // own JSON declarations and processing them would reject an otherwise valid concrete method.
      if (method.isSynthetic() || method.isBridge()) {
        continue;
      }
      if (method.getDeclaringClass().isInterface()
          && method.isAnnotationPresent(JsonProperty.class)) {
        validatePropertyMethod(type, method, propertyDiscoveryEnabled, record, generatedCodec);
      }
      JsonAnyGetter getter = method.getAnnotation(JsonAnyGetter.class);
      JsonAnySetter setter = method.getAnnotation(JsonAnySetter.class);
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
          validateAnySetter(method);
          if (anySetter != null) {
            throw new ForyJsonException("Multiple @JsonAnySetter methods on " + type.getName());
          }
          anySetter = method;
        }
      }
      if (!propertyDiscoveryEnabled || record || !isEligibleAccessor(method)) {
        continue;
      }
      String propertyName = getterPropertyName(method);
      if (propertyName != null) {
        FieldBuilder builder = builders.get(propertyName);
        if (builder == null) {
          builder = new FieldBuilder(propertyName);
          builders.put(propertyName, builder);
        }
        builder.setWriteGetter(type, method);
        continue;
      }
      propertyName = setterPropertyName(method);
      if (propertyName != null) {
        FieldBuilder builder = builders.get(propertyName);
        if (builder == null) {
          builder = new FieldBuilder(propertyName);
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
        builder = new FieldBuilder(propertyName);
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
        || method.isAnnotationPresent(JsonBase64.class)) {
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
        || method.isAnnotationPresent(JsonBase64.class)
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

  private static void rejectRecordCreator(Class<?> type) {
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (constructor.isAnnotationPresent(JsonCreator.class)) {
        throw new ForyJsonException("@JsonCreator is not supported on record " + type.getName());
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      if (method.isAnnotationPresent(JsonCreator.class)) {
        throw new ForyJsonException("@JsonCreator is not supported on record " + type.getName());
      }
    }
  }

  private static JsonCreatorInfo buildRecordCreatorInfo(
      Class<?> type,
      TypeRef<?> ownerType,
      LinkedHashMap<String, FieldBuilder> builders,
      PropertyNamingStrategy namingStrategy,
      GeneratedJsonCodec<?> generatedCodec) {
    rejectRecordCreator(type);
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
      Type resolved =
          parameterTypes == null
              ? builder.logicalType(ownerType)
              : ownerType.resolveType(parameterTypes[i]).getType();
      fields.add(
          new JsonCreatorFieldInfo(
              builder.jsonName(namingStrategy),
              i,
              resolved,
              rawTypes[i],
              builder.codecAnnotation(),
              builder.valueCodecClass()));
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
      GeneratedJsonCodec<?> generatedCodec) {
    JsonCreatorDeclaration declaration = JsonCreatorDeclaration.find(type);
    if (declaration == null) {
      if (generatedCodec != null && generatedCodec.validatedCreatorParameterNames() != null) {
        throw new ForyJsonException(
            "Generated JSON creator does not match runtime annotations on " + type.getName());
      }
      return null;
    }
    Executable creator = declaration.executable();
    JsonCreator annotation = declaration.annotation();
    validateGeneratedCreator(type, creator, annotation, generatedCodec);

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
        if (parameters[i].isAnnotationPresent(JsonProperty.class)) {
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
          Type resolved = ownerType.resolveType(parameterTypes[i]).getType();
          JsonCodec codecAnnotation = builder.codecAnnotation();
          Class<? extends JsonValueCodec<?>> valueCodecClass = builder.valueCodecClass();
          fields.add(
              new JsonCreatorFieldInfo(
                  builder.jsonName(namingStrategy),
                  i,
                  resolved,
                  rawTypes[i],
                  codecAnnotation,
                  valueCodecClass));
        }
      }
    } else {
      Set<String> names = new HashSet<>();
      for (int i = 0; i < parameters.length; i++) {
        JsonProperty property = parameters[i].getAnnotation(JsonProperty.class);
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
        Type resolved = ownerType.resolveType(parameterTypes[i]).getType();
        JsonCodec codecAnnotation =
            builder == null
                ? parameters[i].getAnnotation(JsonCodec.class)
                : builder.codecAnnotation();
        Class<? extends JsonValueCodec<?>> valueCodecClass =
            builder == null ? null : builder.valueCodecClass();
        JsonUnwrapped unwrapped =
            builder == null
                ? parameters[i].getAnnotation(JsonUnwrapped.class)
                : builder.unwrappedAnnotation;
        if (unwrapped != null) {
          if (codecAnnotation != null || valueCodecClass != null) {
            throw new ForyJsonException(
                "Value codecs are not supported on @JsonUnwrapped creator property " + jsonName);
          }
          if (builder == null) {
            creatorOnlyUnwrapped.add(
                new Declaration(
                    jsonName,
                    unwrapped.prefix(),
                    unwrapped.suffix(),
                    resolved,
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
                  jsonName, i, resolved, rawTypes[i], codecAnnotation, valueCodecClass));
        }
      }
    }
    JsonCreatorFieldInfo[] fieldArray = fields.toArray(new JsonCreatorFieldInfo[0]);
    rejectCreatorHashCollisions(fieldArray);
    return new JsonCreatorInfo(
        type, creator, fieldArray, creatorDefaults(rawTypes), generatedCodec);
  }

  private static void validateGeneratedCreator(
      Class<?> type,
      Executable creator,
      JsonCreator annotation,
      GeneratedJsonCodec<?> generatedCodec) {
    if (generatedCodec == null) {
      return;
    }
    String[] names = generatedCodec.validatedCreatorParameterNames();
    Class<?>[] parameterTypes = generatedCodec.validatedCreatorParameterTypes();
    String factoryName = generatedCodec.validatedCreatorFactoryName();
    if (names == null
        || !creator.equals(generatedCodec.validatedCreator())
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
        JsonProperty property = parameters[i].getAnnotation(JsonProperty.class);
        runtimeNames[i] = property == null ? null : property.value();
      }
    }
    if (!Arrays.equals(names, runtimeNames)) {
      throw new ForyJsonException(
          "Generated JSON creator names do not match " + creator + " on " + type.getName());
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
    Type resolvedParameter = ownerType.resolveType(parameterType).getType();
    Type propertyType = builder.logicalType(ownerType);
    if (!resolvedParameter.equals(propertyType)) {
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
      GeneratedJsonCodec<?> generatedCodec) {
    boolean hasAnyField = false;
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        if (field.isAnnotationPresent(JsonBase64.class)) {
          validateBase64Field(field);
        }
        if (field.isAnnotationPresent(JsonRawValue.class)) {
          validateRawField(field);
        }
        if (field.isAnnotationPresent(JsonCodec.class) && !isEligibleField(field)) {
          throw new ForyJsonException("@JsonCodec is not supported on JSON field: " + field);
        }
        JsonIgnore ignore = field.getAnnotation(JsonIgnore.class);
        if (field.isAnnotationPresent(JsonUnwrapped.class)
            && ignore != null
            && ignore.ignoreRead()
            && ignore.ignoreWrite()) {
          throw new ForyJsonException(
              "@JsonUnwrapped has no JSON read or write direction: " + field);
        }
        if (field.isAnnotationPresent(JsonCodec.class)
            && ignore != null
            && ignore.ignoreRead()
            && ignore.ignoreWrite()) {
          throw new ForyJsonException("@JsonCodec has no JSON read or write direction: " + field);
        }
        if (field.isAnnotationPresent(JsonProperty.class) && !isEligibleField(field)) {
          throw new ForyJsonException("@JsonProperty is not supported on JSON field: " + field);
        }
        if (field.isAnnotationPresent(JsonAnyProperty.class)) {
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
        if (method.isAnnotationPresent(JsonCodec.class)) {
          validateCodecMethod(type, method, propertyDiscoveryEnabled, record, generatedCodec);
        }
        validateCodecParameters(method, propertyDiscoveryEnabled, record);
        if (method.isAnnotationPresent(JsonRawValue.class)) {
          validateRawMethod(type, method, propertyDiscoveryEnabled, record, generatedCodec);
        }
        if (method.isAnnotationPresent(JsonBase64.class)) {
          validateBase64Method(type, method, propertyDiscoveryEnabled, record, generatedCodec);
        }
        if (method.isAnnotationPresent(JsonUnwrapped.class)) {
          validateUnwrappedMethod(type, method, propertyDiscoveryEnabled, record, generatedCodec);
        }
        validateUnwrappedParameters(type, method, propertyDiscoveryEnabled, record);
        if (method.isAnnotationPresent(JsonProperty.class)) {
          validatePropertyMethod(type, method, propertyDiscoveryEnabled, record, generatedCodec);
        }
        if (method.isAnnotationPresent(JsonAnyGetter.class)) {
          if (!propertyDiscoveryEnabled) {
            throw new ForyJsonException(
                "JSON Any method annotations require property discovery: " + method);
          }
          validateAnyGetter(method);
        }
        if (method.isAnnotationPresent(JsonAnySetter.class)) {
          if (!propertyDiscoveryEnabled) {
            throw new ForyJsonException(
                "JSON Any method annotations require property discovery: " + method);
          }
          validateAnySetter(method);
        }
      }
    }
    // Generated Record parameter annotations are checked by the source processor against fields
    // and accessors. Do not re-read desugared constructor parameters: Android 8 ART may crash.
    if (!record || generatedCodec == null) {
      for (Constructor<?> constructor : type.getDeclaredConstructors()) {
        validateCodecParameters(type, constructor, record);
        validateUnwrappedParameters(type, constructor, record);
      }
    }
    for (Method method : type.getMethods()) {
      if (!method.getDeclaringClass().isInterface()) {
        continue;
      }
      if (method.isAnnotationPresent(JsonCodec.class)) {
        // getMethods exposes only the effective inherited declaration. A class or child-interface
        // override therefore suppresses an annotation from the overridden interface method.
        validateCodecMethod(type, method, propertyDiscoveryEnabled, record, generatedCodec);
      }
      validateCodecParameters(method, propertyDiscoveryEnabled, record);
      if (method.isAnnotationPresent(JsonRawValue.class)) {
        validateRawMethod(type, method, propertyDiscoveryEnabled, record, generatedCodec);
      }
      if (method.isAnnotationPresent(JsonBase64.class)) {
        validateBase64Method(type, method, propertyDiscoveryEnabled, record, generatedCodec);
      }
      if (method.isAnnotationPresent(JsonUnwrapped.class)) {
        validateUnwrappedMethod(type, method, propertyDiscoveryEnabled, record, generatedCodec);
      }
      validateUnwrappedParameters(type, method, propertyDiscoveryEnabled, record);
    }
    return hasAnyField;
  }

  private static void validateUnwrappedMethod(
      Class<?> type,
      Method method,
      boolean propertyDiscoveryEnabled,
      boolean record,
      GeneratedJsonCodec<?> generatedCodec) {
    if (method.isAnnotationPresent(JsonAnyGetter.class)
        || method.isAnnotationPresent(JsonAnySetter.class)) {
      throw new ForyJsonException("@JsonUnwrapped cannot annotate a JSON Any method: " + method);
    }
    if (record) {
      if (isPropagatedRecordUnwrapped(type, method, generatedCodec)) {
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
      Class<?> type, Method method, boolean propertyDiscoveryEnabled, boolean record) {
    Parameter[] parameters = method.getParameters();
    for (int i = 0; i < parameters.length; i++) {
      if (!parameters[i].isAnnotationPresent(JsonUnwrapped.class)) {
        continue;
      }
      if (method.isAnnotationPresent(JsonCreator.class)) {
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
      Class<?> type, Constructor<?> constructor, boolean record) {
    Parameter[] parameters = constructor.getParameters();
    for (int i = 0; i < parameters.length; i++) {
      JsonUnwrapped annotation = parameters[i].getAnnotation(JsonUnwrapped.class);
      if (annotation == null || constructor.isAnnotationPresent(JsonCreator.class)) {
        continue;
      }
      if (record && isPropagatedRecordUnwrapped(type, constructor, i, annotation)) {
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
      GeneratedJsonCodec<?> generatedCodec) {
    if (method.isAnnotationPresent(JsonAnyGetter.class)) {
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
    if (!propertyDiscoveryEnabled
        || !isEligibleAccessor(method)
        || getterPropertyName(method) == null) {
      throw new ForyJsonException(
          "@JsonCodec requires an effective ordinary JSON getter: " + method);
    }
  }

  private static void validateCodecParameters(
      Method method, boolean propertyDiscoveryEnabled, boolean record) {
    Parameter[] parameters = method.getParameters();
    for (int i = 0; i < parameters.length; i++) {
      if (!parameters[i].isAnnotationPresent(JsonCodec.class)) {
        continue;
      }
      if (method.isAnnotationPresent(JsonCreator.class)) {
        continue;
      }
      if (method.isAnnotationPresent(JsonAnySetter.class)) {
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
      Class<?> type, Constructor<?> constructor, boolean record) {
    Parameter[] parameters = constructor.getParameters();
    for (int i = 0; i < parameters.length; i++) {
      JsonCodec annotation = parameters[i].getAnnotation(JsonCodec.class);
      if (annotation == null || constructor.isAnnotationPresent(JsonCreator.class)) {
        continue;
      }
      if (record && isRecordConstructor(type, constructor)) {
        continue;
      }
      throw new ForyJsonException("@JsonCodec parameter requires a @JsonCreator: " + constructor);
    }
  }

  private static void validateRawField(Field field) {
    if (!isEligibleField(field) || field.getType() != String.class) {
      throw new ForyJsonException("Invalid @JsonRawValue field " + field);
    }
    if (field.isAnnotationPresent(JsonCodec.class)
        || field.isAnnotationPresent(JsonBase64.class)
        || field.isAnnotationPresent(JsonAnyProperty.class)) {
      throw new ForyJsonException("Conflicting JSON annotations on @JsonRawValue field " + field);
    }
    JsonIgnore ignore = field.getAnnotation(JsonIgnore.class);
    if (ignore != null && ignore.ignoreRead() && ignore.ignoreWrite()) {
      throw new ForyJsonException("@JsonRawValue has no JSON read or write direction: " + field);
    }
  }

  private static void validateRawMethod(
      Class<?> type,
      Method method,
      boolean propertyDiscoveryEnabled,
      boolean record,
      GeneratedJsonCodec<?> generatedCodec) {
    if ((!propertyDiscoveryEnabled
            && !(record
                && isPropagatedRecordAnnotation(type, method, JsonRawValue.class, generatedCodec)))
        || !isEligibleAccessor(method)
        || method.isVarArgs()
        || method.getTypeParameters().length != 0
        || method.getParameterCount() != 0
        || method.getReturnType() != String.class
        || (!method.isAnnotationPresent(JsonValue.class)
            && ((!record && getterPropertyName(method) == null)
                || (record && !isRecordAccessor(type, method, generatedCodec))))) {
      throw new ForyJsonException("Invalid @JsonRawValue method " + method);
    }
    if (method.isAnnotationPresent(JsonCodec.class)
        || method.isAnnotationPresent(JsonBase64.class)
        || method.isAnnotationPresent(JsonAnyGetter.class)) {
      throw new ForyJsonException("Conflicting JSON annotations on @JsonRawValue method " + method);
    }
  }

  private static void validateBase64Field(Field field) {
    if (!isEligibleField(field) || field.getType() != byte[].class) {
      throw new ForyJsonException("Invalid @JsonBase64 field " + field);
    }
    if (field.isAnnotationPresent(JsonCodec.class)
        || field.isAnnotationPresent(JsonRawValue.class)
        || field.isAnnotationPresent(JsonAnyProperty.class)) {
      throw new ForyJsonException("Conflicting JSON annotations on @JsonBase64 field " + field);
    }
    JsonIgnore ignore = field.getAnnotation(JsonIgnore.class);
    if (ignore != null && ignore.ignoreRead() && ignore.ignoreWrite()) {
      throw new ForyJsonException("@JsonBase64 has no JSON read or write direction: " + field);
    }
  }

  private static void validateBase64Method(
      Class<?> type,
      Method method,
      boolean propertyDiscoveryEnabled,
      boolean record,
      GeneratedJsonCodec<?> generatedCodec) {
    if ((!propertyDiscoveryEnabled
            && !(record
                && isPropagatedRecordAnnotation(type, method, JsonBase64.class, generatedCodec)))
        || !isEligibleAccessor(method)
        || method.isVarArgs()
        || method.getTypeParameters().length != 0
        || method.getParameterCount() != 0
        || method.getReturnType() != byte[].class
        || ((!record && getterPropertyName(method) == null)
            || (record && !isRecordAccessor(type, method, generatedCodec)))) {
      throw new ForyJsonException("Invalid @JsonBase64 method " + method);
    }
    if (method.isAnnotationPresent(JsonCodec.class)
        || method.isAnnotationPresent(JsonRawValue.class)
        || method.isAnnotationPresent(JsonAnyGetter.class)) {
      throw new ForyJsonException("Conflicting JSON annotations on @JsonBase64 method " + method);
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
      GeneratedJsonCodec<?> generatedCodec) {
    if (!isRecordAccessor(type, method, generatedCodec)) {
      return false;
    }
    try {
      return type.getDeclaredField(method.getName()).isAnnotationPresent(annotationType);
    } catch (NoSuchFieldException e) {
      return false;
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException(
          "Cannot read record-component @" + annotationType.getSimpleName() + " for " + method, e);
    }
  }

  private static boolean isPropagatedRecordUnwrapped(
      Class<?> type, Method method, GeneratedJsonCodec<?> generatedCodec) {
    if (!isRecordAccessor(type, method, generatedCodec)) {
      return false;
    }
    try {
      JsonUnwrapped fieldAnnotation =
          type.getDeclaredField(method.getName()).getAnnotation(JsonUnwrapped.class);
      JsonUnwrapped methodAnnotation = method.getAnnotation(JsonUnwrapped.class);
      return sameUnwrapped(fieldAnnotation, methodAnnotation);
    } catch (NoSuchFieldException e) {
      return false;
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException("Cannot read record-component @JsonUnwrapped for " + method, e);
    }
  }

  private static boolean isPropagatedRecordUnwrapped(
      Class<?> type, Constructor<?> constructor, int parameterIndex, JsonUnwrapped annotation) {
    if (!isRecordConstructor(type, constructor)) {
      return false;
    }
    RecordComponent[] components = RecordUtils.getRecordComponents(type);
    if (parameterIndex >= components.length) {
      return false;
    }
    try {
      JsonUnwrapped fieldAnnotation =
          type.getDeclaredField(components[parameterIndex].getName())
              .getAnnotation(JsonUnwrapped.class);
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

  private static void validateAnySetter(Method method) {
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
        || method.isAnnotationPresent(JsonProperty.class)) {
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
      GeneratedJsonCodec<?> generatedCodec) {
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
      if (anySetter.getParameters()[0].isAnnotationPresent(JsonCodec.class)) {
        throw new ForyJsonException("@JsonCodec is not supported on a JSON Any setter key");
      }
      valueCodecAnnotation = anySetter.getParameters()[1].getAnnotation(JsonCodec.class);
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

  private static final class FieldBuilder {
    private final String name;
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
    private JsonCodec codecAnnotation;
    private Class<? extends JsonValueCodec<?>> valueCodecClass;
    private AnnotatedElement codecSource;
    private JsonUnwrapped unwrappedAnnotation;
    private AnnotatedElement unwrappedSource;

    private FieldBuilder(String name) {
      this.name = name;
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
      fieldWriteAllowed = writeAllowed;
      fieldReadAllowed = readAllowed;
      if (writeSource) {
        writeField = field;
      }
      if (readSink) {
        readField = field;
      }
      mergeAnnotation(type, field);
      if (field.isAnnotationPresent(JsonAnyProperty.class)) {
        if (!writeSource && !readSink) {
          throw new ForyJsonException("@JsonAnyProperty must enable reading or writing: " + field);
        }
        anyField = field;
      }
    }

    private void setWriteGetter(Class<?> type, Method getter) {
      mergeAnnotation(type, getter);
      if (field != null && !fieldWriteAllowed) {
        return;
      }
      if (writeGetter != null) {
        throw new ForyJsonException("Duplicate JSON getter for property " + name);
      }
      writeGetter = getter;
      writeField = null;
    }

    private void setReadSetter(Class<?> type, Method setter) {
      mergeAnnotation(type, setter);
      Parameter parameter = setter.getParameters()[0];
      mergeCodec(parameter);
      mergeUnwrapped(parameter);
      if (field != null && !fieldReadAllowed) {
        return;
      }
      if (readSetter != null) {
        throw new ForyJsonException("Duplicate JSON setter for property " + name);
      }
      readSetter = setter;
      readField = null;
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
          || unwrappedAnnotation != null;
    }

    private boolean hasIndex() {
      return explicitIndex != JsonProperty.INDEX_UNKNOWN;
    }

    private boolean hasLogicalMember() {
      return field != null || writeGetter != null || readSetter != null;
    }

    private boolean creatorReadAllowed() {
      return field == null || fieldReadAllowed;
    }

    private String jsonName(PropertyNamingStrategy strategy) {
      return explicitName == null ? translateName(name, strategy) : explicitName;
    }

    private String nameDescription(PropertyNamingStrategy strategy) {
      return explicitName == null
          ? "Java property " + name + " transformed by " + strategy
          : "Java property " + name + " explicitly named by " + explicitNameSource;
    }

    private Type logicalType(TypeRef<?> ownerType) {
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
      return ownerType.resolveType(type).getType();
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
        if (codecAnnotation != null || valueCodecClass != null) {
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
          codecAnnotation,
          valueCodecClass,
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
      if (codecAnnotation != null || valueCodecClass != null || rawValueSource != null) {
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
      if (creatorInfo != null && hasReadSink() && creatorArgumentIndex < 0) {
        throw new ForyJsonException(
            "Read-enabled @JsonUnwrapped property must bind one @JsonCreator argument on "
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

    private void mergeAnnotation(Class<?> type, AnnotatedElement source) {
      mergeCodec(source);
      if (source.isAnnotationPresent(JsonRawValue.class) && rawValueSource == null) {
        rawValueSource = source;
      }
      mergeUnwrapped(source);
      JsonProperty property = source.getAnnotation(JsonProperty.class);
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
      mergeCodec(parameter);
      mergeUnwrapped(parameter);
      JsonProperty property = parameter.getAnnotation(JsonProperty.class);
      if (unwrappedAnnotation == null) {
        mergeAnnotation(type, parameter);
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

    private void mergeUnwrapped(AnnotatedElement source) {
      JsonUnwrapped declared = source.getAnnotation(JsonUnwrapped.class);
      if (declared == null) {
        return;
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
      JsonCodec declared = source.getAnnotation(JsonCodec.class);
      if (source.isAnnotationPresent(JsonBase64.class)) {
        if (declared != null || codecAnnotation != null) {
          throw new ForyJsonException(
              "@JsonBase64 cannot coexist with @JsonCodec for property " + name);
        }
        if (valueCodecClass == null) {
          valueCodecClass = Base64ByteArrayCodec.class;
          codecSource = source;
        }
        return;
      }
      if (declared != null && valueCodecClass != null) {
        throw new ForyJsonException(
            "@JsonBase64 cannot coexist with @JsonCodec for property " + name);
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
