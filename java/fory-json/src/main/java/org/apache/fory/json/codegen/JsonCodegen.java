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
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.apache.fory.annotation.Internal;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.codegen.JaninoUtils;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.CodecUtils;
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
import org.apache.fory.json.resolver.JsonTypeResolver;

/**
 * Generates concrete object and exact-collection capability classes.
 *
 * <p>One instance belongs to one {@link org.apache.fory.json.resolver.JsonSharedRegistry}. The
 * registry owns every generated-class future and single-flight decision. A resolver is passed only
 * to the active source-generation call for short canonical metadata lookups; neither owner retains
 * it.
 *
 * <p>This class owns class generation only. Resolver-local generated instances, final direct-child
 * capture, canonical cycle slots, and {@link JsonTypeInfo} slot installation belong to {@link
 * org.apache.fory.json.resolver.JsonTypeResolver}. The raw types emitted for Janino stop at the
 * generated source and constructor boundary; handwritten runtime capability APIs remain generic.
 */
public final class JsonCodegen {
  // HotSpot's ordinary hot-callsite ceiling. Generated methods cross it with real schema work;
  // padding or compiler directives would make the boundary dependent on deployment flags.
  private static final int HOT_INLINE_LIMIT = 325;
  private static final Map<String, Map<String, Integer>> ID_GENERATOR = new ConcurrentHashMap<>();

  private final int codegenHash;
  private final CodeGenerator codeGenerator;
  private final ClassLoader jsonLoader;

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
   * <p>Source generation and Janino compilation are not enclosed by a resolver-local JIT lock.
   * Canonical child metadata is read through short resolver-owned lookups; source shape never
   * depends on mutable capability slots. Active codec classes are inspected only for non-canonical
   * bindings, whose capability fields are never replaced by generated raw-object codecs.
   *
   * <p>The shared registry caches the resulting class future for every pooled resolver of one Fory
   * JSON instance. Resolver-local construction and capability publication belong to {@link
   * org.apache.fory.json.resolver.JsonTypeResolver} and are ordered by its {@link JsonJITContext}.
   */
  @Internal
  public Class<?> compileStringWriter(ObjectCodec<?> codec, JsonTypeResolver resolver) {
    if (!canCompileWriter(codec)) {
      return null;
    }
    return buildStringWriter(codec, resolver);
  }

  @Internal
  public Class<?> compileUtf8Writer(ObjectCodec<?> codec, JsonTypeResolver resolver) {
    if (!canCompileWriter(codec)) {
      return null;
    }
    return buildUtf8Writer(codec, resolver);
  }

  @Internal
  public Class<?> compileLatin1Reader(ObjectCodec<?> codec, JsonTypeResolver resolver) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return buildLatin1Reader(codec, resolver);
  }

  @Internal
  public Class<?> compileUtf16Reader(ObjectCodec<?> codec, JsonTypeResolver resolver) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return buildUtf16Reader(codec, resolver);
  }

  @Internal
  public Class<?> compileUtf8Reader(
      ObjectCodec<?> codec, JsonTypeResolver resolver, boolean finalDependencies) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return buildUtf8Reader(codec, resolver, finalDependencies);
  }

  @Internal
  public Class<?> compileUtf8CollectionWriter(Type declaredType, CollectionCodec<?> owner) {
    Class<?> rawType = CodecUtils.rawType(declaredType, Collection.class);
    Class<?> elementType = CodecUtils.rawType(CodecUtils.elementType(declaredType), Object.class);
    String generatedPackage = CodeGenerator.getPackage(elementType);
    String className = className(elementType, simpleClassName(rawType) + "Utf8CollectionWriter");
    boolean stringElements = owner instanceof CollectionCodec.StringCollectionCodec;
    String code =
        new Utf8CollectionWriterCodegen().genCode(generatedPackage, className, stringElements);
    return compileCodecClass(generatedPackage, className, code);
  }

  @Internal
  public Class<?> compileUtf8CollectionReader(Type declaredType, CollectionCodec<?> owner) {
    if (!owner.createsArrayList()) {
      throw new IllegalArgumentException(
          "Generated UTF-8 collection requires an ArrayList binding");
    }
    Class<?> rawType = CodecUtils.rawType(declaredType, Collection.class);
    Class<?> elementType = CodecUtils.rawType(CodecUtils.elementType(declaredType), Object.class);
    String generatedPackage = CodeGenerator.getPackage(elementType);
    String className = className(elementType, simpleClassName(rawType) + "Utf8CollectionReader");
    boolean stringElements = owner instanceof CollectionCodec.StringCollectionCodec;
    String code =
        new Utf8CollectionReaderCodegen().genCode(generatedPackage, className, stringElements);
    return compileCodecClass(generatedPackage, className, code);
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

  private Class<?> buildStringWriter(ObjectCodec<?> codec, JsonTypeResolver resolver) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "StringWriter");
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new StringWriterCodegen(this, resolver)
              .genUnwrappedWriterCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code);
    }
    AnyInfo any = codec.anyInfo();
    JsonFieldInfo[] properties = codec.writeFields();
    if (any != null && (any.writeField() != null || any.writeGetter() != null)) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new StringWriterCodegen(this, resolver).genAnyWriterCode(builder, type, properties, any);
      return compileCodecClass(generatedPackage, className, code);
    }
    Function<int[], String> source =
        groupEnds -> {
          JsonGeneratedCodecBuilder builder =
              new JsonGeneratedCodecBuilder(generatedPackage, className, type);
          return new StringWriterCodegen(this, resolver)
              .genWriterCode(builder, type, properties, groupEnds);
        };
    return compileWriterClass(
        generatedPackage, className, properties, "writeString", "writeStringMembers", source);
  }

  private Class<?> buildUtf8Writer(ObjectCodec<?> codec, JsonTypeResolver resolver) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Utf8Writer");
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Utf8WriterCodegen(this, resolver)
              .genUnwrappedWriterCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code);
    }
    AnyInfo any = codec.anyInfo();
    JsonFieldInfo[] properties = codec.writeFields();
    if (any != null && (any.writeField() != null || any.writeGetter() != null)) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Utf8WriterCodegen(this, resolver).genAnyWriterCode(builder, type, properties, any);
      return compileCodecClass(generatedPackage, className, code);
    }
    Function<int[], String> source =
        groupEnds -> {
          JsonGeneratedCodecBuilder builder =
              new JsonGeneratedCodecBuilder(generatedPackage, className, type);
          return new Utf8WriterCodegen(this, resolver)
              .genWriterCode(builder, type, properties, groupEnds);
        };
    return compileWriterClass(
        generatedPackage, className, properties, "writeUtf8", "writeUtf8Members", source);
  }

  private Class<?> buildLatin1Reader(ObjectCodec<?> codec, JsonTypeResolver resolver) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Latin1Reader");
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Latin1ReaderCodegen(this, resolver)
              .genUnwrappedReaderCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code);
    }
    AnyInfo any = codec.anyInfo();
    JsonFieldInfo[] properties = codec.readFields();
    Function<int[], String> source =
        groupEnds -> {
          JsonGeneratedCodecBuilder builder =
              new JsonGeneratedCodecBuilder(generatedPackage, className, type);
          Latin1ReaderCodegen reader = new Latin1ReaderCodegen(this, resolver, groupEnds);
          return any == null || any.readField() == null && any.readSetter() == null
              ? reader.genReaderCode(builder, type, properties, codec.creatorInfo())
              : reader.genAnyReaderCode(builder, type, properties, codec.creatorInfo(), any);
        };
    return compileReaderClass(
        generatedPackage,
        className,
        properties.length,
        "readLatin1",
        codec.creatorInfo() == null,
        source);
  }

  private Class<?> buildUtf16Reader(ObjectCodec<?> codec, JsonTypeResolver resolver) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Utf16Reader");
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Utf16ReaderCodegen(this, resolver)
              .genUnwrappedReaderCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code);
    }
    AnyInfo any = codec.anyInfo();
    JsonFieldInfo[] properties = codec.readFields();
    Function<int[], String> source =
        groupEnds -> {
          JsonGeneratedCodecBuilder builder =
              new JsonGeneratedCodecBuilder(generatedPackage, className, type);
          Utf16ReaderCodegen reader = new Utf16ReaderCodegen(this, resolver, groupEnds);
          return any == null || any.readField() == null && any.readSetter() == null
              ? reader.genReaderCode(builder, type, properties, codec.creatorInfo())
              : reader.genAnyReaderCode(builder, type, properties, codec.creatorInfo(), any);
        };
    return compileReaderClass(
        generatedPackage,
        className,
        properties.length,
        "readUtf16",
        codec.creatorInfo() == null,
        source);
  }

  private Class<?> buildUtf8Reader(
      ObjectCodec<?> codec, JsonTypeResolver resolver, boolean finalDependencies) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(type, "Utf8Reader");
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Utf8ReaderCodegen(this, resolver, finalDependencies)
              .genUnwrappedReaderCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code);
    }
    AnyInfo any = codec.anyInfo();
    JsonFieldInfo[] properties = codec.readFields();
    Function<int[], String> source =
        groupEnds -> {
          JsonGeneratedCodecBuilder builder =
              new JsonGeneratedCodecBuilder(generatedPackage, className, type);
          Utf8ReaderCodegen reader =
              new Utf8ReaderCodegen(this, resolver, finalDependencies, groupEnds);
          return any == null || any.readField() == null && any.readSetter() == null
              ? reader.genReaderCode(builder, type, properties, codec.creatorInfo())
              : reader.genAnyReaderCode(builder, type, properties, codec.creatorInfo(), any);
        };
    return compileReaderClass(
        generatedPackage,
        className,
        properties.length,
        "readUtf8",
        codec.creatorInfo() == null,
        source);
  }

  private Class<?> compileReaderClass(
      String generatedPackage,
      String className,
      int propertyCount,
      String readMethod,
      boolean groupable,
      Function<int[], String> source) {
    int[] groupEnds =
        groupable
            ? readerGroupEnds(generatedPackage, className, propertyCount, readMethod, source)
            : oneGroup(propertyCount);
    return compileCodecClass(generatedPackage, className, source.apply(groupEnds));
  }

  private Class<?> compileWriterClass(
      String generatedPackage,
      String className,
      JsonFieldInfo[] properties,
      String writeMethod,
      String memberMethod,
      Function<int[], String> source) {
    if (properties.length < 2) {
      return compileCodecClass(generatedPackage, className, source.apply(null));
    }
    int[] oneGroup = new int[] {properties.length};
    JaninoUtils.CodeStats oneGroupStats =
        codeStats(generatedPackage, className, source.apply(oneGroup));
    if (privateMethodSize(oneGroupStats, writeMethod + "Object") <= HOT_INLINE_LIMIT) {
      return compileCodecClass(generatedPackage, className, source.apply(null));
    }
    int[] groupEnds =
        writerGroupEnds(
            generatedPackage,
            className,
            properties.length,
            JsonWriterCodegen.firstGroupMember(properties),
            writeMethod,
            memberMethod,
            source);
    return compileCodecClass(generatedPackage, className, source.apply(groupEnds));
  }

  private int[] writerGroupEnds(
      String generatedPackage,
      String className,
      int propertyCount,
      int firstGroupMember,
      String writeMethod,
      String memberMethod,
      Function<int[], String> source) {
    List<Integer> ends = new ArrayList<>(propertyCount - firstGroupMember);
    for (int end = firstGroupMember + 1; end <= propertyCount; end++) {
      ends.add(end);
    }
    if (ends.size() < 2) {
      return oneGroup(propertyCount);
    }
    while (ends.size() > 1) {
      int[] candidate = toIntArray(ends);
      JaninoUtils.CodeStats stats = codeStats(generatedPackage, className, source.apply(candidate));
      boolean merged = false;
      for (int group = 0; group < ends.size() - 1; group++) {
        String method = group == 0 ? memberMethod : memberMethod + group;
        if (privateMethodSize(stats, method) <= HOT_INLINE_LIMIT) {
          ends.remove(group);
          merged = true;
          break;
        }
      }
      if (merged) {
        continue;
      }
      return candidate;
    }
    return toIntArray(ends);
  }

  private int[] readerGroupEnds(
      String generatedPackage,
      String className,
      int propertyCount,
      String readMethod,
      Function<int[], String> source) {
    if (propertyCount < 2) {
      return oneGroup(propertyCount);
    }
    // Child method bodies do not belong to their generated caller's bytecode budget. Compile the
    // exact caller shape on this class-owned cold path, then merge declaration-order ranges until
    // every emitted helper and the root that owns the final range naturally cross the hot-inline
    // ceiling. Probe classes are never defined or dumped, so class publication and source shape
    // remain independent of capability-slot timing.
    List<Integer> ends = new ArrayList<>(propertyCount);
    for (int end = 1; end <= propertyCount; end++) {
      ends.add(end);
    }
    while (ends.size() > 1) {
      int[] candidate = toIntArray(ends);
      JaninoUtils.CodeStats stats = codeStats(generatedPackage, className, source.apply(candidate));
      int start = 0;
      boolean merged = false;
      for (int group = 0; group < ends.size() - 1; group++) {
        if (methodSize(stats, readMethod + "Group" + start) <= HOT_INLINE_LIMIT) {
          ends.remove(group);
          merged = true;
          break;
        }
        start = ends.get(group);
      }
      if (merged) {
        continue;
      }
      if (methodSize(stats, readMethod) <= HOT_INLINE_LIMIT) {
        ends.remove(ends.size() - 2);
        continue;
      }
      return candidate;
    }
    return toIntArray(ends);
  }

  private JaninoUtils.CodeStats codeStats(String generatedPackage, String className, String code) {
    CompileUnit unit = new CompileUnit(generatedPackage, className, code);
    Map<String, byte[]> classes = JaninoUtils.toBytecode(jsonLoader, "", unit);
    String classFile =
        (generatedPackage.isEmpty() ? "" : generatedPackage.replace('.', '/') + "/")
            + className
            + ".class";
    byte[] bytecode = classes.get(classFile);
    if (bytecode == null) {
      throw new ForyJsonException("Missing generated JSON bytecode " + classFile);
    }
    return JaninoUtils.getClassStats(bytecode);
  }

  private int methodSize(JaninoUtils.CodeStats stats, String method) {
    Integer size = stats.methodsSize.get(method);
    if (size == null) {
      throw new ForyJsonException(
          "Missing generated JSON method " + method + " in " + stats.methodsSize.keySet());
    }
    return size;
  }

  private int privateMethodSize(JaninoUtils.CodeStats stats, String sourceName) {
    return methodSize(stats, sourceName + "$");
  }

  private int[] oneGroup(int propertyCount) {
    return new int[] {propertyCount};
  }

  private int[] toIntArray(List<Integer> values) {
    int[] result = new int[values.size()];
    for (int i = 0; i < values.size(); i++) {
      result[i] = values.get(i);
    }
    return result;
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

  Class<?> stringWriterFieldType(JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    if (typeInfo.usesAnnotationCodec()) {
      return StringWriterCodec.class;
    }
    if (resolver.canonicalObjectCodec(typeInfo) != null) {
      return StringWriterCodec.class;
    }
    Object codec = typeInfo.stringWriter();
    Class<?> type = codec.getClass();
    if (isPublicSourceType(type) && isVisible(type)) {
      return type;
    }
    return StringWriterCodec.class;
  }

  Class<?> utf8WriterFieldType(JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    if (typeInfo.usesAnnotationCodec()) {
      return Utf8WriterCodec.class;
    }
    if (resolver.exactUtf8WriterCollection(typeInfo) != null) {
      return Utf8WriterCodec.class;
    }
    if (resolver.canonicalObjectCodec(typeInfo) != null) {
      return Utf8WriterCodec.class;
    }
    Object codec = typeInfo.utf8Writer();
    Class<?> type = codec.getClass();
    if (isPublicSourceType(type) && isVisible(type)) {
      return type;
    }
    return Utf8WriterCodec.class;
  }

  Class<?> latin1ReaderFieldType(JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    if (typeInfo.usesAnnotationCodec()) {
      return Latin1ReaderCodec.class;
    }
    if (resolver.canonicalObjectCodec(typeInfo) != null) {
      return Latin1ReaderCodec.class;
    }
    Class<?> type = typeInfo.latin1Reader().getClass();
    if (isPublicSourceType(type) && isVisible(type)) {
      return type;
    }
    return Latin1ReaderCodec.class;
  }

  Class<?> utf16ReaderFieldType(JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    if (typeInfo.usesAnnotationCodec()) {
      return Utf16ReaderCodec.class;
    }
    if (resolver.canonicalObjectCodec(typeInfo) != null) {
      return Utf16ReaderCodec.class;
    }
    Class<?> type = typeInfo.utf16Reader().getClass();
    if (isPublicSourceType(type) && isVisible(type)) {
      return type;
    }
    return Utf16ReaderCodec.class;
  }

  Class<?> utf8ReaderFieldType(
      JsonTypeInfo typeInfo, JsonTypeResolver resolver, boolean finalDependencies) {
    if (typeInfo.usesAnnotationCodec()) {
      return Utf8ReaderCodec.class;
    }
    if (finalDependencies && resolver.exactUtf8Collection(typeInfo) != null) {
      return Utf8ReaderCodec.class;
    }
    if (resolver.canonicalObjectCodec(typeInfo) != null) {
      return Utf8ReaderCodec.class;
    }
    Class<?> type = typeInfo.utf8Reader().getClass();
    if (isPublicSourceType(type) && isVisible(type)) {
      return type;
    }
    return Utf8ReaderCodec.class;
  }

  @Internal
  public static Class<?> readNestedType(JsonFieldInfo property, JsonTypeResolver resolver) {
    if (property.readKind() == JsonFieldKind.OBJECT
        && property.readRawType() != Object.class
        && resolver.canonicalObjectCodec(property.readTypeInfo()) != null) {
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

  @Internal
  public static boolean usesUtf8WriteCodec(JsonFieldInfo property, JsonTypeResolver resolver) {
    return usesWriteCodec(property)
        || property.writeKind() == JsonFieldKind.COLLECTION
            && resolver.exactUtf8WriterCollection(property.writeTypeInfo()) != null;
  }

  static boolean writesStringCollectionDirectly(JsonFieldInfo property) {
    return property.writeElementRawType() == String.class
        && property.writeTypeInfo().stringWriter().getClass()
            == CollectionCodec.StringCollectionCodec.class;
  }

  private static JsonTypeInfo writeObjectTypeInfo(
      JsonFieldInfo property, JsonTypeResolver resolver) {
    JsonTypeInfo typeInfo = property.writeTypeInfo();
    return usesWriteCodec(property) && resolver.canonicalObjectCodec(typeInfo) != null
        ? typeInfo
        : null;
  }

  @Internal
  public static Class<?> writeNestedType(JsonFieldInfo property, JsonTypeResolver resolver) {
    JsonTypeInfo typeInfo = writeObjectTypeInfo(property, resolver);
    return typeInfo == null ? null : typeInfo.rawType();
  }

  @Internal
  public static boolean usesReadCodec(JsonFieldInfo property, JsonTypeResolver resolver) {
    switch (property.readKind()) {
      case ENUM:
      case ARRAY:
      case COLLECTION:
      case MAP:
        return true;
      case OBJECT:
        return !usesReadObjectCodec(property, resolver);
      default:
        return false;
    }
  }

  static boolean usesReadObjectCodec(JsonFieldInfo property, JsonTypeResolver resolver) {
    return property.readKind() == JsonFieldKind.OBJECT
        && property.readRawType() != Object.class
        && resolver.canonicalObjectCodec(property.readTypeInfo()) != null;
  }

  static boolean storesReadObjectCodec(
      Class<?> type, JsonFieldInfo property, JsonTypeResolver resolver) {
    Class<?> nestedType = readNestedType(property, resolver);
    return nestedType != null && nestedType != type;
  }

  @Internal
  public static boolean storesSelfReader(ObjectCodec<?> owner, JsonTypeResolver resolver) {
    AnyInfo any = owner.anyInfo();
    if (any == null || any.readField() == null && any.readSetter() == null) {
      return false;
    }
    if (storesSelfReader(
        owner.type(), owner.readFields(), owner.creatorInfo() != null, any, resolver)) {
      return true;
    }
    JsonUnwrappedInfo unwrapped = owner.unwrappedInfo();
    if (unwrapped != null) {
      for (JsonUnwrappedInfo.ReadRoute route : unwrapped.readRoutes()) {
        if (route.field() != null && readNestedType(route.field(), resolver) == owner.type()) {
          return true;
        }
      }
    }
    return false;
  }

  static boolean storesSelfReader(
      Class<?> type,
      JsonFieldInfo[] properties,
      boolean creator,
      AnyInfo any,
      JsonTypeResolver resolver) {
    if (any.valueRawType() == type && resolver.canonicalObjectCodec(any.valueTypeInfo()) != null) {
      return true;
    }
    if (creator) {
      return false;
    }
    for (JsonFieldInfo property : properties) {
      if (readNestedType(property, resolver) == type) {
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
