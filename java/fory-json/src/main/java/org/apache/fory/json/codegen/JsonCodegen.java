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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.annotation.Internal;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.CollectionCodec;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
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
  private static final Map<String, Map<String, Integer>> ID_GENERATOR = new ConcurrentHashMap<>();

  final boolean writeNullFields;
  private final int codegenHash;
  private final CodeGenerator codeGenerator;
  private final ClassLoader jsonLoader;
  private final ConcurrentHashMap<Class<?>, GeneratedStringWriterClass> stringWriters;
  private final ConcurrentHashMap<Class<?>, GeneratedUtf8WriterClass> utf8Writers;
  private final ConcurrentHashMap<Class<?>, GeneratedLatin1ReaderClass> latin1Readers;
  private final ConcurrentHashMap<Class<?>, GeneratedUtf16ReaderClass> utf16Readers;
  private final ConcurrentHashMap<Class<?>, GeneratedUtf8ReaderClass> utf8Readers;

  static String generatedCodecType(CodegenContext ctx, Class<?> codecType) {
    // Janino-generated serializers use erased types, matching Fory core code generation. Runtime
    // construction binds the instance to the typed Object capability once on the cold path.
    return ctx.type(codecType);
  }

  static String generatedCodecArrayType(CodegenContext ctx, Class<?> arrayType) {
    return ctx.type(arrayType);
  }

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

  GeneratedStringWriterClass stringWriterClass(Class<?> type) {
    return stringWriters.get(type);
  }

  GeneratedUtf8WriterClass utf8WriterClass(Class<?> type) {
    return utf8Writers.get(type);
  }

  GeneratedLatin1ReaderClass latin1ReaderClass(Class<?> type) {
    return latin1Readers.get(type);
  }

  GeneratedUtf16ReaderClass utf16ReaderClass(Class<?> type) {
    return utf16Readers.get(type);
  }

  GeneratedUtf8ReaderClass utf8ReaderClass(Class<?> type) {
    return utf8Readers.get(type);
  }

  GeneratedStringWriterClass compileStringWriter(ObjectCodec<?> codec) {
    if (!canCompileWriter(codec)) {
      return null;
    }
    return stringWriters.computeIfAbsent(codec.type(), ignored -> buildStringWriter(codec));
  }

  GeneratedUtf8WriterClass compileUtf8Writer(ObjectCodec<?> codec) {
    if (!canCompileWriter(codec)) {
      return null;
    }
    return utf8Writers.computeIfAbsent(codec.type(), ignored -> buildUtf8Writer(codec));
  }

  GeneratedLatin1ReaderClass compileLatin1Reader(ObjectCodec<?> codec) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return latin1Readers.computeIfAbsent(codec.type(), ignored -> buildLatin1Reader(codec));
  }

  GeneratedUtf16ReaderClass compileUtf16Reader(ObjectCodec<?> codec) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return utf16Readers.computeIfAbsent(codec.type(), ignored -> buildUtf16Reader(codec));
  }

  GeneratedUtf8ReaderClass compileUtf8Reader(ObjectCodec<?> codec) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return utf8Readers.computeIfAbsent(codec.type(), ignored -> buildUtf8Reader(codec));
  }

  @SuppressWarnings("unchecked")
  StringWriterCodec<Object> newStringWriter(
      ObjectCodec<?> owner, JsonTypeResolver resolver, GeneratedStringWriterClass generatedClass) {
    JsonFieldInfo[] properties = owner.writeFields();
    StringWriterCodec<Object>[] codecs =
        (StringWriterCodec<Object>[]) new StringWriterCodec<?>[properties.length];
    for (int i = 0; i < properties.length; i++) {
      if (usesWriteCodec(properties[i])) {
        codecs[i] = stringWriter(properties[i]);
      }
    }
    StringWriterCodec<Object> writer = generatedClass.newCodec(properties, codecs);
    registerStringWriterUpdates(resolver, writer, owner.type(), properties);
    return writer;
  }

  @SuppressWarnings("unchecked")
  Utf8WriterCodec<Object> newUtf8Writer(
      ObjectCodec<?> owner, JsonTypeResolver resolver, GeneratedUtf8WriterClass generatedClass) {
    JsonFieldInfo[] properties = owner.writeFields();
    Utf8WriterCodec<Object>[] codecs =
        (Utf8WriterCodec<Object>[]) new Utf8WriterCodec<?>[properties.length];
    for (int i = 0; i < properties.length; i++) {
      if (usesWriteCodec(properties[i])) {
        codecs[i] = utf8Writer(properties[i]);
      }
    }
    Utf8WriterCodec<Object> writer = generatedClass.newCodec(properties, codecs);
    registerUtf8WriterUpdates(resolver, writer, owner.type(), properties);
    return writer;
  }

  @SuppressWarnings("unchecked")
  Latin1ReaderCodec<Object> newLatin1Reader(
      ObjectCodec<?> owner, JsonTypeResolver resolver, GeneratedLatin1ReaderClass generatedClass) {
    JsonFieldInfo[] properties = owner.readFields();
    Latin1ReaderCodec<Object>[] codecs =
        (Latin1ReaderCodec<Object>[]) new Latin1ReaderCodec<?>[properties.length << 1];
    collectLatin1Readers(owner.type(), properties, codecs);
    Latin1ReaderCodec<Object> reader = generatedClass.newCodec(owner, properties, codecs);
    registerLatin1ReaderUpdates(resolver, reader, owner.type(), properties);
    return reader;
  }

  @SuppressWarnings("unchecked")
  Utf16ReaderCodec<Object> newUtf16Reader(
      ObjectCodec<?> owner, JsonTypeResolver resolver, GeneratedUtf16ReaderClass generatedClass) {
    JsonFieldInfo[] properties = owner.readFields();
    Utf16ReaderCodec<Object>[] codecs =
        (Utf16ReaderCodec<Object>[]) new Utf16ReaderCodec<?>[properties.length << 1];
    collectUtf16Readers(owner.type(), properties, codecs);
    Utf16ReaderCodec<Object> reader = generatedClass.newCodec(owner, properties, codecs);
    registerUtf16ReaderUpdates(resolver, reader, owner.type(), properties);
    return reader;
  }

  @SuppressWarnings("unchecked")
  Utf8ReaderCodec<Object> newUtf8Reader(
      ObjectCodec<?> owner, JsonTypeResolver resolver, GeneratedUtf8ReaderClass generatedClass) {
    JsonFieldInfo[] properties = owner.readFields();
    Utf8ReaderCodec<Object>[] codecs =
        (Utf8ReaderCodec<Object>[]) new Utf8ReaderCodec<?>[properties.length << 1];
    collectUtf8Readers(owner.type(), properties, codecs);
    Utf8ReaderCodec<Object> reader = generatedClass.newCodec(owner, properties, codecs);
    registerUtf8ReaderUpdates(resolver, reader, owner.type(), properties);
    return reader;
  }

  private GeneratedStringWriterClass buildStringWriter(ObjectCodec<?> codec) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "StringWriter");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    String code =
        new JsonWriterCodegen.StringGenerator(this)
            .genWriterCode(builder, type, codec.writeFields());
    return new GeneratedStringWriterClass(compileCodecClass(generatedPackage, className, code));
  }

  private GeneratedUtf8WriterClass buildUtf8Writer(ObjectCodec<?> codec) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Utf8Writer");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    String code =
        new JsonWriterCodegen.Utf8Generator(this).genWriterCode(builder, type, codec.writeFields());
    return new GeneratedUtf8WriterClass(compileCodecClass(generatedPackage, className, code));
  }

  private GeneratedLatin1ReaderClass buildLatin1Reader(ObjectCodec<?> codec) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Latin1Reader");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    String code =
        new JsonReaderCodegen.Latin1Generator(this)
            .genReaderCode(builder, type, codec.readFields(), codec.isRecord());
    return new GeneratedLatin1ReaderClass(compileCodecClass(generatedPackage, className, code));
  }

  private GeneratedUtf16ReaderClass buildUtf16Reader(ObjectCodec<?> codec) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Utf16Reader");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    String code =
        new JsonReaderCodegen.Utf16Generator(this)
            .genReaderCode(builder, type, codec.readFields(), codec.isRecord());
    return new GeneratedUtf16ReaderClass(compileCodecClass(generatedPackage, className, code));
  }

  private GeneratedUtf8ReaderClass buildUtf8Reader(ObjectCodec<?> codec) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Utf8Reader");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    String code =
        new JsonReaderCodegen.Utf8Generator(this)
            .genReaderCode(builder, type, codec.readFields(), codec.isRecord());
    return new GeneratedUtf8ReaderClass(compileCodecClass(generatedPackage, className, code));
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

  boolean canCompileWriter(ObjectCodec<?> codec) {
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

  boolean canCompileReader(ObjectCodec<?> codec) {
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

  Class<?> stringWriterFieldType(JsonTypeInfo typeInfo) {
    if (typeInfo.usesDefaultObjectCodec()) {
      return StringWriterCodec.class;
    }
    Object codec = typeInfo.stringWriter();
    Class<?> type = codec.getClass();
    if (isPublicSourceType(type) && isVisible(type)) {
      return type;
    }
    return StringWriterCodec.class;
  }

  Class<?> utf8WriterFieldType(JsonTypeInfo typeInfo) {
    if (typeInfo.usesDefaultObjectCodec()) {
      return Utf8WriterCodec.class;
    }
    Object codec = typeInfo.utf8Writer();
    Class<?> type = codec.getClass();
    if (isPublicSourceType(type) && isVisible(type)) {
      return type;
    }
    return Utf8WriterCodec.class;
  }

  Class<?> latin1ReaderFieldType(JsonTypeInfo typeInfo) {
    if (typeInfo.usesDefaultObjectCodec()) {
      return Latin1ReaderCodec.class;
    }
    Class<?> type = typeInfo.latin1Reader().getClass();
    if (isPublicSourceType(type) && isVisible(type)) {
      return type;
    }
    return Latin1ReaderCodec.class;
  }

  Class<?> utf16ReaderFieldType(JsonTypeInfo typeInfo) {
    if (typeInfo.usesDefaultObjectCodec()) {
      return Utf16ReaderCodec.class;
    }
    Class<?> type = typeInfo.utf16Reader().getClass();
    if (isPublicSourceType(type) && isVisible(type)) {
      return type;
    }
    return Utf16ReaderCodec.class;
  }

  Class<?> utf8ReaderFieldType(JsonTypeInfo typeInfo) {
    if (typeInfo.usesDefaultObjectCodec()) {
      return Utf8ReaderCodec.class;
    }
    Class<?> type = typeInfo.utf8Reader().getClass();
    if (isPublicSourceType(type) && isVisible(type)) {
      return type;
    }
    return Utf8ReaderCodec.class;
  }

  static Class<?> readNestedType(JsonFieldInfo property) {
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

  private static JsonTypeInfo writeObjectTypeInfo(JsonFieldInfo property) {
    JsonTypeInfo typeInfo = property.writeTypeInfo();
    return usesWriteCodec(property) && typeInfo.usesDefaultObjectCodec() ? typeInfo : null;
  }

  private static StringWriterCodec<Object> stringWriter(JsonFieldInfo property) {
    JsonTypeInfo typeInfo = writeObjectTypeInfo(property);
    return typeInfo == null ? property.writeTypeInfo().stringWriter() : typeInfo.stringWriter();
  }

  private static Utf8WriterCodec<Object> utf8Writer(JsonFieldInfo property) {
    JsonTypeInfo typeInfo = writeObjectTypeInfo(property);
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

  static boolean usesReadObjectCodec(JsonFieldInfo property) {
    return property.readKind() == JsonFieldKind.OBJECT
        && property.readRawType() != Object.class
        && property.readTypeInfo().usesDefaultObjectCodec();
  }

  static boolean storesReadObjectCodec(Class<?> type, JsonFieldInfo property) {
    Class<?> nestedType = readNestedType(property);
    return nestedType != null && nestedType != type;
  }

  static JsonTypeInfo readObjectTypeInfo(JsonFieldInfo property) {
    return readNestedType(property) == null ? null : property.readTypeInfo();
  }

  private static void collectLatin1Readers(
      Class<?> type, JsonFieldInfo[] properties, Latin1ReaderCodec<Object>[] codecs) {
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      if (usesReadCodec(property)) {
        codecs[i] = property.readTypeInfo().latin1Reader();
      }
      if (storesReadObjectCodec(type, property)) {
        codecs[properties.length + i] =
            (Latin1ReaderCodec<Object>) property.readTypeInfo().latin1Reader();
      }
    }
  }

  private static void collectUtf16Readers(
      Class<?> type, JsonFieldInfo[] properties, Utf16ReaderCodec<Object>[] codecs) {
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      if (usesReadCodec(property)) {
        codecs[i] = property.readTypeInfo().utf16Reader();
      }
      if (storesReadObjectCodec(type, property)) {
        codecs[properties.length + i] =
            (Utf16ReaderCodec<Object>) property.readTypeInfo().utf16Reader();
      }
    }
  }

  private static void collectUtf8Readers(
      Class<?> type, JsonFieldInfo[] properties, Utf8ReaderCodec<Object>[] codecs) {
    for (int i = 0; i < properties.length; i++) {
      JsonFieldInfo property = properties[i];
      if (usesReadCodec(property)) {
        codecs[i] = property.readTypeInfo().utf8Reader();
      }
      if (storesReadObjectCodec(type, property)) {
        codecs[properties.length + i] =
            (Utf8ReaderCodec<Object>) property.readTypeInfo().utf8Reader();
      }
    }
  }

  private static void registerStringWriterUpdates(
      JsonTypeResolver resolver, Object owner, Class<?> type, JsonFieldInfo[] properties) {
    for (int i = 0; i < properties.length; i++) {
      JsonTypeInfo typeInfo = writeObjectTypeInfo(properties[i]);
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
      JsonTypeInfo typeInfo = writeObjectTypeInfo(properties[i]);
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
    return true;
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
    return true;
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

  @Internal
  static final class GeneratedStringWriterClass {
    private final MethodHandle constructor;
    private final Constructor<?> androidConstructor;

    private GeneratedStringWriterClass(Class<?> codecClass) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          androidConstructor =
              codecClass.getDeclaredConstructor(JsonFieldInfo[].class, StringWriterCodec[].class);
          androidConstructor.setAccessible(true);
          constructor = null;
        } else {
          constructor =
              _JDKAccess._trustedLookup(codecClass)
                  .findConstructor(
                      codecClass,
                      MethodType.methodType(
                          void.class, JsonFieldInfo[].class, StringWriterCodec[].class));
          androidConstructor = null;
        }
      } catch (Throwable e) {
        throw new ForyJsonException(
            "Cannot resolve generated JSON String writer constructor " + codecClass.getName(), e);
      }
    }

    @SuppressWarnings("unchecked")
    private StringWriterCodec<Object> newCodec(
        JsonFieldInfo[] properties, StringWriterCodec<Object>[] codecs) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          return (StringWriterCodec<Object>) androidConstructor.newInstance(properties, codecs);
        }
        return (StringWriterCodec<Object>) constructor.invoke(properties, codecs);
      } catch (Throwable e) {
        throw new ForyJsonException("Cannot instantiate generated JSON String writer", e);
      }
    }
  }

  @Internal
  static final class GeneratedUtf8WriterClass {
    private final MethodHandle constructor;
    private final Constructor<?> androidConstructor;

    private GeneratedUtf8WriterClass(Class<?> codecClass) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          androidConstructor =
              codecClass.getDeclaredConstructor(JsonFieldInfo[].class, Utf8WriterCodec[].class);
          androidConstructor.setAccessible(true);
          constructor = null;
        } else {
          constructor =
              _JDKAccess._trustedLookup(codecClass)
                  .findConstructor(
                      codecClass,
                      MethodType.methodType(
                          void.class, JsonFieldInfo[].class, Utf8WriterCodec[].class));
          androidConstructor = null;
        }
      } catch (Throwable e) {
        throw new ForyJsonException(
            "Cannot resolve generated JSON UTF8 writer constructor " + codecClass.getName(), e);
      }
    }

    @SuppressWarnings("unchecked")
    private Utf8WriterCodec<Object> newCodec(
        JsonFieldInfo[] properties, Utf8WriterCodec<Object>[] codecs) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          return (Utf8WriterCodec<Object>) androidConstructor.newInstance(properties, codecs);
        }
        return (Utf8WriterCodec<Object>) constructor.invoke(properties, codecs);
      } catch (Throwable e) {
        throw new ForyJsonException("Cannot instantiate generated JSON UTF8 writer", e);
      }
    }
  }

  @Internal
  static final class GeneratedLatin1ReaderClass {
    private final MethodHandle constructor;
    private final Constructor<?> androidConstructor;

    private GeneratedLatin1ReaderClass(Class<?> codecClass) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          androidConstructor =
              codecClass.getDeclaredConstructor(
                  ObjectCodec.class, JsonFieldInfo[].class, Latin1ReaderCodec[].class);
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
                          Latin1ReaderCodec[].class));
          androidConstructor = null;
        }
      } catch (Throwable e) {
        throw new ForyJsonException(
            "Cannot resolve generated JSON Latin1 reader constructor " + codecClass.getName(), e);
      }
    }

    @SuppressWarnings("unchecked")
    private Latin1ReaderCodec<Object> newCodec(
        ObjectCodec<?> owner, JsonFieldInfo[] properties, Latin1ReaderCodec<Object>[] codecs) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          return (Latin1ReaderCodec<Object>)
              androidConstructor.newInstance(owner, properties, codecs);
        }
        return (Latin1ReaderCodec<Object>) constructor.invoke(owner, properties, codecs);
      } catch (Throwable e) {
        throw new ForyJsonException("Cannot instantiate generated JSON Latin1 reader", e);
      }
    }
  }

  @Internal
  static final class GeneratedUtf16ReaderClass {
    private final MethodHandle constructor;
    private final Constructor<?> androidConstructor;

    private GeneratedUtf16ReaderClass(Class<?> codecClass) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          androidConstructor =
              codecClass.getDeclaredConstructor(
                  ObjectCodec.class, JsonFieldInfo[].class, Utf16ReaderCodec[].class);
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
                          Utf16ReaderCodec[].class));
          androidConstructor = null;
        }
      } catch (Throwable e) {
        throw new ForyJsonException(
            "Cannot resolve generated JSON UTF16 reader constructor " + codecClass.getName(), e);
      }
    }

    @SuppressWarnings("unchecked")
    private Utf16ReaderCodec<Object> newCodec(
        ObjectCodec<?> owner, JsonFieldInfo[] properties, Utf16ReaderCodec<Object>[] codecs) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          return (Utf16ReaderCodec<Object>)
              androidConstructor.newInstance(owner, properties, codecs);
        }
        return (Utf16ReaderCodec<Object>) constructor.invoke(owner, properties, codecs);
      } catch (Throwable e) {
        throw new ForyJsonException("Cannot instantiate generated JSON UTF16 reader", e);
      }
    }
  }

  @Internal
  static final class GeneratedUtf8ReaderClass {
    private final MethodHandle constructor;
    private final Constructor<?> androidConstructor;

    private GeneratedUtf8ReaderClass(Class<?> codecClass) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          androidConstructor =
              codecClass.getDeclaredConstructor(
                  ObjectCodec.class, JsonFieldInfo[].class, Utf8ReaderCodec[].class);
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
                          Utf8ReaderCodec[].class));
          androidConstructor = null;
        }
      } catch (Throwable e) {
        throw new ForyJsonException(
            "Cannot resolve generated JSON UTF8 reader constructor " + codecClass.getName(), e);
      }
    }

    @SuppressWarnings("unchecked")
    private Utf8ReaderCodec<Object> newCodec(
        ObjectCodec<?> owner, JsonFieldInfo[] properties, Utf8ReaderCodec<Object>[] codecs) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          return (Utf8ReaderCodec<Object>)
              androidConstructor.newInstance(owner, properties, codecs);
        }
        return (Utf8ReaderCodec<Object>) constructor.invoke(owner, properties, codecs);
      } catch (Throwable e) {
        throw new ForyJsonException("Cannot instantiate generated JSON UTF8 reader", e);
      }
    }
  }
}
