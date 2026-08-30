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

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.apache.fory.annotation.Internal;
import org.apache.fory.builder.Generated;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.codegen.JaninoUtils;
import org.apache.fory.codegen.JaninoUtils.DirectInvocation;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.json.ForyJsonException;
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
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.platform.internal.DefineClass;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.util.ClassLoaderUtils;

/**
 * Generates concrete object and exact-collection capability classes.
 *
 * <p>One frontend instance belongs to one {@link org.apache.fory.json.resolver.JsonSharedRegistry}.
 * Generated classes are shared by exact {@link GeneratedCodecKey} through a class-lifecycle cache.
 * On an ordinary JVM, {@link CodeGenerator} owns compilation and definition single-flight. Hosted
 * compilation defines completed classes beside their source owners before Native Image freezes its
 * exact-key registry.
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
  private static final AtomicLong GENERATED_CLASS_SUFFIX = new AtomicLong();
  private static volatile ClassValueCache<PerClassGeneratedCodecCache> generatedClassCache =
      newGeneratedClassCache();

  private final CodeGenerator codeGenerator;
  private final ClassLoader jsonLoader;
  private final boolean hostedCodegen;
  // Hosted visibility must be checked against the loader that will own the defined class, not the
  // composed loader that Janino uses to read source dependencies.
  private final Class<?> hostedDefinitionOwner;
  private final String generatedClassName;

  static String generatedCodecType(CodegenContext ctx, Class<?> codecType) {
    // Janino-generated serializers use erased types, matching Fory core code generation. Runtime
    // construction binds the instance to the typed Object capability once on the cold path. Do not
    // spread this source-language limitation into handwritten generic capability APIs.
    return ctx.type(codecType);
  }

  static String generatedCodecArrayType(CodegenContext ctx, Class<?> arrayType) {
    return ctx.type(arrayType);
  }

  public JsonCodegen(boolean hostedCodegen) {
    this(null, null, hostedCodegen, null, null);
  }

  private JsonCodegen(
      CodeGenerator codeGenerator,
      ClassLoader jsonLoader,
      boolean hostedCodegen,
      Class<?> hostedDefinitionOwner,
      String generatedClassName) {
    this.jsonLoader = jsonLoader;
    this.hostedCodegen = hostedCodegen;
    this.codeGenerator = codeGenerator;
    this.hostedDefinitionOwner = hostedDefinitionOwner;
    this.generatedClassName = generatedClassName;
  }

  /**
   * Compiles one concrete capability from fully resolved object metadata.
   *
   * <p>Source generation and Janino compilation are not enclosed by a resolver-local JIT lock.
   * Canonical child metadata is read through short resolver-owned lookups; source shape never
   * depends on mutable capability slots. Active codec classes are inspected only for non-canonical
   * bindings, whose capability fields are never replaced by generated raw-object codecs.
   *
   * <p>The shared registry owns resolver-graph completion futures; they coordinate atomic
   * resolver-local installation, not compilation or class-definition single-flight. Resolver-local
   * construction and capability publication belong to {@link
   * org.apache.fory.json.resolver.JsonTypeResolver} and are ordered by its {@link JsonJITContext}.
   */
  @Internal
  public Class<?> compileStringWriter(
      GeneratedCodecKey key, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    return compileObject(key, compiler -> compiler.buildStringWriter(codec, resolver));
  }

  @Internal
  public Class<?> compileUtf8Writer(
      GeneratedCodecKey key, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    return compileObject(key, compiler -> compiler.buildUtf8Writer(codec, resolver));
  }

  @Internal
  public Class<?> compileLatin1Reader(
      GeneratedCodecKey key, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    return compileObject(key, compiler -> compiler.buildLatin1Reader(codec, resolver));
  }

  @Internal
  public Class<?> compileUtf16Reader(
      GeneratedCodecKey key, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    return compileObject(key, compiler -> compiler.buildUtf16Reader(codec, resolver));
  }

  @Internal
  public Class<?> compileUtf8Reader(
      GeneratedCodecKey key, ObjectCodec<?> codec, JsonTypeResolver resolver) {
    return compileObject(key, compiler -> compiler.buildUtf8Reader(codec, resolver));
  }

  @Internal
  public Class<?> compileUtf8CollectionWriter(GeneratedCodecKey key) {
    Class<?> elementType = key.collectionElementClass();
    String generatedPackage = CodeGenerator.getPackage(elementType);
    return compile(
        key,
        elementType,
        compiler ->
            compiler.buildUtf8CollectionWriter(generatedPackage, key.stringCollectionElements()));
  }

  private Class<?> buildUtf8CollectionWriter(String generatedPackage, boolean stringElements) {
    String className = className();
    String code =
        new Utf8CollectionWriterCodegen().genCode(generatedPackage, className, stringElements);
    return compileCodecClass(generatedPackage, className, code);
  }

  @Internal
  public Class<?> compileUtf8CollectionReader(GeneratedCodecKey key) {
    Class<?> elementType = key.collectionElementClass();
    String generatedPackage = CodeGenerator.getPackage(elementType);
    return compile(
        key,
        elementType,
        compiler ->
            compiler.buildUtf8CollectionReader(generatedPackage, key.stringCollectionElements()));
  }

  private Class<?> buildUtf8CollectionReader(String generatedPackage, boolean stringElements) {
    String className = className();
    String code =
        new Utf8CollectionReaderCodegen().genCode(generatedPackage, className, stringElements);
    return compileCodecClass(generatedPackage, className, code);
  }

  private Class<?> compileObject(GeneratedCodecKey key, CompilerOperation operation) {
    return compile(key, key.targetClass(), operation);
  }

  private Class<?> compile(
      GeneratedCodecKey key, Class<?> sourceOwner, CompilerOperation operation) {
    String generatedPackage = CodeGenerator.getPackage(sourceOwner);
    PerClassGeneratedCodecCache perClass =
        generatedClassCache.get(key.anchorClass(), PerClassGeneratedCodecCache::new);
    CacheEntry entry =
        perClass.entries.computeIfAbsent(
            key,
            ignored -> {
              String className =
                  generatedNamePrefix(key.targetClass())
                      + key.role().classSuffix()
                      + "ForyJsonCodec_"
                      + GENERATED_CLASS_SUFFIX.incrementAndGet();
              return new CacheEntry(className);
            });
    Class<?> completed = entry.generatedClass;
    if (completed != null) {
      return completed;
    }
    JsonCodegen compiler = compiler(key, sourceOwner, entry.className, generatedPackage);
    Class<?> generatedClass = operation.compile(compiler);
    if (generatedClass != null) {
      entry.publish(generatedClass);
    }
    return generatedClass;
  }

  private JsonCodegen compiler(
      GeneratedCodecKey key, Class<?> sourceOwner, String className, String generatedPackage) {
    ClassLoader[] loaders = canonicalLoaders(key);
    if (hostedCodegen) {
      // Use the canonical loader tuple only for source compilation and visibility decisions. The
      // generated class is defined beside its source owner below, so this hosted-only composed
      // loader cannot become reachable from the frozen Native Image registry.
      ClassLoader loader =
          loaders.length == 1 ? loaders[0] : new ClassLoaderUtils.ComposedClassLoader(loaders);
      return new JsonCodegen(
          null, loader, true, hostedDefinitionOwner(sourceOwner, generatedPackage), className);
    }
    CodeGenerator generator =
        loaders.length == 1
            ? CodeGenerator.getSharedCodeGenerator(loaders[0])
            : CodeGenerator.getSharedCodeGenerator(loaders);
    return new JsonCodegen(generator, generator.getClassLoader(), false, null, className);
  }

  private ClassLoader[] canonicalLoaders(GeneratedCodecKey key) {
    ArrayList<ClassLoader> loaders = new ArrayList<>();
    IdentityHashMap<ClassLoader, Boolean> seen = new IdentityHashMap<>();
    for (Class<?> type : key.referencedClasses()) {
      ClassLoader loader = type.getClassLoader();
      if (loader != null && seen.put(loader, Boolean.TRUE) == null) {
        loaders.add(loader);
      }
    }
    ClassLoader foryLoader = JsonCodegen.class.getClassLoader();
    if (foryLoader != null && seen.put(foryLoader, Boolean.TRUE) == null) {
      loaders.add(foryLoader);
    }
    if (loaders.isEmpty()) {
      loaders.add(CodeGenerator.class.getClassLoader());
    }
    return loaders.toArray(new ClassLoader[0]);
  }

  /** Releases the hosted strong cache after Native Image analysis freezes the runtime registry. */
  @Internal
  public static void resetGeneratedClassCache() {
    generatedClassCache = newGeneratedClassCache();
  }

  private static ClassValueCache<PerClassGeneratedCodecCache> newGeneratedClassCache() {
    return ClassValueCache.newClassKeySoftCache(32);
  }

  private interface CompilerOperation {
    Class<?> compile(JsonCodegen compiler);
  }

  private static final class PerClassGeneratedCodecCache {
    private final ConcurrentHashMap<GeneratedCodecKey, CacheEntry> entries =
        new ConcurrentHashMap<>();
  }

  private static final class CacheEntry {
    private final String className;
    private volatile Class<?> generatedClass;

    private CacheEntry(String className) {
      this.className = className;
    }

    private void publish(Class<?> generatedClass) {
      Class<?> completed = this.generatedClass;
      if (completed != null && completed != generatedClass) {
        throw new IllegalStateException("Conflicting generated JSON class " + className);
      }
      this.generatedClass = generatedClass;
    }
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

  private Class<?> buildStringWriter(ObjectCodec<?> codec, JsonTypeResolver resolver) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className();
    DirectInvocation[] invocations = writerInvocations(codec);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new StringWriterCodegen(this, resolver, codec)
              .genUnwrappedWriterCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code, invocations);
    }
    AnyInfo any = codec.anyInfo();
    JsonFieldInfo[] properties = codec.writeFields();
    if (any != null && (any.writeField() != null || any.writeGetter() != null)) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new StringWriterCodegen(this, resolver, codec)
              .genAnyWriterCode(builder, type, properties, any);
      return compileCodecClass(generatedPackage, className, code, invocations);
    }
    Function<int[], String> source =
        groupEnds -> {
          JsonGeneratedCodecBuilder builder =
              new JsonGeneratedCodecBuilder(generatedPackage, className, type);
          return new StringWriterCodegen(this, resolver, codec)
              .genWriterCode(builder, type, properties, groupEnds);
        };
    return compileWriterClass(
        generatedPackage,
        className,
        properties,
        "writeString",
        "writeStringMembers",
        source,
        invocations);
  }

  private Class<?> buildUtf8Writer(ObjectCodec<?> codec, JsonTypeResolver resolver) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className();
    DirectInvocation[] invocations = writerInvocations(codec);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Utf8WriterCodegen(this, resolver, codec, false)
              .genUnwrappedWriterCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code, invocations);
    }
    AnyInfo any = codec.anyInfo();
    JsonFieldInfo[] properties = codec.writeFields();
    if (any != null && (any.writeField() != null || any.writeGetter() != null)) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Utf8WriterCodegen(this, resolver, codec, false)
              .genAnyWriterCode(builder, type, properties, any);
      return compileCodecClass(generatedPackage, className, code, invocations);
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
        return compileCodecClass(generatedPackage, className, expandedSource, invocations);
      }
    }
    return compileUtf8WriterClass(
        generatedPackage,
        className,
        properties,
        "writeUtf8",
        directSource,
        groupedSource,
        invocations);
  }

  private Class<?> buildLatin1Reader(ObjectCodec<?> codec, JsonTypeResolver resolver) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className();
    DirectInvocation[] invocations = readerInvocations(codec);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Latin1ReaderCodegen(this, resolver)
              .genUnwrappedReaderCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code, invocations);
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
        generatedPackage,
        className,
        properties.length,
        "readLatin1",
        codec.creatorInfo() == null,
        source,
        invocations);
  }

  private Class<?> buildUtf16Reader(ObjectCodec<?> codec, JsonTypeResolver resolver) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className();
    DirectInvocation[] invocations = readerInvocations(codec);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Utf16ReaderCodegen(this, resolver)
              .genUnwrappedReaderCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code, invocations);
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
        generatedPackage,
        className,
        properties.length,
        "readUtf16",
        codec.creatorInfo() == null,
        source,
        invocations);
  }

  private Class<?> buildUtf8Reader(ObjectCodec<?> codec, JsonTypeResolver resolver) {
    Class<?> type = codec.type();
    String generatedPackage = CodeGenerator.getPackage(type);
    String className = className();
    DirectInvocation[] invocations = readerInvocations(codec);
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      JsonGeneratedCodecBuilder builder =
          new JsonGeneratedCodecBuilder(generatedPackage, className, type);
      String code =
          new Utf8ReaderCodegen(this, resolver)
              .genUnwrappedReaderCode(builder, type, codec, unwrapped);
      return compileCodecClass(generatedPackage, className, code, invocations);
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
        generatedPackage,
        className,
        properties.length,
        "readUtf8",
        codec.creatorInfo() == null,
        source,
        invocations);
  }

  private Class<?> compileReaderClass(
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
    return compileCodecClass(generatedPackage, className, source.apply(groupEnds), invocations);
  }

  private Class<?> compileWriterClass(
      String generatedPackage,
      String className,
      JsonFieldInfo[] properties,
      String writeMethod,
      String memberMethod,
      Function<int[], String> source,
      DirectInvocation[] invocations) {
    if (properties.length < 2) {
      return compileCodecClass(generatedPackage, className, source.apply(null), invocations);
    }
    // Group only the bytecode emitted in this generated class. A callee with its own stable
    // boundary contributes its invocation, not the body that C2 must keep in the callee.
    int[] oneGroup = new int[] {properties.length};
    JaninoUtils.CodeStats oneGroupStats =
        codeStats(generatedPackage, className, source.apply(oneGroup));
    if (privateMethodSize(oneGroupStats, writeMethod + "Object") <= HOT_INLINE_LIMIT) {
      return compileCodecClass(generatedPackage, className, source.apply(null), invocations);
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
    return compileCodecClass(generatedPackage, className, source.apply(groupEnds), invocations);
  }

  private Class<?> compileUtf8WriterClass(
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
      return compileCodecClass(generatedPackage, className, directSource, invocations);
    }
    int firstGroupMember = JsonWriterCodegen.firstGroupMember(properties);
    if (properties.length - firstGroupMember < 2) {
      return compileCodecClass(generatedPackage, className, directSource, invocations);
    }
    int[] groupEnds =
        utf8WriterGroupEnds(
            generatedPackage, className, properties.length, firstGroupMember, writeMethod, source);
    if (groupEnds.length < 2) {
      return compileCodecClass(generatedPackage, className, directSource, invocations);
    }
    return compileCodecClass(generatedPackage, className, source.apply(groupEnds), invocations);
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

  private Class<?> compileCodecClass(
      String generatedPackage, String className, String code, DirectInvocation[] invocations) {
    try {
      CompileUnit unit = new CompileUnit(generatedPackage, className, code);
      if (hostedCodegen) {
        return hostedDefinitionOwner == null
            ? null
            : compileHostedClass(hostedDefinitionOwner, unit, invocations);
      }
      ClassLoader classLoader = codeGenerator.compileDirect(unit, invocations);
      return classLoader.loadClass(qualifiedClassName(generatedPackage, className));
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot compile generated JSON codec " + className, e);
    }
  }

  private Class<?> compileCodecClass(String generatedPackage, String className, String code) {
    return compileCodecClass(generatedPackage, className, code, new DirectInvocation[0]);
  }

  private static Class<?> hostedDefinitionOwner(Class<?> sourceOwner, String generatedPackage) {
    while (sourceOwner.isArray()) {
      sourceOwner = sourceOwner.getComponentType();
    }
    if (sourceOwner.getClassLoader() != null
        && CodeGenerator.getPackage(sourceOwner).equals(generatedPackage)) {
      return sourceOwner;
    }
    if (CodeGenerator.getPackage(Generated.class).equals(generatedPackage)) {
      return Generated.class;
    }
    return null;
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
    if (JdkVersion.MAJOR_VERSION >= 9) {
      Object ownerModule = _JDKAccess.getModule(ownerType);
      // The generated source names APIs from both JSON and core. A concealed third-party model
      // package may not already read those implementation modules, so establish the generated
      // class's actual dependencies before defining it in the model module. JDK 8-24 core field
      // access also emits sun.misc.Unsafe calls; the generated class, rather than Fory core, owns
      // that linkage and therefore needs its own read edge to jdk.unsupported.
      _JDKAccess.addReads(ownerModule, _JDKAccess.getModule(JsonCodegen.class));
      _JDKAccess.addReads(ownerModule, _JDKAccess.getModule(DefineClass.class));
      if (JdkVersion.MAJOR_VERSION < 25) {
        try {
          _JDKAccess.addReads(
              ownerModule, _JDKAccess.getModule(Class.forName("sun.misc.Unsafe", false, null)));
        } catch (ClassNotFoundException e) {
          throw new ForyJsonException("Cannot resolve generated Unsafe field access", e);
        }
      }
    }
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

  private boolean canCompileWriter(ObjectCodec<?> codec) {
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

  /** Checks source visibility through the same canonical loader tuple used by compilation. */
  @Internal
  public boolean canCompileWriter(GeneratedCodecKey key, ObjectCodec<?> codec) {
    return compiler(key, codec.type(), "ForyJsonCodecProbe", CodeGenerator.getPackage(codec.type()))
        .canCompileWriter(codec);
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
        if (!isGeneratedClassVisible(entry.group().childCodec().type())
            || !canCompileUnwrappedWrite(owner, entry.group().writeEntries())) {
          return false;
        }
      }
    }
    AnyInfo any = owner.anyInfo();
    return any == null || canCompileAnyWrite(any);
  }

  private boolean canCompileReader(ObjectCodec<?> codec) {
    if (codec.fixedInstance() || !canCompileType(codec.type())) {
      return false;
    }
    JsonCreatorInfo creator = codec.creatorInfo();
    if (!canCompileCreator(creator)) {
      return false;
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

  /** Checks source visibility through the same canonical loader tuple used by compilation. */
  @Internal
  public boolean canCompileReader(GeneratedCodecKey key, ObjectCodec<?> codec) {
    return compiler(key, codec.type(), "ForyJsonCodecProbe", CodeGenerator.getPackage(codec.type()))
        .canCompileReader(codec);
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
      if (!isGeneratedClassVisible(group.childCodec().type())) {
        return false;
      }
      JsonCreatorInfo creator = group.childCodec().creatorInfo();
      if (!canCompileCreator(creator)) {
        return false;
      }
    }
    for (JsonUnwrappedInfo.ReadRoute route : unwrapped.readRoutes()) {
      JsonFieldInfo field = route.field();
      if (field != null && !canCompileRead(field)) {
        return false;
      }
      JsonCreatorFieldInfo creatorField = route.creatorField();
      if (creatorField != null) {
        if (!canCompileType(creatorField.rawType())
            || !canCompileUnboxed(creatorField.unboxedValueCodec(), true)) {
          return false;
        }
      }
    }
    AnyInfo any = owner.anyInfo();
    return any == null || canCompileAnyRead(any, owner.creatorInfo() != null);
  }

  private boolean canCompileCreator(JsonCreatorInfo creator) {
    if (creator == null) {
      return true;
    }
    if (!canResolveExecutable(creator.invocationExecutable())
        || creator.defaultConstructor() != null
            && !canResolveExecutable(creator.defaultConstructor())) {
      return false;
    }
    Class<?>[] parameterTypes = creator.executable().getParameterTypes();
    for (int i = 0; i < parameterTypes.length; i++) {
      if (!canCompileType(parameterTypes[i])) {
        return false;
      }
      Method defaultMethod = creator.defaultMethod(i);
      // JsonCreatorInfo guarantees that a default method belongs to the creator owner, or to the
      // language singleton that owns instance defaults, and that its dependency types are the
      // preceding creator parameters. The generated reader invokes that exact method on that exact
      // declaring class, so validate its access from the final definition context as well.
      if (defaultMethod != null && !canCall(defaultMethod)) {
        return false;
      }
    }
    for (JsonCreatorFieldInfo field : creator.fields()) {
      if (!canCompileUnboxed(field.unboxedValueCodec(), true)) {
        return false;
      }
    }
    return true;
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
    return isGeneratedClassVisible(mapType) && isGeneratedClassVisible(any.valueRawType());
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
    if (field != null && !isGeneratedClassVisible(field.getType())) {
      return false;
    }
    if (field != null && !canCompileField(field)) {
      return false;
    }
    if (setter != null && creator) {
      return false;
    }
    return isGeneratedClassVisible(any.valueRawType());
  }

  @Internal
  public static Class<?> readNestedType(JsonFieldInfo field, JsonTypeResolver resolver) {
    if (!field.readsUnboxedValue()
        && field.readKind() == JsonFieldKind.OBJECT
        && field.readRawType() != Object.class
        && resolver.canonicalObjectCodec(field.readTypeInfo()) != null) {
      return field.readRawType();
    }
    return null;
  }

  @Internal
  public static boolean usesWriteCodec(JsonFieldInfo field) {
    if (field.writesUnboxedValue() && field.writeKind() == JsonFieldKind.ENUM) {
      return true;
    }
    switch (field.writeKind()) {
      case ARRAY:
      case MAP:
      case OBJECT:
        return true;
      case COLLECTION:
        return !writesStringCollectionDirectly(field);
      default:
        return false;
    }
  }

  @Internal
  public static boolean usesUtf8WriteCodec(JsonFieldInfo field, JsonTypeResolver resolver) {
    return usesWriteCodec(field)
        || field.writeKind() == JsonFieldKind.COLLECTION
            && resolver.exactUtf8WriterCollection(field.writeTypeInfo()) != null;
  }

  static boolean writesStringCollectionDirectly(JsonFieldInfo field) {
    return field.writeElementRawType() == String.class
        && field.writeTypeInfo().stringWriter().getClass()
            == CollectionCodec.StringCollectionCodec.class;
  }

  @Internal
  public static boolean usesReadCodec(JsonFieldInfo field, JsonTypeResolver resolver) {
    if (field.readsUnboxedValue()) {
      if (field.readDirectUnboxedValueCodec() != null) {
        return false;
      }
      Class<?> rawType = field.readTypeInfo().rawType();
      JsonFieldKind kind = field.readKind();
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
    switch (field.readKind()) {
      case ENUM:
      case ARRAY:
      case COLLECTION:
      case MAP:
        return true;
      case OBJECT:
        return !usesReadObjectCodec(field, resolver);
      default:
        return false;
    }
  }

  private static boolean usesReadObjectCodec(JsonFieldInfo field, JsonTypeResolver resolver) {
    return field.readRawType() != Object.class
        && resolver.canonicalObjectCodec(field.readTypeInfo()) != null;
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
    return isPublicSourceType(type) && isGeneratedClassVisible(type)
        ? type
        : StringWriterCodec.class;
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
    return isPublicSourceType(type) && isGeneratedClassVisible(type) ? type : Utf8WriterCodec.class;
  }

  Class<?> latin1ReaderFieldType(JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    if (typeInfo.usesAnnotationCodec()) {
      return Latin1ReaderCodec.class;
    }
    if (resolver.canonicalObjectCodec(typeInfo) != null) {
      return Latin1ReaderCodec.class;
    }
    Class<?> type = typeInfo.latin1Reader().getClass();
    return isPublicSourceType(type) && isGeneratedClassVisible(type)
        ? type
        : Latin1ReaderCodec.class;
  }

  Class<?> utf16ReaderFieldType(JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
    if (typeInfo.usesAnnotationCodec()) {
      return Utf16ReaderCodec.class;
    }
    if (resolver.canonicalObjectCodec(typeInfo) != null) {
      return Utf16ReaderCodec.class;
    }
    Class<?> type = typeInfo.utf16Reader().getClass();
    return isPublicSourceType(type) && isGeneratedClassVisible(type)
        ? type
        : Utf16ReaderCodec.class;
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
    return isPublicSourceType(type) && isGeneratedClassVisible(type) ? type : Utf8ReaderCodec.class;
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
    if (rawType != null && !rawType.isPrimitive() && !isGeneratedClassVisible(rawType)) {
      return false;
    }
    return canCompileUnboxed(property.writeUnboxedValueCodec(), false);
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
    if (rawType != null && !rawType.isPrimitive() && !isGeneratedClassVisible(rawType)) {
      return false;
    }
    return canCompileUnboxed(property.readUnboxedValueCodec(), true);
  }

  private boolean canCompileType(Class<?> type) {
    return isPublicSourceType(type) && isGeneratedClassVisible(type);
  }

  private boolean canCompileField(Field field) {
    // Descriptor emits public fields as direct Java member access. A public field inherited from
    // an inaccessible declaring class is reflectively visible but cannot be resolved by Janino.
    // Non-public fields use the existing generated accessor path and do not spell their owner.
    return !Modifier.isPublic(field.getModifiers()) || canCompileType(field.getDeclaringClass());
  }

  private boolean canCall(Method method) {
    return Modifier.isPublic(method.getModifiers())
        && isPublicSourceType(method.getDeclaringClass())
        && isGeneratedClassVisible(method.getDeclaringClass())
        && canResolveExecutable(method);
  }

  private boolean canCompileUnboxed(UnboxedValueCodec codec, boolean reader) {
    if (!hostedCodegen || codec == null) {
      return true;
    }
    if (codec instanceof DirectUnboxedValueCodec) {
      DirectUnboxedValueCodec direct = (DirectUnboxedValueCodec) codec;
      return canResolveExecutable(
          reader ? direct.readCarrierMethod() : direct.writeCarrierMethod());
    }
    TransparentUnboxedValueCodec transparent = (TransparentUnboxedValueCodec) codec;
    if (!canCompileType(transparent.valueTypeInfo().rawType())) {
      return false;
    }
    Method[] methods = reader ? transparent.constructMethods() : transparent.extractMethods();
    for (Method method : methods) {
      if (!canResolveExecutable(method)) {
        return false;
      }
    }
    UnboxedValueCodec terminal = transparent.valueTypeInfo().unboxedValueCodec();
    if (terminal instanceof DirectUnboxedValueCodec) {
      DirectUnboxedValueCodec direct = (DirectUnboxedValueCodec) terminal;
      return canResolveExecutable(
          reader ? direct.readCarrierMethod() : direct.writeCarrierMethod());
    }
    return true;
  }

  private boolean canResolveExecutable(Executable executable) {
    if (!hostedCodegen || !isDefinitionVisible(executable.getDeclaringClass())) {
      return !hostedCodegen;
    }
    if (executable instanceof Method
        && !isDefinitionVisible(((Method) executable).getReturnType())) {
      return false;
    }
    for (Class<?> parameterType : executable.getParameterTypes()) {
      if (!isDefinitionVisible(parameterType)) {
        return false;
      }
    }
    return true;
  }

  private boolean isGeneratedClassVisible(Class<?> type) {
    return isVisible(type) && isDefinitionVisible(type);
  }

  private boolean isDefinitionVisible(Class<?> type) {
    if (!hostedCodegen || type.isPrimitive()) {
      return true;
    }
    if (hostedDefinitionOwner == null) {
      return false;
    }
    while (type.isArray()) {
      type = type.getComponentType();
    }
    if (type.isPrimitive()) {
      return true;
    }
    ClassLoader loader = hostedDefinitionOwner.getClassLoader();
    try {
      return Class.forName(type.getName(), false, loader) == type
          && isDefinitionModuleVisible(type);
    } catch (ReflectiveOperationException e) {
      return false;
    }
  }

  private boolean isDefinitionModuleVisible(Class<?> type) throws ReflectiveOperationException {
    if (JdkVersion.MAJOR_VERSION < 9) {
      return true;
    }
    Object ownerModule = _JDKAccess.getModule(hostedDefinitionOwner);
    Object typeModule = _JDKAccess.getModule(type);
    if (ownerModule == typeModule) {
      return true;
    }
    // Source compilation uses the composed loader, but the generated class belongs to the
    // definition owner's module. Loader visibility alone cannot make a concealed package or an
    // unread module legal at linkage time.
    Class<?> moduleType = ownerModule.getClass();
    if (!(Boolean) moduleType.getMethod("canRead", moduleType).invoke(ownerModule, typeModule)) {
      return false;
    }
    Package typePackage = type.getPackage();
    String packageName = typePackage == null ? "" : typePackage.getName();
    return (Boolean)
        moduleType
            .getMethod("isExported", String.class, moduleType)
            .invoke(typeModule, packageName, ownerModule);
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

  private String className() {
    if (generatedClassName == null) {
      throw new IllegalStateException("Generated JSON class name has not been assigned");
    }
    return generatedClassName;
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
