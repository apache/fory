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
import org.apache.fory.json.codec.JsonUnwrappedInfo;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ObjectCodec.AnyInfo;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
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
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "StringWriter");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      String code =
          new StringWriterCodegen(this).genUnwrappedWriterCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code);
    }
    AnyInfo any = codec.anyInfo();
    String code =
        any == null || any.writeField() == null && any.writeGetter() == null
            ? new StringWriterCodegen(this).genWriterCode(builder, type, codec.writeFields())
            : new StringWriterCodegen(this)
                .genAnyWriterCode(builder, type, codec.writeFields(), any);
    return compileCodecClass(generatedPackage, className, code);
  }

  private Class<?> buildUtf8Writer(ObjectCodec<?> codec) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Utf8Writer");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      String code =
          new Utf8WriterCodegen(this).genUnwrappedWriterCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code);
    }
    AnyInfo any = codec.anyInfo();
    String code =
        any == null || any.writeField() == null && any.writeGetter() == null
            ? new Utf8WriterCodegen(this).genWriterCode(builder, type, codec.writeFields())
            : new Utf8WriterCodegen(this).genAnyWriterCode(builder, type, codec.writeFields(), any);
    return compileCodecClass(generatedPackage, className, code);
  }

  private Class<?> buildLatin1Reader(ObjectCodec<?> codec) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Latin1Reader");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      String code =
          new Latin1ReaderCodegen(this).genUnwrappedReaderCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code);
    }
    AnyInfo any = codec.anyInfo();
    String code =
        any == null || any.readField() == null && any.readSetter() == null
            ? new Latin1ReaderCodegen(this)
                .genReaderCode(builder, type, codec.readFields(), codec.creatorInfo())
            : new Latin1ReaderCodegen(this)
                .genAnyReaderCode(builder, type, codec.readFields(), codec.creatorInfo(), any);
    return compileCodecClass(generatedPackage, className, code);
  }

  private Class<?> buildUtf16Reader(ObjectCodec<?> codec) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Utf16Reader");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      String code =
          new Utf16ReaderCodegen(this).genUnwrappedReaderCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code);
    }
    AnyInfo any = codec.anyInfo();
    String code =
        any == null || any.readField() == null && any.readSetter() == null
            ? new Utf16ReaderCodegen(this)
                .genReaderCode(builder, type, codec.readFields(), codec.creatorInfo())
            : new Utf16ReaderCodegen(this)
                .genAnyReaderCode(builder, type, codec.readFields(), codec.creatorInfo(), any);
    return compileCodecClass(generatedPackage, className, code);
  }

  private Class<?> buildUtf8Reader(ObjectCodec<?> codec) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Utf8Reader");
    JsonGeneratedCodecBuilder builder =
        new JsonGeneratedCodecBuilder(generatedPackage, className, type);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      String code =
          new Utf8ReaderCodegen(this).genUnwrappedReaderCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code);
    }
    AnyInfo any = codec.anyInfo();
    String code =
        any == null || any.readField() == null && any.readSetter() == null
            ? new Utf8ReaderCodegen(this)
                .genReaderCode(builder, type, codec.readFields(), codec.creatorInfo())
            : new Utf8ReaderCodegen(this)
                .genAnyReaderCode(builder, type, codec.readFields(), codec.creatorInfo(), any);
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
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      return canCompileUnwrappedWrite(codec, unwrapped.writeEntries());
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

  private boolean canCompileUnwrappedWrite(
      ObjectCodec<?> owner, JsonUnwrappedInfo.WriteEntry[] entries) {
    for (JsonUnwrappedInfo.WriteEntry entry : entries) {
      if (entry.kind() == JsonUnwrappedInfo.DIRECT) {
        if (!canCompileWrite(entry.field())) {
          return false;
        }
      } else if (entry.kind() == JsonUnwrappedInfo.GROUP) {
        JsonUnwrappedInfo.Declaration declaration = entry.group().declaration();
        Method getter = declaration.writeAccessor().getter();
        if (getter != null && !canCall(getter)) {
          return false;
        }
        if (!isVisible(entry.group().childCodec().type())
            || !canCompileUnwrappedWrite(owner, entry.group().writeEntries())) {
          return false;
        }
      }
    }
    AnyInfo any = owner.anyInfo();
    return any == null || canCompileAnyWrite(any);
  }

  @Internal
  public boolean canCompileReader(ObjectCodec<?> codec) {
    if (!canCompileType(codec.type())) {
      return false;
    }
    JsonCreatorInfo creator = codec.creatorInfo();
    if (creator != null) {
      for (Class<?> parameterType : creator.executable().getParameterTypes()) {
        if (!canCompileType(parameterType)) {
          return false;
        }
      }
    }
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      return canCompileUnwrappedRead(codec, unwrapped);
    }
    JsonFieldInfo[] properties = codec.readFields();
    for (int i = 0; i < properties.length; i++) {
      if (!canCompileRead(properties[i])) {
        return false;
      }
    }
    AnyInfo any = codec.anyInfo();
    return any == null || canCompileAnyRead(any, codec.creatorInfo() != null);
  }

  private boolean canCompileUnwrappedRead(ObjectCodec<?> owner, JsonUnwrappedInfo unwrapped) {
    for (JsonFieldInfo field : owner.readFields()) {
      if (!canCompileRead(field)) {
        return false;
      }
    }
    for (JsonUnwrappedInfo.Group group : unwrapped.groups()) {
      JsonUnwrappedInfo.Declaration declaration = group.declaration();
      Method setter =
          declaration.readAccessor() == null ? null : declaration.readAccessor().setter();
      if (setter != null && !canCall(setter)) {
        return false;
      }
      if (!isVisible(group.childCodec().type())) {
        return false;
      }
      JsonCreatorInfo creator = group.childCodec().creatorInfo();
      if (creator != null) {
        for (Class<?> parameterType : creator.executable().getParameterTypes()) {
          if (!canCompileType(parameterType)) {
            return false;
          }
        }
      }
    }
    for (JsonUnwrappedInfo.ReadRoute route : unwrapped.readRoutes()) {
      JsonFieldInfo field = route.field();
      if (field != null && !canCompileRead(field)) {
        return false;
      }
      JsonCreatorFieldInfo creatorField = route.creatorField();
      if (creatorField != null && !canCompileType(creatorField.rawType())) {
        return false;
      }
    }
    AnyInfo any = owner.anyInfo();
    return any == null || canCompileAnyRead(any, owner.creatorInfo() != null);
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
    if (field != null && !canCompileField(field)) {
      return false;
    }
    Class<?> mapType = getter == null ? field.getType() : getter.getReturnType();
    return isVisible(mapType) && isVisible(any.valueRawType());
  }

  private boolean canCompileAnyRead(AnyInfo any, boolean creator) {
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
    if (field != null && !canCompileField(field)) {
      return false;
    }
    if (setter != null && creator) {
      return false;
    }
    return isVisible(any.valueRawType());
  }

  Class<?> stringWriterFieldType(JsonTypeInfo typeInfo) {
    if (typeInfo.usesAnnotationCodec()) {
      return StringWriterCodec.class;
    }
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
    if (typeInfo.usesAnnotationCodec()) {
      return Utf8WriterCodec.class;
    }
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
    if (typeInfo.usesAnnotationCodec()) {
      return Latin1ReaderCodec.class;
    }
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
    if (typeInfo.usesAnnotationCodec()) {
      return Utf16ReaderCodec.class;
    }
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
    if (typeInfo.usesAnnotationCodec()) {
      return Utf8ReaderCodec.class;
    }
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
    if (storesSelfReader(owner.type(), owner.readFields(), owner.creatorInfo() != null, any)) {
      return true;
    }
    JsonUnwrappedInfo unwrapped = owner.unwrappedInfo();
    if (unwrapped != null) {
      for (JsonUnwrappedInfo.ReadRoute route : unwrapped.readRoutes()) {
        if (route.field() != null && readNestedType(route.field()) == owner.type()) {
          return true;
        }
      }
    }
    return false;
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
    if (field != null && !canCompileField(field)) {
      return false;
    }
    Class<?> rawType = property.writeRawType();
    if (rawType != null && !rawType.isPrimitive() && !isVisible(rawType)) {
      return false;
    }
    return true;
  }

  private boolean canCompileRead(JsonFieldInfo property) {
    if (property.readAccessor() == null) {
      return false;
    }
    if (property.readSetter() != null && !canCall(property.readSetter())) {
      return false;
    }
    if (property.readField() != null && !canCompileField(property.readField())) {
      return false;
    }
    // Generated field accessors deliberately have no Fory core FieldAccessor. The selected Field
    // remains the runtime-codegen owner, so exact field metadata is sufficient for direct codegen.
    if (property.readSetter() == null && property.readField() == null) {
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

  private boolean canCompileField(Field field) {
    // Descriptor emits public fields as direct Java member access. A public field inherited from
    // an inaccessible declaring class is reflectively visible but cannot be resolved by Janino.
    // Non-public fields use the existing generated accessor path and do not spell their owner.
    return !Modifier.isPublic(field.getModifiers()) || canCompileType(field.getDeclaringClass());
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
