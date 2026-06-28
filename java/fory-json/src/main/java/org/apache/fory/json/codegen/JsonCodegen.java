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

package org.apache.fory.json.codegen;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.BaseObjectCodec;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.codec.ObjectCodecs;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.reader.Latin1ObjectReader;
import org.apache.fory.json.reader.ObjectReader;
import org.apache.fory.json.reader.Utf16ObjectReader;
import org.apache.fory.json.reader.Utf8ObjectReader;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringObjectWriter;
import org.apache.fory.json.writer.Utf8ObjectWriter;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.util.record.RecordUtils;

public final class JsonCodegen {
  static final String PACKAGE = "org.apache.fory.json.codegen";
  static final int GENERIC_READER = 0;
  static final int LATIN1_READER = 1;
  static final int UTF16_READER = 2;
  static final int UTF8_READER = 3;
  private static final AtomicInteger ID = new AtomicInteger();

  final boolean writeNullFields;
  private final CodeGenerator codeGenerator;
  private final ClassLoader jsonLoader;

  public JsonCodegen(boolean writeNullFields) {
    this.writeNullFields = writeNullFields;
    jsonLoader = JsonCodegen.class.getClassLoader();
    codeGenerator = new CodeGenerator(jsonLoader);
  }

  public ObjectCodecs compile(BaseObjectCodec objectCodec, JsonTypeResolver typeResolver) {
    Class<?> type = objectCodec.type();
    if (!canCompile(type)) {
      return null;
    }
    boolean record = objectCodec.isRecord();
    JsonFieldInfo[] writeProperties = objectCodec.writeFields();
    for (int i = 0; i < writeProperties.length; i++) {
      if (!canCompileWrite(writeProperties[i])) {
        return null;
      }
    }
    JsonFieldInfo[] readProperties = objectCodec.readFields();
    for (int i = 0; i < readProperties.length; i++) {
      if (!canCompileRead(readProperties[i], record)) {
        return null;
      }
    }
    String className = className(type);
    JsonCodec[] writeCodecs = writeCodecs(writeProperties);
    Utf8ObjectWriter utf8Writer =
        (Utf8ObjectWriter)
            compileWriter(className + "_Utf8", type, writeProperties, writeCodecs, true);
    if (utf8Writer == null) {
      return null;
    }
    StringObjectWriter stringWriter =
        (StringObjectWriter)
            compileWriter(className + "_String", type, writeProperties, writeCodecs, false);
    if (stringWriter == null) {
      return null;
    }
    JsonCodec[] readCodecs = readCodecs(readProperties);
    BaseObjectCodec[] readObjectCodecs = readObjectCodecs(objectCodec, typeResolver);
    ObjectReader reader =
        (ObjectReader)
            compileReader(
                className + "_Reader", type, readProperties, readCodecs, readObjectCodecs, record);
    if (reader == null) {
      return null;
    }
    return new ObjectCodecs(
        stringWriter,
        utf8Writer,
        reader,
        (Latin1ObjectReader) reader,
        (Utf16ObjectReader) reader,
        (Utf8ObjectReader) reader);
  }

  private Object compileWriter(
      String className,
      Class<?> type,
      JsonFieldInfo[] properties,
      JsonCodec[] nestedCodecs,
      boolean utf8) {
    String code =
        new JsonGeneratedCodecBuilder(this, className, type, properties, utf8, true, false)
            .genCode();
    try {
      CompileUnit unit = new CompileUnit(PACKAGE, className, code, JsonCodegen.class);
      Class<?> writerClass = codeGenerator.compileAndLoad(unit, state -> state.lock.lock());
      Constructor<?> constructor =
          writerClass.getDeclaredConstructor(JsonFieldInfo[].class, JsonCodec[].class);
      constructor.setAccessible(true);
      return constructor.newInstance(properties, nestedCodecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot compile generated JSON writer " + className, e);
    }
  }

  private Object compileReader(
      String className,
      Class<?> type,
      JsonFieldInfo[] properties,
      JsonCodec[] readCodecs,
      BaseObjectCodec[] nestedCodecs,
      boolean record) {
    String code =
        new JsonGeneratedCodecBuilder(this, className, type, properties, false, false, record)
            .genCode();
    try {
      CompileUnit unit = new CompileUnit(PACKAGE, className, code, JsonCodegen.class);
      Class<?> readerClass = codeGenerator.compileAndLoad(unit, state -> state.lock.lock());
      Constructor<?> constructor =
          readerClass.getDeclaredConstructor(
              JsonFieldInfo[].class, JsonCodec[].class, BaseObjectCodec[].class);
      constructor.setAccessible(true);
      return constructor.newInstance(properties, readCodecs, nestedCodecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot compile generated JSON reader " + className, e);
    }
  }

  private static JsonCodec[] writeCodecs(JsonFieldInfo[] properties) {
    JsonCodec[] codecs = new JsonCodec[properties.length];
    for (int i = 0; i < properties.length; i++) {
      if (usesWriteCodec(properties[i])) {
        codecs[i] = properties[i].writeTypeInfo().codec();
      }
    }
    return codecs;
  }

  private static JsonCodec[] readCodecs(JsonFieldInfo[] properties) {
    JsonCodec[] codecs = new JsonCodec[properties.length];
    for (int i = 0; i < properties.length; i++) {
      if (usesReadCodec(properties[i])) {
        codecs[i] = properties[i].readTypeInfo().codec();
      }
    }
    return codecs;
  }

  private BaseObjectCodec[] readObjectCodecs(
      BaseObjectCodec objectCodec, JsonTypeResolver typeResolver) {
    JsonFieldInfo[] properties = objectCodec.readFields();
    BaseObjectCodec[] nestedCodecs = new BaseObjectCodec[properties.length];
    Class<?> type = objectCodec.type();
    for (int i = 0; i < properties.length; i++) {
      Class<?> nestedType = readNestedType(properties[i]);
      if (nestedType != null && nestedType != type) {
        nestedCodecs[i] = typeResolver.getObjectCodec(nestedType);
      }
    }
    return nestedCodecs;
  }

  static Class<?> readNestedType(JsonFieldInfo property) {
    if (property.readKind() == JsonFieldKind.OBJECT
        && property.readRawType() != Object.class
        && property.readTypeInfo().codec() instanceof BaseObjectCodec) {
      return property.readRawType();
    }
    return null;
  }

  private boolean canCompileWrite(JsonFieldInfo property) {
    Field field = property.writeField();
    if (field == null) {
      return false;
    }
    if (!isRecordField(property) && property.writeField() == null) {
      return false;
    }
    Class<?> rawType = property.writeRawType();
    if (rawType != null && !rawType.isPrimitive() && !isVisible(rawType)) {
      return false;
    }
    Class<?> elementType = property.writeElementRawType();
    return !isPojo(elementType) || isVisible(elementType);
  }

  private boolean canCompileRead(JsonFieldInfo property, boolean record) {
    if (!record && property.readAccessor() == null) {
      return false;
    }
    if (!record && property.readAccessor().coreAccessor() == null) {
      return false;
    }
    Class<?> rawType = property.readRawType();
    if (rawType != null && !rawType.isPrimitive() && !isVisible(rawType)) {
      return false;
    }
    Class<?> elementType = property.readElementRawType();
    return !isPojo(elementType) || isVisible(elementType);
  }

  private boolean canCompile(Class<?> type) {
    return supportsHiddenNestmateLoading()
        && CodeGenerator.sourcePublicAccessible(type)
        && isVisible(type);
  }

  private static boolean supportsHiddenNestmateLoading() {
    // Generated JSON codecs are defined as hidden nestmates of JsonCodegen; JDK 8 must keep using
    // the interpreter path because hidden classes are unavailable there.
    return JdkVersion.MAJOR_VERSION >= 15;
  }

  private boolean isVisible(Class<?> type) {
    if (type.isPrimitive()) {
      return true;
    }
    while (type.isArray()) {
      type = type.getComponentType();
    }
    if (type.isPrimitive()) {
      return true;
    }
    String name = type.getName();
    try {
      return Class.forName(name, false, jsonLoader) == type;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  Class<?> codecFieldType(JsonCodec codec) {
    Class<?> type = codec.getClass();
    if (isPublicSourceType(type) && isVisible(type)) {
      return type;
    }
    return JsonCodec.class;
  }

  private static boolean isPublicSourceType(Class<?> type) {
    if (!CodeGenerator.sourcePublicAccessible(type)) {
      return false;
    }
    for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
      if (!Modifier.isPublic(current.getModifiers())) {
        return false;
      }
    }
    return true;
  }

  static boolean usesWriteCodec(JsonFieldInfo property) {
    switch (property.writeKind()) {
      case ARRAY:
      case MAP:
      case OBJECT:
        return true;
      case COLLECTION:
        return property.writeElementRawType() != String.class;
      default:
        return false;
    }
  }

  static boolean usesReadCodec(JsonFieldInfo property) {
    switch (property.readKind()) {
      case ENUM:
      case ARRAY:
      case COLLECTION:
      case MAP:
        return true;
      default:
        return false;
    }
  }

  static boolean usesReadTypeField(JsonFieldInfo property) {
    switch (property.readKind()) {
      case ARRAY:
      case COLLECTION:
      case MAP:
        return true;
      case OBJECT:
        return usesReadObjectCodec(property);
      default:
        return false;
    }
  }

  static boolean usesReadObjectCodec(JsonFieldInfo property) {
    return property.readKind() == JsonFieldKind.OBJECT
        && property.readRawType() != Object.class
        && property.readTypeInfo().codec() instanceof BaseObjectCodec;
  }

  static boolean storesReadObjectCodec(Class<?> type, JsonFieldInfo property) {
    Class<?> nestedType = readNestedType(property);
    return nestedType != null && nestedType != type;
  }

  private static String className(Class<?> type) {
    String name = type.getName().replace('.', '_').replace('$', '_');
    String uniqueId = CodeGenerator.getClassUniqueId(type);
    if (uniqueId.isEmpty()) {
      uniqueId = String.valueOf(ID.incrementAndGet());
    }
    return "JsonWriter_" + name + "_" + uniqueId;
  }

  private static boolean isPojo(Class<?> type) {
    return type != null
        && type != Object.class
        && type != String.class
        && type != Boolean.class
        && type != Byte.class
        && type != Short.class
        && type != Integer.class
        && type != Long.class
        && type != Float.class
        && type != Double.class
        && type != Character.class
        && !type.isPrimitive()
        && !type.isEnum()
        && !type.isArray()
        && !Collection.class.isAssignableFrom(type)
        && !Map.class.isAssignableFrom(type);
  }

  private static boolean isRecordField(JsonFieldInfo property) {
    Field field = property.writeField();
    return field != null && RecordUtils.isRecord(field.getDeclaringClass());
  }
}
