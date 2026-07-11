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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.annotation.Expose;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldTable;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.ObjectInstantiator;
import org.apache.fory.reflect.ObjectInstantiators;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.util.record.RecordInfo;
import org.apache.fory.util.record.RecordUtils;

public class ObjectCodec<T> implements JsonCodec<T> {
  protected final Class<?> type;
  protected final JsonFieldInfo[] writeFields;
  protected final JsonFieldInfo[] readFields;
  protected final JsonFieldTable readTable;
  protected final ObjectInstantiator<?> instantiator;
  protected final boolean record;
  private final RecordInfo recordInfo;
  private final Object[] recordFieldDefaults;

  private ObjectCodec(
      Class<?> type,
      JsonFieldInfo[] writeFields,
      JsonFieldInfo[] readFields,
      ObjectInstantiator<?> instantiator) {
    this.type = type;
    this.writeFields = writeFields;
    this.readFields = readFields;
    readTable = new JsonFieldTable(readFields);
    this.instantiator = instantiator;
    record = RecordUtils.isRecord(type);
    if (record) {
      List<String> fieldNames = new ArrayList<>(readFields.length);
      for (JsonFieldInfo field : readFields) {
        fieldNames.add(field.name());
      }
      recordInfo = new RecordInfo(type, fieldNames);
      recordFieldDefaults = recordFieldDefaults(type, readFields, recordInfo);
    } else {
      recordInfo = null;
      recordFieldDefaults = null;
    }
  }

  public static <T> ObjectCodec<T> build(TypeRef<T> ownerType, boolean propertyDiscoveryEnabled) {
    Class<?> type = ownerType.getRawType();
    if (type.isInterface()
        || Modifier.isAbstract(type.getModifiers())
        || type.isPrimitive()
        || type.isArray()
        || type.isEnum()) {
      throw new ForyJsonException("Unsupported JSON object type " + type);
    }
    boolean record = RecordUtils.isRecord(type);
    boolean writeExpose = hasWriteExpose(type);
    boolean readExpose = hasReadExpose(type, record);
    LinkedHashMap<String, FieldBuilder> builders = new LinkedHashMap<>();
    addFields(type, record, writeExpose, readExpose, propertyDiscoveryEnabled, builders);
    if (propertyDiscoveryEnabled && !record) {
      addAccessors(type, writeExpose, readExpose, builders);
    }
    List<JsonFieldInfo> writes = new ArrayList<>();
    List<JsonFieldInfo> reads = new ArrayList<>();
    for (FieldBuilder builder : builders.values()) {
      if (!builder.hasWriteSource() && !builder.hasReadSink()) {
        continue;
      }
      JsonFieldInfo field = builder.build(record, ownerType);
      if (builder.hasWriteSource()) {
        writes.add(field);
      }
      if (builder.hasReadSink()) {
        reads.add(field);
      }
    }
    JsonFieldInfo[] writeArray = writes.toArray(new JsonFieldInfo[0]);
    JsonFieldInfo[] readArray = reads.toArray(new JsonFieldInfo[0]);
    for (int i = 0; i < readArray.length; i++) {
      readArray[i].setReadIndex(i);
    }
    ObjectInstantiator<?> instantiator = ObjectInstantiators.createObjectInstantiator(type);
    if (ownerType.getType() instanceof Class) {
      return new ObjectCodec<>(type, writeArray, readArray, instantiator);
    }
    return new ParameterizedObjectCodec<>(type, writeArray, readArray, instantiator);
  }

  private static void addFields(
      Class<?> type,
      boolean record,
      boolean writeExpose,
      boolean readExpose,
      boolean propertyDiscoveryEnabled,
      LinkedHashMap<String, FieldBuilder> builders) {
    List<Class<?>> hierarchy = new ArrayList<>();
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      hierarchy.add(current);
    }
    for (int i = hierarchy.size() - 1; i >= 0; i--) {
      Class<?> current = hierarchy.get(i);
      for (Field field : current.getDeclaredFields()) {
        int modifiers = field.getModifiers();
        if (!isEligibleField(field)) {
          continue;
        }
        boolean write = includeWrite(field, writeExpose);
        boolean readAllowed = includeRead(field, readExpose);
        boolean read = (record || !Modifier.isFinal(modifiers)) && readAllowed;
        if (!propertyDiscoveryEnabled && !write && !read) {
          continue;
        }
        FieldBuilder builder =
            builders.computeIfAbsent(field.getName(), name -> new FieldBuilder(name));
        builder.setField(field, write, read, write, readAllowed);
      }
    }
  }

  private static void addAccessors(
      Class<?> type,
      boolean writeExpose,
      boolean readExpose,
      LinkedHashMap<String, FieldBuilder> builders) {
    for (Method method : type.getMethods()) {
      if (!isEligibleAccessor(method)) {
        continue;
      }
      String propertyName = getterPropertyName(method);
      if (propertyName != null) {
        FieldBuilder builder = builders.get(propertyName);
        if (builder == null) {
          if (writeExpose) {
            continue;
          }
          builder = new FieldBuilder(propertyName);
          builders.put(propertyName, builder);
        }
        builder.setWriteGetter(method, writeExpose);
        continue;
      }
      propertyName = setterPropertyName(method);
      if (propertyName != null) {
        FieldBuilder builder = builders.get(propertyName);
        if (builder == null) {
          if (readExpose) {
            continue;
          }
          builder = new FieldBuilder(propertyName);
          builders.put(propertyName, builder);
        }
        builder.setReadSetter(method, readExpose);
      }
    }
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
    return record;
  }

  public final void resolveTypes(JsonTypeResolver typeResolver) {
    for (JsonFieldInfo field : writeFields) {
      field.resolveTypes(typeResolver);
    }
    for (JsonFieldInfo field : readFields) {
      field.resolveTypes(typeResolver);
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
    reader.enterDepth();
    if (record) {
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
    reader.enterDepth();
    if (record) {
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
    reader.enterDepth();
    if (record) {
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

  final void writeStringObject(StringJsonWriter writer, Object value) {
    writer.writeObjectStart();
    int written = 0;
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
    writer.writeObjectEnd();
  }

  final void writeUtf8Object(Utf8JsonWriter writer, Object value) {
    writer.writeObjectStart();
    int written = 0;
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
    writer.writeObjectEnd();
  }

  private static boolean hasWriteExpose(Class<?> type) {
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        rejectClassExpose(field);
        if (isEligibleField(field) && field.isAnnotationPresent(Expose.class)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasReadExpose(Class<?> type, boolean record) {
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        rejectClassExpose(field);
        if (isEligibleField(field)
            && (record || !Modifier.isFinal(field.getModifiers()))
            && field.isAnnotationPresent(Expose.class)) {
          return true;
        }
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

  private static void rejectClassExpose(Field field) {
    if (field.getType() == Class.class && field.isAnnotationPresent(Expose.class)) {
      // Class fields are never JSON data fields, so @Expose on them cannot define an allowlist.
      throw new ForyJsonException("@Expose is not supported on JSON Class field: " + field);
    }
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

  private static boolean includeWrite(Field field, boolean exposeMode) {
    return include(field, exposeMode, true);
  }

  private static boolean includeRead(Field field, boolean exposeMode) {
    return include(field, exposeMode, false);
  }

  private static boolean include(Field field, boolean exposeMode, boolean write) {
    JsonIgnore ignore = field.getAnnotation(JsonIgnore.class);
    boolean ignored = ignore != null && (write ? ignore.ignoreWrite() : ignore.ignoreRead());
    boolean exposed = field.isAnnotationPresent(Expose.class);
    if (ignored && exposed) {
      throw new ForyJsonException("JSON field cannot be both exposed and ignored: " + field);
    }
    if (ignored) {
      return false;
    }
    return !exposeMode || exposed;
  }

  private static Object[] recordFieldDefaults(
      Class<?> type, JsonFieldInfo[] readFields, RecordInfo recordInfo) {
    Object[] defaults = new Object[readFields.length];
    Object[] componentDefaults = recordInfo.getRecordComponentsDefaultValues();
    Map<String, Integer> componentIndexes = RecordUtils.buildFieldToComponentMapping(type);
    for (int i = 0; i < readFields.length; i++) {
      Integer componentIndex = componentIndexes.get(readFields[i].name());
      defaults[i] = componentIndex == null ? null : componentDefaults[componentIndex.intValue()];
    }
    return defaults;
  }

  private static final class FieldBuilder {
    private final String name;
    private Field field;
    private boolean fieldWriteAllowed;
    private boolean fieldReadAllowed;
    private Field writeField;
    private Field readField;
    private Method writeGetter;
    private Method readSetter;
    private JsonFieldAccessor writeAccessor;
    private JsonFieldAccessor readAccessor;

    private FieldBuilder(String name) {
      this.name = name;
    }

    private void setField(
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
    }

    private void setWriteGetter(Method getter, boolean exposeMode) {
      if (!methodAllowed(exposeMode, fieldWriteAllowed)) {
        return;
      }
      if (writeGetter != null) {
        throw new ForyJsonException("Duplicate JSON getter for property " + name);
      }
      writeGetter = getter;
      writeField = null;
    }

    private void setReadSetter(Method setter, boolean exposeMode) {
      if (!methodAllowed(exposeMode, fieldReadAllowed)) {
        return;
      }
      if (readSetter != null) {
        throw new ForyJsonException("Duplicate JSON setter for property " + name);
      }
      readSetter = setter;
      readField = null;
    }

    private boolean hasWriteSource() {
      return writeGetter != null || writeField != null;
    }

    private boolean hasReadSink() {
      return readSetter != null || readField != null;
    }

    private JsonFieldInfo build(boolean record, TypeRef<?> ownerType) {
      validateTypes();
      writeAccessor =
          writeGetter != null
              ? JsonFieldAccessor.forGetter(writeGetter)
              : (writeField == null ? null : JsonFieldAccessor.forField(writeField));
      readAccessor =
          readSetter != null
              ? JsonFieldAccessor.forSetter(readSetter)
              : (readField == null || record ? null : JsonFieldAccessor.forField(readField));
      return new JsonFieldInfo(
          name,
          writeField,
          writeGetter,
          readField,
          readSetter,
          writeAccessor,
          readAccessor,
          ownerType);
    }

    private boolean methodAllowed(boolean exposeMode, boolean fieldAllowed) {
      // A same-named field owns @Expose/@JsonIgnore direction decisions for the JSON property.
      return field == null ? !exposeMode : fieldAllowed;
    }

    private void validateTypes() {
      Type writeType =
          writeGetter == null ? fieldType(writeField) : writeGetter.getGenericReturnType();
      Type readType =
          readSetter == null ? fieldType(readField) : readSetter.getGenericParameterTypes()[0];
      if (writeType != null && readType != null && !writeType.equals(readType)) {
        throw new ForyJsonException(
            "Conflicting JSON property types for " + name + ": " + writeType + " and " + readType);
      }
    }

    private static Type fieldType(Field field) {
      return field == null ? null : field.getGenericType();
    }
  }

  /** Owns one parameterized POJO binding whose child types differ from the raw-class binding. */
  private static final class ParameterizedObjectCodec<T> extends ObjectCodec<T> {
    private ParameterizedObjectCodec(
        Class<?> type,
        JsonFieldInfo[] writeFields,
        JsonFieldInfo[] readFields,
        ObjectInstantiator<?> instantiator) {
      super(type, writeFields, readFields, instantiator);
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
