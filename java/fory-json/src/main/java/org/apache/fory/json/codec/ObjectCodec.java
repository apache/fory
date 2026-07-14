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

import java.lang.invoke.MethodHandle;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
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
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.JsonAnyGetter;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonAnySetter;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.meta.JsonFieldTable;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.reflect.ObjectInstantiator;
import org.apache.fory.reflect.ObjectInstantiators;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.util.record.RecordComponent;
import org.apache.fory.util.record.RecordInfo;
import org.apache.fory.util.record.RecordUtils;

/**
 * Reflection-backed semantic codec and metadata owner for one Java object type.
 *
 * <p>Construction discovers eligible fields and JavaBean properties, merges each
 * field/getter/setter group into one logical property, applies its name, inclusion, serialization
 * order, and directional ignore rules, resolves generic member types against the owner {@link
 * TypeRef}, and builds separate read and write field arrays. Class-valued fields and properties are
 * never JSON members. Records retain constructor metadata and field defaults; ordinary objects
 * retain an allocation strategy plus field or accessor sinks.
 *
 * <p>This codec is the interpreted implementation and the semantic fallback. Only an exact
 * raw-class instance of this class is eligible for generated capability replacement. Parameterized
 * object codecs retain binding-specific member types and remain the owner of all five slots.
 * Generated code may replace paths independently, but it is built from this codec's immutable field
 * metadata and preserves the same null, unknown-field, record, and member-discovery semantics.
 */
public class ObjectCodec<T> implements JsonCodec<T>, StringObjectWriter<T>, Utf8ObjectWriter<T> {
  private static final int MUTABLE = 0;
  private static final int RECORD = 1;
  private static final int CREATOR = 2;

  protected final Class<?> type;
  protected final JsonFieldInfo[] writeFields;
  protected final JsonFieldInfo[] readFields;
  protected final JsonFieldTable readTable;
  protected final ObjectInstantiator<?> instantiator;
  private final int creationKind;
  private final JsonCreatorInfo creatorInfo;
  private final AnyInfo anyInfo;
  private final RecordInfo recordInfo;
  private final Object[] recordFieldDefaults;

  private ObjectCodec(
      Class<?> type,
      JsonFieldInfo[] writeFields,
      JsonFieldInfo[] readFields,
      String[] readJavaNames,
      JsonCreatorInfo creatorInfo,
      AnyInfo anyInfo,
      String[] skippedNames,
      ObjectInstantiator<?> instantiator) {
    this.type = type;
    this.writeFields = writeFields;
    this.readFields = readFields;
    this.anyInfo = anyInfo;
    readTable =
        anyInfo == null
            ? new JsonFieldTable(readFields)
            : new JsonFieldTable(readFields, skippedNames);
    this.instantiator = instantiator;
    this.creatorInfo = creatorInfo;
    creationKind = RecordUtils.isRecord(type) ? RECORD : creatorInfo == null ? MUTABLE : CREATOR;
    if (creationKind == RECORD) {
      recordInfo = new RecordInfo(type, Arrays.asList(readJavaNames));
      recordFieldDefaults = recordFieldDefaults(type, readJavaNames, recordInfo);
    } else {
      recordInfo = null;
      recordFieldDefaults = null;
    }
  }

  public static <T> ObjectCodec<T> build(
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
            ? AnyInfo.build(ownerType, anyBuilder, anySetter, anyWriteIndex, constructionIndex)
            : null;
    ObjectInstantiator<?> instantiator = ObjectInstantiators.createObjectInstantiator(type);
    String[] recordNames = record ? readJavaNames.toArray(new String[0]) : null;
    String[] skipped = hasAny ? skippedNames.toArray(new String[0]) : null;
    if (ownerType.getType() instanceof Class) {
      return new ObjectCodec<>(
          type, writeArray, readArray, recordNames, creatorInfo, anyInfo, skipped, instantiator);
    }
    return new ParameterizedObjectCodec<>(
        type, writeArray, readArray, recordNames, creatorInfo, anyInfo, skipped, instantiator);
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
        FieldBuilder builder =
            builders.computeIfAbsent(field.getName(), name -> new FieldBuilder(name));
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
          fields.add(
              new JsonCreatorFieldInfo(builder.jsonName(namingStrategy), i, resolved, rawTypes[i]));
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
        fields.add(new JsonCreatorFieldInfo(jsonName, i, resolved, rawTypes[i]));
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

  public final Class<?> type() {
    return type;
  }

  public final JsonFieldInfo[] writeFields() {
    return writeFields;
  }

  public final JsonFieldInfo[] readFields() {
    return readFields;
  }

  public final JsonFieldTable readTable() {
    return readTable;
  }

  public final boolean isRecord() {
    return creationKind == RECORD;
  }

  public final JsonCreatorInfo creatorInfo() {
    return creatorInfo;
  }

  @Internal
  public final AnyInfo anyInfo() {
    return anyInfo;
  }

  @Internal
  public final Object requireCreatorResult(Object value) {
    if (value == null || value.getClass() != type) {
      throw new ForyJsonException("JSON creator must return an exact non-null " + type.getName());
    }
    return value;
  }

  @Internal
  public final ForyJsonException creatorFailure(Throwable cause) {
    if (cause instanceof Error) {
      throw (Error) cause;
    }
    return new ForyJsonException("JSON creator failed for " + type.getName(), cause);
  }

  public final void resolveTypes(JsonTypeResolver typeResolver) {
    for (JsonFieldInfo field : writeFields) {
      field.resolveTypes(typeResolver);
    }
    for (JsonFieldInfo field : readFields) {
      field.resolveTypes(typeResolver);
    }
    if (creatorInfo != null) {
      creatorInfo.resolveTypes(typeResolver);
    }
    if (anyInfo != null) {
      anyInfo.resolveTypes(typeResolver);
    }
  }

  @SuppressWarnings("unchecked")
  public final T newInstance() {
    return (T) instantiator.newInstance();
  }

  @Internal
  public final Object[] newRecordFieldValues() {
    return Arrays.copyOf(recordFieldDefaults, recordFieldDefaults.length);
  }

  @Internal
  @SuppressWarnings("unchecked")
  public final T newRecord(Object[] values) {
    Object[] arguments = RecordUtils.remapping(recordInfo, values);
    Object object = instantiator.newInstanceWithArguments(arguments);
    Arrays.fill(recordInfo.getRecordComponents(), null);
    return (T) object;
  }

  @Internal
  public final Map<Object, Object> newAnyMap() {
    return anyInfo.mapCodec.newMap();
  }

  @Internal
  public final Map<?, ?> finishAnyMap(Map<Object, Object> map) {
    return anyInfo.mapCodec.finishMap(map);
  }

  @Internal
  public final void putAnyMap(Map<Object, Object> map, String name, Object value) {
    try {
      map.put(name, value);
    } catch (UnsupportedOperationException e) {
      throw immutableAnyMap(e);
    }
  }

  @Internal
  public final ForyJsonException nullFinalAnyMap() {
    return new ForyJsonException(
        "Final @JsonAnyProperty field must hold a mutable Map on " + type.getName());
  }

  @Internal
  public final ForyJsonException nullPrimitiveAnyValue() {
    return new ForyJsonException(
        "Cannot read null into primitive @JsonAnySetter parameter " + anyInfo.setterValueRawType);
  }

  @Internal
  public final ForyJsonException anyAccessorFailure(String memberName, Throwable cause) {
    return new ForyJsonException(
        "Cannot access JSON Any member " + memberName + " on " + type.getName(), cause);
  }

  private ForyJsonException immutableAnyMap(UnsupportedOperationException cause) {
    return new ForyJsonException(
        "@JsonAnyProperty field must hold a mutable Map on " + type.getName(), cause);
  }

  @Internal
  public final int writeStringAny(
      StringJsonWriter writer, Map<?, ?> map, StringWriterCodec<Object> codec, int written) {
    if (map == null) {
      return written;
    }
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      Object key = entry.getKey();
      if (key == null || key.getClass() != String.class) {
        throw invalidAnyKey(key);
      }
      String name = (String) key;
      long hash = JsonFieldNameHash.hash(name);
      if (reservedAnyHash(hash)) {
        throw reservedAnyName(name);
      }
      writer.writeComma(written);
      writer.writeFieldName(name);
      codec.writeString(writer, entry.getValue());
      written = 1;
    }
    return written;
  }

  @Internal
  public final int writeUtf8Any(
      Utf8JsonWriter writer, Map<?, ?> map, Utf8WriterCodec<Object> codec, int written) {
    if (map == null) {
      return written;
    }
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      Object key = entry.getKey();
      if (key == null || key.getClass() != String.class) {
        throw invalidAnyKey(key);
      }
      String name = (String) key;
      long hash = JsonFieldNameHash.hash(name);
      if (reservedAnyHash(hash)) {
        throw reservedAnyName(name);
      }
      writer.writeComma(written);
      writer.writeFieldName(name);
      codec.writeUtf8(writer, entry.getValue());
      written = 1;
    }
    return written;
  }

  private boolean reservedAnyHash(long hash) {
    return readTable.containsHash(hash) || creatorInfo != null && creatorInfo.index(hash) >= 0;
  }

  private ForyJsonException invalidAnyKey(Object key) {
    String actualType = key == null ? "null" : key.getClass().getName();
    return new ForyJsonException(
        "JSON Any Map key must be an exact String on "
            + type.getName()
            + "; actual type is "
            + actualType);
  }

  private ForyJsonException reservedAnyName(String name) {
    return new ForyJsonException(
        "JSON Any member conflicts with a reserved property on " + type.getName() + ": " + name);
  }

  @Override
  public void writeString(StringJsonWriter writer, T value) {
    StringWriterCodec<T> codec = writer.typeResolver().stringWriter(this);
    if (codec != this) {
      codec.writeString(writer, value);
    } else if (value == null) {
      writer.writeNull();
    } else {
      writeStringObject(writer, value);
    }
  }

  @Override
  public void writeUtf8(Utf8JsonWriter writer, T value) {
    Utf8WriterCodec<T> codec = writer.typeResolver().utf8Writer(this);
    if (codec != this) {
      codec.writeUtf8(writer, value);
    } else if (value == null) {
      writer.writeNull();
    } else {
      writeUtf8Object(writer, value);
    }
  }

  // Raw and parameterized bindings share the same interpreted object algorithms inside this
  // top-level owner. Package access avoids Java 8 synthetic accessors from the nested binding;
  // these methods are not codec entries and must not be used for capability dispatch.
  final T readLatin1Object(Latin1JsonReader reader) {
    if (anyInfo != null && anyInfo.readEnabled()) {
      return readLatin1AnyObject(reader, readTable);
    }
    return readLatin1FixedObject(reader);
  }

  final T readLatin1Object(Latin1JsonReader reader, JsonFieldTable table) {
    return readLatin1AnyObject(reader, table);
  }

  private T readLatin1FixedObject(Latin1JsonReader reader) {
    reader.enterDepth();
    if (creationKind != MUTABLE) {
      if (creationKind == CREATOR) {
        Object[] arguments = readLatin1CreatorArguments(reader);
        reader.exitDepth();
        return create(arguments);
      }
      T object = readLatin1Record(reader);
      reader.exitDepth();
      return object;
    }
    T object = newInstance();
    reader.expect('{');
    if (reader.consume('}')) {
      reader.exitDepth();
      return object;
    }
    do {
      JsonFieldInfo field = reader.readField(readTable);
      reader.expect(':');
      if (field == null) {
        reader.skipValue();
      } else {
        field.readLatin1(reader, object);
      }
    } while (reader.consume(','));
    reader.expect('}');
    reader.exitDepth();
    return object;
  }

  final T readUtf16Object(Utf16JsonReader reader) {
    if (anyInfo != null && anyInfo.readEnabled()) {
      return readUtf16AnyObject(reader, readTable);
    }
    return readUtf16FixedObject(reader);
  }

  final T readUtf16Object(Utf16JsonReader reader, JsonFieldTable table) {
    return readUtf16AnyObject(reader, table);
  }

  private T readUtf16FixedObject(Utf16JsonReader reader) {
    reader.enterDepth();
    if (creationKind != MUTABLE) {
      if (creationKind == CREATOR) {
        Object[] arguments = readUtf16CreatorArguments(reader);
        reader.exitDepth();
        return create(arguments);
      }
      T object = readUtf16Record(reader);
      reader.exitDepth();
      return object;
    }
    T object = newInstance();
    reader.expect('{');
    if (reader.consume('}')) {
      reader.exitDepth();
      return object;
    }
    do {
      JsonFieldInfo field = reader.readField(readTable);
      reader.expect(':');
      if (field == null) {
        reader.skipValue();
      } else {
        field.readUtf16(reader, object);
      }
    } while (reader.consume(','));
    reader.expect('}');
    reader.exitDepth();
    return object;
  }

  final T readUtf8Object(Utf8JsonReader reader) {
    if (anyInfo != null && anyInfo.readEnabled()) {
      return readUtf8AnyObject(reader, readTable);
    }
    return readUtf8FixedObject(reader);
  }

  final T readUtf8Object(Utf8JsonReader reader, JsonFieldTable table) {
    return readUtf8AnyObject(reader, table);
  }

  private T readUtf8FixedObject(Utf8JsonReader reader) {
    reader.enterDepth();
    if (creationKind != MUTABLE) {
      if (creationKind == CREATOR) {
        Object[] arguments = readUtf8CreatorArguments(reader);
        reader.exitDepth();
        return create(arguments);
      }
      T object = readUtf8Record(reader);
      reader.exitDepth();
      return object;
    }
    T object = newInstance();
    reader.expect('{');
    if (reader.consume('}')) {
      reader.exitDepth();
      return object;
    }
    do {
      JsonFieldInfo field = reader.readField(readTable);
      reader.expect(':');
      if (field == null) {
        reader.skipValue();
      } else {
        field.readUtf8(reader, object);
      }
    } while (reader.consume(','));
    reader.expect('}');
    reader.exitDepth();
    return object;
  }

  @Override
  public T readLatin1(Latin1JsonReader reader) {
    Latin1ReaderCodec<T> codec = reader.typeResolver().latin1Reader(this);
    if (codec != this) {
      return codec.readLatin1(reader);
    }
    if (reader.tryReadNullToken()) {
      return null;
    }
    return readLatin1Object(reader);
  }

  @Override
  public T readUtf16(Utf16JsonReader reader) {
    Utf16ReaderCodec<T> codec = reader.typeResolver().utf16Reader(this);
    if (codec != this) {
      return codec.readUtf16(reader);
    }
    if (reader.tryReadNullToken()) {
      return null;
    }
    return readUtf16Object(reader);
  }

  @Override
  public T readUtf8(Utf8JsonReader reader) {
    Utf8ReaderCodec<T> codec = reader.typeResolver().utf8Reader(this);
    if (codec != this) {
      return codec.readUtf8(reader);
    }
    if (reader.tryReadNullToken()) {
      return null;
    }
    return readUtf8Object(reader);
  }

  private T readLatin1AnyObject(Latin1JsonReader reader, JsonFieldTable table) {
    reader.enterDepth();
    T object;
    if (creationKind == CREATOR) {
      object = create(readLatin1AnyCreatorArguments(reader, table));
    } else if (creationKind == RECORD) {
      object = readLatin1AnyRecord(reader, table);
    } else {
      object = readLatin1AnyMutable(reader, table);
    }
    reader.exitDepth();
    return object;
  }

  private T readUtf16AnyObject(Utf16JsonReader reader, JsonFieldTable table) {
    reader.enterDepth();
    T object;
    if (creationKind == CREATOR) {
      object = create(readUtf16AnyCreatorArguments(reader, table));
    } else if (creationKind == RECORD) {
      object = readUtf16AnyRecord(reader, table);
    } else {
      object = readUtf16AnyMutable(reader, table);
    }
    reader.exitDepth();
    return object;
  }

  private T readUtf8AnyObject(Utf8JsonReader reader, JsonFieldTable table) {
    reader.enterDepth();
    T object;
    if (creationKind == CREATOR) {
      object = create(readUtf8AnyCreatorArguments(reader, table));
    } else if (creationKind == RECORD) {
      object = readUtf8AnyRecord(reader, table);
    } else {
      object = readUtf8AnyMutable(reader, table);
    }
    reader.exitDepth();
    return object;
  }

  private T readLatin1AnyMutable(Latin1JsonReader reader, JsonFieldTable table) {
    T object = newInstance();
    Map<Object, Object> anyMap = null;
    boolean newMap = false;
    Latin1ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.latin1Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int match = table.match(hash);
        reader.expect(':');
        if (match >= 0) {
          readFields[match].readLatin1(reader, object);
        } else if (match == JsonFieldTable.SKIP) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readLatin1(reader);
          if (anyInfo.fieldRead()) {
            if (anyMap == null) {
              anyMap = anyInfo.readMap(object);
              if (anyMap == null) {
                if (anyInfo.finalReadField()) {
                  throw nullFinalAnyMap();
                }
                anyMap = newAnyMap();
                newMap = true;
              }
            }
            putAnyMap(anyMap, name, value);
          } else {
            anyInfo.put(object, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (newMap) {
      anyInfo.setReadMap(object, finishAnyMap(anyMap));
    }
    return object;
  }

  private T readUtf16AnyMutable(Utf16JsonReader reader, JsonFieldTable table) {
    T object = newInstance();
    Map<Object, Object> anyMap = null;
    boolean newMap = false;
    Utf16ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.utf16Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int match = table.match(hash);
        reader.expect(':');
        if (match >= 0) {
          readFields[match].readUtf16(reader, object);
        } else if (match == JsonFieldTable.SKIP) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readUtf16(reader);
          if (anyInfo.fieldRead()) {
            if (anyMap == null) {
              anyMap = anyInfo.readMap(object);
              if (anyMap == null) {
                if (anyInfo.finalReadField()) {
                  throw nullFinalAnyMap();
                }
                anyMap = newAnyMap();
                newMap = true;
              }
            }
            putAnyMap(anyMap, name, value);
          } else {
            anyInfo.put(object, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (newMap) {
      anyInfo.setReadMap(object, finishAnyMap(anyMap));
    }
    return object;
  }

  private T readUtf8AnyMutable(Utf8JsonReader reader, JsonFieldTable table) {
    T object = newInstance();
    Map<Object, Object> anyMap = null;
    boolean newMap = false;
    Utf8ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.utf8Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int match = table.match(hash);
        reader.expect(':');
        if (match >= 0) {
          readFields[match].readUtf8(reader, object);
        } else if (match == JsonFieldTable.SKIP) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readUtf8(reader);
          if (anyInfo.fieldRead()) {
            if (anyMap == null) {
              anyMap = anyInfo.readMap(object);
              if (anyMap == null) {
                if (anyInfo.finalReadField()) {
                  throw nullFinalAnyMap();
                }
                anyMap = newAnyMap();
                newMap = true;
              }
            }
            putAnyMap(anyMap, name, value);
          } else {
            anyInfo.put(object, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (newMap) {
      anyInfo.setReadMap(object, finishAnyMap(anyMap));
    }
    return object;
  }

  private T readLatin1AnyRecord(Latin1JsonReader reader, JsonFieldTable table) {
    Object[] values = newRecordFieldValues();
    Map<Object, Object> anyMap = null;
    Latin1ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.latin1Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int match = table.match(hash);
        reader.expect(':');
        if (match >= 0) {
          JsonFieldInfo field = readFields[match];
          values[field.readIndex()] = field.readLatin1Value(reader);
        } else if (match == JsonFieldTable.SKIP) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readLatin1(reader);
          if (anyMap == null) {
            anyMap = newAnyMap();
          }
          putAnyMap(anyMap, name, value);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (anyMap != null) {
      values[anyInfo.constructionIndex] = finishAnyMap(anyMap);
    }
    return newRecord(values);
  }

  private T readUtf16AnyRecord(Utf16JsonReader reader, JsonFieldTable table) {
    Object[] values = newRecordFieldValues();
    Map<Object, Object> anyMap = null;
    Utf16ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.utf16Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int match = table.match(hash);
        reader.expect(':');
        if (match >= 0) {
          JsonFieldInfo field = readFields[match];
          values[field.readIndex()] = field.readUtf16Value(reader);
        } else if (match == JsonFieldTable.SKIP) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readUtf16(reader);
          if (anyMap == null) {
            anyMap = newAnyMap();
          }
          putAnyMap(anyMap, name, value);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (anyMap != null) {
      values[anyInfo.constructionIndex] = finishAnyMap(anyMap);
    }
    return newRecord(values);
  }

  private T readUtf8AnyRecord(Utf8JsonReader reader, JsonFieldTable table) {
    Object[] values = newRecordFieldValues();
    Map<Object, Object> anyMap = null;
    Utf8ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.utf8Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int match = table.match(hash);
        reader.expect(':');
        if (match >= 0) {
          JsonFieldInfo field = readFields[match];
          values[field.readIndex()] = field.readUtf8Value(reader);
        } else if (match == JsonFieldTable.SKIP) {
          reader.skipValue();
        } else {
          String name = reader.materializeFieldName(start);
          Object value = anyReader.readUtf8(reader);
          if (anyMap == null) {
            anyMap = newAnyMap();
          }
          putAnyMap(anyMap, name, value);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (anyMap != null) {
      values[anyInfo.constructionIndex] = finishAnyMap(anyMap);
    }
    return newRecord(values);
  }

  private Object[] readLatin1AnyCreatorArguments(Latin1JsonReader reader, JsonFieldTable table) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    Map<Object, Object> anyMap = null;
    Latin1ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.latin1Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int index = creatorInfo.index(hash);
        reader.expect(':');
        if (index >= 0) {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readLatin1(reader);
        } else {
          int match = table.match(hash);
          if (match != JsonFieldTable.UNKNOWN) {
            reader.skipValue();
          } else {
            String name = reader.materializeFieldName(start);
            Object value = anyReader.readLatin1(reader);
            if (anyMap == null) {
              anyMap = newAnyMap();
            }
            putAnyMap(anyMap, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (anyMap != null) {
      arguments[anyInfo.constructionIndex] = finishAnyMap(anyMap);
    }
    return arguments;
  }

  private Object[] readUtf16AnyCreatorArguments(Utf16JsonReader reader, JsonFieldTable table) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    Map<Object, Object> anyMap = null;
    Utf16ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.utf16Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int index = creatorInfo.index(hash);
        reader.expect(':');
        if (index >= 0) {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readUtf16(reader);
        } else {
          int match = table.match(hash);
          if (match != JsonFieldTable.UNKNOWN) {
            reader.skipValue();
          } else {
            String name = reader.materializeFieldName(start);
            Object value = anyReader.readUtf16(reader);
            if (anyMap == null) {
              anyMap = newAnyMap();
            }
            putAnyMap(anyMap, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (anyMap != null) {
      arguments[anyInfo.constructionIndex] = finishAnyMap(anyMap);
    }
    return arguments;
  }

  private Object[] readUtf8AnyCreatorArguments(Utf8JsonReader reader, JsonFieldTable table) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    Map<Object, Object> anyMap = null;
    Utf8ReaderCodec<Object> anyReader = anyInfo.valueTypeInfo.utf8Reader();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int start = reader.position();
        long hash = reader.readFieldNameHash();
        int index = creatorInfo.index(hash);
        reader.expect(':');
        if (index >= 0) {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readUtf8(reader);
        } else {
          int match = table.match(hash);
          if (match != JsonFieldTable.UNKNOWN) {
            reader.skipValue();
          } else {
            String name = reader.materializeFieldName(start);
            Object value = anyReader.readUtf8(reader);
            if (anyMap == null) {
              anyMap = newAnyMap();
            }
            putAnyMap(anyMap, name, value);
          }
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    if (anyMap != null) {
      arguments[anyInfo.constructionIndex] = finishAnyMap(anyMap);
    }
    return arguments;
  }

  private T readLatin1Record(Latin1JsonReader reader) {
    Object[] values = newRecordFieldValues();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        JsonFieldInfo field = reader.readField(readTable);
        reader.expect(':');
        if (field == null) {
          reader.skipValue();
        } else {
          values[field.readIndex()] = field.readLatin1Value(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return newRecord(values);
  }

  private T readUtf16Record(Utf16JsonReader reader) {
    Object[] values = newRecordFieldValues();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        JsonFieldInfo field = reader.readField(readTable);
        reader.expect(':');
        if (field == null) {
          reader.skipValue();
        } else {
          values[field.readIndex()] = field.readUtf16Value(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return newRecord(values);
  }

  private T readUtf8Record(Utf8JsonReader reader) {
    Object[] values = newRecordFieldValues();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        JsonFieldInfo field = reader.readField(readTable);
        reader.expect(':');
        if (field == null) {
          reader.skipValue();
        } else {
          values[field.readIndex()] = field.readUtf8Value(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return newRecord(values);
  }

  private Object[] readLatin1CreatorArguments(Latin1JsonReader reader) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int index = creatorInfo.index(reader.readFieldNameHash());
        reader.expect(':');
        if (index < 0) {
          reader.skipValue();
        } else {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readLatin1(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return arguments;
  }

  private Object[] readUtf16CreatorArguments(Utf16JsonReader reader) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int index = creatorInfo.index(reader.readFieldNameHash());
        reader.expect(':');
        if (index < 0) {
          reader.skipValue();
        } else {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readUtf16(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return arguments;
  }

  private Object[] readUtf8CreatorArguments(Utf8JsonReader reader) {
    Object[] arguments = creatorInfo.newArguments();
    JsonCreatorFieldInfo[] fields = creatorInfo.fields();
    reader.expect('{');
    if (!reader.consume('}')) {
      do {
        int index = creatorInfo.index(reader.readFieldNameHash());
        reader.expect(':');
        if (index < 0) {
          reader.skipValue();
        } else {
          JsonCreatorFieldInfo field = fields[index];
          arguments[field.argumentIndex()] = field.readUtf8(reader);
        }
      } while (reader.consume(','));
      reader.expect('}');
    }
    return arguments;
  }

  @SuppressWarnings("unchecked")
  private T create(Object[] arguments) {
    return (T) creatorInfo.create(arguments);
  }

  final void writeStringObject(StringJsonWriter writer, T value) {
    writer.writeObjectStart();
    writeStringMembers(writer, value, 0);
    writer.writeObjectEnd();
  }

  @Override
  public final void writeStringMembers(StringJsonWriter writer, T value, int written) {
    if (anyInfo != null && anyInfo.writeEnabled()) {
      writeStringAnyMembers(writer, value, written);
      return;
    }
    writeStringFixedMembers(writer, value, written);
  }

  private void writeStringFixedMembers(StringJsonWriter writer, T value, int written) {
    JsonFieldInfo[] fields = writeFields;
    int length = fields.length;
    int i = 0;
    while (i + 4 <= length) {
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
    }
    while (i < length) {
      if (fields[i++].writeString(writer, value, written)) {
        written++;
      }
    }
  }

  private void writeStringAnyMembers(StringJsonWriter writer, T value, int written) {
    int anyIndex = anyInfo.writeIndex;
    JsonFieldInfo[] fields = writeFields;
    for (int i = 0; i < anyIndex; i++) {
      if (fields[i].writeString(writer, value, written)) {
        written++;
      }
    }
    written =
        writeStringAny(
            writer, anyInfo.writeMap(value), anyInfo.valueTypeInfo.stringWriter(), written);
    for (int i = anyIndex; i < fields.length; i++) {
      if (fields[i].writeString(writer, value, written)) {
        written++;
      }
    }
  }

  final void writeUtf8Object(Utf8JsonWriter writer, T value) {
    writer.writeObjectStart();
    writeUtf8Members(writer, value, 0);
    writer.writeObjectEnd();
  }

  @Override
  public final void writeUtf8Members(Utf8JsonWriter writer, T value, int written) {
    if (anyInfo != null && anyInfo.writeEnabled()) {
      writeUtf8AnyMembers(writer, value, written);
      return;
    }
    writeUtf8FixedMembers(writer, value, written);
  }

  private void writeUtf8FixedMembers(Utf8JsonWriter writer, T value, int written) {
    JsonFieldInfo[] fields = writeFields;
    int length = fields.length;
    int i = 0;
    while (i + 4 <= length) {
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
    }
    while (i < length) {
      if (fields[i++].writeUtf8(writer, value, written)) {
        written++;
      }
    }
  }

  private void writeUtf8AnyMembers(Utf8JsonWriter writer, T value, int written) {
    int anyIndex = anyInfo.writeIndex;
    JsonFieldInfo[] fields = writeFields;
    for (int i = 0; i < anyIndex; i++) {
      if (fields[i].writeUtf8(writer, value, written)) {
        written++;
      }
    }
    written =
        writeUtf8Any(writer, anyInfo.writeMap(value), anyInfo.valueTypeInfo.utf8Writer(), written);
    for (int i = anyIndex; i < fields.length; i++) {
      if (fields[i].writeUtf8(writer, value, written)) {
        written++;
      }
    }
  }

  private static boolean validateMemberAnnotations(
      Class<?> type, boolean propertyDiscoveryEnabled, boolean record) {
    boolean hasAnyField = false;
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
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
    return hasAnyField;
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

  private static Object[] recordFieldDefaults(
      Class<?> type, String[] readJavaNames, RecordInfo recordInfo) {
    Object[] defaults = new Object[readJavaNames.length];
    Object[] componentDefaults = recordInfo.getRecordComponentsDefaultValues();
    Map<String, Integer> componentIndexes = RecordUtils.buildFieldToComponentMapping(type);
    for (int i = 0; i < readJavaNames.length; i++) {
      Integer componentIndex = componentIndexes.get(readJavaNames[i]);
      defaults[i] = componentIndex == null ? null : componentDefaults[componentIndex.intValue()];
    }
    return defaults;
  }

  @Internal
  public static final class AnyInfo {
    private final Field writeField;
    private final Method writeGetter;
    private final Field readField;
    private final Method readSetter;
    private final Class<?> setterValueRawType;
    private final JsonFieldAccessor writeAccessor;
    private final JsonFieldAccessor readAccessor;
    private final MethodHandle setterHandle;
    private final Type mapType;
    private final Class<?> mapRawType;
    private final Type valueType;
    private final Class<?> valueRawType;
    private final int writeIndex;
    private final int constructionIndex;
    private JsonTypeInfo valueTypeInfo;
    private MapCodec<?> mapCodec;

    private AnyInfo(
        Field writeField,
        Method writeGetter,
        Field readField,
        Method readSetter,
        Type mapType,
        Class<?> mapRawType,
        Type valueType,
        Class<?> valueRawType,
        int writeIndex,
        int constructionIndex) {
      this.writeField = writeField;
      this.writeGetter = writeGetter;
      this.readField = readField;
      this.readSetter = readSetter;
      setterValueRawType = readSetter == null ? null : readSetter.getParameterTypes()[1];
      writeAccessor =
          writeGetter != null
              ? JsonFieldAccessor.forGetter(writeGetter)
              : writeField == null ? null : JsonFieldAccessor.forField(writeField);
      readAccessor = readField == null ? null : JsonFieldAccessor.forField(readField);
      setterHandle =
          readSetter == null || AndroidSupport.IS_ANDROID ? null : methodHandle(readSetter);
      if (readSetter != null && AndroidSupport.IS_ANDROID) {
        readSetter.setAccessible(true);
      }
      this.mapType = mapType;
      this.mapRawType = mapRawType;
      this.valueType = valueType;
      this.valueRawType = valueRawType;
      this.writeIndex = writeIndex;
      this.constructionIndex = constructionIndex;
    }

    private static AnyInfo build(
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
      if (anyField != null || anyGetter != null) {
        Type declaredMapType =
            anyGetter == null ? anyField.getGenericType() : anyGetter.getGenericReturnType();
        mapType = ownerType.resolveType(declaredMapType).getType();
        mapRawType = CodecUtils.rawType(mapType, null);
        valueType = anyMapValueType(mapType, mapRawType, anyField != null ? anyField : anyGetter);
        valueRawType = CodecUtils.rawType(valueType, Object.class);
        validateAnyLogicalTypes(ownerType, builder, mapType);
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
          writeIndex,
          constructionIndex);
    }

    private void resolveTypes(JsonTypeResolver resolver) {
      valueTypeInfo = resolver.getTypeInfo(valueType, valueRawType);
      if (readField != null) {
        mapCodec = MapCodec.create(mapRawType, TypeRef.of(mapType), resolver);
      }
    }

    public Field writeField() {
      return writeField;
    }

    public Method writeGetter() {
      return writeGetter;
    }

    public Field readField() {
      return readField;
    }

    public Method readSetter() {
      return readSetter;
    }

    public Class<?> valueRawType() {
      return valueRawType;
    }

    public JsonTypeInfo valueTypeInfo() {
      return valueTypeInfo;
    }

    public int writeIndex() {
      return writeIndex;
    }

    public int constructionIndex() {
      return constructionIndex;
    }

    private boolean writeEnabled() {
      return writeAccessor != null;
    }

    private boolean readEnabled() {
      return readAccessor != null || readSetter != null;
    }

    private boolean fieldRead() {
      return readField != null;
    }

    private boolean finalReadField() {
      return readField != null && Modifier.isFinal(readField.getModifiers());
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> writeMap(Object target) {
      try {
        return (Map<?, ?>) writeAccessor.getObject(target);
      } catch (ForyJsonException e) {
        if (e.getCause() instanceof Error) {
          throw (Error) e.getCause();
        }
        throw e;
      }
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> readMap(Object target) {
      return (Map<Object, Object>) readAccessor.getObject(target);
    }

    private void setReadMap(Object target, Map<?, ?> map) {
      readAccessor.putObject(target, map);
    }

    private void put(Object target, String name, Object value) {
      if (value == null && setterValueRawType.isPrimitive()) {
        throw new ForyJsonException(
            "Cannot read null into primitive @JsonAnySetter parameter " + setterValueRawType);
      }
      try {
        if (AndroidSupport.IS_ANDROID) {
          readSetter.invoke(target, name, value);
        } else {
          setterHandle.invoke(target, name, value);
        }
      } catch (Throwable e) {
        Throwable cause =
            e instanceof InvocationTargetException ? ((InvocationTargetException) e).getCause() : e;
        if (cause instanceof Error) {
          throw (Error) cause;
        }
        throw new ForyJsonException("Cannot invoke @JsonAnySetter " + readSetter, cause);
      }
    }

    private static Type anyMapValueType(
        Type mapType, Class<?> mapRawType, AnnotatedElement source) {
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
        validateAnyLogicalType(
            ownerType, builder.field.getGenericType(), anyMapType, builder.field);
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

    private static MethodHandle methodHandle(Method method) {
      try {
        return _JDKAccess._trustedLookup(method.getDeclaringClass()).unreflect(method);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot access @JsonAnySetter " + method, e);
      }
    }
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
          || explicitInclude != JsonProperty.Include.DEFAULT;
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
          ownerType);
    }

    private void mergeAnnotation(Class<?> type, AnnotatedElement source) {
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

  /** Owns one parameterized POJO binding whose child types differ from the raw-class binding. */
  private static final class ParameterizedObjectCodec<T> extends ObjectCodec<T> {
    private ParameterizedObjectCodec(
        Class<?> type,
        JsonFieldInfo[] writeFields,
        JsonFieldInfo[] readFields,
        String[] readJavaNames,
        JsonCreatorInfo creatorInfo,
        AnyInfo anyInfo,
        String[] skippedNames,
        ObjectInstantiator<?> instantiator) {
      super(
          type,
          writeFields,
          readFields,
          readJavaNames,
          creatorInfo,
          anyInfo,
          skippedNames,
          instantiator);
    }

    @Override
    public void writeString(StringJsonWriter writer, T value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writeStringObject(writer, value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, T value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writeUtf8Object(writer, value);
      }
    }

    @Override
    public T readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : readLatin1Object(reader);
    }

    @Override
    public T readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : readUtf16Object(reader);
    }

    @Override
    public T readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : readUtf8Object(reader);
    }
  }
}
