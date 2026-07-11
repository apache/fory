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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.annotation.Internal;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.CollectionCodec;
import org.apache.fory.json.codec.Latin1ObjectReaderCodec;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.StringObjectWriterCodec;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ObjectReaderCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ObjectReaderCodec;
import org.apache.fory.json.codec.Utf8ObjectWriterCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.reflect.ReflectionUtils;

public final class JsonCodegen {
  static final int LATIN1_READER = 1;
  static final int UTF16_READER = 2;
  static final int UTF8_READER = 3;

  private static final Map<String, Map<String, Integer>> ID_GENERATOR = new ConcurrentHashMap<>();

  final boolean writeNullFields;
  private final int codegenHash;
  private final CodeGenerator codeGenerator;
  private final ClassLoader jsonLoader;
  private final ConcurrentHashMap<Class<?>, GeneratedWriterClass> stringWriters;
  private final ConcurrentHashMap<Class<?>, GeneratedWriterClass> utf8Writers;
  private final ConcurrentHashMap<Class<?>, GeneratedReaderClass> latin1Readers;
  private final ConcurrentHashMap<Class<?>, GeneratedReaderClass> utf16Readers;
  private final ConcurrentHashMap<Class<?>, GeneratedReaderClass> utf8Readers;

  public JsonCodegen(boolean writeNullFields, int codegenHash) {
    this.writeNullFields = writeNullFields;
    this.codegenHash = codegenHash;
    jsonLoader = JsonCodegen.class.getClassLoader();
    codeGenerator = new CodeGenerator(jsonLoader);
    stringWriters = new ConcurrentHashMap<>();
    utf8Writers = new ConcurrentHashMap<>();
    latin1Readers = new ConcurrentHashMap<>();
    utf16Readers = new ConcurrentHashMap<>();
    utf8Readers = new ConcurrentHashMap<>();
  }

  GeneratedWriterClass stringWriterClass(Class<?> type) {
    return stringWriters.get(type);
  }

  GeneratedWriterClass utf8WriterClass(Class<?> type) {
    return utf8Writers.get(type);
  }

  GeneratedReaderClass latin1ReaderClass(Class<?> type) {
    return latin1Readers.get(type);
  }

  GeneratedReaderClass utf16ReaderClass(Class<?> type) {
    return utf16Readers.get(type);
  }

  GeneratedReaderClass utf8ReaderClass(Class<?> type) {
    return utf8Readers.get(type);
  }

  GeneratedWriterClass compileStringWriter(ObjectCodec codec) {
    if (!canCompileWriter(codec)) {
      return null;
    }
    return stringWriters.computeIfAbsent(
        codec.type(), ignored -> buildWriter(codec, JsonCodecPath.STRING_WRITER));
  }

  GeneratedWriterClass compileUtf8Writer(ObjectCodec codec) {
    if (!canCompileWriter(codec)) {
      return null;
    }
    return utf8Writers.computeIfAbsent(
        codec.type(), ignored -> buildWriter(codec, JsonCodecPath.UTF8_WRITER));
  }

  GeneratedReaderClass compileLatin1Reader(ObjectCodec codec) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return latin1Readers.computeIfAbsent(
        codec.type(), ignored -> buildReader(codec, JsonCodecPath.LATIN1_READER));
  }

  GeneratedReaderClass compileUtf16Reader(ObjectCodec codec) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return utf16Readers.computeIfAbsent(
        codec.type(), ignored -> buildReader(codec, JsonCodecPath.UTF16_READER));
  }

  GeneratedReaderClass compileUtf8Reader(ObjectCodec codec) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return utf8Readers.computeIfAbsent(
        codec.type(), ignored -> buildReader(codec, JsonCodecPath.UTF8_READER));
  }

  StringObjectWriterCodec newStringWriter(
      ObjectCodec owner, JsonTypeResolver resolver, GeneratedWriterClass generatedClass) {
    JsonFieldInfo[] properties = owner.writeFields();
    StringWriterCodec[] codecs = new StringWriterCodec[properties.length];
    for (int i = 0; i < properties.length; i++) {
      if (usesWriteCodec(properties[i])) {
        codecs[i] = stringWriter(properties[i], resolver);
      }
    }
    StringObjectWriterCodec writer =
        (StringObjectWriterCodec) generatedClass.newCodec(properties, codecs);
    registerStringWriterUpdates(resolver, writer, owner.type(), properties);
    return writer;
  }

  Utf8ObjectWriterCodec newUtf8Writer(
      ObjectCodec owner, JsonTypeResolver resolver, GeneratedWriterClass generatedClass) {
    JsonFieldInfo[] properties = owner.writeFields();
    Utf8WriterCodec[] codecs = new Utf8WriterCodec[properties.length];
    for (int i = 0; i < properties.length; i++) {
      if (usesWriteCodec(properties[i])) {
        codecs[i] = utf8Writer(properties[i], resolver);
      }
    }
    Utf8ObjectWriterCodec writer =
        (Utf8ObjectWriterCodec) generatedClass.newCodec(properties, codecs);
    registerUtf8WriterUpdates(resolver, writer, owner.type(), properties);
    return writer;
  }

  Latin1ObjectReaderCodec newLatin1Reader(
      ObjectCodec owner, JsonTypeResolver resolver, GeneratedReaderClass generatedClass) {
    JsonFieldInfo[] properties = owner.readFields();
    Latin1ReaderCodec[] codecs = new Latin1ReaderCodec[properties.length];
    Latin1ObjectReaderCodec[] objects = new Latin1ObjectReaderCodec[properties.length];
    collectLatin1Readers(owner.type(), properties, codecs, objects);
    Latin1ObjectReaderCodec reader =
        (Latin1ObjectReaderCodec) generatedClass.newCodec(owner, properties, codecs, objects);
    registerLatin1ReaderUpdates(resolver, reader, owner.type(), properties);
    return reader;
  }

  Utf16ObjectReaderCodec newUtf16Reader(
      ObjectCodec owner, JsonTypeResolver resolver, GeneratedReaderClass generatedClass) {
    JsonFieldInfo[] properties = owner.readFields();
    Utf16ReaderCodec[] codecs = new Utf16ReaderCodec[properties.length];
    Utf16ObjectReaderCodec[] objects = new Utf16ObjectReaderCodec[properties.length];
    collectUtf16Readers(owner.type(), properties, codecs, objects);
    Utf16ObjectReaderCodec reader =
        (Utf16ObjectReaderCodec) generatedClass.newCodec(owner, properties, codecs, objects);
    registerUtf16ReaderUpdates(resolver, reader, owner.type(), properties);
    return reader;
  }

  Utf8ObjectReaderCodec newUtf8Reader(
      ObjectCodec owner, JsonTypeResolver resolver, GeneratedReaderClass generatedClass) {
    JsonFieldInfo[] properties = owner.readFields();
    Utf8ReaderCodec[] codecs = new Utf8ReaderCodec[properties.length];
    Utf8ObjectReaderCodec[] objects = new Utf8ObjectReaderCodec[properties.length];
    collectUtf8Readers(owner.type(), properties, codecs, objects);
    Utf8ObjectReaderCodec reader =
        (Utf8ObjectReaderCodec) generatedClass.newCodec(owner, properties, codecs, objects);
    registerUtf8ReaderUpdates(resolver, reader, owner.type(), properties);
    return reader;
  }

  private GeneratedWriterClass buildWriter(ObjectCodec codec, JsonCodecPath path) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String role = path == JsonCodecPath.STRING_WRITER ? "StringWriter" : "Utf8Writer";
    String className = className(type, role);
    String code =
        new JsonGeneratedCodecBuilder(
                this,
                generatedPackage,
                className,
                type,
                codec.writeFields(),
                path,
                codec.isRecord())
            .genCode();
    return new GeneratedWriterClass(
        compileCodecClass(generatedPackage, className, code), path == JsonCodecPath.UTF8_WRITER);
  }

  private GeneratedReaderClass buildReader(ObjectCodec codec, JsonCodecPath path) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, readerRole(path));
    String code =
        new JsonGeneratedCodecBuilder(
                this, generatedPackage, className, type, codec.readFields(), path, codec.isRecord())
            .genCode();
    return new GeneratedReaderClass(
        compileCodecClass(generatedPackage, className, code), readerMode(path));
  }

  private Class<?> compileCodecClass(String generatedPackage, String className, String code) {
    try {
      CompileUnit unit = new CompileUnit(generatedPackage, className, code);
      ClassLoader classLoader = codeGenerator.compile(unit);
      return classLoader.loadClass(qualifiedClassName(generatedPackage, className));
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot compile generated JSON codec " + className, e);
    }
  }

  boolean canCompileWriter(ObjectCodec codec) {
    if (!canCompileType(codec.type())) {
      return false;
    }
    JsonFieldInfo[] properties = codec.writeFields();
    for (int i = 0; i < properties.length; i++) {
      if (!canCompileWrite(properties[i])) {
        return false;
      }
    }
    return true;
  }

  boolean canCompileReader(ObjectCodec codec) {
    if (!canCompileType(codec.type())) {
      return false;
    }
    boolean record = codec.isRecord();
    JsonFieldInfo[] properties = codec.readFields();
    for (int i = 0; i < properties.length; i++) {
      if (!canCompileRead(properties[i], record)) {
        return false;
      }
    }
    return true;
  }

  Class<?> writerFieldType(JsonTypeInfo typeInfo, boolean utf8) {
    if (typeInfo.usesDefaultObjectCodec()) {
      return utf8 ? Utf8ObjectWriterCodec.class : StringObjectWriterCodec.class;
    }
    Object codec = utf8 ? typeInfo.utf8Writer() : typeInfo.stringWriter();
    Class<?> type = codec.getClass();
    if (isPublicSourceType(type) && isVisible(type)) {
      return type;
    }
    return utf8 ? Utf8WriterCodec.class : StringWriterCodec.class;
  }

  Class<?> readerFieldType(JsonTypeInfo typeInfo, int readerMode) {
    if (typeInfo.usesDefaultObjectCodec()) {
      return objectReaderType(readerMode);
    }
    Object codec;
    switch (readerMode) {
      case LATIN1_READER:
        codec = typeInfo.latin1Reader();
        break;
      case UTF16_READER:
        codec = typeInfo.utf16Reader();
        break;
      case UTF8_READER:
        codec = typeInfo.utf8Reader();
        break;
      default:
        throw new IllegalArgumentException(String.valueOf(readerMode));
    }
    Class<?> type = codec.getClass();
    if (isPublicSourceType(type) && isVisible(type)) {
      return type;
    }
    return readerType(readerMode);
  }

  static Class<?> readNestedType(JsonFieldInfo property) {
    if (readsObjectCollectionDirectly(property)) {
      return property.readElementRawType();
    }
    if (property.readKind() == JsonFieldKind.OBJECT
        && property.readRawType() != Object.class
        && property.readTypeInfo().usesDefaultObjectCodec()) {
      return property.readRawType();
    }
    return null;
  }

  static boolean usesWriteCodec(JsonFieldInfo property) {
    switch (property.writeKind()) {
      case ARRAY:
      case MAP:
      case OBJECT:
        return true;
      case COLLECTION:
        return !writesStringCollectionDirectly(property);
      default:
        return false;
    }
  }

  static boolean writesStringCollectionDirectly(JsonFieldInfo property) {
    return property.writeElementRawType() == String.class
        && property.writeTypeInfo().stringWriter().getClass()
            == CollectionCodec.StringCollectionCodec.class;
  }

  static boolean writesObjectCollectionDirectly(JsonFieldInfo property) {
    return property.writeKind() == JsonFieldKind.COLLECTION
        && property.writeElementRawType() != null
        && property.writeTypeInfo().stringWriter().getClass()
            == CollectionCodec.ObjectCollectionCodec.class
        && property.writeTypeInfo().utf8Writer().getClass()
            == CollectionCodec.ObjectCollectionCodec.class;
  }

  static JsonTypeInfo writeObjectTypeInfo(
      JsonFieldInfo property, JsonTypeResolver resolver) {
    if (writesObjectCollectionDirectly(property)) {
      return resolver.getTypeInfo(property.writeElementType(), property.writeElementRawType());
    }
    JsonTypeInfo typeInfo = property.writeTypeInfo();
    return usesWriteCodec(property) && typeInfo.usesDefaultObjectCodec() ? typeInfo : null;
  }

  private static StringWriterCodec stringWriter(JsonFieldInfo property, JsonTypeResolver resolver) {
    JsonTypeInfo typeInfo = writeObjectTypeInfo(property, resolver);
    return typeInfo == null ? property.writeTypeInfo().stringWriter() : typeInfo.stringWriter();
  }

  private static Utf8WriterCodec utf8Writer(JsonFieldInfo property, JsonTypeResolver resolver) {
    JsonTypeInfo typeInfo = writeObjectTypeInfo(property, resolver);
    return typeInfo == null ? property.writeTypeInfo().utf8Writer() : typeInfo.utf8Writer();
  }

  static boolean usesReadCodec(JsonFieldInfo property) {
    switch (property.readKind()) {
      case ENUM:
      case ARRAY:
      case COLLECTION:
      case MAP:
        return true;
      case OBJECT:
        return !usesReadObjectCodec(property);
      default:
        return false;
    }
  }

  static boolean storesReadCodec(JsonFieldInfo property) {
    if (readsObjectCollectionDirectly(property)) {
      return !((CollectionCodec.ObjectCollectionCodec) property.readTypeInfo().latin1Reader())
          .createsArrayList();
    }
    return usesReadCodec(property);
  }

  static boolean usesReadTypeField(JsonFieldInfo property) {
    switch (property.readKind()) {
      case ARRAY:
      case COLLECTION:
      case MAP:
      case OBJECT:
        return true;
      default:
        return false;
    }
  }

  static boolean usesReadObjectCodec(JsonFieldInfo property) {
    return property.readKind() == JsonFieldKind.OBJECT
        && property.readRawType() != Object.class
        && property.readTypeInfo().usesDefaultObjectCodec();
  }

  static boolean readsObjectCollectionDirectly(JsonFieldInfo property) {
    JsonTypeInfo elementTypeInfo = property.readElementTypeInfo();
    return property.readKind() == JsonFieldKind.COLLECTION
        && property.readElementRawType() != null
        && elementTypeInfo != null
        && elementTypeInfo.usesDefaultObjectCodec()
        && property.readTypeInfo().latin1Reader().getClass()
            == CollectionCodec.ObjectCollectionCodec.class
        && property.readTypeInfo().utf16Reader().getClass()
            == CollectionCodec.ObjectCollectionCodec.class
        && property.readTypeInfo().utf8Reader().getClass()
            == CollectionCodec.ObjectCollectionCodec.class;
  }

  static boolean storesReadObjectCodec(Class<?> type, JsonFieldInfo property) {
    Class<?> nestedType = readNestedType(property);
    return nestedType != null && nestedType != type;
  }

  static JsonTypeInfo readObjectTypeInfo(JsonFieldInfo property) {
    return readNestedType(property) == null ? null : nestedReadTypeInfo(property);
  }

  private static JsonTypeInfo nestedReadTypeInfo(JsonFieldInfo property) {
    return readsObjectCollectionDirectly(property)
        ? property.readElementTypeInfo()
        : property.readTypeInfo();
  }

  private static void collectLatin1Readers(
      Class<?> type,
      JsonFieldInfo[] properties,
      Latin1ReaderCodec[] codecs,
      Latin1ObjectReaderCodec[] objects) {
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      if (storesReadCodec(property)) {
        codecs[i] = property.readTypeInfo().latin1Reader();
      }
      if (storesReadObjectCodec(type, property)) {
        objects[i] = (Latin1ObjectReaderCodec) nestedReadTypeInfo(property).latin1Reader();
      }
    }
  }

  private static void collectUtf16Readers(
      Class<?> type,
      JsonFieldInfo[] properties,
      Utf16ReaderCodec[] codecs,
      Utf16ObjectReaderCodec[] objects) {
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      if (storesReadCodec(property)) {
        codecs[i] = property.readTypeInfo().utf16Reader();
      }
      if (storesReadObjectCodec(type, property)) {
        objects[i] = (Utf16ObjectReaderCodec) nestedReadTypeInfo(property).utf16Reader();
      }
    }
  }

  private static void collectUtf8Readers(
      Class<?> type,
      JsonFieldInfo[] properties,
      Utf8ReaderCodec[] codecs,
      Utf8ObjectReaderCodec[] objects) {
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      if (storesReadCodec(property)) {
        codecs[i] = property.readTypeInfo().utf8Reader();
      }
      if (storesReadObjectCodec(type, property)) {
        objects[i] = (Utf8ObjectReaderCodec) nestedReadTypeInfo(property).utf8Reader();
      }
    }
  }

  private static void registerStringWriterUpdates(
      JsonTypeResolver resolver, Object owner, Class<?> type, JsonFieldInfo[] properties) {
    for (int i = 0; i < properties.length; i++) {
      JsonTypeInfo typeInfo = writeObjectTypeInfo(properties[i], resolver);
      Class<?> nestedType = typeInfo == null ? null : typeInfo.rawType();
      if (nestedType != null && nestedType != type) {
        Field field = ReflectionUtils.getField(owner.getClass(), "w" + i);
        resolver.registerStringWriterUpdate(nestedType, value -> setField(owner, field, value));
      }
    }
  }

  private static void registerUtf8WriterUpdates(
      JsonTypeResolver resolver, Object owner, Class<?> type, JsonFieldInfo[] properties) {
    for (int i = 0; i < properties.length; i++) {
      JsonTypeInfo typeInfo = writeObjectTypeInfo(properties[i], resolver);
      Class<?> nestedType = typeInfo == null ? null : typeInfo.rawType();
      if (nestedType != null && nestedType != type) {
        Field field = ReflectionUtils.getField(owner.getClass(), "w" + i);
        resolver.registerUtf8WriterUpdate(nestedType, value -> setField(owner, field, value));
      }
    }
  }

  private static void registerLatin1ReaderUpdates(
      JsonTypeResolver resolver, Object owner, Class<?> type, JsonFieldInfo[] properties) {
    for (int i = 0; i < properties.length; i++) {
      JsonTypeInfo typeInfo = readObjectTypeInfo(properties[i]);
      Class<?> nestedType = typeInfo == null ? null : typeInfo.rawType();
      if (nestedType != null && nestedType != type) {
        Field field = ReflectionUtils.getField(owner.getClass(), "o" + i);
        resolver.registerLatin1ReaderUpdate(nestedType, value -> setField(owner, field, value));
      }
    }
  }

  private static void registerUtf16ReaderUpdates(
      JsonTypeResolver resolver, Object owner, Class<?> type, JsonFieldInfo[] properties) {
    for (int i = 0; i < properties.length; i++) {
      JsonTypeInfo typeInfo = readObjectTypeInfo(properties[i]);
      Class<?> nestedType = typeInfo == null ? null : typeInfo.rawType();
      if (nestedType != null && nestedType != type) {
        Field field = ReflectionUtils.getField(owner.getClass(), "o" + i);
        resolver.registerUtf16ReaderUpdate(nestedType, value -> setField(owner, field, value));
      }
    }
  }

  private static void registerUtf8ReaderUpdates(
      JsonTypeResolver resolver, Object owner, Class<?> type, JsonFieldInfo[] properties) {
    for (int i = 0; i < properties.length; i++) {
      JsonTypeInfo typeInfo = readObjectTypeInfo(properties[i]);
      Class<?> nestedType = typeInfo == null ? null : typeInfo.rawType();
      if (nestedType != null && nestedType != type) {
        Field field = ReflectionUtils.getField(owner.getClass(), "o" + i);
        resolver.registerUtf8ReaderUpdate(nestedType, value -> setField(owner, field, value));
      }
    }
  }

  private static void setField(Object owner, Field field, Object value) {
    ReflectionUtils.setObjectFieldValue(owner, field, value);
  }

  private boolean canCompileWrite(JsonFieldInfo property) {
    Field field = property.writeField();
    if (field == null && property.writeGetter() == null) {
      return false;
    }
    if (property.writeGetter() != null && !canCall(property.writeGetter())) {
      return false;
    }
    Class<?> rawType = property.writeRawType();
    if (rawType != null && !rawType.isPrimitive() && !isVisible(rawType)) {
      return false;
    }
    if (property.writeKind() != JsonFieldKind.COLLECTION) {
      return true;
    }
    Class<?> elementType = property.writeElementRawType();
    return !isPojo(elementType) || isVisible(elementType);
  }

  private boolean canCompileRead(JsonFieldInfo property, boolean record) {
    if (!record && property.readAccessor() == null) {
      return false;
    }
    if (!record && property.readSetter() != null && !canCall(property.readSetter())) {
      return false;
    }
    if (!record
        && property.readSetter() == null
        && property.readAccessor().coreAccessor() == null) {
      return false;
    }
    Class<?> rawType = property.readRawType();
    if (rawType != null && !rawType.isPrimitive() && !isVisible(rawType)) {
      return false;
    }
    if (property.readKind() != JsonFieldKind.COLLECTION) {
      return true;
    }
    Class<?> elementType = property.readElementRawType();
    return !isPojo(elementType) || isVisible(elementType);
  }

  private boolean canCompileType(Class<?> type) {
    return CodeGenerator.sourcePublicAccessible(type) && isVisible(type);
  }

  private boolean canCall(Method method) {
    return Modifier.isPublic(method.getModifiers())
        && CodeGenerator.sourcePublicAccessible(method.getDeclaringClass());
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
    try {
      return Class.forName(type.getName(), false, jsonLoader) == type;
    } catch (ClassNotFoundException e) {
      return false;
    }
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

  private static Class<?> readerType(int readerMode) {
    switch (readerMode) {
      case LATIN1_READER:
        return Latin1ReaderCodec.class;
      case UTF16_READER:
        return Utf16ReaderCodec.class;
      case UTF8_READER:
        return Utf8ReaderCodec.class;
      default:
        throw new IllegalArgumentException(String.valueOf(readerMode));
    }
  }

  private static Class<?> objectReaderType(int readerMode) {
    switch (readerMode) {
      case LATIN1_READER:
        return Latin1ObjectReaderCodec.class;
      case UTF16_READER:
        return Utf16ObjectReaderCodec.class;
      case UTF8_READER:
        return Utf8ObjectReaderCodec.class;
      default:
        throw new IllegalArgumentException(String.valueOf(readerMode));
    }
  }

  private static int readerMode(JsonCodecPath path) {
    switch (path) {
      case LATIN1_READER:
        return LATIN1_READER;
      case UTF16_READER:
        return UTF16_READER;
      case UTF8_READER:
        return UTF8_READER;
      default:
        throw new IllegalArgumentException(String.valueOf(path));
    }
  }

  private static String readerRole(JsonCodecPath path) {
    switch (path) {
      case LATIN1_READER:
        return "Latin1Reader";
      case UTF16_READER:
        return "Utf16Reader";
      case UTF8_READER:
        return "Utf8Reader";
      default:
        throw new IllegalArgumentException(String.valueOf(path));
    }
  }

  private String className(Class<?> type, String role) {
    String name = simpleClassName(type) + role + "ForyJsonCodec";
    Map<String, Integer> subGenerator =
        ID_GENERATOR.computeIfAbsent(name, key -> new ConcurrentHashMap<>());
    String key = codegenHash + "_" + CodeGenerator.getClassUniqueId(type);
    Integer id = subGenerator.get(key);
    if (id == null) {
      synchronized (subGenerator) {
        id = subGenerator.computeIfAbsent(key, ignored -> subGenerator.size());
      }
    }
    return id == 0 ? name : name + id;
  }

  private static String simpleClassName(Class<?> type) {
    String name = type.getName();
    Package declaringPackage = type.getPackage();
    if (declaringPackage != null) {
      String prefix = declaringPackage.getName() + ".";
      if (name.startsWith(prefix)) {
        name = name.substring(prefix.length());
      }
    } else {
      int separator = name.lastIndexOf('.');
      if (separator >= 0) {
        name = name.substring(separator + 1);
      }
    }
    return name.replace('.', '_').replace('$', '_');
  }

  private static String qualifiedClassName(String generatedPackage, String className) {
    return generatedPackage.isEmpty() ? className : generatedPackage + "." + className;
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

  @Internal
  static final class GeneratedWriterClass {
    private final boolean utf8;
    private final MethodHandle constructor;
    private final Constructor<?> androidConstructor;

    private GeneratedWriterClass(Class<?> codecClass, boolean utf8) {
      this.utf8 = utf8;
      try {
        Class<?> arrayType = utf8 ? Utf8WriterCodec[].class : StringWriterCodec[].class;
        if (AndroidSupport.IS_ANDROID) {
          androidConstructor = codecClass.getDeclaredConstructor(JsonFieldInfo[].class, arrayType);
          androidConstructor.setAccessible(true);
          constructor = null;
        } else {
          constructor =
              _JDKAccess._trustedLookup(codecClass)
                  .findConstructor(
                      codecClass,
                      MethodType.methodType(void.class, JsonFieldInfo[].class, arrayType));
          androidConstructor = null;
        }
      } catch (Throwable e) {
        throw new ForyJsonException(
            "Cannot resolve generated JSON writer constructor " + codecClass.getName(), e);
      }
    }

    private Object newCodec(JsonFieldInfo[] properties, Object codecs) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          return androidConstructor.newInstance(properties, codecs);
        }
        return constructor.invoke(properties, codecs);
      } catch (Throwable e) {
        throw new ForyJsonException(
            "Cannot instantiate generated " + (utf8 ? "UTF8" : "String") + " writer", e);
      }
    }
  }

  @Internal
  static final class GeneratedReaderClass {
    private final int readerMode;
    private final MethodHandle constructor;
    private final Constructor<?> androidConstructor;

    private GeneratedReaderClass(Class<?> codecClass, int readerMode) {
      this.readerMode = readerMode;
      try {
        Class<?> codecArray = readerArrayType(readerMode);
        Class<?> objectArray = objectReaderArrayType(readerMode);
        if (AndroidSupport.IS_ANDROID) {
          androidConstructor =
              codecClass.getDeclaredConstructor(
                  ObjectCodec.class, JsonFieldInfo[].class, codecArray, objectArray);
          androidConstructor.setAccessible(true);
          constructor = null;
        } else {
          constructor =
              _JDKAccess._trustedLookup(codecClass)
                  .findConstructor(
                      codecClass,
                      MethodType.methodType(
                          void.class,
                          ObjectCodec.class,
                          JsonFieldInfo[].class,
                          codecArray,
                          objectArray));
          androidConstructor = null;
        }
      } catch (Throwable e) {
        throw new ForyJsonException(
            "Cannot resolve generated JSON reader constructor " + codecClass.getName(), e);
      }
    }

    private Object newCodec(
        ObjectCodec owner, JsonFieldInfo[] properties, Object codecs, Object objects) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          return androidConstructor.newInstance(owner, properties, codecs, objects);
        }
        return constructor.invoke(owner, properties, codecs, objects);
      } catch (Throwable e) {
        throw new ForyJsonException(
            "Cannot instantiate generated JSON reader mode " + readerMode, e);
      }
    }

    private static Class<?> readerArrayType(int readerMode) {
      switch (readerMode) {
        case LATIN1_READER:
          return Latin1ReaderCodec[].class;
        case UTF16_READER:
          return Utf16ReaderCodec[].class;
        case UTF8_READER:
          return Utf8ReaderCodec[].class;
        default:
          throw new IllegalArgumentException(String.valueOf(readerMode));
      }
    }

    private static Class<?> objectReaderArrayType(int readerMode) {
      switch (readerMode) {
        case LATIN1_READER:
          return Latin1ObjectReaderCodec[].class;
        case UTF16_READER:
          return Utf16ObjectReaderCodec[].class;
        case UTF8_READER:
          return Utf8ObjectReaderCodec[].class;
        default:
          throw new IllegalArgumentException(String.valueOf(readerMode));
      }
    }
  }
}
