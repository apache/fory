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

package org.apache.fory.json.meta;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.JsonStringEscaper;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.memory.NativeByteOrder;
import org.apache.fory.reflect.TypeRef;

/**
 * Immutable member metadata shared by interpreted and generated object codecs.
 *
 * <p>One instance describes the independently resolved write source and read sink for a JSON
 * member: reflected field or accessor, declared and raw type, semantic kind, and interpreted
 * accessor. Construction also precomputes escaped field-name prefixes, enum tokens, boolean tokens,
 * and the field-name hash for all concrete reader and writer representations. This moves encoding
 * and classification out of per-object and per-field hot paths.
 *
 * <p>{@link #resolveTypes(JsonTypeResolver)} installs the resolved read and write {@link
 * JsonTypeInfo} bindings after recursive object metadata has been published. Those bindings are the
 * only mutable lifecycle phase; generated instances capture the current concrete child capability
 * under the resolver-local JIT lock and receive later child replacements through resolver
 * callbacks.
 */
public final class JsonFieldInfo {
  private static final int KIND_BOOLEAN = 1;
  private static final int KIND_BYTE = 2;
  private static final int KIND_SHORT = 3;
  private static final int KIND_INT = 4;
  private static final int KIND_LONG = 5;
  private static final int KIND_FLOAT = 6;
  private static final int KIND_DOUBLE = 7;
  private static final int KIND_CHAR = 8;
  private static final int KIND_STRING = 9;
  private static final int KIND_ENUM = 10;
  private static final int KIND_ARRAY = 11;
  private static final int KIND_COLLECTION = 12;
  private static final int KIND_MAP = 13;
  private static final int KIND_OBJECT = 14;
  private static final int KIND_CUSTOM_PRIMITIVE = 15;
  private static final int KIND_RAW_STRING = 16;
  private static final int WRITE_NULL_MASK = Integer.MIN_VALUE;
  private static final byte[] TRUE_BYTES = "true".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] FALSE_BYTES = "false".getBytes(StandardCharsets.ISO_8859_1);

  private final String name;
  private final Field writeField;
  private final Method writeGetter;
  private final Field readField;
  private final Method readSetter;
  private final Type writeType;
  private final Class<?> writeRawType;
  private final Type readType;
  private final Class<?> readRawType;
  private final JsonCodec codecAnnotation;
  private final Class<? extends JsonValueCodec<?>> valueCodecClass;
  private JsonFieldKind writeKind;
  private JsonFieldKind readKind;
  private int writeKindId;
  private int readPrimitiveKindId;
  private final JsonFieldAccessor writeAccessor;
  private final JsonFieldAccessor readAccessor;
  private final Type writeMapValueType;
  private final Class<?> writeArrayComponentType;
  private final Class<?> writeElementRawType;
  private final byte[] stringNamePrefix;
  private final byte[] stringCommaNamePrefix;
  private final byte[] stringUtf16NamePrefix;
  private final byte[] stringUtf16CommaNamePrefix;
  private final byte[] utf8NamePrefix;
  private final byte[] utf8CommaNamePrefix;
  private final long utf8NamePrefixWord0;
  private final long utf8NamePrefixWord1;
  private final long utf8CommaNamePrefixWord0;
  private final long utf8CommaNamePrefixWord1;
  private final byte[][] stringEnumValues;
  private final byte[][] stringElementEnumValues;
  private final byte[][] stringEnumNameValues;
  private final byte[][] stringEnumCommaValues;
  private final byte[][] stringUtf16EnumNameValues;
  private final byte[][] stringUtf16EnumCommaValues;
  private final byte[][] utf8EnumValues;
  private final byte[][] utf8ElementEnumValues;
  private final byte[][] utf8EnumNameValues;
  private final byte[][] utf8EnumCommaValues;
  private final byte[] stringTrueNameToken;
  private final byte[] stringTrueCommaToken;
  private final byte[] stringFalseNameToken;
  private final byte[] stringFalseCommaToken;
  private final byte[] stringUtf16TrueNameToken;
  private final byte[] stringUtf16TrueCommaToken;
  private final byte[] stringUtf16FalseNameToken;
  private final byte[] stringUtf16FalseCommaToken;
  private final byte[] utf8TrueNameToken;
  private final byte[] utf8TrueCommaToken;
  private final byte[] utf8FalseNameToken;
  private final byte[] utf8FalseCommaToken;
  private final long nameHash;
  private int readIndexAndWriteNull;
  private JsonTypeInfo writeTypeInfo;
  private JsonTypeInfo readTypeInfo;

  public JsonFieldInfo(
      String name,
      boolean writeNull,
      Field writeField,
      Method writeGetter,
      Field readField,
      Method readSetter,
      JsonFieldAccessor writeAccessor,
      JsonFieldAccessor readAccessor,
      TypeRef<?> ownerType,
      JsonCodec codecAnnotation,
      Class<? extends JsonValueCodec<?>> valueCodecClass,
      boolean rawValue) {
    this.name = name;
    // The write-null decision and read index are both immutable after ObjectCodec construction.
    // Packing the flag into the unused sign bit avoids a separate state field. Read schemas are
    // bounded by Java array indexes, so the remaining 31 bits cover every representable property
    // table.
    readIndexAndWriteNull = writeNull ? WRITE_NULL_MASK : 0;
    nameHash = JsonFieldNameHash.hash(name);
    this.writeField = writeField;
    this.writeGetter = writeGetter;
    this.readField = readField;
    this.readSetter = readSetter;
    Class<?> writeFallback = writeRawType(writeField, writeGetter);
    Class<?> readFallback = readRawType(readField, readSetter);
    this.writeType = resolveType(ownerType, writeType(writeField, writeGetter));
    this.writeRawType = semanticRawType(writeType, writeFallback);
    this.readType = resolveType(ownerType, readType(readField, readSetter));
    this.readRawType = semanticRawType(readType, readFallback);
    this.codecAnnotation = codecAnnotation;
    this.valueCodecClass = valueCodecClass;
    this.writeAccessor = writeAccessor;
    this.readAccessor = readAccessor;
    writeKind = writeRawType == null ? null : kind(writeRawType);
    readKind = readRawType == null ? null : kind(readRawType);
    writeKindId = writeKind == null ? 0 : (rawValue ? KIND_RAW_STRING : kindId(writeKind));
    readPrimitiveKindId = primitiveKindId(readRawType, readKind);
    Type writeElementType =
        writeKind == JsonFieldKind.COLLECTION ? CodecUtils.elementType(writeType) : null;
    writeMapValueType = writeKind == JsonFieldKind.MAP ? CodecUtils.mapValueType(writeType) : null;
    writeArrayComponentType =
        writeKind == JsonFieldKind.ARRAY ? writeRawType.getComponentType() : null;
    writeElementRawType = writeElementType == null ? null : knownRawType(writeElementType);
    String stringPrefix = JsonStringEscaper.escapedNamePrefix(name, true);
    String utf8Prefix = JsonStringEscaper.escapedNamePrefix(name, false);
    stringNamePrefix = stringPrefix.getBytes(StandardCharsets.ISO_8859_1);
    stringCommaNamePrefix = ("," + stringPrefix).getBytes(StandardCharsets.ISO_8859_1);
    stringUtf16NamePrefix = toUtf16Bytes(stringNamePrefix);
    stringUtf16CommaNamePrefix = toUtf16Bytes(stringCommaNamePrefix);
    utf8NamePrefix = utf8Prefix.getBytes(StandardCharsets.UTF_8);
    utf8CommaNamePrefix = ("," + utf8Prefix).getBytes(StandardCharsets.UTF_8);
    utf8NamePrefixWord0 = packedPrefixWord(utf8NamePrefix, 0);
    utf8NamePrefixWord1 = packedPrefixWord(utf8NamePrefix, Long.BYTES);
    utf8CommaNamePrefixWord0 = packedPrefixWord(utf8CommaNamePrefix, 0);
    utf8CommaNamePrefixWord1 = packedPrefixWord(utf8CommaNamePrefix, Long.BYTES);
    stringEnumValues = writeKind == JsonFieldKind.ENUM ? stringEnumValues(writeRawType) : null;
    stringEnumNameValues =
        writeKind == JsonFieldKind.ENUM ? fieldValues(stringNamePrefix, stringEnumValues) : null;
    stringEnumCommaValues =
        writeKind == JsonFieldKind.ENUM
            ? fieldValues(stringCommaNamePrefix, stringEnumValues)
            : null;
    stringUtf16EnumNameValues =
        writeKind == JsonFieldKind.ENUM
            ? utf16FieldValues(stringUtf16NamePrefix, stringEnumValues)
            : null;
    stringUtf16EnumCommaValues =
        writeKind == JsonFieldKind.ENUM
            ? utf16FieldValues(stringUtf16CommaNamePrefix, stringEnumValues)
            : null;
    stringElementEnumValues =
        writeElementRawType != null && writeElementRawType.isEnum()
            ? stringEnumValues(writeElementRawType)
            : null;
    utf8EnumValues = writeKind == JsonFieldKind.ENUM ? enumValues(writeRawType) : null;
    utf8EnumNameValues =
        writeKind == JsonFieldKind.ENUM ? fieldValues(utf8NamePrefix, utf8EnumValues) : null;
    utf8EnumCommaValues =
        writeKind == JsonFieldKind.ENUM ? fieldValues(utf8CommaNamePrefix, utf8EnumValues) : null;
    utf8ElementEnumValues =
        writeElementRawType != null && writeElementRawType.isEnum()
            ? enumValues(writeElementRawType)
            : null;
    if (writeKind == JsonFieldKind.BOOLEAN) {
      stringTrueNameToken = join(stringNamePrefix, TRUE_BYTES);
      stringTrueCommaToken = join(stringCommaNamePrefix, TRUE_BYTES);
      stringFalseNameToken = join(stringNamePrefix, FALSE_BYTES);
      stringFalseCommaToken = join(stringCommaNamePrefix, FALSE_BYTES);
      stringUtf16TrueNameToken = join(stringUtf16NamePrefix, toUtf16Bytes(TRUE_BYTES));
      stringUtf16TrueCommaToken = join(stringUtf16CommaNamePrefix, toUtf16Bytes(TRUE_BYTES));
      stringUtf16FalseNameToken = join(stringUtf16NamePrefix, toUtf16Bytes(FALSE_BYTES));
      stringUtf16FalseCommaToken = join(stringUtf16CommaNamePrefix, toUtf16Bytes(FALSE_BYTES));
      utf8TrueNameToken = join(utf8NamePrefix, TRUE_BYTES);
      utf8TrueCommaToken = join(utf8CommaNamePrefix, TRUE_BYTES);
      utf8FalseNameToken = join(utf8NamePrefix, FALSE_BYTES);
      utf8FalseCommaToken = join(utf8CommaNamePrefix, FALSE_BYTES);
    } else {
      stringTrueNameToken = null;
      stringTrueCommaToken = null;
      stringFalseNameToken = null;
      stringFalseCommaToken = null;
      stringUtf16TrueNameToken = null;
      stringUtf16TrueCommaToken = null;
      stringUtf16FalseNameToken = null;
      stringUtf16FalseCommaToken = null;
      utf8TrueNameToken = null;
      utf8TrueCommaToken = null;
      utf8FalseNameToken = null;
      utf8FalseCommaToken = null;
    }
  }

  public String name() {
    return name;
  }

  /** Returns parent-local metadata with a transformed JSON name and the same Java member owner. */
  public JsonFieldInfo withName(String transformedName, TypeRef<?> ownerType) {
    JsonFieldInfo copy =
        new JsonFieldInfo(
            transformedName,
            writeNull(),
            writeField,
            writeGetter,
            readField,
            readSetter,
            writeAccessor,
            readAccessor,
            ownerType,
            codecAnnotation,
            valueCodecClass,
            writesRawString());
    copy.setReadIndex(readIndex());
    return copy;
  }

  public long nameHash() {
    return nameHash;
  }

  /** Returns the normalized null-write decision consumed by interpreted and generated writers. */
  public boolean writeNull() {
    return readIndexAndWriteNull < 0;
  }

  public Field writeField() {
    return writeField;
  }

  public Method writeGetter() {
    return writeGetter;
  }

  public Type writeType() {
    return writeType;
  }

  private static Type writeType(Field field, Method getter) {
    return getter == null ? fieldType(field) : getter.getGenericReturnType();
  }

  public Class<?> writeRawType() {
    return writeRawType;
  }

  private static Class<?> writeRawType(Field field, Method getter) {
    return getter == null ? fieldRawType(field) : getter.getReturnType();
  }

  public JsonFieldKind writeKind() {
    return writeKind;
  }

  /** Returns whether the write source is emitted as trusted raw JSON text. */
  public boolean writesRawString() {
    return writeKindId == KIND_RAW_STRING;
  }

  public JsonFieldAccessor writeAccessor() {
    return writeAccessor;
  }

  public Class<?> writeElementRawType() {
    return writeElementRawType;
  }

  public Type writeMapValueType() {
    return writeMapValueType;
  }

  public Class<?> writeArrayComponentType() {
    return writeArrayComponentType;
  }

  public Type readType() {
    return readType;
  }

  private static Type readType(Field field, Method setter) {
    return setter == null ? fieldType(field) : setter.getGenericParameterTypes()[0];
  }

  public Field readField() {
    return readField;
  }

  public Method readSetter() {
    return readSetter;
  }

  public Class<?> readRawType() {
    return readRawType;
  }

  private static Class<?> readRawType(Field field, Method setter) {
    return setter == null ? fieldRawType(field) : setter.getParameterTypes()[0];
  }

  public JsonFieldKind readKind() {
    return readKind;
  }

  public JsonFieldAccessor readAccessor() {
    return readAccessor;
  }

  private static Type fieldType(Field field) {
    return field == null ? null : field.getGenericType();
  }

  private static Class<?> fieldRawType(Field field) {
    return field == null ? null : field.getType();
  }

  private static Type resolveType(TypeRef<?> ownerType, Type type) {
    return type == null ? null : ownerType.resolveType(type).getType();
  }

  private static Class<?> semanticRawType(Type type, Class<?> fallback) {
    return type == null ? null : CodecUtils.rawType(type, fallback);
  }

  public void resolveTypes(JsonTypeResolver typeResolver) {
    Type codecType = writeType == null ? readType : writeType;
    Class<?> codecRawType = writeRawType == null ? readRawType : writeRawType;
    JsonTypeInfo resolvedTypeInfo =
        codecAnnotation != null
            ? typeResolver.getTypeInfo(codecType, codecRawType, codecAnnotation)
            : valueCodecClass != null
                ? typeResolver.getTypeInfo(codecType, codecRawType, valueCodecClass)
                : null;
    boolean rawString = writeKindId == KIND_RAW_STRING;
    if (writeRawType != null) {
      writeTypeInfo =
          resolvedTypeInfo == null
              ? typeResolver.getTypeInfo(writeType, writeRawType)
              : resolvedTypeInfo;
      if (!rawString) {
        writeKind = writeTypeInfo.kind();
        writeKindId = kindId(writeKind);
      }
    }
    if (readRawType != null) {
      readTypeInfo =
          resolvedTypeInfo == null
              ? typeResolver.getTypeInfo(readType, readRawType)
              : resolvedTypeInfo;
      readKind = readTypeInfo.kind();
      readPrimitiveKindId = primitiveKindId(readRawType, readKind);
    }
  }

  public void readLatin1(Latin1JsonReader reader, Object object) {
    switch (readPrimitiveKindId) {
      case KIND_BOOLEAN:
        rejectPrimitiveNull(reader);
        readAccessor.putBoolean(object, reader.readBoolean());
        return;
      case KIND_BYTE:
        rejectPrimitiveNull(reader);
        readAccessor.putByte(object, checkedByte(reader.readInt()));
        return;
      case KIND_SHORT:
        rejectPrimitiveNull(reader);
        readAccessor.putShort(object, checkedShort(reader.readInt()));
        return;
      case KIND_INT:
        rejectPrimitiveNull(reader);
        readAccessor.putInt(object, reader.readInt());
        return;
      case KIND_LONG:
        rejectPrimitiveNull(reader);
        readAccessor.putLong(object, reader.readLong());
        return;
      case KIND_FLOAT:
        rejectPrimitiveNull(reader);
        readAccessor.putFloat(object, reader.readFloat());
        return;
      case KIND_DOUBLE:
        rejectPrimitiveNull(reader);
        readAccessor.putDouble(object, reader.readDouble());
        return;
      case KIND_CHAR:
        rejectPrimitiveNull(reader);
        readAccessor.putChar(object, reader.readChar());
        return;
      case KIND_CUSTOM_PRIMITIVE:
        readAccessor.putObject(
            object, requirePrimitive(readTypeInfo.latin1Reader().readLatin1(reader)));
        return;
      default:
        readAccessor.putObject(object, readTypeInfo.latin1Reader().readLatin1(reader));
    }
  }

  public Object readLatin1Value(Latin1JsonReader reader) {
    Object value = readTypeInfo.latin1Reader().readLatin1(reader);
    return readPrimitiveKindId == KIND_CUSTOM_PRIMITIVE ? requirePrimitive(value) : value;
  }

  public void readUtf16(Utf16JsonReader reader, Object object) {
    switch (readPrimitiveKindId) {
      case KIND_BOOLEAN:
        rejectPrimitiveNull(reader);
        readAccessor.putBoolean(object, reader.readBoolean());
        return;
      case KIND_BYTE:
        rejectPrimitiveNull(reader);
        readAccessor.putByte(object, checkedByte(reader.readInt()));
        return;
      case KIND_SHORT:
        rejectPrimitiveNull(reader);
        readAccessor.putShort(object, checkedShort(reader.readInt()));
        return;
      case KIND_INT:
        rejectPrimitiveNull(reader);
        readAccessor.putInt(object, reader.readInt());
        return;
      case KIND_LONG:
        rejectPrimitiveNull(reader);
        readAccessor.putLong(object, reader.readLong());
        return;
      case KIND_FLOAT:
        rejectPrimitiveNull(reader);
        readAccessor.putFloat(object, reader.readFloat());
        return;
      case KIND_DOUBLE:
        rejectPrimitiveNull(reader);
        readAccessor.putDouble(object, reader.readDouble());
        return;
      case KIND_CHAR:
        rejectPrimitiveNull(reader);
        readAccessor.putChar(object, reader.readChar());
        return;
      case KIND_CUSTOM_PRIMITIVE:
        readAccessor.putObject(
            object, requirePrimitive(readTypeInfo.utf16Reader().readUtf16(reader)));
        return;
      default:
        readAccessor.putObject(object, readTypeInfo.utf16Reader().readUtf16(reader));
    }
  }

  public Object readUtf16Value(Utf16JsonReader reader) {
    Object value = readTypeInfo.utf16Reader().readUtf16(reader);
    return readPrimitiveKindId == KIND_CUSTOM_PRIMITIVE ? requirePrimitive(value) : value;
  }

  public void readUtf8(Utf8JsonReader reader, Object object) {
    switch (readPrimitiveKindId) {
      case KIND_BOOLEAN:
        rejectPrimitiveNull(reader);
        readAccessor.putBoolean(object, reader.readBoolean());
        return;
      case KIND_BYTE:
        rejectPrimitiveNull(reader);
        readAccessor.putByte(object, checkedByte(reader.readInt()));
        return;
      case KIND_SHORT:
        rejectPrimitiveNull(reader);
        readAccessor.putShort(object, checkedShort(reader.readInt()));
        return;
      case KIND_INT:
        rejectPrimitiveNull(reader);
        readAccessor.putInt(object, reader.readInt());
        return;
      case KIND_LONG:
        rejectPrimitiveNull(reader);
        readAccessor.putLong(object, reader.readLong());
        return;
      case KIND_FLOAT:
        rejectPrimitiveNull(reader);
        readAccessor.putFloat(object, reader.readFloat());
        return;
      case KIND_DOUBLE:
        rejectPrimitiveNull(reader);
        readAccessor.putDouble(object, reader.readDouble());
        return;
      case KIND_CHAR:
        rejectPrimitiveNull(reader);
        readAccessor.putChar(object, reader.readChar());
        return;
      case KIND_CUSTOM_PRIMITIVE:
        readAccessor.putObject(
            object, requirePrimitive(readTypeInfo.utf8Reader().readUtf8(reader)));
        return;
      default:
        readAccessor.putObject(object, readTypeInfo.utf8Reader().readUtf8(reader));
    }
  }

  public Object readUtf8Value(Utf8JsonReader reader) {
    Object value = readTypeInfo.utf8Reader().readUtf8(reader);
    return readPrimitiveKindId == KIND_CUSTOM_PRIMITIVE ? requirePrimitive(value) : value;
  }

  // A custom codec may return null, but primitive storage has no nullable representation. Keep
  // this check at the field owner; built-in primitive fast paths never call it.
  public Object requirePrimitive(Object value) {
    if (value == null) {
      throw primitiveNull();
    }
    return value;
  }

  private void rejectPrimitiveNull(JsonReader reader) {
    if (reader.peekNull()) {
      reader.readNull();
      throw primitiveNull();
    }
  }

  private ForyJsonException primitiveNull() {
    return new ForyJsonException("Cannot read null into primitive " + readRawType);
  }

  private static short checkedShort(int value) {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw new ForyJsonException("Short overflow");
    }
    return (short) value;
  }

  private static byte checkedByte(int value) {
    if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
      throw new ForyJsonException("Byte overflow");
    }
    return (byte) value;
  }

  public int readIndex() {
    return readIndexAndWriteNull & Integer.MAX_VALUE;
  }

  public void setReadIndex(int readIndex) {
    readIndexAndWriteNull = (readIndexAndWriteNull & WRITE_NULL_MASK) | readIndex;
  }

  public JsonTypeInfo writeTypeInfo() {
    return writeTypeInfo;
  }

  public JsonTypeInfo readTypeInfo() {
    return readTypeInfo;
  }

  public byte[] stringNamePrefix() {
    return stringNamePrefix;
  }

  public byte[] stringCommaNamePrefix() {
    return stringCommaNamePrefix;
  }

  public byte[] stringUtf16NamePrefix() {
    return stringUtf16NamePrefix;
  }

  public byte[] stringUtf16CommaNamePrefix() {
    return stringUtf16CommaNamePrefix;
  }

  public byte[] utf8NamePrefix() {
    return utf8NamePrefix;
  }

  public byte[] utf8CommaNamePrefix() {
    return utf8CommaNamePrefix;
  }

  public byte[] utf8EnumValue(Enum<?> value) {
    return utf8EnumValues[value.ordinal()];
  }

  public byte[] utf8EnumFieldValue(Enum<?> value, boolean comma) {
    return (comma ? utf8EnumCommaValues : utf8EnumNameValues)[value.ordinal()];
  }

  public byte[] utf8BooleanFieldValue(boolean value, boolean comma) {
    return value
        ? (comma ? utf8TrueCommaToken : utf8TrueNameToken)
        : (comma ? utf8FalseCommaToken : utf8FalseNameToken);
  }

  public byte[] stringEnumValue(Enum<?> value) {
    return stringEnumValues[value.ordinal()];
  }

  public byte[] stringEnumFieldValue(Enum<?> value, boolean comma) {
    return (comma ? stringEnumCommaValues : stringEnumNameValues)[value.ordinal()];
  }

  public byte[] stringUtf16EnumFieldValue(Enum<?> value, boolean comma) {
    return (comma ? stringUtf16EnumCommaValues : stringUtf16EnumNameValues)[value.ordinal()];
  }

  public byte[] stringBooleanFieldValue(boolean value, boolean comma) {
    return value
        ? (comma ? stringTrueCommaToken : stringTrueNameToken)
        : (comma ? stringFalseCommaToken : stringFalseNameToken);
  }

  public byte[] stringUtf16BooleanFieldValue(boolean value, boolean comma) {
    return value
        ? (comma ? stringUtf16TrueCommaToken : stringUtf16TrueNameToken)
        : (comma ? stringUtf16FalseCommaToken : stringUtf16FalseNameToken);
  }

  public byte[] utf8ElementEnumValue(Enum<?> value) {
    return utf8ElementEnumValues[value.ordinal()];
  }

  public byte[] stringElementEnumValue(Enum<?> value) {
    return stringElementEnumValues[value.ordinal()];
  }

  public boolean writeString(StringJsonWriter writer, Object object, int index) {
    switch (writeKindId) {
      case KIND_BOOLEAN:
        if (!writeRawType.isPrimitive()) {
          return writeStringScalar(writer, object, index);
        }
        boolean booleanValue = writeAccessor.getBoolean(object);
        writer.writeRawValue(
            stringBooleanFieldValue(booleanValue, index != 0),
            stringUtf16BooleanFieldValue(booleanValue, index != 0));
        return true;
      case KIND_BYTE:
        if (!writeRawType.isPrimitive()) {
          return writeStringScalar(writer, object, index);
        }
        writer.writeIntField(
            stringNamePrefix, stringCommaNamePrefix, index, writeAccessor.getByte(object));
        return true;
      case KIND_SHORT:
        if (!writeRawType.isPrimitive()) {
          return writeStringScalar(writer, object, index);
        }
        writer.writeIntField(
            stringNamePrefix, stringCommaNamePrefix, index, writeAccessor.getShort(object));
        return true;
      case KIND_INT:
        if (!writeRawType.isPrimitive()) {
          return writeStringScalar(writer, object, index);
        }
        writer.writeIntField(
            stringNamePrefix, stringCommaNamePrefix, index, writeAccessor.getInt(object));
        return true;
      case KIND_LONG:
        if (!writeRawType.isPrimitive()) {
          return writeStringScalar(writer, object, index);
        }
        writer.writeLongField(
            stringNamePrefix, stringCommaNamePrefix, index, writeAccessor.getLong(object));
        return true;
      case KIND_FLOAT:
        if (!writeRawType.isPrimitive()) {
          return writeStringScalar(writer, object, index);
        }
        writer.writeFieldName(this, index);
        writer.writeFloat(writeAccessor.getFloat(object));
        return true;
      case KIND_DOUBLE:
        if (!writeRawType.isPrimitive()) {
          return writeStringScalar(writer, object, index);
        }
        writer.writeFieldName(this, index);
        writer.writeDouble(writeAccessor.getDouble(object));
        return true;
      case KIND_CHAR:
        if (!writeRawType.isPrimitive()) {
          return writeStringScalar(writer, object, index);
        }
        writer.writeFieldName(this, index);
        writer.writeChar(writeAccessor.getChar(object));
        return true;
      case KIND_STRING:
        return writeStringText(writer, object, index);
      case KIND_RAW_STRING:
        return writeStringRaw(writer, object, index);
      case KIND_ENUM:
        return writeStringEnum(writer, object, index);
      case KIND_ARRAY:
        return writeStringArray(writer, object, index);
      case KIND_COLLECTION:
        return writeStringCollection(writer, object, index);
      case KIND_MAP:
        return writeStringMap(writer, object, index);
      case KIND_OBJECT:
        return writeStringPojo(writer, object, index);
      default:
        return writeStringObject(writer, object, index);
    }
  }

  public boolean writeUtf8(Utf8JsonWriter writer, Object object, int index) {
    switch (writeKindId) {
      case KIND_BOOLEAN:
        if (!writeRawType.isPrimitive()) {
          return writeUtf8Scalar(writer, object, index);
        }
        writer.writeRawValue(utf8BooleanFieldValue(writeAccessor.getBoolean(object), index != 0));
        return true;
      case KIND_BYTE:
        if (!writeRawType.isPrimitive()) {
          return writeUtf8Scalar(writer, object, index);
        }
        writer.writeIntField(
            utf8NamePrefix, utf8CommaNamePrefix, index, writeAccessor.getByte(object));
        return true;
      case KIND_SHORT:
        if (!writeRawType.isPrimitive()) {
          return writeUtf8Scalar(writer, object, index);
        }
        writer.writeIntField(
            utf8NamePrefix, utf8CommaNamePrefix, index, writeAccessor.getShort(object));
        return true;
      case KIND_INT:
        if (!writeRawType.isPrimitive()) {
          return writeUtf8Scalar(writer, object, index);
        }
        writer.writeIntField(
            utf8NamePrefix, utf8CommaNamePrefix, index, writeAccessor.getInt(object));
        return true;
      case KIND_LONG:
        if (!writeRawType.isPrimitive()) {
          return writeUtf8Scalar(writer, object, index);
        }
        writer.writeLongField(
            utf8NamePrefix, utf8CommaNamePrefix, index, writeAccessor.getLong(object));
        return true;
      case KIND_FLOAT:
        if (!writeRawType.isPrimitive()) {
          return writeUtf8Scalar(writer, object, index);
        }
        writer.writeFieldName(this, index);
        writer.writeFloat(writeAccessor.getFloat(object));
        return true;
      case KIND_DOUBLE:
        if (!writeRawType.isPrimitive()) {
          return writeUtf8Scalar(writer, object, index);
        }
        writer.writeFieldName(this, index);
        writer.writeDouble(writeAccessor.getDouble(object));
        return true;
      case KIND_CHAR:
        if (!writeRawType.isPrimitive()) {
          return writeUtf8Scalar(writer, object, index);
        }
        writer.writeFieldName(this, index);
        writer.writeChar(writeAccessor.getChar(object));
        return true;
      case KIND_STRING:
        return writeUtf8String(writer, object, index);
      case KIND_RAW_STRING:
        return writeUtf8Raw(writer, object, index);
      case KIND_ENUM:
        return writeUtf8Enum(writer, object, index);
      case KIND_ARRAY:
        return writeUtf8Array(writer, object, index);
      case KIND_COLLECTION:
        return writeUtf8Collection(writer, object, index);
      case KIND_MAP:
        return writeUtf8Map(writer, object, index);
      case KIND_OBJECT:
        return writeUtf8Pojo(writer, object, index);
      default:
        return writeUtf8Object(writer, object, index);
    }
  }

  private boolean writeStringObject(StringJsonWriter writer, Object object, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    writer.writeFieldName(this, index);
    writeTypeInfo.stringWriter().writeString(writer, value);
    return true;
  }

  private boolean writeStringScalar(StringJsonWriter writer, Object object, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    if (value == null) {
      writer.writeFieldName(this, index);
      writer.writeNull();
      return true;
    }
    switch (writeKind) {
      case BOOLEAN:
        boolean booleanValue = ((Boolean) value).booleanValue();
        writer.writeRawValue(
            stringBooleanFieldValue(booleanValue, index != 0),
            stringUtf16BooleanFieldValue(booleanValue, index != 0));
        return true;
      case BYTE:
        writer.writeIntField(
            stringNamePrefix, stringCommaNamePrefix, index, ((Byte) value).intValue());
        return true;
      case SHORT:
        writer.writeIntField(
            stringNamePrefix, stringCommaNamePrefix, index, ((Short) value).intValue());
        return true;
      case INT:
        writer.writeIntField(
            stringNamePrefix, stringCommaNamePrefix, index, ((Integer) value).intValue());
        return true;
      case LONG:
        writer.writeLongField(
            stringNamePrefix, stringCommaNamePrefix, index, ((Long) value).longValue());
        return true;
      default:
        writer.writeFieldName(this, index);
        writeStringScalarValue(writer, value);
        return true;
    }
  }

  private boolean writeUtf8Scalar(Utf8JsonWriter writer, Object object, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    if (value == null) {
      writer.writeFieldName(this, index);
      writer.writeNull();
      return true;
    }
    switch (writeKind) {
      case BOOLEAN:
        writer.writeRawValue(utf8BooleanFieldValue(((Boolean) value).booleanValue(), index != 0));
        return true;
      case BYTE:
        writer.writeIntField(utf8NamePrefix, utf8CommaNamePrefix, index, ((Byte) value).intValue());
        return true;
      case SHORT:
        writer.writeIntField(
            utf8NamePrefix, utf8CommaNamePrefix, index, ((Short) value).intValue());
        return true;
      case INT:
        writer.writeIntField(
            utf8NamePrefix, utf8CommaNamePrefix, index, ((Integer) value).intValue());
        return true;
      case LONG:
        writer.writeLongField(
            utf8NamePrefix, utf8CommaNamePrefix, index, ((Long) value).longValue());
        return true;
      default:
        writer.writeFieldName(this, index);
        writeUtf8ScalarValue(writer, value);
        return true;
    }
  }

  private boolean writeStringText(StringJsonWriter writer, Object object, int index) {
    String value = (String) writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    if (value == null) {
      writer.writeFieldName(this, index);
      writer.writeNull();
    } else {
      writer.writeStringField(stringNamePrefix, stringCommaNamePrefix, index, value);
    }
    return true;
  }

  private boolean writeStringRaw(StringJsonWriter writer, Object object, int index) {
    String value = (String) writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    writer.writeFieldName(this, index);
    if (value == null) {
      writer.writeNull();
    } else {
      writer.writeRawValue(value);
    }
    return true;
  }

  private boolean writeStringEnum(StringJsonWriter writer, Object object, int index) {
    Enum<?> value = (Enum<?>) writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    if (value == null) {
      writer.writeFieldName(this, index);
      writer.writeNull();
    } else {
      writer.writeRawValue(
          stringEnumFieldValue(value, index != 0), stringUtf16EnumFieldValue(value, index != 0));
    }
    return true;
  }

  private boolean writeStringArray(StringJsonWriter writer, Object object, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    // Field metadata owns omission only. Once present, the registered codec owns null semantics.
    writer.writeFieldName(this, index);
    writeTypeInfo.stringWriter().writeString(writer, value);
    return true;
  }

  private boolean writeStringCollection(StringJsonWriter writer, Object object, int index) {
    Collection<?> value = (Collection<?>) writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    writer.writeFieldName(this, index);
    writeTypeInfo.stringWriter().writeString(writer, value);
    return true;
  }

  private boolean writeStringMap(StringJsonWriter writer, Object object, int index) {
    Map<?, ?> value = (Map<?, ?>) writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    writer.writeFieldName(this, index);
    writeTypeInfo.stringWriter().writeString(writer, value);
    return true;
  }

  private boolean writeStringPojo(StringJsonWriter writer, Object object, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    writer.writeFieldName(this, index);
    writeTypeInfo.stringWriter().writeString(writer, value);
    return true;
  }

  private void writeStringScalarValue(StringJsonWriter writer, Object value) {
    if (value == null) {
      writer.writeNull();
      return;
    }
    switch (writeKind) {
      case BOOLEAN:
        writer.writeBoolean(((Boolean) value).booleanValue());
        return;
      case BYTE:
        writer.writeInt(((Byte) value).intValue());
        return;
      case SHORT:
        writer.writeInt(((Short) value).intValue());
        return;
      case INT:
        writer.writeInt(((Integer) value).intValue());
        return;
      case LONG:
        writer.writeLong(((Long) value).longValue());
        return;
      case FLOAT:
        writer.writeFloat(((Float) value).floatValue());
        return;
      case DOUBLE:
        writer.writeDouble(((Double) value).doubleValue());
        return;
      case CHAR:
        writer.writeChar(((Character) value).charValue());
        return;
      default:
        throw new ForyJsonException("Not a scalar JSON field " + name);
    }
  }

  private void writeUtf8ScalarValue(Utf8JsonWriter writer, Object value) {
    if (value == null) {
      writer.writeNull();
      return;
    }
    switch (writeKind) {
      case BOOLEAN:
        writer.writeBoolean(((Boolean) value).booleanValue());
        return;
      case BYTE:
        writer.writeInt(((Byte) value).intValue());
        return;
      case SHORT:
        writer.writeInt(((Short) value).intValue());
        return;
      case INT:
        writer.writeInt(((Integer) value).intValue());
        return;
      case LONG:
        writer.writeLong(((Long) value).longValue());
        return;
      case FLOAT:
        writer.writeFloat(((Float) value).floatValue());
        return;
      case DOUBLE:
        writer.writeDouble(((Double) value).doubleValue());
        return;
      case CHAR:
        writer.writeChar(((Character) value).charValue());
        return;
      default:
        throw new ForyJsonException("Not a scalar JSON field " + name);
    }
  }

  private boolean writeUtf8Object(Utf8JsonWriter writer, Object object, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    writer.writeFieldName(this, index);
    writeTypeInfo.utf8Writer().writeUtf8(writer, value);
    return true;
  }

  private boolean writeUtf8String(Utf8JsonWriter writer, Object object, int index) {
    String value = (String) writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    if (value == null) {
      writer.writeFieldName(this, index);
      writer.writeNull();
    } else if (index == 0) {
      // Keep interpreted packable fields on the same concrete writer entry as generated codecs.
      // A separate byte-array entry leaves the packed encoder cold until generated parents compile,
      // allowing C2 to copy the complete encoder graph into those parents instead.
      if (utf8NamePrefix.length <= Long.BYTES * 2) {
        writer.writeStringField(
            utf8NamePrefixWord0, utf8NamePrefixWord1, utf8NamePrefix.length, value);
      } else {
        writer.writeStringField(utf8NamePrefix, value);
      }
    } else if (utf8CommaNamePrefix.length <= Long.BYTES * 2) {
      writer.writeStringField(
          utf8CommaNamePrefixWord0, utf8CommaNamePrefixWord1, utf8CommaNamePrefix.length, value);
    } else {
      writer.writeStringField(utf8CommaNamePrefix, value);
    }
    return true;
  }

  private boolean writeUtf8Raw(Utf8JsonWriter writer, Object object, int index) {
    String value = (String) writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    writer.writeFieldName(this, index);
    if (value == null) {
      writer.writeNull();
    } else {
      writer.writeRawValue(value);
    }
    return true;
  }

  private boolean writeUtf8Enum(Utf8JsonWriter writer, Object object, int index) {
    Enum<?> value = (Enum<?>) writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    if (value == null) {
      writer.writeFieldName(this, index);
      writer.writeNull();
    } else {
      writer.writeRawValue(utf8EnumFieldValue(value, index != 0));
    }
    return true;
  }

  private boolean writeUtf8Array(Utf8JsonWriter writer, Object object, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    // Field metadata owns omission only. Once present, the registered codec owns null semantics.
    writer.writeFieldName(this, index);
    writeTypeInfo.utf8Writer().writeUtf8(writer, value);
    return true;
  }

  private boolean writeUtf8Collection(Utf8JsonWriter writer, Object object, int index) {
    Collection<?> value = (Collection<?>) writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    writer.writeFieldName(this, index);
    writeTypeInfo.utf8Writer().writeUtf8(writer, value);
    return true;
  }

  private boolean writeUtf8Map(Utf8JsonWriter writer, Object object, int index) {
    Map<?, ?> value = (Map<?, ?>) writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    writer.writeFieldName(this, index);
    writeTypeInfo.utf8Writer().writeUtf8(writer, value);
    return true;
  }

  private boolean writeUtf8Pojo(Utf8JsonWriter writer, Object object, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writeNull()) {
      return false;
    }
    writer.writeFieldName(this, index);
    writeTypeInfo.utf8Writer().writeUtf8(writer, value);
    return true;
  }

  private static JsonFieldKind kind(Class<?> rawType) {
    if (rawType == boolean.class || rawType == Boolean.class) {
      return JsonFieldKind.BOOLEAN;
    } else if (rawType == byte.class || rawType == Byte.class) {
      return JsonFieldKind.BYTE;
    } else if (rawType == short.class || rawType == Short.class) {
      return JsonFieldKind.SHORT;
    } else if (rawType == int.class || rawType == Integer.class) {
      return JsonFieldKind.INT;
    } else if (rawType == long.class || rawType == Long.class) {
      return JsonFieldKind.LONG;
    } else if (rawType == float.class || rawType == Float.class) {
      return JsonFieldKind.FLOAT;
    } else if (rawType == double.class || rawType == Double.class) {
      return JsonFieldKind.DOUBLE;
    } else if (rawType == char.class || rawType == Character.class) {
      return JsonFieldKind.CHAR;
    } else if (rawType == String.class) {
      return JsonFieldKind.STRING;
    } else if (rawType.isEnum()) {
      return JsonFieldKind.ENUM;
    } else if (rawType.isArray()) {
      return JsonFieldKind.ARRAY;
    } else if (java.util.Collection.class.isAssignableFrom(rawType)) {
      return JsonFieldKind.COLLECTION;
    } else if (java.util.Map.class.isAssignableFrom(rawType)) {
      return JsonFieldKind.MAP;
    }
    return JsonFieldKind.OBJECT;
  }

  private static int kindId(JsonFieldKind kind) {
    switch (kind) {
      case BOOLEAN:
        return KIND_BOOLEAN;
      case BYTE:
        return KIND_BYTE;
      case SHORT:
        return KIND_SHORT;
      case INT:
        return KIND_INT;
      case LONG:
        return KIND_LONG;
      case FLOAT:
        return KIND_FLOAT;
      case DOUBLE:
        return KIND_DOUBLE;
      case CHAR:
        return KIND_CHAR;
      case STRING:
        return KIND_STRING;
      case ENUM:
        return KIND_ENUM;
      case ARRAY:
        return KIND_ARRAY;
      case COLLECTION:
        return KIND_COLLECTION;
      case MAP:
        return KIND_MAP;
      case OBJECT:
        return KIND_OBJECT;
      default:
        throw new ForyJsonException("Unsupported JSON field kind " + kind);
    }
  }

  private static int primitiveKindId(Class<?> rawType, JsonFieldKind kind) {
    if (rawType == null || !rawType.isPrimitive() || kind == null) {
      return 0;
    }
    if (kind == JsonFieldKind.OBJECT) {
      return KIND_CUSTOM_PRIMITIVE;
    }
    int kindId = kindId(kind);
    return kindId <= KIND_CHAR ? kindId : 0;
  }

  private static Class<?> knownRawType(Type type) {
    Class<?> rawType = CodecUtils.rawType(type, null);
    return rawType == Object.class ? null : rawType;
  }

  private static byte[][] enumValues(Class<?> enumType) {
    Object[] constants = enumType.getEnumConstants();
    byte[][] values = new byte[constants.length][];
    for (Object constant : constants) {
      Enum<?> enumValue = (Enum<?>) constant;
      values[enumValue.ordinal()] = JsonStringEscaper.utf8Value(enumValue.name());
    }
    return values;
  }

  private static byte[][] stringEnumValues(Class<?> enumType) {
    Object[] constants = enumType.getEnumConstants();
    byte[][] values = new byte[constants.length][];
    for (Object constant : constants) {
      Enum<?> enumValue = (Enum<?>) constant;
      values[enumValue.ordinal()] = JsonStringEscaper.stringValue(enumValue.name());
    }
    return values;
  }

  private static byte[][] fieldValues(byte[] prefix, byte[][] values) {
    byte[][] fieldValues = new byte[values.length][];
    for (int i = 0; i < values.length; i++) {
      fieldValues[i] = join(prefix, values[i]);
    }
    return fieldValues;
  }

  private static byte[][] utf16FieldValues(byte[] utf16Prefix, byte[][] values) {
    byte[][] fieldValues = new byte[values.length][];
    for (int i = 0; i < values.length; i++) {
      fieldValues[i] = join(utf16Prefix, toUtf16Bytes(values[i]));
    }
    return fieldValues;
  }

  private static byte[] join(byte[] prefix, byte[] token) {
    byte[] joined = new byte[prefix.length + token.length];
    System.arraycopy(prefix, 0, joined, 0, prefix.length);
    System.arraycopy(token, 0, joined, prefix.length, token.length);
    return joined;
  }

  private static byte[] toUtf16Bytes(byte[] latin1) {
    byte[] utf16 = new byte[latin1.length << 1];
    if (NativeByteOrder.IS_LITTLE_ENDIAN) {
      for (int i = 0, j = 0; i < latin1.length; i++, j += 2) {
        utf16[j] = latin1[i];
      }
    } else {
      for (int i = 0, j = 0; i < latin1.length; i++, j += 2) {
        utf16[j + 1] = latin1[i];
      }
    }
    return utf16;
  }

  private static long packedPrefixWord(byte[] prefix, int offset) {
    long word = 0;
    int end = Math.min(prefix.length, offset + Long.BYTES);
    for (int i = offset; i < end; i++) {
      word |= (prefix[i] & 0xffL) << ((i - offset) << 3);
    }
    return word;
  }
}
