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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.fory.annotation.Internal;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.codegen.JaninoUtils;
import org.apache.fory.codegen.JaninoUtils.DirectInvocation;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.CollectionCodec;
import org.apache.fory.json.codec.DirectUnboxedValueCodec;
import org.apache.fory.json.codec.JsonUnwrappedInfo;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ObjectCodec.AnyInfo;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.TransparentUnboxedValueCodec;
import org.apache.fory.json.codec.UnboxedValueCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldKind;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.platform.internal.DefineClass;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.reflect.TypeRef;

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
  // HotSpot JDK 25's measured hot-callsite bytecode ceiling. This is a local generated-method
  // limit, not a transitive subtree estimate: once a concrete String/scalar/container callee owns
  // a natural method larger than this limit, a generated group pays only the call bytecodes. Large
  // generated groups cross the limit with real schema work and call one another directly. Never
  // use padding, annotations, or compiler directives to manufacture the boundary, and never add
  // the already-independent callee body back to this planner's budget.
  private static final int HOT_INLINE_LIMIT = 325;
  private static final int GENERATED_NAME_PREFIX_CODE_POINTS = 32;
  private final String codegenIdentity;
  private final CodeGenerator codeGenerator;
  private final ClassLoader jsonLoader;
  private final boolean hostedCodegen;

  static String generatedCodecType(CodegenContext ctx, Class<?> codecType) {
    // Janino-generated serializers use erased types, matching Fory core code generation. Runtime
    // construction binds the instance to the typed Object capability once on the cold path. Do not
    // spread this source-language limitation into handwritten generic capability APIs.
    return ctx.type(codecType);
  }

  static String generatedCodecArrayType(CodegenContext ctx, Class<?> arrayType) {
    return ctx.type(arrayType);
  }

  public JsonCodegen(JsonCodegenKey codegenKey, ClassLoader jsonLoader, boolean hostedCodegen) {
    codegenIdentity = codegenKey.identity();
    this.jsonLoader = jsonLoader;
    this.hostedCodegen = hostedCodegen;
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
  public Class<?> compileStringWriter(
      TypeRef<?> declaredType, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    if (!canCompileWriter(codec)) {
      return null;
    }
    return buildStringWriter(declaredType, codec, resolver);
  }

  @Internal
  public Class<?> compileUtf8Writer(
      TypeRef<?> declaredType, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    if (!canCompileWriter(codec)) {
      return null;
    }
    return buildUtf8Writer(declaredType, codec, resolver);
  }

  @Internal
  public Class<?> compileLatin1Reader(
      TypeRef<?> declaredType, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return buildLatin1Reader(declaredType, codec, resolver);
  }

  @Internal
  public Class<?> compileUtf16Reader(
      TypeRef<?> declaredType, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return buildUtf16Reader(declaredType, codec, resolver);
  }

  @Internal
  public Class<?> compileUtf8Reader(
      TypeRef<?> declaredType, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    if (!canCompileReader(codec)) {
      return null;
    }
    return buildUtf8Reader(declaredType, codec, resolver);
  }

  @Internal
  public Class<?> compileUtf8CollectionWriter(TypeRef<?> declaredType, CollectionCodec<?> owner) {
    Type type = declaredType.getType();
    Class<?> rawType = CodecUtils.rawType(type, Collection.class);
    Class<?> elementType = CodecUtils.rawType(CodecUtils.elementType(type), Object.class);
    String generatedPackage = CodeGenerator.getPackage(elementType);
    boolean stringElements = owner instanceof CollectionCodec.StringCollectionCodec;
    String className =
        className(declaredType, simpleClassName(rawType) + "Utf8CollectionWriter", stringElements);
    String code =
        new Utf8CollectionWriterCodegen().genCode(generatedPackage, className, stringElements);
    return compileCodecClass(generatedPackage, className, code);
  }

  @Internal
  public Class<?> compileUtf8CollectionReader(TypeRef<?> declaredType, CollectionCodec<?> owner) {
    if (!owner.createsArrayList()) {
      throw new IllegalArgumentException(
          "Generated UTF-8 collection requires an ArrayList binding");
    }
    Type type = declaredType.getType();
    Class<?> rawType = CodecUtils.rawType(type, Collection.class);
    Class<?> elementType = CodecUtils.rawType(CodecUtils.elementType(type), Object.class);
    String generatedPackage = CodeGenerator.getPackage(elementType);
    boolean stringElements = owner instanceof CollectionCodec.StringCollectionCodec;
    String className =
        className(declaredType, simpleClassName(rawType) + "Utf8CollectionReader", stringElements);
    String code =
        new Utf8CollectionReaderCodegen().genCode(generatedPackage, className, stringElements);
    return compileCodecClass(generatedPackage, className, code);
  }

  private DirectInvocation[] writerInvocations(ObjectCodec<?> codec) {
    LinkedHashMap<String, DirectInvocation> invocations = new LinkedHashMap<>();
    for (JsonFieldInfo field : codec.writeFields()) {
      addWriteInvocations(invocations, field);
    }
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      for (JsonFieldInfo field : unwrapped.writeFields()) {
        addWriteInvocations(invocations, field);
      }
      for (JsonUnwrappedInfo.Group group : unwrapped.groups()) {
        JsonFieldAccessor accessor = group.declaration().writeAccessor();
        Method getter = accessor == null ? null : accessor.getter();
        if (getter != null && !DirectMethodCodegen.sourceNameable(getter)) {
          addInvocation(invocations, DirectMethodCodegen.getterInvocation(getter));
        }
      }
    }
    AnyInfo any = codec.anyInfo();
    if (any != null
        && any.writeGetter() != null
        && !DirectMethodCodegen.sourceNameable(any.writeGetter())) {
      addInvocation(invocations, DirectMethodCodegen.getterInvocation(any.writeGetter()));
    }
    return invocations.values().toArray(new DirectInvocation[0]);
  }

  private static void addWriteInvocations(
      Map<String, DirectInvocation> invocations, JsonFieldInfo field) {
    Method getter = field.writeGetter();
    if (getter != null && !DirectMethodCodegen.sourceNameable(getter)) {
      addInvocation(invocations, DirectMethodCodegen.getterInvocation(getter));
    }
    if (field.writeDirectUnboxedValueCodec() != null) {
      addInvocation(
          invocations,
          DirectMethodCodegen.valueOperationInvocation(
              field.writeDirectUnboxedValueCodec().writeCarrierMethod()));
    } else if (field.writeTransparentUnboxedValueCodec() != null) {
      for (Method method : field.writeTransparentUnboxedValueCodec().extractMethods()) {
        addInvocation(invocations, DirectMethodCodegen.valueOperationInvocation(method));
      }
      UnboxedValueCodec terminal = field.writeTypeInfo().unboxedValueCodec();
      if (terminal instanceof DirectUnboxedValueCodec) {
        addInvocation(
            invocations,
            DirectMethodCodegen.valueOperationInvocation(
                ((DirectUnboxedValueCodec) terminal).writeCarrierMethod()));
      }
    }
  }

  private DirectInvocation[] readerInvocations(ObjectCodec<?> codec) {
    LinkedHashMap<String, DirectInvocation> invocations = new LinkedHashMap<>();
    for (JsonFieldInfo field : codec.readFields()) {
      addReadInvocations(invocations, field);
    }
    JsonCreatorInfo creator = codec.creatorInfo();
    addCreatorInvocations(invocations, creator);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      for (JsonUnwrappedInfo.ReadRoute route : unwrapped.readRoutes()) {
        JsonFieldInfo field = route.field();
        if (field != null) {
          addReadInvocations(invocations, field);
        } else {
          JsonCreatorFieldInfo creatorField = route.creatorField();
          addReadValueInvocations(
              invocations,
              creatorField.directUnboxedValueCodec(),
              creatorField.transparentUnboxedValueCodec());
        }
      }
      for (JsonUnwrappedInfo.Group group : unwrapped.groups()) {
        JsonFieldAccessor accessor = group.declaration().readAccessor();
        Method setter = accessor == null ? null : accessor.setter();
        if (setter != null && !DirectMethodCodegen.sourceNameable(setter)) {
          addInvocation(invocations, DirectMethodCodegen.setterInvocation(setter));
        }
        if (group.declaration().readEnabled()) {
          addCreatorInvocations(invocations, group.childCodec().creatorInfo());
        }
      }
    }
    AnyInfo any = codec.anyInfo();
    if (any != null
        && any.readSetter() != null
        && !DirectMethodCodegen.sourceNameable(any.readSetter())) {
      addInvocation(invocations, DirectMethodCodegen.anySetterInvocation(any.readSetter()));
    }
    return invocations.values().toArray(new DirectInvocation[0]);
  }

  private static void addReadInvocations(
      Map<String, DirectInvocation> invocations, JsonFieldInfo field) {
    Method setter = field.readSetter();
    if (setter != null && !DirectMethodCodegen.sourceNameable(setter)) {
      addInvocation(invocations, DirectMethodCodegen.setterInvocation(setter));
    }
    addReadValueInvocations(
        invocations, field.readDirectUnboxedValueCodec(), field.readTransparentUnboxedValueCodec());
  }

  private static void addCreatorInvocations(
      Map<String, DirectInvocation> invocations, JsonCreatorInfo creator) {
    if (creator != null && !creator.fixedInstance()) {
      for (JsonCreatorFieldInfo field : creator.fields()) {
        addReadValueInvocations(
            invocations, field.directUnboxedValueCodec(), field.transparentUnboxedValueCodec());
      }
      for (JsonFieldInfo field : creator.deferredFields()) {
        addReadInvocations(invocations, field);
      }
      if (DirectMethodCodegen.requiresFullCreatorBridge(creator)) {
        addInvocation(invocations, DirectMethodCodegen.fullCreatorInvocation(creator));
      }
      if (creator.defaultConstructor() != null) {
        addInvocation(invocations, DirectMethodCodegen.defaultCreatorInvocation(creator));
      }
    }
  }

  private static void addReadValueInvocations(
      Map<String, DirectInvocation> invocations,
      DirectUnboxedValueCodec direct,
      TransparentUnboxedValueCodec unboxed) {
    if (direct != null) {
      addInvocation(
          invocations, DirectMethodCodegen.valueOperationInvocation(direct.readCarrierMethod()));
    } else if (unboxed != null) {
      UnboxedValueCodec terminal = unboxed.valueTypeInfo().unboxedValueCodec();
      if (terminal instanceof DirectUnboxedValueCodec) {
        addInvocation(
            invocations,
            DirectMethodCodegen.valueOperationInvocation(
                ((DirectUnboxedValueCodec) terminal).readCarrierMethod()));
      }
      for (Method method : unboxed.constructMethods()) {
        addInvocation(invocations, DirectMethodCodegen.valueOperationInvocation(method));
      }
    }
  }

  private static void addInvocation(
      Map<String, DirectInvocation> invocations, DirectInvocation invocation) {
    String key = invocation.bridgeName() + invocation.descriptor();
    DirectInvocation previous = invocations.putIfAbsent(key, invocation);
    if (previous != null && !previous.sameTarget(invocation)) {
      throw new ForyJsonException("Generated direct invocation collision for " + key);
    }
  }

  private Class<?> buildStringWriter(
      TypeRef<?> declaredType, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(declaredType, "StringWriter");
    DirectInvocation[] invocations = writerInvocations(codec);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new StringWriterCodegen(this, resolver, codec)
              .genUnwrappedWriterCode(builder, type, codec, unwrapped);
      return compileObjectCodecClass(type, generatedPackage, className, code, invocations);
    }
    AnyInfo any = codec.anyInfo();
    JsonFieldInfo[] properties = codec.writeFields();
    if (any != null && (any.writeField() != null || any.writeGetter() != null)) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new StringWriterCodegen(this, resolver, codec)
              .genAnyWriterCode(builder, type, properties, any);
      return compileObjectCodecClass(type, generatedPackage, className, code, invocations);
    }
    Function<int[], String> source =
        groupEnds -> {
          JsonGeneratedCodecBuilder builder =
              new JsonGeneratedCodecBuilder(generatedPackage, className, type);
          return new StringWriterCodegen(this, resolver, codec)
              .genWriterCode(builder, type, properties, groupEnds);
        };
    return compileWriterClass(
        type,
        generatedPackage,
        className,
        properties,
        "writeString",
        "writeStringMembers",
        source,
        invocations);
  }

  private Class<?> buildUtf8Writer(
      TypeRef<?> declaredType, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(declaredType, "Utf8Writer");
    DirectInvocation[] invocations = writerInvocations(codec);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Utf8WriterCodegen(this, resolver, codec, false)
              .genUnwrappedWriterCode(builder, type, codec, unwrapped);
      return compileObjectCodecClass(type, generatedPackage, className, code, invocations);
    }
    AnyInfo any = codec.anyInfo();
    JsonFieldInfo[] properties = codec.writeFields();
    if (any != null && (any.writeField() != null || any.writeGetter() != null)) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Utf8WriterCodegen(this, resolver, codec, false)
              .genAnyWriterCode(builder, type, properties, any);
      return compileObjectCodecClass(type, generatedPackage, className, code, invocations);
    }
    Function<int[], String> normalSource =
        groupEnds -> {
          JsonGeneratedCodecBuilder builder =
              new JsonGeneratedCodecBuilder(generatedPackage, className, type);
          return new Utf8WriterCodegen(this, resolver, codec, false)
              .genWriterCode(builder, type, properties, groupEnds);
        };
    Function<int[], String> groupedSource =
        groupEnds -> {
          JsonGeneratedCodecBuilder builder =
              new JsonGeneratedCodecBuilder(generatedPackage, className, type);
          return new Utf8WriterCodegen(this, resolver, codec, false)
              .genRootGroupedWriterCode(builder, type, properties, groupEnds);
        };
    String directSource = normalSource.apply(null);
    int directSize = methodSize(codeStats(generatedPackage, className, directSource), "writeUtf8");
    if (directSize <= HOT_INLINE_LIMIT) {
      // A small generated object entry can be absorbed by an outer collection before its
      // transitive field callees finish compiling. Probe the same entry with real schema-owned
      // prefix, primitive-prefix, framing, and writer-state work emitted locally. Select that
      // source only when it naturally crosses the JDK 25 hot-inline ceiling; the generated type,
      // field names, and runtime values never participate in this decision.
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String expandedSource =
          new Utf8WriterCodegen(this, resolver, codec, true)
              .genWriterCode(builder, type, properties, null);
      int expandedSize =
          methodSize(codeStats(generatedPackage, className, expandedSource), "writeUtf8");
      if (expandedSize > HOT_INLINE_LIMIT) {
        return compileObjectCodecClass(
            type, generatedPackage, className, expandedSource, invocations);
      }
    }
    return compileUtf8WriterClass(
        type,
        generatedPackage,
        className,
        properties,
        "writeUtf8",
        directSource,
        groupedSource,
        invocations);
  }

  private Class<?> buildLatin1Reader(
      TypeRef<?> declaredType, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(declaredType, "Latin1Reader");
    DirectInvocation[] invocations = readerInvocations(codec);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Latin1ReaderCodegen(this, resolver)
              .genUnwrappedReaderCode(builder, type, codec, unwrapped);
      return compileObjectCodecClass(type, generatedPackage, className, code, invocations);
    }
    AnyInfo any = codec.anyInfo();
    JsonFieldInfo[] properties = codec.readFields();
    Function<int[], String> source =
        groupEnds -> {
          JsonGeneratedCodecBuilder builder =
              new JsonGeneratedCodecBuilder(generatedPackage, className, type);
          Latin1ReaderCodegen reader = new Latin1ReaderCodegen(this, resolver, groupEnds);
          return any == null || any.readField() == null && any.readSetter() == null
              ? reader.genReaderCode(builder, codec, properties, codec.creatorInfo())
              : reader.genAnyReaderCode(builder, codec, properties, codec.creatorInfo(), any);
        };
    return compileReaderClass(
        type,
        generatedPackage,
        className,
        properties.length,
        "readLatin1",
        codec.creatorInfo() == null,
        source,
        invocations);
  }

  private Class<?> buildUtf16Reader(
      TypeRef<?> declaredType, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(declaredType, "Utf16Reader");
    DirectInvocation[] invocations = readerInvocations(codec);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Utf16ReaderCodegen(this, resolver)
              .genUnwrappedReaderCode(builder, type, codec, unwrapped);
      return compileObjectCodecClass(type, generatedPackage, className, code, invocations);
    }
    AnyInfo any = codec.anyInfo();
    JsonFieldInfo[] properties = codec.readFields();
    Function<int[], String> source =
        groupEnds -> {
          JsonGeneratedCodecBuilder builder =
              new JsonGeneratedCodecBuilder(generatedPackage, className, type);
          Utf16ReaderCodegen reader = new Utf16ReaderCodegen(this, resolver, groupEnds);
          return any == null || any.readField() == null && any.readSetter() == null
              ? reader.genReaderCode(builder, codec, properties, codec.creatorInfo())
              : reader.genAnyReaderCode(builder, codec, properties, codec.creatorInfo(), any);
        };
    return compileReaderClass(
        type,
        generatedPackage,
        className,
        properties.length,
        "readUtf16",
        codec.creatorInfo() == null,
        source,
        invocations);
  }

  private Class<?> buildUtf8Reader(
      TypeRef<?> declaredType, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className(declaredType, "Utf8Reader");
    DirectInvocation[] invocations = readerInvocations(codec);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Utf8ReaderCodegen(this, resolver)
              .genUnwrappedReaderCode(builder, type, codec, unwrapped);
      return compileObjectCodecClass(type, generatedPackage, className, code, invocations);
    }
    AnyInfo any = codec.anyInfo();
    JsonFieldInfo[] properties = codec.readFields();
    Function<int[], String> source =
        groupEnds -> {
          JsonGeneratedCodecBuilder builder =
              new JsonGeneratedCodecBuilder(generatedPackage, className, type);
          Utf8ReaderCodegen reader = new Utf8ReaderCodegen(this, resolver, groupEnds);
          return any == null || any.readField() == null && any.readSetter() == null
              ? reader.genReaderCode(builder, codec, properties, codec.creatorInfo())
              : reader.genAnyReaderCode(builder, codec, properties, codec.creatorInfo(), any);
        };
    return compileReaderClass(
        type,
        generatedPackage,
        className,
        properties.length,
        "readUtf8",
        codec.creatorInfo() == null,
        source,
        invocations);
  }

  private Class<?> compileReaderClass(
      Class<?> ownerType,
      String generatedPackage,
      String className,
      int propertyCount,
      String readMethod,
      boolean groupable,
      Function<int[], String> source,
      DirectInvocation[] invocations) {
    int[] groupEnds =
        groupable
            ? readerGroupEnds(generatedPackage, className, propertyCount, readMethod, source)
            : oneGroup(propertyCount);
    return compileObjectCodecClass(
        ownerType, generatedPackage, className, source.apply(groupEnds), invocations);
  }

  private Class<?> compileWriterClass(
      Class<?> ownerType,
      String generatedPackage,
      String className,
      JsonFieldInfo[] properties,
      String writeMethod,
      String memberMethod,
      Function<int[], String> source,
      DirectInvocation[] invocations) {
    if (properties.length < 2) {
      return compileObjectCodecClass(
          ownerType, generatedPackage, className, source.apply(null), invocations);
    }
    // Group only the bytecode emitted in this generated class. A callee with its own stable
    // boundary contributes its invocation, not the body that C2 must keep in the callee.
    int[] oneGroup = new int[] {properties.length};
    JaninoUtils.CodeStats oneGroupStats =
        codeStats(generatedPackage, className, source.apply(oneGroup));
    if (privateMethodSize(oneGroupStats, writeMethod + "Object") <= HOT_INLINE_LIMIT) {
      return compileObjectCodecClass(
          ownerType, generatedPackage, className, source.apply(null), invocations);
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
    return compileObjectCodecClass(
        ownerType, generatedPackage, className, source.apply(groupEnds), invocations);
  }

  private Class<?> compileUtf8WriterClass(
      Class<?> ownerType,
      String generatedPackage,
      String className,
      JsonFieldInfo[] properties,
      String writeMethod,
      String directSource,
      Function<int[], String> source,
      DirectInvocation[] invocations) {
    if (properties.length < 2
        || methodSize(codeStats(generatedPackage, className, directSource), writeMethod)
            <= HOT_INLINE_LIMIT) {
      return compileObjectCodecClass(
          ownerType, generatedPackage, className, directSource, invocations);
    }
    int firstGroupMember = JsonWriterCodegen.firstGroupMember(properties);
    if (properties.length - firstGroupMember < 2) {
      return compileObjectCodecClass(
          ownerType, generatedPackage, className, directSource, invocations);
    }
    int[] groupEnds =
        utf8WriterGroupEnds(
            generatedPackage, className, properties.length, firstGroupMember, writeMethod, source);
    if (groupEnds.length < 2) {
      return compileObjectCodecClass(
          ownerType, generatedPackage, className, directSource, invocations);
    }
    return compileObjectCodecClass(
        ownerType, generatedPackage, className, source.apply(groupEnds), invocations);
  }

  private int[] utf8WriterGroupEnds(
      String generatedPackage,
      String className,
      int propertyCount,
      int firstGroupMember,
      String writeMethod,
      Function<int[], String> source) {
    // Compile every candidate source and measure only the bytecode emitted in this generated class.
    // Stable leaf owners contribute their call instructions, never transitive implementation cost.
    // The final range stays in the public root; preceding ranges become direct private methods.
    List<Integer> ends = new ArrayList<>(propertyCount - firstGroupMember);
    for (int end = firstGroupMember + 1; end <= propertyCount; end++) {
      ends.add(end);
    }
    while (ends.size() > 1) {
      int[] candidate = toIntArray(ends);
      JaninoUtils.CodeStats stats = codeStats(generatedPackage, className, source.apply(candidate));
      int start = firstGroupMember;
      boolean merged = false;
      for (int group = 0; group < ends.size() - 1; group++) {
        String method = JsonWriterCodegen.writerGroupMethod(writeMethod, start);
        if (privateMethodSize(stats, method) <= HOT_INLINE_LIMIT) {
          ends.remove(group);
          merged = true;
          break;
        }
        start = ends.get(group);
      }
      if (merged) {
        continue;
      }
      if (methodSize(stats, writeMethod) <= HOT_INLINE_LIMIT) {
        ends.remove(ends.size() - 2);
        continue;
      }
      return candidate;
    }
    return toIntArray(ends);
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
    Map<String, JaninoUtils.CodeStats> stats = codeStatsByClass(generatedPackage, className, code);
    return statsForMainClass(generatedPackage, className, stats);
  }

  private JaninoUtils.CodeStats statsForMainClass(
      String generatedPackage, String className, Map<String, JaninoUtils.CodeStats> stats) {
    String classFile =
        (generatedPackage.isEmpty() ? "" : generatedPackage.replace('.', '/') + "/")
            + className
            + ".class";
    JaninoUtils.CodeStats classStats = stats.get(classFile);
    if (classStats == null) {
      throw new ForyJsonException("Missing generated JSON bytecode " + classFile);
    }
    return classStats;
  }

  private Map<String, JaninoUtils.CodeStats> codeStatsByClass(
      String generatedPackage, String className, String code) {
    CompileUnit unit = new CompileUnit(generatedPackage, className, code);
    Map<String, byte[]> classes = JaninoUtils.toBytecode(jsonLoader, "", unit);
    Map<String, JaninoUtils.CodeStats> stats = new LinkedHashMap<>();
    for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
      stats.put(entry.getKey(), JaninoUtils.getClassStats(entry.getValue()));
    }
    return stats;
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
    // Janino lowers a private generated instance method to a same-class static helper whose
    // bytecode name has a trailing '$'. The planner must measure that real method, not the source
    // spelling, or every direct group appears to be missing.
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

  private Class<?> compileObjectCodecClass(
      Class<?> ownerType,
      String generatedPackage,
      String className,
      String code,
      DirectInvocation[] invocations) {
    if (!hostedCodegen || _JDKAccess.isExported(ownerType)) {
      return compileCodecClass(generatedPackage, className, code, invocations);
    }
    try {
      // A codec for a concealed model package must live beside the model to access its public
      // members without an application export or open. Exported and bootstrap models stay in the
      // generated loader, which also avoids changing their module graph.
      CompileUnit unit = new CompileUnit(generatedPackage, className, code);
      return compileHostedClass(ownerType, unit, invocations);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot compile generated JSON codec " + className, e);
    }
  }

  private Class<?> compileCodecClass(
      String generatedPackage, String className, String code, DirectInvocation[] invocations) {
    try {
      CompileUnit unit = new CompileUnit(generatedPackage, className, code);
      ClassLoader classLoader = codeGenerator.compileDirect(unit, invocations);
      return classLoader.loadClass(qualifiedClassName(generatedPackage, className));
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot compile generated JSON codec " + className, e);
    }
  }

  private Class<?> compileCodecClass(String generatedPackage, String className, String code) {
    return compileCodecClass(generatedPackage, className, code, new DirectInvocation[0]);
  }

  private Class<?> compileHostedClass(
      Class<?> ownerType, CompileUnit unit, DirectInvocation[] invocations) {
    Map<String, byte[]> classes = JaninoUtils.toBytecode(jsonLoader, "", unit);
    String mainClassName = unit.getQualifiedClassName();
    String mainClassPath = mainClassName.replace('.', '/') + ".class";
    byte[] mainBytecode = classes.get(mainClassPath);
    if (mainBytecode == null) {
      throw new ForyJsonException("Missing generated JSON codec bytecode " + mainClassName);
    }
    mainBytecode = JaninoUtils.installDirectInvocations(mainBytecode, invocations);
    ClassLoader ownerLoader = ownerType.getClassLoader();
    if (ownerLoader == null) {
      throw new ForyJsonException(
          "Cannot define generated JSON codec beside bootstrap type " + ownerType.getName());
    }
    Object ownerModule = _JDKAccess.getModule(ownerType);
    // The generated source names APIs from both JSON and core. A concealed third-party model
    // package may not already read either module, so establish only those two implementation
    // dependencies before defining the ordinary class in the model module.
    _JDKAccess.addReads(ownerModule, _JDKAccess.getModule(JsonCodegen.class));
    _JDKAccess.addReads(ownerModule, _JDKAccess.getModule(DefineClass.class));
    Class<?> mainClass =
        DefineClass.defineClass(
            mainClassName, ownerType, ownerLoader, ownerType.getProtectionDomain(), mainBytecode);
    for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
      if (!entry.getKey().equals(mainClassPath)) {
        String className = CodeGenerator.fullClassNameFromClassFilePath(entry.getKey());
        DefineClass.defineClass(
            className, ownerType, ownerLoader, ownerType.getProtectionDomain(), entry.getValue());
      }
    }
    return mainClass;
  }

  @Internal
  public boolean canCompileWriter(ObjectCodec<?> codec) {
    if (codec.fixedInstance() || !canCompileType(codec.type())) {
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
    if (codec.fixedInstance() || !canCompileType(codec.type())) {
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

  Class<?> utf8ReaderFieldType(JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    if (typeInfo.usesAnnotationCodec()) {
      return Utf8ReaderCodec.class;
    }
    if (resolver.exactUtf8Collection(typeInfo) != null) {
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
    if (!property.readsUnboxedValue()
        && property.readKind() == JsonFieldKind.OBJECT
        && property.readRawType() != Object.class
        && resolver.canonicalObjectCodec(property.readTypeInfo()) != null) {
      return property.readRawType();
    }
    return null;
  }

  @Internal
  public static boolean usesWriteCodec(JsonFieldInfo property) {
    if (property.writesUnboxedValue() && property.writeKind() == JsonFieldKind.ENUM) {
      return true;
    }
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

  @Internal
  public static boolean usesReadCodec(JsonFieldInfo property, JsonTypeResolver resolver) {
    if (property.readsUnboxedValue()) {
      if (property.readDirectUnboxedValueCodec() != null) {
        return false;
      }
      Class<?> rawType = property.readTypeInfo().rawType();
      JsonFieldKind kind = property.readKind();
      if (rawType == String.class && kind == JsonFieldKind.STRING) {
        return false;
      }
      if (rawType.isPrimitive()) {
        return !((rawType == boolean.class && kind == JsonFieldKind.BOOLEAN)
            || (rawType == byte.class && kind == JsonFieldKind.BYTE)
            || (rawType == short.class && kind == JsonFieldKind.SHORT)
            || (rawType == int.class && kind == JsonFieldKind.INT)
            || (rawType == long.class && kind == JsonFieldKind.LONG)
            || (rawType == float.class && kind == JsonFieldKind.FLOAT)
            || (rawType == double.class && kind == JsonFieldKind.DOUBLE)
            || (rawType == char.class && kind == JsonFieldKind.CHAR));
      }
      return true;
    }
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

  private String className(TypeRef<?> type, String role) {
    String identity = generatedIdentity(type, role);
    return generatedNamePrefix(type.getRawType()) + role + "ForyJsonCodec_" + digest(identity);
  }

  private String className(TypeRef<?> type, String role, boolean stringElements) {
    StringBuilder identity = new StringBuilder(generatedIdentity(type, role));
    appendIdentity(identity, stringElements ? "1" : "0");
    return generatedNamePrefix(type.getRawType())
        + role
        + "ForyJsonCodec_"
        + digest(identity.toString());
  }

  private String generatedIdentity(TypeRef<?> type, String role) {
    StringBuilder identity = new StringBuilder(codegenIdentity.length() + role.length() + 96);
    appendIdentity(identity, codegenIdentity);
    appendIdentity(identity, role);
    appendIdentity(identity, type.getTypeKey());
    return identity.toString();
  }

  private static void appendIdentity(StringBuilder builder, String value) {
    builder.append(value.length()).append(':').append(value);
  }

  private static String digest(String value) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      char[] hex = new char[bytes.length * 2];
      char[] digits = "0123456789abcdef".toCharArray();
      for (int i = 0; i < bytes.length; i++) {
        int current = bytes[i] & 0xff;
        hex[i * 2] = digits[current >>> 4];
        hex[i * 2 + 1] = digits[current & 0x0f];
      }
      return new String(hex);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is unavailable", e);
    }
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

  private static String generatedNamePrefix(Class<?> type) {
    String name = simpleClassName(type);
    int codePoints = name.codePointCount(0, name.length());
    if (codePoints <= GENERATED_NAME_PREFIX_CODE_POINTS) {
      return name;
    }
    return name.substring(0, name.offsetByCodePoints(0, GENERATED_NAME_PREFIX_CODE_POINTS));
  }

  private static String qualifiedClassName(String generatedPackage, String className) {
    return generatedPackage.isEmpty() ? className : generatedPackage + "." + className;
  }
}
