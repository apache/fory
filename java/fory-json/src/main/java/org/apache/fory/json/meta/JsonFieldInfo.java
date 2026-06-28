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
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.JsonStringEscaper;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

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
  private static final byte[] TRUE_BYTES = "true".getBytes(StandardCharsets.ISO_8859_1);
  private static final byte[] FALSE_BYTES = "false".getBytes(StandardCharsets.ISO_8859_1);

  private final String name;
  private final Field writeField;
  private final Type writeType;
  private final Class<?> writeRawType;
  private final Type readType;
  private final Class<?> readRawType;
  private final JsonFieldKind writeKind;
  private final JsonFieldKind readKind;
  private final int writeKindId;
  private final JsonFieldAccessor writeAccessor;
  private final JsonFieldAccessor readAccessor;
  private final Type writeElementType;
  private final Type readElementType;
  private final Type writeMapValueType;
  private final Class<?> writeArrayComponentType;
  private final Class<?> writeElementRawType;
  private final Class<?> readElementRawType;
  private final byte[] stringNamePrefix;
  private final byte[] stringCommaNamePrefix;
  private final byte[] utf8NamePrefix;
  private final byte[] utf8CommaNamePrefix;
  private final byte[][] stringEnumValues;
  private final byte[][] stringElementEnumValues;
  private final byte[][] stringEnumNameValues;
  private final byte[][] stringEnumCommaValues;
  private final byte[][] utf8EnumValues;
  private final byte[][] utf8ElementEnumValues;
  private final byte[][] utf8EnumNameValues;
  private final byte[][] utf8EnumCommaValues;
  private final byte[] stringTrueNameToken;
  private final byte[] stringTrueCommaToken;
  private final byte[] stringFalseNameToken;
  private final byte[] stringFalseCommaToken;
  private final byte[] utf8TrueNameToken;
  private final byte[] utf8TrueCommaToken;
  private final byte[] utf8FalseNameToken;
  private final byte[] utf8FalseCommaToken;
  private final long nameHash;
  private int readIndex = -1;
  private JsonTypeInfo writeTypeInfo;
  private JsonTypeInfo readTypeInfo;
  private JsonTypeInfo readElementTypeInfo;

  public JsonFieldInfo(
      String name,
      Field writeField,
      Field readField,
      JsonFieldAccessor writeAccessor,
      JsonFieldAccessor readAccessor) {
    this.name = name;
    nameHash = JsonFieldNameHash.hash(name);
    this.writeField = writeField;
    this.writeType = fieldType(writeField);
    this.writeRawType = fieldRawType(writeField);
    this.readType = fieldType(readField);
    this.readRawType = fieldRawType(readField);
    this.writeAccessor = writeAccessor;
    this.readAccessor = readAccessor;
    writeKind = writeRawType == null ? null : kind(writeRawType);
    readKind = readRawType == null ? null : kind(readRawType);
    writeKindId = writeKind == null ? 0 : kindId(writeKind);
    writeElementType =
        writeKind == JsonFieldKind.COLLECTION ? CodecUtils.elementType(writeType) : null;
    readElementType =
        readKind == JsonFieldKind.COLLECTION ? CodecUtils.elementType(readType) : null;
    writeMapValueType = writeKind == JsonFieldKind.MAP ? CodecUtils.mapValueType(writeType) : null;
    writeArrayComponentType =
        writeKind == JsonFieldKind.ARRAY ? writeRawType.getComponentType() : null;
    writeElementRawType = writeElementType == null ? null : knownRawType(writeElementType);
    readElementRawType = readElementType == null ? null : knownRawType(readElementType);
    String stringPrefix = JsonStringEscaper.escapedNamePrefix(name, true);
    String utf8Prefix = JsonStringEscaper.escapedNamePrefix(name, false);
    stringNamePrefix = stringPrefix.getBytes(StandardCharsets.ISO_8859_1);
    stringCommaNamePrefix = ("," + stringPrefix).getBytes(StandardCharsets.ISO_8859_1);
    utf8NamePrefix = utf8Prefix.getBytes(StandardCharsets.UTF_8);
    utf8CommaNamePrefix = ("," + utf8Prefix).getBytes(StandardCharsets.UTF_8);
    stringEnumValues = writeKind == JsonFieldKind.ENUM ? stringEnumValues(writeRawType) : null;
    stringEnumNameValues =
        writeKind == JsonFieldKind.ENUM ? fieldValues(stringNamePrefix, stringEnumValues) : null;
    stringEnumCommaValues =
        writeKind == JsonFieldKind.ENUM
            ? fieldValues(stringCommaNamePrefix, stringEnumValues)
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
      utf8TrueNameToken = join(utf8NamePrefix, TRUE_BYTES);
      utf8TrueCommaToken = join(utf8CommaNamePrefix, TRUE_BYTES);
      utf8FalseNameToken = join(utf8NamePrefix, FALSE_BYTES);
      utf8FalseCommaToken = join(utf8CommaNamePrefix, FALSE_BYTES);
    } else {
      stringTrueNameToken = null;
      stringTrueCommaToken = null;
      stringFalseNameToken = null;
      stringFalseCommaToken = null;
      utf8TrueNameToken = null;
      utf8TrueCommaToken = null;
      utf8FalseNameToken = null;
      utf8FalseCommaToken = null;
    }
  }

  public String name() {
    return name;
  }

  public long nameHash() {
    return nameHash;
  }

  public Field writeField() {
    return writeField;
  }

  public Type writeType() {
    return writeType;
  }

  public Class<?> writeRawType() {
    return writeRawType;
  }

  public JsonFieldKind writeKind() {
    return writeKind;
  }

  public JsonFieldAccessor writeAccessor() {
    return writeAccessor;
  }

  public Type writeElementType() {
    return writeElementType;
  }

  public Type readElementType() {
    return readElementType;
  }

  public Class<?> writeElementRawType() {
    return writeElementRawType;
  }

  public Class<?> readElementRawType() {
    return readElementRawType;
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

  public Field readField() {
    return readAccessor == null ? null : readAccessor.field();
  }

  public Class<?> readRawType() {
    return readRawType;
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

  public void resolveTypes(JsonTypeResolver typeResolver) {
    if (writeRawType != null) {
      writeTypeInfo = typeResolver.getTypeInfo(writeType, writeRawType);
    }
    if (readRawType != null) {
      readTypeInfo = typeResolver.getTypeInfo(readType, readRawType);
    }
    if (readElementRawType != null) {
      readElementTypeInfo = typeResolver.getTypeInfo(readElementType, readElementRawType);
    }
  }

  public void read(JsonReader reader, Object object, JsonTypeResolver typeResolver) {
    readTypeInfo.codec().readField(reader, object, readAccessor, readTypeInfo, typeResolver);
  }

  public Object readValue(JsonReader reader, JsonTypeResolver typeResolver) {
    return readTypeInfo.codec().read(reader, readTypeInfo, typeResolver);
  }

  public int readIndex() {
    return readIndex;
  }

  public void setReadIndex(int readIndex) {
    this.readIndex = readIndex;
  }

  public JsonTypeInfo writeTypeInfo() {
    return writeTypeInfo;
  }

  public JsonTypeInfo readTypeInfo() {
    return readTypeInfo;
  }

  public JsonTypeInfo readElementTypeInfo() {
    return readElementTypeInfo;
  }

  public byte[] stringNamePrefix() {
    return stringNamePrefix;
  }

  public byte[] stringCommaNamePrefix() {
    return stringCommaNamePrefix;
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

  public byte[] stringBooleanFieldValue(boolean value, boolean comma) {
    return value
        ? (comma ? stringTrueCommaToken : stringTrueNameToken)
        : (comma ? stringFalseCommaToken : stringFalseNameToken);
  }

  public byte[] utf8ElementEnumValue(Enum<?> value) {
    return utf8ElementEnumValues[value.ordinal()];
  }

  public byte[] stringElementEnumValue(Enum<?> value) {
    return stringElementEnumValues[value.ordinal()];
  }

  public boolean write(JsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    switch (writeKind) {
      case BOOLEAN:
        if (!writeRawType.isPrimitive()) {
          return writeScalar(writer, object, index);
        }
        writer.writeComma(index);
        writer.writeFieldName(this);
        writer.writeBoolean(writeAccessor.getBoolean(object));
        return true;
      case BYTE:
        if (!writeRawType.isPrimitive()) {
          return writeScalar(writer, object, index);
        }
        writer.writeComma(index);
        writer.writeFieldName(this);
        writer.writeInt(writeAccessor.getByte(object));
        return true;
      case SHORT:
        if (!writeRawType.isPrimitive()) {
          return writeScalar(writer, object, index);
        }
        writer.writeComma(index);
        writer.writeFieldName(this);
        writer.writeInt(writeAccessor.getShort(object));
        return true;
      case INT:
        if (!writeRawType.isPrimitive()) {
          return writeScalar(writer, object, index);
        }
        writer.writeComma(index);
        writer.writeFieldName(this);
        writer.writeInt(writeAccessor.getInt(object));
        return true;
      case LONG:
        if (!writeRawType.isPrimitive()) {
          return writeScalar(writer, object, index);
        }
        writer.writeComma(index);
        writer.writeFieldName(this);
        writer.writeLong(writeAccessor.getLong(object));
        return true;
      case FLOAT:
        if (!writeRawType.isPrimitive()) {
          return writeScalar(writer, object, index);
        }
        writer.writeComma(index);
        writer.writeFieldName(this);
        writer.writeFloat(writeAccessor.getFloat(object));
        return true;
      case DOUBLE:
        if (!writeRawType.isPrimitive()) {
          return writeScalar(writer, object, index);
        }
        writer.writeComma(index);
        writer.writeFieldName(this);
        writer.writeDouble(writeAccessor.getDouble(object));
        return true;
      case CHAR:
        if (!writeRawType.isPrimitive()) {
          return writeScalar(writer, object, index);
        }
        writer.writeComma(index);
        writer.writeFieldName(this);
        writer.writeChar(writeAccessor.getChar(object));
        return true;
      case STRING:
        return writeString(writer, object, index);
      case ENUM:
        return writeEnum(writer, object, index);
      case ARRAY:
        return writeArray(writer, object, typeResolver, index);
      case COLLECTION:
        return writeCollection(writer, object, typeResolver, index);
      case MAP:
        return writeMap(writer, object, typeResolver, index);
      case OBJECT:
        return writePojo(writer, object, typeResolver, index);
      default:
        return writeObject(writer, object, typeResolver, index);
    }
  }

  public boolean writeString(
      StringJsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    switch (writeKindId) {
      case KIND_BOOLEAN:
        if (!writeRawType.isPrimitive()) {
          return writeStringScalar(writer, object, index);
        }
        writer.writeRawValue(stringBooleanFieldValue(writeAccessor.getBoolean(object), index != 0));
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
      case KIND_ENUM:
        return writeStringEnum(writer, object, index);
      case KIND_ARRAY:
        return writeStringArray(writer, object, typeResolver, index);
      case KIND_COLLECTION:
        return writeStringCollection(writer, object, typeResolver, index);
      case KIND_MAP:
        return writeStringMap(writer, object, typeResolver, index);
      case KIND_OBJECT:
        return writeStringPojo(writer, object, typeResolver, index);
      default:
        return writeObject(writer, object, typeResolver, index);
    }
  }

  private boolean writeString(JsonWriter writer, Object object, int index) {
    String value = (String) writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeComma(index);
    writer.writeFieldName(this);
    if (value == null) {
      writer.writeNull();
    } else {
      writer.writeString(value);
    }
    return true;
  }

  public boolean writeUtf8(
      Utf8JsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
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
      case KIND_ENUM:
        return writeUtf8Enum(writer, object, index);
      case KIND_ARRAY:
        return writeUtf8Array(writer, object, typeResolver, index);
      case KIND_COLLECTION:
        return writeUtf8Collection(writer, object, typeResolver, index);
      case KIND_MAP:
        return writeUtf8Map(writer, object, typeResolver, index);
      case KIND_OBJECT:
        return writeUtf8Pojo(writer, object, typeResolver, index);
      default:
        return writeUtf8Object(writer, object, typeResolver, index);
    }
  }

  private boolean writeObject(
      JsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeComma(index);
    writer.writeFieldName(this);
    writeTypeInfo.codec().write(writer, value, typeResolver);
    return true;
  }

  private boolean writeScalar(JsonWriter writer, Object object, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeComma(index);
    writer.writeFieldName(this);
    writeScalarValue(writer, value);
    return true;
  }

  private boolean writeStringScalar(StringJsonWriter writer, Object object, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    if (value == null) {
      writer.writeFieldName(this, index);
      writer.writeNull();
      return true;
    }
    switch (writeKind) {
      case BOOLEAN:
        writer.writeRawValue(stringBooleanFieldValue(((Boolean) value).booleanValue(), index != 0));
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
        writeScalarValue(writer, value);
        return true;
    }
  }

  private boolean writeUtf8Scalar(Utf8JsonWriter writer, Object object, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
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
        writeScalarValue(writer, value);
        return true;
    }
  }

  private boolean writeStringText(StringJsonWriter writer, Object object, int index) {
    String value = (String) writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
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

  private boolean writeStringEnum(StringJsonWriter writer, Object object, int index) {
    Enum<?> value = (Enum<?>) writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    if (value == null) {
      writer.writeFieldName(this, index);
      writer.writeNull();
    } else {
      writer.writeRawValue(stringEnumFieldValue(value, index != 0));
    }
    return true;
  }

  private boolean writeStringArray(
      StringJsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeFieldName(this, index);
    if (value == null) {
      writer.writeNull();
    } else {
      writeTypeInfo.codec().writeString(writer, value, typeResolver);
    }
    return true;
  }

  private boolean writeStringCollection(
      StringJsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    Collection<?> value = (Collection<?>) writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeFieldName(this, index);
    if (value == null) {
      writer.writeNull();
    } else {
      writeTypeInfo.codec().writeString(writer, value, typeResolver);
    }
    return true;
  }

  private boolean writeStringMap(
      StringJsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    Map<?, ?> value = (Map<?, ?>) writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeFieldName(this, index);
    if (value == null) {
      writer.writeNull();
    } else {
      writeTypeInfo.codec().writeString(writer, value, typeResolver);
    }
    return true;
  }

  private boolean writeStringPojo(
      StringJsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeFieldName(this, index);
    if (value == null) {
      writer.writeNull();
    } else {
      writeTypeInfo.codec().writeString(writer, value, typeResolver);
    }
    return true;
  }

  private void writeScalarValue(JsonWriter writer, Object value) {
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

  private boolean writeUtf8Object(
      Utf8JsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeFieldName(this, index);
    writeTypeInfo.codec().writeUtf8(writer, value, typeResolver);
    return true;
  }

  private boolean writeUtf8String(Utf8JsonWriter writer, Object object, int index) {
    String value = (String) writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    if (value == null) {
      writer.writeFieldName(this, index);
      writer.writeNull();
    } else {
      writer.writeStringField(utf8NamePrefix, utf8CommaNamePrefix, index, value);
    }
    return true;
  }

  private boolean writeEnum(JsonWriter writer, Object object, int index) {
    Enum<?> value = (Enum<?>) writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeComma(index);
    writer.writeFieldName(this);
    if (value == null) {
      writer.writeNull();
    } else {
      writer.writeString(value.name());
    }
    return true;
  }

  private boolean writeUtf8Enum(Utf8JsonWriter writer, Object object, int index) {
    Enum<?> value = (Enum<?>) writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
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

  private boolean writeArray(
      JsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeComma(index);
    writer.writeFieldName(this);
    if (value == null) {
      writer.writeNull();
    } else {
      writeTypeInfo.codec().write(writer, value, typeResolver);
    }
    return true;
  }

  private boolean writeUtf8Array(
      Utf8JsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeFieldName(this, index);
    if (value == null) {
      writer.writeNull();
    } else {
      writeTypeInfo.codec().writeUtf8(writer, value, typeResolver);
    }
    return true;
  }

  private boolean writeCollection(
      JsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    Collection<?> value = (Collection<?>) writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeComma(index);
    writer.writeFieldName(this);
    if (value == null) {
      writer.writeNull();
    } else {
      writeTypeInfo.codec().write(writer, value, typeResolver);
    }
    return true;
  }

  private boolean writeUtf8Collection(
      Utf8JsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    Collection<?> value = (Collection<?>) writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeFieldName(this, index);
    if (value == null) {
      writer.writeNull();
    } else {
      writeTypeInfo.codec().writeUtf8(writer, value, typeResolver);
    }
    return true;
  }

  private boolean writeMap(
      JsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    Map<?, ?> value = (Map<?, ?>) writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeComma(index);
    writer.writeFieldName(this);
    if (value == null) {
      writer.writeNull();
    } else {
      writeTypeInfo.codec().write(writer, value, typeResolver);
    }
    return true;
  }

  private boolean writeUtf8Map(
      Utf8JsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    Map<?, ?> value = (Map<?, ?>) writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeFieldName(this, index);
    if (value == null) {
      writer.writeNull();
    } else {
      writeTypeInfo.codec().writeUtf8(writer, value, typeResolver);
    }
    return true;
  }

  private boolean writePojo(
      JsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeComma(index);
    writer.writeFieldName(this);
    if (value == null) {
      writer.writeNull();
    } else {
      writeTypeInfo.codec().write(writer, value, typeResolver);
    }
    return true;
  }

  private boolean writeUtf8Pojo(
      Utf8JsonWriter writer, Object object, JsonTypeResolver typeResolver, int index) {
    Object value = writeAccessor.getObject(object);
    if (value == null && !writer.writeNullFields()) {
      return false;
    }
    writer.writeFieldName(this, index);
    if (value == null) {
      writer.writeNull();
    } else {
      writeTypeInfo.codec().writeUtf8(writer, value, typeResolver);
    }
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

  private static Class<?> knownRawType(Type type) {
    Class<?> rawType = CodecUtils.rawType(type, null);
    return rawType == Object.class ? null : rawType;
  }

  private static boolean isScalarType(Class<?> rawType) {
    return rawType == String.class
        || rawType == Boolean.class
        || rawType == Byte.class
        || rawType == Short.class
        || rawType == Integer.class
        || rawType == Long.class
        || rawType == Float.class
        || rawType == Double.class
        || rawType == Character.class
        || rawType.isPrimitive()
        || rawType.isArray()
        || Collection.class.isAssignableFrom(rawType)
        || Map.class.isAssignableFrom(rawType);
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

  private static byte[] join(byte[] prefix, byte[] token) {
    byte[] joined = new byte[prefix.length + token.length];
    System.arraycopy(prefix, 0, joined, 0, prefix.length);
    System.arraycopy(token, 0, joined, prefix.length, token.length);
    return joined;
  }
}
