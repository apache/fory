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

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
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
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.codec.ObjectCodec.AnyInfo;
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
      boolean writeNullFields) {
    Class<?> type = ownerType.getRawType();
    if (type.isInterface()
        || Modifier.isAbstract(type.getModifiers())
        || type.isPrimitive()
        || type.isArray()
        || type.isEnum()) {
      throw new ForyJsonException("Unsupported JSON object type " + type);
    }
    boolean record = RecordUtils.isRecord(type);
    boolean hasAnyField = validateMemberAnnotations(type, propertyDiscoveryEnabled, record);
    LinkedHashMap<String, FieldBuilder> builders = new LinkedHashMap<>();
    addFields(type, record, propertyDiscoveryEnabled, hasAnyField, builders);
    if (record) {
      addRecordAccessors(type, builders);
    }
    Method anySetter = addJsonMethods(type, propertyDiscoveryEnabled, record, builders);
    FieldBuilder anyBuilder = findAnyBuilder(type, builders);
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
    JsonCreatorInfo creatorInfo =
        record
            ? rejectRecordCreator(type)
            : buildCreatorInfo(type, ownerType, builders, propertyNamingStrategy);
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
    boolean anyWrites = anyBuilder != null && anyBuilder.anyWriteEnabled();
    boolean orderWrites = propertyOrder != null || hasIndexedProperty(builders) || anyWrites;
    List<JsonFieldInfo> writes = new ArrayList<>();
    List<FieldBuilder> writeBuilders = orderWrites ? new ArrayList<>(builders.size()) : null;
    List<JsonFieldInfo> reads = new ArrayList<>();
    List<String> readJavaNames = record ? new ArrayList<>() : null;
    List<String> skippedNames = hasAny ? new ArrayList<>() : null;
    Map<String, FieldBuilder> canonicalNames = new LinkedHashMap<>();
    Map<Long, String> canonicalHashes = new LinkedHashMap<>();
    int anyOriginalIndex = -1;
    for (FieldBuilder builder : builders.values()) {
      if (builder == anyBuilder) {
        if (anyWrites) {
          anyOriginalIndex = writes.size();
        }
        continue;
      }
      if (hasAny && builder.hasLogicalMember()) {
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
          builder.build(record, ownerType, propertyNamingStrategy, writeNullFields);
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
        if (record) {
          readJavaNames.add(builder.name);
        }
      }
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
    if (anyWrites) {
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
      if (record) {
        constructionIndex = readArray.length;
        readJavaNames.add(anyBuilder.name);
      } else if (creatorInfo != null) {
        constructionIndex = anyBuilder.creatorArgumentIndex;
      }
    }
    AnyInfo anyInfo =
        hasAny
            ? buildAnyInfo(ownerType, anyBuilder, anySetter, anyWriteIndex, constructionIndex)
            : null;
    ObjectInstantiator<?> instantiator = ObjectInstantiators.createObjectInstantiator(type);
    String[] recordNames = record ? readJavaNames.toArray(new String[0]) : null;
    String[] skipped = hasAny ? skippedNames.toArray(new String[0]) : null;
    return ObjectCodec.createCodec(
        ownerType, writeArray, readArray, recordNames, creatorInfo, anyInfo, skipped, instantiator);
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
      LinkedHashMap<String, FieldBuilder> builders) {
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
        validatePropertyMethod(type, method, propertyDiscoveryEnabled, record);
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
        || method.isAnnotationPresent(JsonAnySetter.class)) {
      return true;
    }
    return !record
        && isEligibleAccessor(method)
        && (usesJsonReturn(method) || usesJsonParameters(method));
  }

  static boolean usesJsonReturn(Method method) {
    // Java rejects type-use annotations on void, and setter returns are not JSON value owners.
    // Keep return and parameter roles separate so hosted metadata follows the same ownership.
    return method.isAnnotationPresent(JsonAnyGetter.class) || getterPropertyName(method) != null;
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
      Class<?> type, LinkedHashMap<String, FieldBuilder> builders) {
    RecordComponent[] components = RecordUtils.getRecordComponents(type);
    for (RecordComponent component : components) {
      FieldBuilder builder = builders.get(component.getName());
      if (builder == null) {
        throw new ForyJsonException("Missing JSON record field " + component.getName());
      }
      // Record accessors carry component annotations in Java 16+, but field access remains the
      // optimized read/write owner. The accessor participates only in logical-property annotation
      // merging and is discarded before hot metadata is published.
      builder.mergeAnnotation(type, component.getAccessor());
    }
  }

  private static JsonCreatorInfo rejectRecordCreator(Class<?> type) {
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
    return null;
  }

  private static JsonCreatorInfo buildCreatorInfo(
      Class<?> type,
      TypeRef<?> ownerType,
      LinkedHashMap<String, FieldBuilder> builders,
      PropertyNamingStrategy namingStrategy) {
    Executable creator = null;
    JsonCreator annotation = null;
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      JsonCreator candidate = constructor.getAnnotation(JsonCreator.class);
      if (candidate != null) {
        validateCreator(type, constructor);
        if (creator != null) {
          throw new ForyJsonException("Multiple @JsonCreator declarations on " + type.getName());
        }
        creator = constructor;
        annotation = candidate;
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      JsonCreator candidate = method.getAnnotation(JsonCreator.class);
      if (candidate != null) {
        validateCreator(type, method);
        if (creator != null) {
          throw new ForyJsonException("Multiple @JsonCreator declarations on " + type.getName());
        }
        creator = method;
        annotation = candidate;
      }
    }
    if (creator == null) {
      return null;
    }

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
        if (builder.isAny() && !builder.anyReadEnabled()) {
          throw new ForyJsonException(
              "JSON Any creator property has no read-enabled field: " + javaName);
        }
        if (!builder.creatorReadAllowed()) {
          throw new ForyJsonException("@JsonCreator property is ignored for reading: " + javaName);
        }
        if (!builder.isAny()) {
          Type resolved = ownerType.resolveType(parameterTypes[i]).getType();
          JsonCodec codecAnnotation = builder.codecAnnotation();
          fields.add(
              new JsonCreatorFieldInfo(
                  builder.jsonName(namingStrategy), i, resolved, rawTypes[i], codecAnnotation));
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
          builder.mergeAnnotation(type, parameters[i]);
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
        fields.add(new JsonCreatorFieldInfo(jsonName, i, resolved, rawTypes[i], codecAnnotation));
      }
    }
    JsonCreatorFieldInfo[] fieldArray = fields.toArray(new JsonCreatorFieldInfo[0]);
    rejectCreatorHashCollisions(fieldArray);
    return new JsonCreatorInfo(type, creator, fieldArray, creatorDefaults(rawTypes));
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

  private static void validateCreator(Class<?> type, Executable creator) {
    int modifiers = creator.getModifiers();
    if (!Modifier.isPublic(modifiers)
        || creator.isSynthetic()
        || creator.isVarArgs()
        || creator.getParameterCount() == 0
        || creator.getTypeParameters().length != 0) {
      throw new ForyJsonException("Invalid @JsonCreator executable " + creator);
    }
    if (creator instanceof Method) {
      Method factory = (Method) creator;
      if (!Modifier.isStatic(modifiers) || factory.isBridge() || factory.getReturnType() != type) {
        throw new ForyJsonException("Invalid @JsonCreator factory " + factory);
      }
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
    builder.setCreatorType(creator, parameterIndex);
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
      Class<?> type, boolean propertyDiscoveryEnabled, boolean record) {
    boolean hasAnyField = false;
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        if (field.isAnnotationPresent(JsonCodec.class) && !isEligibleField(field)) {
          throw new ForyJsonException("@JsonCodec is not supported on JSON field: " + field);
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
          validateCodecMethod(type, method, propertyDiscoveryEnabled, record);
        }
        validateCodecParameters(method, propertyDiscoveryEnabled, record);
        if (method.isAnnotationPresent(JsonProperty.class)) {
          validatePropertyMethod(type, method, propertyDiscoveryEnabled, record);
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
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      validateCodecParameters(type, constructor, record);
    }
    for (Method method : type.getMethods()) {
      if (!method.getDeclaringClass().isInterface()) {
        continue;
      }
      if (method.isAnnotationPresent(JsonCodec.class)) {
        // getMethods exposes only the effective inherited declaration. A class or child-interface
        // override therefore suppresses an annotation from the overridden interface method.
        validateCodecMethod(type, method, propertyDiscoveryEnabled, record);
      }
      validateCodecParameters(method, propertyDiscoveryEnabled, record);
    }
    return hasAnyField;
  }

  private static void validateCodecMethod(
      Class<?> type, Method method, boolean propertyDiscoveryEnabled, boolean record) {
    if (method.isAnnotationPresent(JsonAnyGetter.class)) {
      if (!propertyDiscoveryEnabled) {
        throw new ForyJsonException(
            "JSON Any method annotations require property discovery: " + method);
      }
      validateAnyGetter(method);
      return;
    }
    if (record) {
      // javac copies a record-component annotation to every applicable generated member. The
      // component/backing field remains the existing codec owner; tolerate its identical copies.
      if (isPropagatedRecordCodec(type, method)) {
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
      if (record && isPropagatedRecordCodec(type, constructor, i, annotation)) {
        continue;
      }
      throw new ForyJsonException("@JsonCodec parameter requires a @JsonCreator: " + constructor);
    }
  }

  private static boolean isPropagatedRecordCodec(Class<?> type, Method method) {
    if (!isRecordAccessor(type, method)) {
      return false;
    }
    try {
      JsonCodec fieldCodec = type.getDeclaredField(method.getName()).getAnnotation(JsonCodec.class);
      JsonCodec methodCodec = method.getAnnotation(JsonCodec.class);
      return fieldCodec != null && fieldCodec.equals(methodCodec);
    } catch (NoSuchFieldException e) {
      return false;
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException("Cannot read record-component @JsonCodec for " + method, e);
    }
  }

  private static boolean isPropagatedRecordCodec(
      Class<?> type, Constructor<?> constructor, int parameterIndex, JsonCodec annotation) {
    RecordComponent[] components = RecordUtils.getRecordComponents(type);
    if (components.length != constructor.getParameterCount()
        || parameterIndex >= components.length
        || components[parameterIndex].getType()
            != constructor.getParameterTypes()[parameterIndex]) {
      return false;
    }
    try {
      JsonCodec fieldCodec =
          type.getDeclaredField(components[parameterIndex].getName())
              .getAnnotation(JsonCodec.class);
      return annotation.equals(fieldCodec);
    } catch (NoSuchFieldException e) {
      return false;
    }
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
      Class<?> type, Method method, boolean propertyDiscoveryEnabled, boolean record) {
    if (!propertyDiscoveryEnabled
        || !isEligibleAccessor(method)
        || record && !isRecordAccessor(type, method)) {
      throw new ForyJsonException("@JsonProperty is not supported on JSON method: " + method);
    }
    if (!record && getterPropertyName(method) == null && setterPropertyName(method) == null) {
      throw new ForyJsonException("@JsonProperty requires a JSON getter or setter: " + method);
    }
  }

  private static boolean isRecordAccessor(Class<?> type, Method method) {
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
      int constructionIndex) {
    Field anyField = builder == null ? null : builder.anyField;
    Method anyGetter = builder == null ? null : builder.anyGetter;
    Field writeField = anyField != null && builder.anyWriteEnabled() ? anyField : null;
    Method writeGetter = anyGetter;
    Field readField = anyField != null && builder.anyReadEnabled() ? anyField : null;
    Type mapType = null;
    Class<?> mapRawType = null;
    Type valueType = null;
    Class<?> valueRawType = null;
    Class<? extends JsonValueCodec<?>> valueCodecClass = null;
    JsonCodec valueCodecAnnotation = null;
    if (anyField != null || anyGetter != null) {
      Type declaredMapType =
          anyGetter == null ? anyField.getGenericType() : anyGetter.getGenericReturnType();
      mapType = ownerType.resolveType(declaredMapType).getType();
      mapRawType = CodecUtils.rawType(mapType, null);
      valueType = anyMapValueType(mapType, mapRawType, anyField != null ? anyField : anyGetter);
      valueRawType = CodecUtils.rawType(valueType, Object.class);
      validateAnyLogicalTypes(ownerType, builder, mapType);
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
    return new AnyInfo(
        writeField,
        writeGetter,
        readField,
        anySetter,
        mapType,
        mapRawType,
        valueType,
        valueRawType,
        valueCodecAnnotation,
        valueCodecClass,
        writeIndex,
        constructionIndex);
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
    private boolean hasJsonProperty;
    private int creatorArgumentIndex = -1;
    private JsonCodec codecAnnotation;
    private AnnotatedElement codecSource;

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
      mergeCodec(setter.getParameters()[0]);
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
          || codecAnnotation != null;
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
        boolean defaultWriteNull) {
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
      writeAccessor =
          writeGetter != null
              ? JsonFieldAccessor.forGetter(writeGetter)
              : (writeField == null ? null : JsonFieldAccessor.forField(writeField));
      readAccessor =
          readSetter != null
              ? JsonFieldAccessor.forSetter(readSetter)
              : (readField == null || record ? null : JsonFieldAccessor.forField(readField));
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
          codecAnnotation);
    }

    private void setCreatorType(Executable creator, int parameterIndex) {
      mergeCodec(creator.getParameters()[parameterIndex]);
    }

    private JsonCodec codecAnnotation() {
      return codecAnnotation;
    }

    private void mergeAnnotation(Class<?> type, AnnotatedElement source) {
      mergeCodec(source);
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

    private void mergeCodec(AnnotatedElement source) {
      JsonCodec declared = source.getAnnotation(JsonCodec.class);
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
