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
import org.apache.fory.json.codec.ObjectCodec.AnyInfo;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.resolver.JsonTypeInfo;

/**
 * Shared generated-class owner for the five concrete object-codec capabilities.
 *
 * <p>One instance belongs to one {@link org.apache.fory.json.resolver.JsonSharedRegistry}. Separate
 * concurrent caches single-flight one generated class per Java type and capability, so using only
 * one input or output representation never generates the other paths. Expression construction,
 * source generation, and Janino compilation happen without a resolver-local JIT lock.
 *
 * <p>This class owns classes only. Resolver-local generated instances, child capability capture,
 * {@link JsonTypeInfo} slot installation, and generated parent-field updates belong to {@link
 * org.apache.fory.json.resolver.JsonTypeResolver}. The raw types emitted for Janino stop at the
 * generated source and constructor boundary; handwritten runtime capability APIs remain generic.
 */
public final class JsonCodegen {
  private static final Map<String, Map<String, Integer>> ID_GENERATOR = new ConcurrentHashMap<>();

  private final int codegenHash;
  private final CodeGenerator codeGenerator;
  private final ClassLoader jsonLoader;
  private final Map<Class<?>, Class<?>> stringWriterClasses = new ConcurrentHashMap<>();
  private final Map<Class<?>, Class<?>> utf8WriterClasses = new ConcurrentHashMap<>();
  private final Map<Class<?>, Class<?>> stringObjectWriterClasses = new ConcurrentHashMap<>();
  private final Map<Class<?>, Class<?>> utf8ObjectWriterClasses = new ConcurrentHashMap<>();
  private final Map<Class<?>, Class<?>> latin1ReaderClasses = new ConcurrentHashMap<>();
  private final Map<Class<?>, Class<?>> utf16ReaderClasses = new ConcurrentHashMap<>();
  private final Map<Class<?>, Class<?>> utf8ReaderClasses = new ConcurrentHashMap<>();

  static String generatedCodecType(CodegenContext ctx, Class<?> codecType) {
    // Janino-generated serializers use erased types, matching Fory core code generation. Runtime
    // construction binds the instance to the typed Object capability once on the cold path. Do not
    // spread this source-language limitation into handwritten generic capability APIs.
    return ctx.type(codecType);
  }

  static String generatedCodecArrayType(CodegenContext ctx, Class<?> arrayType) {
    return ctx.type(arrayType);
  }

  public JsonCodegen(int codegenHash, ClassLoader jsonLoader) {
    this.codegenHash = codegenHash;
    this.jsonLoader = jsonLoader;
    codeGenerator = new CodeGenerator(jsonLoader);
  }

  /**
   * Compiles one concrete capability from fully resolved object metadata.
   *
   * <p>These compile methods run without a resolver-local JIT lock. Source-generation decisions may
   * inspect active codec classes only for non-default bindings, whose capability fields are never
   * replaced by generated raw-object codecs. Mutable default-object child capabilities are read
   * only by {@link org.apache.fory.json.resolver.JsonTypeResolver} while it constructs a
   * resolver-local instance under its JIT lock.
   *
   * <p>Generated classes are cached here because this object is shared by every pooled resolver of
   * one Fory JSON instance. Concurrent map computation provides generated-class single-flight;
   * resolver-local construction and capability publication belong to {@link
   * org.apache.fory.json.resolver.JsonTypeResolver} and are ordered by its generic {@link
   * JsonJITContext} callbacks.
   */
  @Internal
  public Class<?> compileStringWriter(ObjectCodec<?> codec) {
    if (!canCompileWriter(codec)) {
      return null;
    }
    return stringWriterClasses.computeIfAbsent(codec.type(), ignored -> buildStringWriter(codec));
  }

  @Internal
  public Class<?> compileUtf8Writer(ObjectCodec<?> codec) {
    if (!canCompileWriter(codec)) {
      return null;
    }
    return utf8WriterClasses.computeIfAbsent(codec.type(), ignored -> buildUtf8Writer(codec));
  }

  @Internal
  public Class<?> compileStringObjectWriter(ObjectCodec<?> codec) {
    if (!canCompileWriter(codec)) {
      return null;
    }
    return stringObjectWriterClasses.computeIfAbsent(
        codec.type(), ignored -> buildStringWriter(codec, true));
  }

  @Internal
  public Class<?> compileUtf8ObjectWriter(ObjectCodec<?> codec) {
    if (!canCompileWriter(codec)) {
      return null;
    }
    return utf8ObjectWriterClasses.computeIfAbsent(
        codec.type(), ignored -> buildUtf8Writer(codec, true));
  }

  @Internal
  public Class<?> compileLatin1Reader(ObjectCodec<?> codec) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return latin1ReaderClasses.computeIfAbsent(codec.type(), ignored -> buildLatin1Reader(codec));
  }

  @Internal
  public Class<?> compileUtf16Reader(ObjectCodec<?> codec) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return utf16ReaderClasses.computeIfAbsent(codec.type(), ignored -> buildUtf16Reader(codec));
  }

  @Internal
  public Class<?> compileUtf8Reader(ObjectCodec<?> codec) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return utf8ReaderClasses.computeIfAbsent(codec.type(), ignored -> buildUtf8Reader(codec));
  }

  @Internal
  public String stringWriterJITId(Class<?> type) {
    return jitId(type, "StringWriter");
  }

  @Internal
  public String utf8WriterJITId(Class<?> type) {
    return jitId(type, "Utf8Writer");
  }

  @Internal
  public String stringObjectWriterJITId(Class<?> type) {
    return jitId(type, "StringObjectWriter");
  }

  @Internal
  public String utf8ObjectWriterJITId(Class<?> type) {
    return jitId(type, "Utf8ObjectWriter");
  }

  @Internal
  public String latin1ReaderJITId(Class<?> type) {
    return jitId(type, "Latin1Reader");
  }

  @Internal
  public String utf16ReaderJITId(Class<?> type) {
    return jitId(type, "Utf16Reader");
  }

  @Internal
  public String utf8ReaderJITId(Class<?> type) {
    return jitId(type, "Utf8Reader");
  }

  private String jitId(Class<?> type, String role) {
    return qualifiedClassName(CodeGenerator.getPackage(type), className(type, role));
  }

  private Class<?> buildStringWriter(ObjectCodec<?> codec) {
    return buildStringWriter(codec, false);
  }

  private Class<?> buildStringWriter(ObjectCodec<?> codec, boolean objectMembers) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, objectMembers ? "StringObjectWriter" : "StringWriter");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    AnyInfo any = codec.anyInfo();
    String code =
        any == null || any.writeField() == null && any.writeGetter() == null
            ? new JsonWriterCodegen.StringGenerator(this)
                .genWriterCode(builder, type, codec.writeFields(), objectMembers)
            : new JsonWriterCodegen.StringGenerator(this)
                .genAnyWriterCode(builder, type, codec.writeFields(), any, objectMembers);
    return compileCodecClass(generatedPackage, className, code);
  }

  private Class<?> buildUtf8Writer(ObjectCodec<?> codec) {
    return buildUtf8Writer(codec, false);
  }

  private Class<?> buildUtf8Writer(ObjectCodec<?> codec, boolean objectMembers) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, objectMembers ? "Utf8ObjectWriter" : "Utf8Writer");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    AnyInfo any = codec.anyInfo();
    String code =
        any == null || any.writeField() == null && any.writeGetter() == null
            ? new JsonWriterCodegen.Utf8Generator(this)
                .genWriterCode(builder, type, codec.writeFields(), objectMembers)
            : new JsonWriterCodegen.Utf8Generator(this)
                .genAnyWriterCode(builder, type, codec.writeFields(), any, objectMembers);
    return compileCodecClass(generatedPackage, className, code);
  }

  private Class<?> buildLatin1Reader(ObjectCodec<?> codec) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Latin1Reader");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    AnyInfo any = codec.anyInfo();
    String code =
        any == null || any.readField() == null && any.readSetter() == null
            ? new JsonReaderCodegen.Latin1Generator(this)
                .genReaderCode(
                    builder, type, codec.readFields(), codec.isRecord(), codec.creatorInfo())
            : new JsonReaderCodegen.Latin1Generator(this)
                .genAnyReaderCode(
                    builder, type, codec.readFields(), codec.isRecord(), codec.creatorInfo(), any);
    return compileCodecClass(generatedPackage, className, code);
  }

  private Class<?> buildUtf16Reader(ObjectCodec<?> codec) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Utf16Reader");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    AnyInfo any = codec.anyInfo();
    String code =
        any == null || any.readField() == null && any.readSetter() == null
            ? new JsonReaderCodegen.Utf16Generator(this)
                .genReaderCode(
                    builder, type, codec.readFields(), codec.isRecord(), codec.creatorInfo())
            : new JsonReaderCodegen.Utf16Generator(this)
                .genAnyReaderCode(
                    builder, type, codec.readFields(), codec.isRecord(), codec.creatorInfo(), any);
    return compileCodecClass(generatedPackage, className, code);
  }

  private Class<?> buildUtf8Reader(ObjectCodec<?> codec) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Utf8Reader");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    AnyInfo any = codec.anyInfo();
    String code =
        any == null || any.readField() == null && any.readSetter() == null
            ? new JsonReaderCodegen.Utf8Generator(this)
                .genReaderCode(
                    builder, type, codec.readFields(), codec.isRecord(), codec.creatorInfo())
            : new JsonReaderCodegen.Utf8Generator(this)
                .genAnyReaderCode(
                    builder, type, codec.readFields(), codec.isRecord(), codec.creatorInfo(), any);
    return compileCodecClass(generatedPackage, className, code);
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

  @Internal
  public boolean canCompileWriter(ObjectCodec<?> codec) {
    if (!canCompileType(codec.type())) {
      return false;
    }
    JsonFieldInfo[] properties = codec.writeFields();
    for (int i = 0; i < properties.length; i++) {
      if (!canCompileWrite(properties[i])) {
        return false;
      }
    }
    AnyInfo any = codec.anyInfo();
    return any == null || canCompileAnyWrite(any);
  }

  @Internal
  public boolean canCompileReader(ObjectCodec<?> codec) {
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
    AnyInfo any = codec.anyInfo();
    return any == null || canCompileAnyRead(any, record, codec.creatorInfo() != null);
  }

  private boolean canCompileAnyWrite(AnyInfo any) {
    Field field = any.writeField();
    Method getter = any.writeGetter();
    if (field == null && getter == null) {
      return true;
    }
    if (getter != null && !canCall(getter)) {
      return false;
    }
    Class<?> mapType = getter == null ? field.getType() : getter.getReturnType();
    return isVisible(mapType) && isVisible(any.valueRawType());
  }

  private boolean canCompileAnyRead(AnyInfo any, boolean record, boolean creator) {
    Field field = any.readField();
    Method setter = any.readSetter();
    if (field == null && setter == null) {
      return true;
    }
    // Generated setter calls spell the value type in Java source, so class-loader visibility alone
    // is insufficient.
    if (setter != null && (!canCall(setter) || !canCompileType(setter.getParameterTypes()[1]))) {
      return false;
    }
    if (field != null && !isVisible(field.getType())) {
      return false;
    }
    if (setter != null && (record || creator)) {
      return false;
    }
    return isVisible(any.valueRawType());
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

  @Internal
  public static Class<?> readNestedType(JsonFieldInfo property) {
    if (property.readKind() == JsonFieldKind.OBJECT
        && property.readRawType() != Object.class
        && property.readTypeInfo().usesDefaultObjectCodec()) {
      return property.readRawType();
    }
    return null;
  }

  @Internal
  public static boolean usesWriteCodec(JsonFieldInfo property) {
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

  @Internal
  public static Class<?> writeNestedType(JsonFieldInfo property) {
    JsonTypeInfo typeInfo = writeObjectTypeInfo(property);
    return typeInfo == null ? null : typeInfo.rawType();
  }

  @Internal
  public static boolean usesReadCodec(JsonFieldInfo property) {
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

  @Internal
  public static boolean storesSelfReader(ObjectCodec<?> owner) {
    AnyInfo any = owner.anyInfo();
    if (any == null || any.readField() == null && any.readSetter() == null) {
      return false;
    }
    return storesSelfReader(owner.type(), owner.readFields(), owner.creatorInfo() != null, any);
  }

  static boolean storesSelfReader(
      Class<?> type, JsonFieldInfo[] properties, boolean creator, AnyInfo any) {
    if (any.valueRawType() == type && any.valueTypeInfo().usesDefaultObjectCodec()) {
      return true;
    }
    if (creator) {
      return false;
    }
    for (JsonFieldInfo property : properties) {
      if (readNestedType(property) == type) {
        return true;
      }
    }
    return false;
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
    return isPublicSourceType(type) && isVisible(type);
  }

  private boolean canCall(Method method) {
    return Modifier.isPublic(method.getModifiers())
        && isPublicSourceType(method.getDeclaringClass());
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
    // An array Class has no enclosing owner, but generated Java names its component type.
    while (type.isArray()) {
      type = type.getComponentType();
    }
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
}
