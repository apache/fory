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

package org.apache.fory.resolver;

import static org.apache.fory.Fory.NOT_SUPPORT_XLANG;
import static org.apache.fory.meta.Encoders.GENERIC_ENCODER;
import static org.apache.fory.meta.Encoders.PACKAGE_DECODER;
import static org.apache.fory.meta.Encoders.PACKAGE_ENCODER;
import static org.apache.fory.meta.Encoders.TYPE_NAME_DECODER;
import static org.apache.fory.meta.Encoders.encodePackage;
import static org.apache.fory.meta.Encoders.encodeTypeName;
import static org.apache.fory.serializer.CodegenSerializer.loadCodegenSerializer;
import static org.apache.fory.serializer.CodegenSerializer.loadCompatibleCodegenSerializer;
import static org.apache.fory.serializer.CodegenSerializer.supportCodegenForJavaSerialization;
import static org.apache.fory.type.TypeUtils.OBJECT_TYPE;
import static org.apache.fory.type.TypeUtils.getRawType;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import java.io.Externalizable;
import java.io.IOException;
import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.ForyCopyable;
import org.apache.fory.annotation.CodegenInvoke;
import org.apache.fory.annotation.Internal;
import org.apache.fory.builder.CodecUtils;
import org.apache.fory.builder.Generated.GeneratedMetaSharedSerializer;
import org.apache.fory.builder.Generated.GeneratedObjectSerializer;
import org.apache.fory.builder.JITContext;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.codegen.Expression.Literal;
import org.apache.fory.collection.IdentityMap;
import org.apache.fory.collection.IdentityObjectIntMap;
import org.apache.fory.collection.LongMap;
import org.apache.fory.collection.ObjectArray;
import org.apache.fory.collection.ObjectMap;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.meta.ClassDef;
import org.apache.fory.meta.ClassSpec;
import org.apache.fory.meta.Encoders;
import org.apache.fory.meta.MetaString;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.ArraySerializers;
import org.apache.fory.serializer.BufferSerializers;
import org.apache.fory.serializer.CodegenSerializer.LazyInitBeanSerializer;
import org.apache.fory.serializer.CompatibleSerializer;
import org.apache.fory.serializer.EnumSerializer;
import org.apache.fory.serializer.ExternalizableSerializer;
import org.apache.fory.serializer.ForyCopyableSerializer;
import org.apache.fory.serializer.JavaSerializer;
import org.apache.fory.serializer.JdkProxySerializer;
import org.apache.fory.serializer.LambdaSerializer;
import org.apache.fory.serializer.LocaleSerializer;
import org.apache.fory.serializer.MetaSharedSerializer;
import org.apache.fory.serializer.NoneSerializer;
import org.apache.fory.serializer.NonexistentClass;
import org.apache.fory.serializer.NonexistentClass.NonexistentMetaShared;
import org.apache.fory.serializer.NonexistentClass.NonexistentSkip;
import org.apache.fory.serializer.NonexistentClassSerializers;
import org.apache.fory.serializer.NonexistentClassSerializers.NonexistentClassSerializer;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.OptionalSerializers;
import org.apache.fory.serializer.PrimitiveSerializers;
import org.apache.fory.serializer.ReplaceResolveSerializer;
import org.apache.fory.serializer.SerializationUtils;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.SerializerFactory;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.serializer.StringSerializer;
import org.apache.fory.serializer.TimeSerializers;
import org.apache.fory.serializer.collection.ChildContainerSerializers;
import org.apache.fory.serializer.collection.CollectionSerializer;
import org.apache.fory.serializer.collection.CollectionSerializers;
import org.apache.fory.serializer.collection.GuavaCollectionSerializers;
import org.apache.fory.serializer.collection.ImmutableCollectionSerializers;
import org.apache.fory.serializer.collection.MapSerializer;
import org.apache.fory.serializer.collection.MapSerializers;
import org.apache.fory.serializer.collection.SubListSerializers;
import org.apache.fory.serializer.collection.SynchronizedSerializers;
import org.apache.fory.serializer.collection.UnmodifiableSerializers;
import org.apache.fory.serializer.scala.SingletonCollectionSerializer;
import org.apache.fory.serializer.scala.SingletonMapSerializer;
import org.apache.fory.serializer.scala.SingletonObjectSerializer;
import org.apache.fory.serializer.shim.ProtobufDispatcher;
import org.apache.fory.serializer.shim.ShimDispatcher;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.ScalaTypes;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.GraalvmSupport.GraalvmSerializerHolder;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.function.Functions;

/**
 * Class registry for types of serializing objects, responsible for reading/writing types, setting
 * up relations between serializer and types.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ClassResolver implements TypeResolver {
  private static final Logger LOG = LoggerFactory.getLogger(ClassResolver.class);

  // bit 0 unset indicates class is written as an id.
  public static final byte USE_CLASS_VALUE_FLAG = 0b1;
  // preserve 0 as flag for class id not set in ClassInfo`
  public static final short NO_CLASS_ID = (short) 0;
  public static final short LAMBDA_STUB_ID = 1;
  public static final short JDK_PROXY_STUB_ID = 2;
  public static final short REPLACE_STUB_ID = 3;
  // Note: following pre-defined class id should be continuous, since they may be used based range.
  public static final short PRIMITIVE_VOID_CLASS_ID = (short) (REPLACE_STUB_ID + 1);
  public static final short PRIMITIVE_BOOLEAN_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 1);
  public static final short PRIMITIVE_BYTE_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 2);
  public static final short PRIMITIVE_CHAR_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 3);
  public static final short PRIMITIVE_SHORT_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 4);
  public static final short PRIMITIVE_INT_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 5);
  public static final short PRIMITIVE_FLOAT_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 6);
  public static final short PRIMITIVE_LONG_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 7);
  public static final short PRIMITIVE_DOUBLE_CLASS_ID = (short) (PRIMITIVE_VOID_CLASS_ID + 8);
  public static final short VOID_CLASS_ID = (short) (PRIMITIVE_DOUBLE_CLASS_ID + 1);
  public static final short BOOLEAN_CLASS_ID = (short) (VOID_CLASS_ID + 1);
  public static final short BYTE_CLASS_ID = (short) (VOID_CLASS_ID + 2);
  public static final short CHAR_CLASS_ID = (short) (VOID_CLASS_ID + 3);
  public static final short SHORT_CLASS_ID = (short) (VOID_CLASS_ID + 4);
  public static final short INTEGER_CLASS_ID = (short) (VOID_CLASS_ID + 5);
  public static final short FLOAT_CLASS_ID = (short) (VOID_CLASS_ID + 6);
  public static final short LONG_CLASS_ID = (short) (VOID_CLASS_ID + 7);
  public static final short DOUBLE_CLASS_ID = (short) (VOID_CLASS_ID + 8);
  public static final short STRING_CLASS_ID = (short) (VOID_CLASS_ID + 9);
  public static final short PRIMITIVE_BOOLEAN_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 1);
  public static final short PRIMITIVE_BYTE_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 2);
  public static final short PRIMITIVE_CHAR_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 3);
  public static final short PRIMITIVE_SHORT_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 4);
  public static final short PRIMITIVE_INT_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 5);
  public static final short PRIMITIVE_FLOAT_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 6);
  public static final short PRIMITIVE_LONG_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 7);
  public static final short PRIMITIVE_DOUBLE_ARRAY_CLASS_ID = (short) (STRING_CLASS_ID + 8);
  public static final short STRING_ARRAY_CLASS_ID = (short) (PRIMITIVE_DOUBLE_ARRAY_CLASS_ID + 1);
  public static final short OBJECT_ARRAY_CLASS_ID = (short) (PRIMITIVE_DOUBLE_ARRAY_CLASS_ID + 2);
  public static final short ARRAYLIST_CLASS_ID = (short) (PRIMITIVE_DOUBLE_ARRAY_CLASS_ID + 3);
  public static final short HASHMAP_CLASS_ID = (short) (PRIMITIVE_DOUBLE_ARRAY_CLASS_ID + 4);
  public static final short HASHSET_CLASS_ID = (short) (PRIMITIVE_DOUBLE_ARRAY_CLASS_ID + 5);
  public static final short CLASS_CLASS_ID = (short) (PRIMITIVE_DOUBLE_ARRAY_CLASS_ID + 6);
  public static final short EMPTY_OBJECT_ID = (short) (PRIMITIVE_DOUBLE_ARRAY_CLASS_ID + 7);
  // use a lower load factor to minimize hash collision
  private static final float foryMapLoadFactor = 0.25f;
  private static final int estimatedNumRegistered = 150;
  static final String SET_META__CONTEXT_MSG =
      "Meta context must be set before serialization, "
          + "please set meta context by SerializationContext.setMetaContext";
  static final ClassInfo NIL_CLASS_INFO =
      new ClassInfo(null, null, null, null, false, null, NO_CLASS_ID, NOT_SUPPORT_XLANG);

  private final Fory fory;
  XtypeResolver xtypeResolver;
  private ClassInfo[] registeredId2ClassInfo = new ClassInfo[] {};

  // IdentityMap has better lookup performance, when loadFactor is 0.05f, performance is better
  private final IdentityMap<Class<?>, ClassInfo> classInfoMap =
      new IdentityMap<>(estimatedNumRegistered, foryMapLoadFactor);
  private ClassInfo classInfoCache;
  // Every deserialization for unregistered class will query it, performance is important.
  private final ObjectMap<TypeNameBytes, ClassInfo> compositeNameBytes2ClassInfo =
      new ObjectMap<>(16, foryMapLoadFactor);
  private final MetaStringResolver metaStringResolver;
  private final boolean metaContextShareEnabled;
  private final Map<Class<?>, ClassDef> classDefMap = new HashMap<>();
  private Class<?> currentReadClass;
  // class id of last default registered class.
  private short innerEndClassId;
  private final ExtRegistry extRegistry;
  private final ShimDispatcher shimDispatcher;

  private static class ExtRegistry {
    // Here we set it to 1 because `NO_CLASS_ID` is 0 to avoid calculating it again in
    // `register(Class<?> cls)`.
    private short classIdGenerator = 1;
    private SerializerFactory serializerFactory;
    private final IdentityMap<Class<?>, Short> registeredClassIdMap =
        new IdentityMap<>(estimatedNumRegistered);
    private final BiMap<String, Class<?>> registeredClasses =
        HashBiMap.create(estimatedNumRegistered);
    // cache absClassInfo, support customized serializer for abstract or interface.
    private final IdentityMap<Class<?>, ClassInfo> absClassInfo =
        new IdentityMap<>(estimatedNumRegistered, foryMapLoadFactor);
    // avoid potential recursive call for seq codec generation.
    // ex. A->field1: B, B.field1: A
    private final Set<Class<?>> getClassCtx = new HashSet<>();
    private final Map<Class<?>, FieldResolver> fieldResolverMap = new HashMap<>();
    private final LongMap<Tuple2<ClassDef, ClassInfo>> classIdToDef = new LongMap<>();
    private final Map<Class<?>, ClassDef> currentLayerClassDef = new HashMap<>();
    // Tuple2<Class, Class>: Tuple2<From Class, To Class>
    private final Map<Tuple2<Class<?>, Class<?>>, ClassInfo> transformedClassInfo = new HashMap<>();
    // TODO(chaokunyang) Better to  use soft reference, see ObjectStreamClass.
    private final ConcurrentHashMap<Tuple2<Class<?>, Boolean>, SortedMap<Member, Descriptor>>
        descriptorsCache = new ConcurrentHashMap<>();
    private ClassChecker classChecker = (classResolver, className) -> true;
    private GenericType objectGenericType;
    private final IdentityMap<Type, GenericType> genericTypes = new IdentityMap<>();
    private final Map<Class, Map<String, GenericType>> classGenericTypes = new HashMap<>();
    private final Map<List<ClassLoader>, CodeGenerator> codeGeneratorMap = new HashMap<>();
    private final Set<ClassInfo> initialClassInfos = new HashSet<>();
  }

  public ClassResolver(Fory fory) {
    this.fory = fory;
    metaStringResolver = fory.getMetaStringResolver();
    classInfoCache = NIL_CLASS_INFO;
    metaContextShareEnabled = fory.getConfig().isMetaShareEnabled();
    extRegistry = new ExtRegistry();
    shimDispatcher = new ShimDispatcher(fory);
    ClassResolver._addGraalvmClassRegistry(fory.getConfig().getConfigHash(), this);
  }

  @Override
  public void initialize() {
    extRegistry.objectGenericType = buildGenericType(OBJECT_TYPE);
    register(LambdaSerializer.ReplaceStub.class, LAMBDA_STUB_ID);
    register(JdkProxySerializer.ReplaceStub.class, JDK_PROXY_STUB_ID);
    register(ReplaceResolveSerializer.ReplaceStub.class, REPLACE_STUB_ID);
    register(void.class, PRIMITIVE_VOID_CLASS_ID);
    register(boolean.class, PRIMITIVE_BOOLEAN_CLASS_ID);
    register(byte.class, PRIMITIVE_BYTE_CLASS_ID);
    register(char.class, PRIMITIVE_CHAR_CLASS_ID);
    register(short.class, PRIMITIVE_SHORT_CLASS_ID);
    register(int.class, PRIMITIVE_INT_CLASS_ID);
    register(float.class, PRIMITIVE_FLOAT_CLASS_ID);
    register(long.class, PRIMITIVE_LONG_CLASS_ID);
    register(double.class, PRIMITIVE_DOUBLE_CLASS_ID);
    register(Void.class, VOID_CLASS_ID);
    register(Boolean.class, BOOLEAN_CLASS_ID);
    register(Byte.class, BYTE_CLASS_ID);
    register(Character.class, CHAR_CLASS_ID);
    register(Short.class, SHORT_CLASS_ID);
    register(Integer.class, INTEGER_CLASS_ID);
    register(Float.class, FLOAT_CLASS_ID);
    register(Long.class, LONG_CLASS_ID);
    register(Double.class, DOUBLE_CLASS_ID);
    register(String.class, STRING_CLASS_ID);
    register(boolean[].class, PRIMITIVE_BOOLEAN_ARRAY_CLASS_ID);
    register(byte[].class, PRIMITIVE_BYTE_ARRAY_CLASS_ID);
    register(char[].class, PRIMITIVE_CHAR_ARRAY_CLASS_ID);
    register(short[].class, PRIMITIVE_SHORT_ARRAY_CLASS_ID);
    register(int[].class, PRIMITIVE_INT_ARRAY_CLASS_ID);
    register(float[].class, PRIMITIVE_FLOAT_ARRAY_CLASS_ID);
    register(long[].class, PRIMITIVE_LONG_ARRAY_CLASS_ID);
    register(double[].class, PRIMITIVE_DOUBLE_ARRAY_CLASS_ID);
    register(String[].class, STRING_ARRAY_CLASS_ID);
    register(Object[].class, OBJECT_ARRAY_CLASS_ID);
    register(ArrayList.class, ARRAYLIST_CLASS_ID);
    register(HashMap.class, HASHMAP_CLASS_ID);
    register(HashSet.class, HASHSET_CLASS_ID);
    register(Class.class, CLASS_CLASS_ID);
    register(Object.class, EMPTY_OBJECT_ID);
    registerCommonUsedClasses();
    registerDefaultClasses();
    addDefaultSerializers();
    shimDispatcher.initialize();
    innerEndClassId = extRegistry.classIdGenerator;
    if (GraalvmSupport.isGraalBuildtime()) {
      classInfoMap.forEach(
          (cls, classInfo) -> {
            if (classInfo.serializer != null) {
              extRegistry.initialClassInfos.add(classInfo);
            }
          });
    }
  }

  private void addDefaultSerializers() {
    // primitive types will be boxed.
    addDefaultSerializer(void.class, NoneSerializer.class);
    addDefaultSerializer(String.class, new StringSerializer(fory));
    PrimitiveSerializers.registerDefaultSerializers(fory);
    Serializers.registerDefaultSerializers(fory);
    ArraySerializers.registerDefaultSerializers(fory);
    TimeSerializers.registerDefaultSerializers(fory);
    OptionalSerializers.registerDefaultSerializers(fory);
    CollectionSerializers.registerDefaultSerializers(fory);
    MapSerializers.registerDefaultSerializers(fory);
    addDefaultSerializer(Locale.class, new LocaleSerializer(fory));
    addDefaultSerializer(
        LambdaSerializer.ReplaceStub.class,
        new LambdaSerializer(fory, LambdaSerializer.ReplaceStub.class));
    addDefaultSerializer(
        JdkProxySerializer.ReplaceStub.class,
        new JdkProxySerializer(fory, JdkProxySerializer.ReplaceStub.class));
    addDefaultSerializer(
        ReplaceResolveSerializer.ReplaceStub.class,
        new ReplaceResolveSerializer(fory, ReplaceResolveSerializer.ReplaceStub.class));
    SynchronizedSerializers.registerSerializers(fory);
    UnmodifiableSerializers.registerSerializers(fory);
    ImmutableCollectionSerializers.registerSerializers(fory);
    SubListSerializers.registerSerializers(fory, true);
    if (fory.getConfig().registerGuavaTypes()) {
      GuavaCollectionSerializers.registerDefaultSerializers(fory);
    }
    if (fory.getConfig().deserializeNonexistentClass()) {
      if (metaContextShareEnabled) {
        addDefaultSerializer(
            NonexistentMetaShared.class, new NonexistentClassSerializer(fory, null));
        // Those class id must be known in advance, here is two bytes, so
        // `NonexistentClassSerializer.writeClassDef`
        // can overwrite written classinfo and replace with real classinfo.
        short classId =
            Objects.requireNonNull(classInfoMap.get(NonexistentMetaShared.class)).classId;
        Preconditions.checkArgument(classId > 63 && classId < 8192, classId);
      } else {
        register(NonexistentSkip.class);
      }
    }
  }

  private void addDefaultSerializer(Class<?> type, Class<? extends Serializer> serializerClass) {
    addDefaultSerializer(type, Serializers.newSerializer(fory, type, serializerClass));
  }

  private void addDefaultSerializer(Class type, Serializer serializer) {
    registerSerializer(type, serializer);
    register(type);
  }

  /** Register common class ahead to get smaller class id for serialization. */
  private void registerCommonUsedClasses() {
    register(LinkedList.class, TreeSet.class);
    register(LinkedHashMap.class, TreeMap.class);
    register(Date.class, Timestamp.class, LocalDateTime.class, Instant.class);
    register(BigInteger.class, BigDecimal.class);
    register(Optional.class, OptionalInt.class);
    register(Boolean[].class, Byte[].class, Short[].class, Character[].class);
    register(Integer[].class, Float[].class, Long[].class, Double[].class);
  }

  private void registerDefaultClasses() {
    register(Platform.HEAP_BYTE_BUFFER_CLASS);
    register(Platform.DIRECT_BYTE_BUFFER_CLASS);
    register(Comparator.naturalOrder().getClass());
    register(Comparator.reverseOrder().getClass());
    register(ConcurrentHashMap.class);
    register(ArrayBlockingQueue.class);
    register(LinkedBlockingQueue.class);
    register(AtomicBoolean.class);
    register(AtomicInteger.class);
    register(AtomicLong.class);
    register(AtomicReference.class);
    register(EnumSet.allOf(Language.class).getClass());
    register(EnumSet.of(Language.JAVA).getClass());
    register(SerializedLambda.class);
    register(Throwable.class, StackTraceElement.class, Exception.class, RuntimeException.class);
    register(NullPointerException.class);
    register(IOException.class);
    register(IllegalArgumentException.class);
    register(IllegalStateException.class);
    register(IndexOutOfBoundsException.class, ArrayIndexOutOfBoundsException.class);
  }

  /** register class. */
  public void register(Class<?> cls) {
    if (!extRegistry.registeredClassIdMap.containsKey(cls)) {
      while (extRegistry.classIdGenerator < registeredId2ClassInfo.length
          && registeredId2ClassInfo[extRegistry.classIdGenerator] != null) {
        extRegistry.classIdGenerator++;
      }
      register(cls, extRegistry.classIdGenerator);
    }
  }

  public void register(String className) {
    register(loadClass(className, false, 0, false));
  }

  public void register(Class<?>... classes) {
    for (Class<?> cls : classes) {
      register(cls);
    }
  }

  public void register(Class<?> cls, boolean createSerializer) {
    register(cls);
    if (createSerializer) {
      createSerializerAhead(cls);
    }
  }

  /**
   * Register class with specified id. Currently class id must be `classId >= 0 && classId < 32767`.
   * In the future this limitation may be relaxed.
   */
  public void register(Class<?> cls, int classId) {
    // class id must be less than Integer.MAX_VALUE/2 since we use bit 0 as class id flag.
    Preconditions.checkArgument(classId >= 0 && classId < Short.MAX_VALUE);
    short id = (short) classId;
    checkRegistration(cls, id, cls.getName());
    extRegistry.registeredClassIdMap.put(cls, id);
    if (registeredId2ClassInfo.length <= id) {
      ClassInfo[] tmp = new ClassInfo[(id + 1) * 2];
      System.arraycopy(registeredId2ClassInfo, 0, tmp, 0, registeredId2ClassInfo.length);
      registeredId2ClassInfo = tmp;
    }
    ClassInfo classInfo = classInfoMap.get(cls);
    if (classInfo != null) {
      classInfo.classId = id;
    } else {
      classInfo = new ClassInfo(this, cls, null, id, NOT_SUPPORT_XLANG);
      // make `extRegistry.registeredClassIdMap` and `classInfoMap` share same classInfo
      // instances.
      classInfoMap.put(cls, classInfo);
    }
    // serializer will be set lazily in `addSerializer` method if it's null.
    registeredId2ClassInfo[id] = classInfo;
    extRegistry.registeredClasses.put(cls.getName(), cls);
    extRegistry.classIdGenerator++;
  }

  public void register(String className, int classId) {
    register(loadClass(className, false, 0, false), classId);
  }

  public void register(Class<?> cls, int id, boolean createSerializer) {
    register(cls, id);
    if (createSerializer) {
      createSerializerAhead(cls);
    }
  }

  public void register(String className, Short classId, boolean createSerializer) {
    register(loadClass(className, false, 0, false), classId, createSerializer);
  }

  /**
   * Register class with specified namespace and name. If a simpler namespace or type name is
   * registered, the serialized class will have smaller payload size. In many cases, it type name
   * has no conflict, namespace can be left as empty.
   */
  public void register(Class<?> cls, String namespace, String name) {
    Preconditions.checkArgument(!Functions.isLambda(cls));
    Preconditions.checkArgument(!ReflectionUtils.isJdkProxy(cls));
    Preconditions.checkArgument(!cls.isArray());
    String fullname = name;
    if (namespace == null) {
      namespace = "";
    }
    if (!StringUtils.isBlank(namespace)) {
      fullname = namespace + "." + name;
    }
    checkRegistration(cls, (short) -1, fullname);
    MetaStringBytes fullNameBytes =
        metaStringResolver.getOrCreateMetaStringBytes(
            GENERIC_ENCODER.encode(fullname, MetaString.Encoding.UTF_8));
    MetaStringBytes nsBytes =
        metaStringResolver.getOrCreateMetaStringBytes(encodePackage(namespace));
    MetaStringBytes nameBytes = metaStringResolver.getOrCreateMetaStringBytes(encodeTypeName(name));
    ClassInfo classInfo =
        new ClassInfo(cls, fullNameBytes, nsBytes, nameBytes, false, null, NO_CLASS_ID, (short) -1);
    classInfoMap.put(cls, classInfo);
    compositeNameBytes2ClassInfo.put(
        new TypeNameBytes(nsBytes.hashCode, nameBytes.hashCode), classInfo);
    extRegistry.registeredClasses.put(fullname, cls);
  }

  private void checkRegistration(Class<?> cls, short classId, String name) {
    if (extRegistry.registeredClassIdMap.containsKey(cls)) {
      throw new IllegalArgumentException(
          String.format(
              "Class %s already registered with id %s.",
              cls, extRegistry.registeredClassIdMap.get(cls)));
    }
    if (classId > 0
        && classId < registeredId2ClassInfo.length
        && registeredId2ClassInfo[classId] != null) {
      throw new IllegalArgumentException(
          String.format(
              "Class %s with id %s has been registered, registering class %s with same id are not allowed.",
              registeredId2ClassInfo[classId].getCls(), classId, cls.getName()));
    }
    if (extRegistry.registeredClasses.containsKey(name)
        || extRegistry.registeredClasses.inverse().containsKey(cls)) {
      throw new IllegalArgumentException(
          String.format(
              "Class %s with name %s has been registered, registering class %s with same name are not allowed.",
              extRegistry.registeredClasses.get(name), name, cls));
    }
  }

  @Override
  public boolean isRegistered(Class<?> cls) {
    return extRegistry.registeredClassIdMap.containsKey(cls)
        || extRegistry.registeredClasses.inverse().containsKey(cls);
  }

  public boolean isRegisteredByName(String name) {
    return extRegistry.registeredClasses.containsKey(name);
  }

  @Override
  public boolean isRegisteredByName(Class<?> cls) {
    return extRegistry.registeredClasses.inverse().containsKey(cls);
  }

  public String getRegisteredName(Class<?> cls) {
    return extRegistry.registeredClasses.inverse().get(cls);
  }

  public Tuple2<String, String> getRegisteredNameTuple(Class<?> cls) {
    String name = extRegistry.registeredClasses.inverse().get(cls);
    int index = name.lastIndexOf(".");
    if (index != -1) {
      return Tuple2.of(name.substring(0, index), name.substring(index + 1));
    } else {
      return Tuple2.of("", name);
    }
  }

  @Override
  public boolean isRegisteredById(Class<?> cls) {
    return extRegistry.registeredClassIdMap.get(cls) != null;
  }

  public Short getRegisteredClassId(Class<?> cls) {
    return extRegistry.registeredClassIdMap.get(cls);
  }

  public Class<?> getRegisteredClass(short id) {
    if (id < registeredId2ClassInfo.length) {
      ClassInfo classInfo = registeredId2ClassInfo[id];
      if (classInfo != null) {
        return classInfo.cls;
      }
    }
    return null;
  }

  public Class<?> getRegisteredClass(String className) {
    return extRegistry.registeredClasses.get(className);
  }

  public List<Class<?>> getRegisteredClasses() {
    return Arrays.stream(registeredId2ClassInfo)
        .filter(Objects::nonNull)
        .map(info -> info.cls)
        .collect(Collectors.toList());
  }

  public String getTypeAlias(Class<?> cls) {
    Short id = extRegistry.registeredClassIdMap.get(cls);
    if (id != null) {
      return String.valueOf(id);
    }
    String name = extRegistry.registeredClasses.inverse().get(cls);
    if (name != null) {
      return name;
    }
    return cls.getName();
  }

  /**
   * Mark non-inner registered final types as non-final to write class def for those types. Note if
   * a class is registered but not an inner class with inner serializer, it will still be taken as
   * non-final to write class def, so that it can be deserialized by the peer still.
   */
  @Override
  public boolean isMonomorphic(Class<?> clz) {
    if (fory.isCrossLanguage()) {
      if (TypeUtils.unwrap(clz).isPrimitive() || clz.isEnum() || clz == String.class) {
        return true;
      }
      if (clz.isArray() && TypeUtils.getArrayComponent(clz).isPrimitive()) {
        return true;
      }
      ClassInfo classInfo = xtypeResolver.getClassInfo(clz, false);
      if (classInfo != null) {
        if (classInfo.serializer instanceof TimeSerializers.TimeSerializer) {
          return true;
        }
        if (classInfo.serializer instanceof TimeSerializers.ImmutableTimeSerializer) {
          return true;
        }
      }
      return false;
    }
    if (fory.getConfig().isMetaShareEnabled()) {
      // can't create final map/collection type using TypeUtils.mapOf(TypeToken<K>,
      // TypeToken<V>)
      if (!ReflectionUtils.isMonomorphic(clz)) {
        return false;
      }
      if (Map.class.isAssignableFrom(clz) || Collection.class.isAssignableFrom(clz)) {
        return false;
      }
      if (clz.isArray()) {
        Class<?> component = TypeUtils.getArrayComponent(clz);
        return isMonomorphic(component);
      }
      return (isInnerClass(clz) || clz.isEnum());
    }
    return ReflectionUtils.isMonomorphic(clz);
  }

  /** Returns true if <code>cls</code> is fory inner registered class. */
  boolean isInnerClass(Class<?> cls) {
    Short classId = extRegistry.registeredClassIdMap.get(cls);
    if (classId == null) {
      ClassInfo classInfo = getClassInfo(cls, false);
      if (classInfo != null) {
        classId = classInfo.getClassId();
      }
    }
    return classId != null && classId != NO_CLASS_ID && classId < innerEndClassId;
  }

  /**
   * Return true if the class has jdk `writeReplace`/`readResolve` method defined, which we need to
   * use {@link ReplaceResolveSerializer}.
   */
  public static boolean useReplaceResolveSerializer(Class<?> clz) {
    // FIXME class with `writeReplace` method defined should be Serializable,
    //  but hessian ignores this check and many existing system are using hessian.
    return (JavaSerializer.getWriteReplaceMethod(clz) != null)
        || JavaSerializer.getReadResolveMethod(clz) != null;
  }

  /**
   * Return true if a class satisfy following requirements.
   * <li>implements {@link Serializable}
   * <li>is not an {@link Enum}
   * <li>is not an array
   * <li>Doesn't have {@code readResolve}/{@code writePlace} method
   * <li>has {@code readObject}/{@code writeObject} method, but doesn't implements {@link
   *     Externalizable}
   * <li/>
   */
  public static boolean requireJavaSerialization(Class<?> clz) {
    if (clz.isEnum() || clz.isArray()) {
      return false;
    }
    if (ReflectionUtils.isDynamicGeneratedCLass(clz)) {
      // use corresponding serializer.
      return false;
    }
    if (!Serializable.class.isAssignableFrom(clz)) {
      return false;
    }
    if (useReplaceResolveSerializer(clz)) {
      return false;
    }
    if (Externalizable.class.isAssignableFrom(clz)) {
      return false;
    } else {
      // `AnnotationInvocationHandler#readObject` may invoke `toString` of object, which may be
      // risky.
      // For example, JsonObject#toString may invoke `getter`.
      // Use fory serialization to avoid this.
      if ("sun.reflect.annotation.AnnotationInvocationHandler".equals(clz.getName())) {
        return false;
      }
      return JavaSerializer.getReadObjectMethod(clz) != null
          || JavaSerializer.getWriteObjectMethod(clz) != null;
    }
  }

  /**
   * Register a Serializer.
   *
   * @param type class needed to be serialized/deserialized
   * @param serializerClass serializer class can be created with {@link Serializers#newSerializer}
   * @param <T> type of class
   */
  public <T> void registerSerializer(Class<T> type, Class<? extends Serializer> serializerClass) {
    registerSerializer(type, Serializers.newSerializer(fory, type, serializerClass));
  }

  /**
   * If a serializer exists before, it will be replaced by new serializer.
   *
   * @param type class needed to be serialized/deserialized
   * @param serializer serializer for object of {@code type}
   */
  public void registerSerializer(Class<?> type, Serializer<?> serializer) {
    if (!serializer.getClass().getPackage().getName().startsWith("org.apache.fory")) {
      SerializationUtils.validate(type, serializer.getClass());
    }
    if (!extRegistry.registeredClassIdMap.containsKey(type) && !fory.isCrossLanguage()) {
      register(type);
    }
    addSerializer(type, serializer);
    ClassInfo classInfo = classInfoMap.get(type);
    classInfoMap.put(type, classInfo);
    // in order to support customized serializer for abstract or interface.
    if (!type.isPrimitive() && (ReflectionUtils.isAbstract(type) || type.isInterface())) {
      extRegistry.absClassInfo.put(type, classInfo);
    }
  }

  public void setSerializerFactory(SerializerFactory serializerFactory) {
    this.extRegistry.serializerFactory = serializerFactory;
  }

  public SerializerFactory getSerializerFactory() {
    return extRegistry.serializerFactory;
  }

  /**
   * Set the serializer for <code>cls</code>, overwrite serializer if exists. Note if class info is
   * already related with a class, this method should try to reuse that class info, otherwise jit
   * callback to update serializer won't take effect in some cases since it can't change that
   * classinfo.
   */
  public <T> void setSerializer(Class<T> cls, Serializer<T> serializer) {
    addSerializer(cls, serializer);
  }

  /** Set serializer for class whose name is {@code className}. */
  public void setSerializer(String className, Class<? extends Serializer> serializer) {
    for (Map.Entry<Class<?>, ClassInfo> entry : classInfoMap.iterable()) {
      if (extRegistry.registeredClasses.containsKey(className)) {
        LOG.warn("Skip clear serializer for registered class {}", className);
        return;
      }
      Class<?> cls = entry.getKey();
      if (cls.getName().equals(className)) {
        LOG.info("Clear serializer for class {}.", className);
        entry.getValue().setSerializer(this, Serializers.newSerializer(fory, cls, serializer));
        classInfoCache = NIL_CLASS_INFO;
        return;
      }
    }
  }

  /** Set serializer for classes starts with {@code classNamePrefix}. */
  public void setSerializers(String classNamePrefix, Class<? extends Serializer> serializer) {
    for (Map.Entry<Class<?>, ClassInfo> entry : classInfoMap.iterable()) {
      Class<?> cls = entry.getKey();
      String className = cls.getName();
      if (extRegistry.registeredClasses.containsKey(className)) {
        continue;
      }
      if (className.startsWith(classNamePrefix)) {
        LOG.info("Clear serializer for class {}.", className);
        entry.getValue().setSerializer(this, Serializers.newSerializer(fory, cls, serializer));
        classInfoCache = NIL_CLASS_INFO;
      }
    }
  }

  /**
   * Reset serializer if <code>serializer</code> is not null, otherwise clear serializer for <code>
   * cls</code>.
   *
   * @see #setSerializer
   * @see #clearSerializer
   * @see #createSerializerSafe
   */
  public <T> void resetSerializer(Class<T> cls, Serializer<T> serializer) {
    if (serializer == null) {
      clearSerializer(cls);
    } else {
      setSerializer(cls, serializer);
    }
  }

  /**
   * Set serializer to avoid circular error when there is a serializer query for fields by {@link
   * #readClassInfoWithMetaShare} and {@link #getSerializer(Class)} which access current creating
   * serializer. This method is used to avoid overwriting existing serializer for class when
   * creating a data serializer for serialization of parts fields of a class.
   */
  public <T> void setSerializerIfAbsent(Class<T> cls, Serializer<T> serializer) {
    Serializer<T> s = getSerializer(cls, false);
    if (s == null) {
      setSerializer(cls, serializer);
    }
  }

  /** Clear serializer associated with <code>cls</code> if not null. */
  public void clearSerializer(Class<?> cls) {
    ClassInfo classInfo = classInfoMap.get(cls);
    if (classInfo != null) {
      classInfo.setSerializer(this, null);
    }
  }

  /** Ass serializer for specified class. */
  private void addSerializer(Class<?> type, Serializer<?> serializer) {
    Preconditions.checkNotNull(serializer);
    // 1. Try to get ClassInfo from `registeredId2ClassInfo` and
    // `classInfoMap` or create a new `ClassInfo`.
    ClassInfo classInfo;
    Short classId = extRegistry.registeredClassIdMap.get(type);
    boolean registered = classId != null;
    // set serializer for class if it's registered by now.
    if (registered) {
      classInfo = registeredId2ClassInfo[classId];
    } else {
      if (serializer instanceof ReplaceResolveSerializer) {
        classId = REPLACE_STUB_ID;
      } else {
        classId = NO_CLASS_ID;
      }
      classInfo = classInfoMap.get(type);
    }

    if (classInfo == null || classId != classInfo.classId) {
      classInfo = new ClassInfo(this, type, null, classId, (short) 0);
      classInfoMap.put(type, classInfo);
      if (registered) {
        registeredId2ClassInfo[classId] = classInfo;
      }
    }

    // 2. Set `Serializer` for `ClassInfo`.
    classInfo.setSerializer(this, serializer);
  }

  @SuppressWarnings("unchecked")
  public <T> Serializer<T> getSerializer(Class<T> cls, boolean createIfNotExist) {
    Preconditions.checkNotNull(cls);
    if (createIfNotExist) {
      return getSerializer(cls);
    }
    ClassInfo classInfo = classInfoMap.get(cls);
    return classInfo == null ? null : (Serializer<T>) classInfo.serializer;
  }

  /** Get or create serializer for <code>cls</code>. */
  @Override
  @SuppressWarnings("unchecked")
  public <T> Serializer<T> getSerializer(Class<T> cls) {
    Preconditions.checkNotNull(cls);
    return (Serializer<T>) getOrUpdateClassInfo(cls).serializer;
  }

  /**
   * Return serializer without generics for specified class. The cast of Serializer to subclass
   * serializer with generic is easy to raise compiler error for javac, so just use raw type.
   */
  @Internal
  @CodegenInvoke
  public Serializer<?> getRawSerializer(Class<?> cls) {
    Preconditions.checkNotNull(cls);
    return getOrUpdateClassInfo(cls).serializer;
  }

  public boolean isSerializable(Class<?> cls) {
    if (ReflectionUtils.isAbstract(cls) || cls.isInterface()) {
      return false;
    }
    try {
      getSerializerClass(cls, false);
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  public Class<? extends Serializer> getSerializerClass(Class<?> cls) {
    boolean codegen =
        supportCodegenForJavaSerialization(cls) && fory.getConfig().isCodeGenEnabled();
    return getSerializerClass(cls, codegen);
  }

  public Class<? extends Serializer> getSerializerClass(Class<?> cls, boolean codegen) {
    if (!cls.isEnum() && (ReflectionUtils.isAbstract(cls) || cls.isInterface())) {
      throw new UnsupportedOperationException(
          String.format("Class %s doesn't support serialization.", cls));
    }
    Class<? extends Serializer> serializerClass = getSerializerClassFromGraalvmRegistry(cls);
    if (serializerClass != null) {
      return serializerClass;
    }
    cls = TypeUtils.boxedType(cls);
    ClassInfo classInfo = classInfoMap.get(cls);
    if (classInfo != null && classInfo.serializer != null) {
      // Note: need to check `classInfo.serializer != null`, because sometimes `cls` is already
      // serialized, which will create a class info with serializer null, see `#writeClassInternal`
      return classInfo.serializer.getClass();
    } else {
      if (getSerializerFactory() != null) {
        Serializer serializer = getSerializerFactory().createSerializer(fory, cls);
        if (serializer != null) {
          return serializer.getClass();
        }
      }
      if (NonexistentClass.isNonexistent(cls)) {
        return NonexistentClassSerializers.getSerializer(fory, "Unknown", cls).getClass();
      }
      if (cls.isArray()) {
        return ArraySerializers.ObjectArraySerializer.class;
      } else if (cls.isEnum()) {
        return EnumSerializer.class;
      } else if (Enum.class.isAssignableFrom(cls) && cls != Enum.class) {
        // handles an enum value that is an inner class. Eg: enum A {b{}};
        return EnumSerializer.class;
      } else if (EnumSet.class.isAssignableFrom(cls)) {
        return CollectionSerializers.EnumSetSerializer.class;
      } else if (Charset.class.isAssignableFrom(cls)) {
        return Serializers.CharsetSerializer.class;
      } else if (ReflectionUtils.isJdkProxy(cls)) {
        if (JavaSerializer.getWriteReplaceMethod(cls) != null) {
          return ReplaceResolveSerializer.class;
        } else {
          return JdkProxySerializer.class;
        }
      } else if (Functions.isLambda(cls)) {
        return LambdaSerializer.class;
      } else if (Calendar.class.isAssignableFrom(cls)) {
        return TimeSerializers.CalendarSerializer.class;
      } else if (ZoneId.class.isAssignableFrom(cls)) {
        return TimeSerializers.ZoneIdSerializer.class;
      } else if (TimeZone.class.isAssignableFrom(cls)) {
        return TimeSerializers.TimeZoneSerializer.class;
      } else if (ByteBuffer.class.isAssignableFrom(cls)) {
        return BufferSerializers.ByteBufferSerializer.class;
      }
      if (shimDispatcher.contains(cls)) {
        return shimDispatcher.getSerializer(cls).getClass();
      }
      serializerClass = ProtobufDispatcher.getSerializerClass(cls);
      if (serializerClass != null) {
        return serializerClass;
      }
      if (fory.getConfig().checkJdkClassSerializable()) {
        if (cls.getName().startsWith("java") && !(Serializable.class.isAssignableFrom(cls))) {
          throw new UnsupportedOperationException(
              String.format("Class %s doesn't support serialization.", cls));
        }
      }
      if (fory.getConfig().isScalaOptimizationEnabled()
          && ReflectionUtils.isScalaSingletonObject(cls)) {
        if (isCollection(cls)) {
          return SingletonCollectionSerializer.class;
        } else if (isMap(cls)) {
          return SingletonMapSerializer.class;
        } else {
          return SingletonObjectSerializer.class;
        }
      }
      if (isCollection(cls)) {
        // Serializer of common collection such as ArrayList/LinkedList should be registered
        // already.
        serializerClass = ChildContainerSerializers.getCollectionSerializerClass(cls);
        if (serializerClass != null) {
          return serializerClass;
        }
        if (requireJavaSerialization(cls) || useReplaceResolveSerializer(cls)) {
          return CollectionSerializers.JDKCompatibleCollectionSerializer.class;
        }
        if (!fory.isCrossLanguage()) {
          return CollectionSerializers.DefaultJavaCollectionSerializer.class;
        } else {
          return CollectionSerializer.class;
        }
      } else if (isMap(cls)) {
        // Serializer of common map such as HashMap/LinkedHashMap should be registered already.
        serializerClass = ChildContainerSerializers.getMapSerializerClass(cls);
        if (serializerClass != null) {
          return serializerClass;
        }
        if (requireJavaSerialization(cls) || useReplaceResolveSerializer(cls)) {
          return MapSerializers.JDKCompatibleMapSerializer.class;
        }
        if (!fory.isCrossLanguage()) {
          return MapSerializers.DefaultJavaMapSerializer.class;
        } else {
          return MapSerializer.class;
        }
      }
      if (fory.isCrossLanguage()) {
        LOG.warn("Class {} isn't supported for cross-language serialization.", cls);
      }
      if (useReplaceResolveSerializer(cls)) {
        return ReplaceResolveSerializer.class;
      }
      if (Externalizable.class.isAssignableFrom(cls)) {
        return ExternalizableSerializer.class;
      }
      if (requireJavaSerialization(cls)) {
        return getJavaSerializer(cls);
      }
      Class<?> clz = cls;
      return getObjectSerializerClass(
          cls,
          metaContextShareEnabled,
          codegen,
          new JITContext.SerializerJITCallback<Class<? extends Serializer>>() {
            @Override
            public void onSuccess(Class<? extends Serializer> result) {
              setSerializer(clz, Serializers.newSerializer(fory, clz, result));
              if (classInfoCache.cls == clz) {
                classInfoCache = NIL_CLASS_INFO; // clear class info cache
              }
              Preconditions.checkState(getSerializer(clz).getClass() == result);
            }

            @Override
            public Object id() {
              return clz;
            }
          });
    }
  }

  public boolean isCollection(Class<?> cls) {
    if (Collection.class.isAssignableFrom(cls)) {
      return true;
    }
    if (fory.getConfig().isScalaOptimizationEnabled()) {
      // Scala map is scala iterable too.
      if (ScalaTypes.getScalaMapType().isAssignableFrom(cls)) {
        return false;
      }
      return ScalaTypes.getScalaIterableType().isAssignableFrom(cls);
    } else {
      return false;
    }
  }

  public boolean isSet(Class<?> cls) {
    if (Set.class.isAssignableFrom(cls)) {
      return true;
    }
    if (fory.getConfig().isScalaOptimizationEnabled()) {
      // Scala map is scala iterable too.
      if (ScalaTypes.getScalaMapType().isAssignableFrom(cls)) {
        return false;
      }
      return ScalaTypes.getScalaSetType().isAssignableFrom(cls);
    } else {
      return false;
    }
  }

  public boolean isMap(Class<?> cls) {
    if (cls == NonexistentMetaShared.class) {
      return false;
    }
    return Map.class.isAssignableFrom(cls)
        || (fory.getConfig().isScalaOptimizationEnabled()
            && ScalaTypes.getScalaMapType().isAssignableFrom(cls));
  }

  public Class<? extends Serializer> getObjectSerializerClass(
      Class<?> cls, JITContext.SerializerJITCallback<Class<? extends Serializer>> callback) {
    boolean codegen =
        supportCodegenForJavaSerialization(cls) && fory.getConfig().isCodeGenEnabled();
    return getObjectSerializerClass(cls, false, codegen, callback);
  }

  private Class<? extends Serializer> getObjectSerializerClass(
      Class<?> cls,
      boolean shareMeta,
      boolean codegen,
      JITContext.SerializerJITCallback<Class<? extends Serializer>> callback) {
    if (codegen) {
      if (extRegistry.getClassCtx.contains(cls)) {
        // avoid potential recursive call for seq codec generation.
        return LazyInitBeanSerializer.class;
      } else {
        try {
          extRegistry.getClassCtx.add(cls);
          Class<? extends Serializer> sc;
          switch (fory.getCompatibleMode()) {
            case SCHEMA_CONSISTENT:
              sc =
                  fory.getJITContext()
                      .registerSerializerJITCallback(
                          () -> ObjectSerializer.class,
                          () -> loadCodegenSerializer(fory, cls),
                          callback);
              return sc;
            case COMPATIBLE:
              // If share class meta, compatible serializer won't be necessary, class
              // definition will be sent to peer to create serializer for deserialization.
              sc =
                  fory.getJITContext()
                      .registerSerializerJITCallback(
                          () -> shareMeta ? ObjectSerializer.class : CompatibleSerializer.class,
                          () ->
                              shareMeta
                                  ? loadCodegenSerializer(fory, cls)
                                  : loadCompatibleCodegenSerializer(fory, cls),
                          callback);
              return sc;
            default:
              throw new UnsupportedOperationException(
                  String.format("Unsupported mode %s", fory.getCompatibleMode()));
          }
        } finally {
          extRegistry.getClassCtx.remove(cls);
        }
      }
    } else {
      if (codegen) {
        LOG.info("Object of type {} can't be serialized by jit", cls);
      }
      switch (fory.getCompatibleMode()) {
        case SCHEMA_CONSISTENT:
          return ObjectSerializer.class;
        case COMPATIBLE:
          return shareMeta ? ObjectSerializer.class : CompatibleSerializer.class;
        default:
          throw new UnsupportedOperationException(
              String.format("Unsupported mode %s", fory.getCompatibleMode()));
      }
    }
  }

  public Class<? extends Serializer> getJavaSerializer(Class<?> clz) {
    if (Collection.class.isAssignableFrom(clz)) {
      return CollectionSerializers.JDKCompatibleCollectionSerializer.class;
    } else if (Map.class.isAssignableFrom(clz)) {
      return MapSerializers.JDKCompatibleMapSerializer.class;
    } else {
      if (useReplaceResolveSerializer(clz)) {
        return ReplaceResolveSerializer.class;
      }
      return fory.getDefaultJDKStreamSerializerType();
    }
  }

  public ClassChecker getClassChecker() {
    return extRegistry.classChecker;
  }

  public void setClassChecker(ClassChecker classChecker) {
    extRegistry.classChecker = classChecker;
  }

  public FieldResolver getFieldResolver(Class<?> cls) {
    // can't use computeIfAbsent, since there may be recursive multiple
    // `getFieldResolver` thus multiple updates, which cause concurrent
    // modification exception.
    FieldResolver fieldResolver = extRegistry.fieldResolverMap.get(cls);
    if (fieldResolver == null) {
      fieldResolver = FieldResolver.of(fory, cls);
      extRegistry.fieldResolverMap.put(cls, fieldResolver);
    }
    return fieldResolver;
  }

  public List<Descriptor> getFieldDescriptors(Class<?> clz, boolean searchParent) {
    SortedMap<Member, Descriptor> allDescriptors = getAllDescriptorsMap(clz, searchParent);
    List<Descriptor> result = new ArrayList<>(allDescriptors.size());
    allDescriptors.forEach(
        (member, descriptor) -> {
          if (member instanceof Field) {
            result.add(descriptor);
          }
        });
    return result;
  }

  // thread safe
  public SortedMap<Member, Descriptor> getAllDescriptorsMap(Class<?> clz, boolean searchParent) {
    // when jit thread query this, it is already built by serialization main thread.
    return extRegistry.descriptorsCache.computeIfAbsent(
        Tuple2.of(clz, searchParent), t -> Descriptor.getAllDescriptorsMap(clz, searchParent));
  }

  /**
   * Whether to track reference for this type. If false, reference tracing of subclasses may be
   * ignored too.
   */
  @Override
  public boolean needToWriteRef(TypeRef<?> typeRef) {
    Object extInfo = typeRef.getExtInfo();
    if (extInfo instanceof TypeExtMeta) {
      TypeExtMeta meta = (TypeExtMeta) extInfo;
      return meta.trackingRef();
    }
    Class<?> cls = typeRef.getRawType();
    if (fory.trackingRef()) {
      ClassInfo classInfo = getClassInfo(cls, false);
      if (classInfo == null || classInfo.serializer == null) {
        // TODO group related logic together for extendability and consistency.
        return !cls.isEnum();
      } else {
        return classInfo.serializer.needToWriteRef();
      }
    }
    return false;
  }

  public ClassInfo getClassInfo(short classId) {
    ClassInfo classInfo = registeredId2ClassInfo[classId];
    assert classInfo != null : classId;
    if (classInfo.serializer == null) {
      addSerializer(classInfo.cls, createSerializer(classInfo.cls));
      classInfo = classInfoMap.get(classInfo.cls);
    }
    return classInfo;
  }

  // Invoked by fory JIT.
  @Override
  public ClassInfo getClassInfo(Class<?> cls) {
    ClassInfo classInfo = classInfoMap.get(cls);
    if (classInfo == null || classInfo.serializer == null) {
      addSerializer(cls, createSerializer(cls));
      classInfo = classInfoMap.get(cls);
    }
    return classInfo;
  }

  /** Get classinfo by cache, update cache if miss. */
  @Override
  public ClassInfo getClassInfo(Class<?> cls, ClassInfoHolder classInfoHolder) {
    ClassInfo classInfo = classInfoHolder.classInfo;
    if (classInfo.getCls() != cls) {
      classInfo = classInfoMap.get(cls);
      if (classInfo == null || classInfo.serializer == null) {
        addSerializer(cls, createSerializer(cls));
        classInfo = Objects.requireNonNull(classInfoMap.get(cls));
      }
      classInfoHolder.classInfo = classInfo;
    }
    assert classInfo.serializer != null;
    return classInfo;
  }

  /**
   * Get class information, create class info if not found and `createClassInfoIfNotFound` is true.
   *
   * @param cls which class to get class info.
   * @param createClassInfoIfNotFound whether create class info if not found.
   * @return Class info.
   */
  @Override
  public ClassInfo getClassInfo(Class<?> cls, boolean createClassInfoIfNotFound) {
    if (createClassInfoIfNotFound) {
      return getOrUpdateClassInfo(cls);
    }
    if (extRegistry.getClassCtx.contains(cls)) {
      return null;
    } else {
      return classInfoMap.get(cls);
    }
  }

  @Internal
  public ClassInfo getOrUpdateClassInfo(Class<?> cls) {
    ClassInfo classInfo = classInfoCache;
    if (classInfo.cls != cls) {
      classInfo = classInfoMap.get(cls);
      if (classInfo == null || classInfo.serializer == null) {
        addSerializer(cls, createSerializer(cls));
        classInfo = classInfoMap.get(cls);
      }
      classInfoCache = classInfo;
    }
    return classInfo;
  }

  private ClassInfo getOrUpdateClassInfo(short classId) {
    ClassInfo classInfo = classInfoCache;
    if (classInfo.classId != classId) {
      classInfo = registeredId2ClassInfo[classId];
      if (classInfo.serializer == null) {
        addSerializer(classInfo.cls, createSerializer(classInfo.cls));
        classInfo = classInfoMap.get(classInfo.cls);
      }
      classInfoCache = classInfo;
    }
    return classInfo;
  }

  public <T> Serializer<T> createSerializerSafe(Class<T> cls, Supplier<Serializer<T>> func) {
    Serializer serializer = fory.getClassResolver().getSerializer(cls, false);
    try {
      return func.get();
    } catch (Throwable t) {
      // Some serializer may set itself in constructor as serializer, but the
      // constructor failed later. For example, some final type field doesn't
      // support serialization.
      resetSerializer(cls, serializer);
      Platform.throwException(t);
      throw new IllegalStateException("unreachable");
    }
  }

  private Serializer createSerializer(Class<?> cls) {
    DisallowedList.checkNotInDisallowedList(cls.getName());
    if (!isSecure(cls)) {
      throw new InsecureException(generateSecurityMsg(cls));
    } else {
      if (!fory.getConfig().suppressClassRegistrationWarnings()
          && !Functions.isLambda(cls)
          && !ReflectionUtils.isJdkProxy(cls)
          && !extRegistry.registeredClassIdMap.containsKey(cls)
          && !shimDispatcher.contains(cls)) {
        LOG.warn(generateSecurityMsg(cls));
      }
    }

    if (extRegistry.serializerFactory != null) {
      Serializer serializer = extRegistry.serializerFactory.createSerializer(fory, cls);
      if (serializer != null) {
        return serializer;
      }
    }

    Serializer<?> shimSerializer = shimDispatcher.getSerializer(cls);
    if (shimSerializer != null) {
      return shimSerializer;
    }

    // support customized serializer for abstract or interface.
    if (!extRegistry.absClassInfo.isEmpty()) {
      Class<?> tmpCls = cls;
      while (tmpCls != null && tmpCls != Object.class) {
        ClassInfo absClass = null;
        if ((absClass = extRegistry.absClassInfo.get(tmpCls.getSuperclass())) != null) {
          return absClass.serializer;
        }
        for (Class<?> tmpI : tmpCls.getInterfaces()) {
          if ((absClass = extRegistry.absClassInfo.get(tmpI)) != null) {
            return absClass.serializer;
          }
        }
        tmpCls = tmpCls.getSuperclass();
      }
    }

    Class<? extends Serializer> serializerClass = getSerializerClass(cls);
    Serializer serializer = Serializers.newSerializer(fory, cls, serializerClass);
    if (ForyCopyable.class.isAssignableFrom(cls)) {
      serializer = new ForyCopyableSerializer<>(fory, cls, serializer);
    }
    return serializer;
  }

  private void createSerializerAhead(Class<?> cls) {
    try {
      fory.getJITContext().lock();
      createSerializer0(cls);
    } finally {
      fory.getJITContext().unlock();
    }
  }

  private void createSerializer0(Class<?> cls) {
    ClassInfo classInfo = getClassInfo(cls);
    ClassInfo deserializationClassInfo;
    if (metaContextShareEnabled && needToWriteClassDef(classInfo.serializer)) {
      ClassDef classDef = classInfo.classDef;
      if (classDef == null) {
        classDef = buildClassDef(classInfo);
      }
      deserializationClassInfo = buildMetaSharedClassInfo(Tuple2.of(classDef, null), classDef);
      if (deserializationClassInfo != null && GraalvmSupport.isGraalBuildtime()) {
        getGraalvmClassRegistry()
            .deserializerClassMap
            .put(classDef.getId(), getGraalvmSerializerClass(deserializationClassInfo.serializer));
        Tuple2<ClassDef, ClassInfo> classDefTuple = extRegistry.classIdToDef.get(classDef.getId());
        // empty serializer for graalvm build time
        classDefTuple.f1.serializer = null;
        extRegistry.classIdToDef.put(classDef.getId(), Tuple2.of(classDefTuple.f0, null));
      }
    }
    if (GraalvmSupport.isGraalBuildtime()) {
      // Instance for generated class should be hold at graalvm runtime only.
      getGraalvmClassRegistry()
          .serializerClassMap
          .put(cls, getGraalvmSerializerClass(classInfo.serializer));
      classInfo.serializer = null;
    }
  }

  private String generateSecurityMsg(Class<?> cls) {
    String tpl =
        "%s is not registered, please check whether it's the type you want to serialize or "
            + "a **vulnerability**. If safe, you should invoke `Fory#register` to register class, "
            + " which will have better performance by skipping classname serialization. "
            + "If your env is 100%% secure, you can also avoid this exception by disabling class "
            + "registration check using `ForyBuilder#requireClassRegistration(false)`";
    return String.format(tpl, cls);
  }

  private boolean isSecure(Class<?> cls) {
    if (extRegistry.registeredClasses.inverse().containsKey(cls) || shimDispatcher.contains(cls)) {
      return true;
    }
    if (cls.isArray()) {
      return isSecure(TypeUtils.getArrayComponent(cls));
    }
    if (fory.getConfig().requireClassRegistration()) {
      return Functions.isLambda(cls)
          || ReflectionUtils.isJdkProxy(cls)
          || extRegistry.registeredClassIdMap.containsKey(cls)
          || shimDispatcher.contains(cls);
    } else {
      return extRegistry.classChecker.checkClass(this, cls.getName());
    }
    // Don't take java Exception as secure in case future JDK introduce insecure JDK exception.
    // if (Exception.class.isAssignableFrom(cls)
    //     && cls.getName().startsWith("java.")
    //     && !cls.getName().startsWith("java.sql")) {
    //   return true;
    // }
  }

  /**
   * Write class info to <code>buffer</code>. TODO(chaokunyang): The method should try to write
   * aligned data to reduce cpu instruction overhead. `writeClassInfo` is the last step before
   * serializing object, if this writes are aligned, then later serialization will be more
   * efficient.
   */
  public void writeClassAndUpdateCache(MemoryBuffer buffer, Class<?> cls) {
    // fast path for common type
    if (cls == Integer.class) {
      buffer.writeVarUint32Small7(INTEGER_CLASS_ID << 1);
    } else if (cls == Long.class) {
      buffer.writeVarUint32Small7(LONG_CLASS_ID << 1);
    } else {
      writeClassInfo(buffer, getOrUpdateClassInfo(cls));
    }
  }

  // The jit-compiled native code for this method will be too big for inline, so we generated
  // `getClassInfo`
  // in fory-jit, see `BaseSeqCodecBuilder#writeAndGetClassInfo`
  // public ClassInfo writeClassInfo(MemoryBuffer buffer, Class<?> cls, ClassInfoHolder
  // classInfoHolder)
  // {
  //   ClassInfo classInfo = getClassInfo(cls, classInfoHolder);
  //   writeClassInfo(buffer, classInfo);
  //   return classInfo;
  // }

  /** Write classname for java serialization. */
  @Override
  public void writeClassInfo(MemoryBuffer buffer, ClassInfo classInfo) {
    if (metaContextShareEnabled) {
      // FIXME(chaokunyang) Register class but not register serializer can't be used with
      //  meta share mode, because no class def are sent to peer.
      writeClassInfoWithMetaShare(buffer, classInfo);
    } else {
      if (classInfo.classId == NO_CLASS_ID) { // no class id provided.
        // use classname
        // if it's null, it's a bug.
        assert classInfo.namespaceBytes != null;
        metaStringResolver.writeMetaStringBytesWithFlag(buffer, classInfo.namespaceBytes);
        assert classInfo.typeNameBytes != null;
        metaStringResolver.writeMetaStringBytes(buffer, classInfo.typeNameBytes);
      } else {
        // use classId
        buffer.writeVarUint32(classInfo.classId << 1);
      }
    }
  }

  public void writeClassInfoWithMetaShare(MemoryBuffer buffer, ClassInfo classInfo) {
    if (classInfo.classId != NO_CLASS_ID && !classInfo.needToWriteClassDef) {
      buffer.writeVarUint32(classInfo.classId << 1);
      return;
    }
    MetaContext metaContext = fory.getSerializationContext().getMetaContext();
    assert metaContext != null : SET_META__CONTEXT_MSG;
    IdentityObjectIntMap<Class<?>> classMap = metaContext.classMap;
    int newId = classMap.size;
    int id = classMap.putOrGet(classInfo.cls, newId);
    if (id >= 0) {
      buffer.writeVarUint32(id << 1 | 0b1);
    } else {
      buffer.writeVarUint32(newId << 1 | 0b1);
      ClassDef classDef = classInfo.classDef;
      if (classDef == null) {
        classDef = buildClassDef(classInfo);
      }
      metaContext.writingClassDefs.add(classDef);
    }
  }

  private ClassDef buildClassDef(ClassInfo classInfo) {
    ClassDef classDef;
    Serializer<?> serializer = classInfo.serializer;
    Preconditions.checkArgument(serializer.getClass() != NonexistentClassSerializer.class);
    if (needToWriteClassDef(serializer)) {
      classDef =
          classDefMap.computeIfAbsent(classInfo.cls, cls -> ClassDef.buildClassDef(fory, cls));
    } else {
      // Some type will use other serializers such MapSerializer and so on.
      classDef =
          classDefMap.computeIfAbsent(
              classInfo.cls, cls -> ClassDef.buildClassDef(this, cls, new ArrayList<>(), false));
    }
    classInfo.classDef = classDef;
    return classDef;
  }

  boolean needToWriteClassDef(Serializer serializer) {
    if (fory.getConfig().getCompatibleMode() != CompatibleMode.COMPATIBLE) {
      return false;
    }
    if (GraalvmSupport.isGraalBuildtime() && serializer instanceof GraalvmSerializerHolder) {
      Class<? extends Serializer> serializerClass =
          ((GraalvmSerializerHolder) serializer).getSerializerClass();
      return GeneratedObjectSerializer.class.isAssignableFrom(serializerClass)
          || GeneratedMetaSharedSerializer.class.isAssignableFrom(serializerClass);
    }
    return (serializer instanceof GeneratedObjectSerializer
        // May already switched to MetaSharedSerializer when update class info cache.
        || serializer instanceof GeneratedMetaSharedSerializer
        || serializer instanceof LazyInitBeanSerializer
        || serializer instanceof ObjectSerializer
        || serializer instanceof MetaSharedSerializer);
  }

  private ClassInfo readClassInfoWithMetaShare(MemoryBuffer buffer, MetaContext metaContext) {
    assert metaContext != null : SET_META__CONTEXT_MSG;
    int header = buffer.readVarUint32Small14();
    int id = header >>> 1;
    if ((header & 0b1) == 0) {
      return getOrUpdateClassInfo((short) id);
    }
    ClassInfo classInfo = metaContext.readClassInfos.get(id);
    if (classInfo == null) {
      classInfo = readClassInfoWithMetaShare(metaContext, id);
    }
    return classInfo;
  }

  ClassInfo readClassInfoWithMetaShare(MetaContext metaContext, int index) {
    ClassDef classDef = metaContext.readClassDefs.get(index);
    Tuple2<ClassDef, ClassInfo> classDefTuple = extRegistry.classIdToDef.get(classDef.getId());
    ClassInfo classInfo;
    if (classDefTuple == null || classDefTuple.f1 == null || classDefTuple.f1.serializer == null) {
      classInfo = buildMetaSharedClassInfo(classDefTuple, classDef);
    } else {
      classInfo = classDefTuple.f1;
    }
    metaContext.readClassInfos.set(index, classInfo);
    return classInfo;
  }

  public ClassInfo readClassInfoWithMetaShare(MemoryBuffer buffer, Class<?> targetClass) {
    assert metaContextShareEnabled;
    ClassInfo classInfo =
        readClassInfoWithMetaShare(buffer, fory.getSerializationContext().getMetaContext());
    Class<?> readClass = classInfo.getCls();
    // replace target class if needed
    if (targetClass != readClass) {
      Tuple2<Class<?>, Class<?>> key = Tuple2.of(readClass, targetClass);
      ClassInfo newClassInfo = extRegistry.transformedClassInfo.get(key);
      if (newClassInfo == null) {
        // similar to create serializer for `NonexistentMetaShared`
        newClassInfo =
            getMetaSharedClassInfo(
                classInfo.classDef.replaceRootClassTo(this, targetClass), targetClass);
        extRegistry.transformedClassInfo.put(key, newClassInfo);
      }
      return newClassInfo;
    }
    return classInfo;
  }

  private ClassInfo buildMetaSharedClassInfo(
      Tuple2<ClassDef, ClassInfo> classDefTuple, ClassDef classDef) {
    ClassInfo classInfo;
    if (classDefTuple != null) {
      classDef = classDefTuple.f0;
    }
    Class<?> cls = loadClass(classDef.getClassSpec());
    if (!classDef.hasFieldsMeta()) {
      classInfo = getClassInfo(cls);
    } else {
      classInfo = getMetaSharedClassInfo(classDef, cls);
    }
    // Share serializer for same version class def to avoid too much different meta
    // context take up too much memory.
    putClassDef(classDef, classInfo);
    return classInfo;
  }

  // TODO(chaokunyang) if ClassDef is consistent with class in this process,
  //  use existing serializer instead.
  private ClassInfo getMetaSharedClassInfo(ClassDef classDef, Class<?> clz) {
    if (clz == NonexistentSkip.class) {
      clz = NonexistentMetaShared.class;
    }
    Class<?> cls = clz;
    Short classId = extRegistry.registeredClassIdMap.get(cls);
    ClassInfo classInfo =
        new ClassInfo(this, cls, null, classId == null ? NO_CLASS_ID : classId, NOT_SUPPORT_XLANG);
    classInfo.classDef = classDef;
    if (NonexistentClass.class.isAssignableFrom(TypeUtils.getComponentIfArray(cls))) {
      if (cls == NonexistentMetaShared.class) {
        classInfo.setSerializer(this, new NonexistentClassSerializer(fory, classDef));
        // ensure `NonexistentMetaSharedClass` registered to write fixed-length class def,
        // so we can rewrite it in `NonexistentClassSerializer`.
        Preconditions.checkNotNull(classId);
      } else {
        classInfo.serializer =
            NonexistentClassSerializers.getSerializer(fory, classDef.getClassName(), cls);
      }
      return classInfo;
    }
    if (clz.isArray() || cls.isEnum()) {
      return getClassInfo(cls);
    }
    Class<? extends Serializer> sc =
        getMetaSharedDeserializerClassFromGraalvmRegistry(cls, classDef);
    if (sc == null) {
      if (GraalvmSupport.isGraalRuntime()) {
        sc = MetaSharedSerializer.class;
        LOG.warn(
            "Can't generate class at runtime in graalvm for class def {}, use {} instead",
            classDef,
            sc);
      } else {
        sc =
            fory.getJITContext()
                .registerSerializerJITCallback(
                    () -> MetaSharedSerializer.class,
                    () -> CodecUtils.loadOrGenMetaSharedCodecClass(fory, cls, classDef),
                    c -> classInfo.setSerializer(this, Serializers.newSerializer(fory, cls, c)));
      }
    }
    if (sc == MetaSharedSerializer.class) {
      classInfo.setSerializer(this, new MetaSharedSerializer(fory, cls, classDef));
    } else {
      classInfo.setSerializer(this, Serializers.newSerializer(fory, cls, sc));
    }
    return classInfo;
  }

  /**
   * Write all new class definitions meta to buffer at last, so that if some class doesn't exist on
   * peer, but one of class which exists on both side are sent in this stream, the definition meta
   * can still be stored in peer, and can be resolved next time when sent only an id.
   */
  public void writeClassDefs(MemoryBuffer buffer) {
    MetaContext metaContext = fory.getSerializationContext().getMetaContext();
    ObjectArray<ClassDef> writingClassDefs = metaContext.writingClassDefs;
    final int size = writingClassDefs.size;
    buffer.writeVarUint32Small7(size);
    if (buffer.isHeapFullyWriteable()) {
      writeClassDefs(buffer, writingClassDefs, size);
    } else {
      for (int i = 0; i < size; i++) {
        writingClassDefs.get(i).writeClassDef(buffer);
      }
    }
    metaContext.writingClassDefs.size = 0;
  }

  private void writeClassDefs(
      MemoryBuffer buffer, ObjectArray<ClassDef> writingClassDefs, int size) {
    int writerIndex = buffer.writerIndex();
    for (int i = 0; i < size; i++) {
      byte[] encoded = writingClassDefs.get(i).getEncoded();
      int bytesLen = encoded.length;
      buffer.ensure(writerIndex + bytesLen);
      final byte[] targetArray = buffer.getHeapMemory();
      System.arraycopy(encoded, 0, targetArray, writerIndex, bytesLen);
      writerIndex += bytesLen;
    }
    buffer.writerIndex(writerIndex);
  }

  /**
   * Ensure all class definition are read and populated, even there are deserialization exception
   * such as ClassNotFound. So next time a class def written previously identified by an id can be
   * got from the meta context.
   */
  public void readClassDefs(MemoryBuffer buffer) {
    MetaContext metaContext = fory.getSerializationContext().getMetaContext();
    assert metaContext != null : SET_META__CONTEXT_MSG;
    int numClassDefs = buffer.readVarUint32Small7();
    for (int i = 0; i < numClassDefs; i++) {
      long id = buffer.readInt64();
      Tuple2<ClassDef, ClassInfo> tuple2 = extRegistry.classIdToDef.get(id);
      if (tuple2 != null) {
        ClassDef.skipClassDef(buffer, id);
      } else {
        tuple2 = readClassDef(buffer, id);
      }
      metaContext.readClassDefs.add(tuple2.f0);
      metaContext.readClassInfos.add(tuple2.f1);
    }
  }

  private Tuple2<ClassDef, ClassInfo> readClassDef(MemoryBuffer buffer, long header) {
    ClassDef readClassDef = ClassDef.readClassDef(fory, buffer, header);
    Tuple2<ClassDef, ClassInfo> tuple2 = extRegistry.classIdToDef.get(readClassDef.getId());
    if (tuple2 == null) {
      tuple2 = putClassDef(readClassDef, null);
    }
    return tuple2;
  }

  private Tuple2<ClassDef, ClassInfo> putClassDef(ClassDef classDef, ClassInfo classInfo) {
    Tuple2<ClassDef, ClassInfo> tuple2 = Tuple2.of(classDef, classInfo);
    extRegistry.classIdToDef.put(classDef.getId(), tuple2);
    return tuple2;
  }

  public ClassDef getClassDef(Class<?> cls, boolean resolveParent) {
    if (resolveParent) {
      return classDefMap.computeIfAbsent(cls, k -> ClassDef.buildClassDef(fory, cls));
    }
    ClassDef classDef = extRegistry.currentLayerClassDef.get(cls);
    if (classDef == null) {
      classDef = ClassDef.buildClassDef(fory, cls, false);
      extRegistry.currentLayerClassDef.put(cls, classDef);
    }
    return classDef;
  }

  /**
   * Native code for ClassResolver.writeClassInfo is too big to inline, so inline it manually.
   *
   * <p>See `already compiled into a big method` in <a
   * href="https://wiki.openjdk.org/display/HotSpot/Server+Compiler+Inlining+Messages">Server+Compiler+Inlining+Messages</a>
   */
  // Note: Thread safe for jit thread to call.
  public Expression writeClassExpr(
      Expression classResolverRef, Expression buffer, Expression classInfo) {
    return new Invoke(classResolverRef, "writeClassInfo", buffer, classInfo);
  }

  // Note: Thread safe for jit thread to call.
  public Expression writeClassExpr(Expression buffer, short classId) {
    Preconditions.checkArgument(classId != NO_CLASS_ID);
    return writeClassExpr(buffer, Literal.ofShort(classId));
  }

  // Note: Thread safe for jit thread to call.
  public Expression writeClassExpr(Expression buffer, Expression classId) {
    return new Invoke(buffer, "writeVarUint32", new Expression.BitShift("<<", classId, 1));
  }

  // Note: Thread safe for jit thread to call.
  public Expression skipRegisteredClassExpr(Expression buffer) {
    return new Invoke(buffer, "readVarUint32Small14");
  }

  /**
   * Write classname for java serialization. Note that the object of provided class can be
   * non-serializable, and class with writeReplace/readResolve defined won't be skipped. For
   * serializable object, {@link #writeClassInfo(MemoryBuffer, ClassInfo)} should be invoked.
   */
  public void writeClassInternal(MemoryBuffer buffer, Class<?> cls) {
    ClassInfo classInfo = classInfoMap.get(cls);
    if (classInfo == null) {
      Short classId = extRegistry.registeredClassIdMap.get(cls);
      // Don't create serializer in case the object for class is non-serializable,
      // Or class is abstract or interface.
      classInfo =
          new ClassInfo(
              this, cls, null, classId == null ? NO_CLASS_ID : classId, NOT_SUPPORT_XLANG);
      classInfoMap.put(cls, classInfo);
    }
    writeClassInternal(buffer, classInfo);
  }

  public void writeClassInternal(MemoryBuffer buffer, ClassInfo classInfo) {
    short classId = classInfo.classId;
    if (classId == REPLACE_STUB_ID) {
      // clear class id to avoid replaced class written as
      // ReplaceResolveSerializer.ReplaceStub
      classInfo.classId = NO_CLASS_ID;
    }
    if (classInfo.classId != NO_CLASS_ID) {
      buffer.writeVarUint32(classInfo.classId << 1);
    } else {
      // let the lowermost bit of next byte be set, so the deserialization can know
      // whether need to read class by name in advance
      metaStringResolver.writeMetaStringBytesWithFlag(buffer, classInfo.namespaceBytes);
      metaStringResolver.writeMetaStringBytes(buffer, classInfo.typeNameBytes);
    }
    classInfo.classId = classId;
  }

  /**
   * Read serialized java classname. Note that the object of the class can be non-serializable. For
   * serializable object, {@link #readClassInfo(MemoryBuffer)} or {@link
   * #readClassInfo(MemoryBuffer, ClassInfoHolder)} should be invoked.
   */
  public Class<?> readClassInternal(MemoryBuffer buffer) {
    int header = buffer.readVarUint32Small14();
    final ClassInfo classInfo;
    if ((header & 0b1) != 0) {
      // let the lowermost bit of next byte be set, so the deserialization can know
      // whether need to read class by name in advance
      MetaStringBytes packageBytes = metaStringResolver.readMetaStringBytesWithFlag(buffer, header);
      MetaStringBytes simpleClassNameBytes = metaStringResolver.readMetaStringBytes(buffer);
      classInfo = loadBytesToClassInfo(packageBytes, simpleClassNameBytes);
    } else {
      classInfo = registeredId2ClassInfo[(short) (header >> 1)];
    }
    final Class<?> cls = classInfo.cls;
    currentReadClass = cls;
    return cls;
  }

  /**
   * Read class info from java data <code>buffer</code>. {@link #readClassInfo(MemoryBuffer,
   * ClassInfo)} is faster since it use a non-global class info cache.
   */
  public ClassInfo readClassInfo(MemoryBuffer buffer) {
    if (metaContextShareEnabled) {
      return readClassInfoWithMetaShare(buffer, fory.getSerializationContext().getMetaContext());
    }
    int header = buffer.readVarUint32Small14();
    ClassInfo classInfo;
    if ((header & 0b1) != 0) {
      classInfo = readClassInfoFromBytes(buffer, classInfoCache, header);
      classInfoCache = classInfo;
    } else {
      classInfo = getOrUpdateClassInfo((short) (header >> 1));
    }
    currentReadClass = classInfo.cls;
    return classInfo;
  }

  /**
   * Read class info from java data <code>buffer</code>. `classInfoCache` is used as a cache to
   * reduce map lookup to load class from binary.
   */
  @CodegenInvoke
  @Override
  public ClassInfo readClassInfo(MemoryBuffer buffer, ClassInfo classInfoCache) {
    if (metaContextShareEnabled) {
      return readClassInfoWithMetaShare(buffer, fory.getSerializationContext().getMetaContext());
    }
    int header = buffer.readVarUint32Small14();
    if ((header & 0b1) != 0) {
      return readClassInfoByCache(buffer, classInfoCache, header);
    } else {
      return getClassInfo((short) (header >> 1));
    }
  }

  /** Read class info, update classInfoHolder if cache not hit. */
  @Override
  @CodegenInvoke
  public ClassInfo readClassInfo(MemoryBuffer buffer, ClassInfoHolder classInfoHolder) {
    if (metaContextShareEnabled) {
      return readClassInfoWithMetaShare(buffer, fory.getSerializationContext().getMetaContext());
    }
    int header = buffer.readVarUint32Small14();
    if ((header & 0b1) != 0) {
      return readClassInfoFromBytes(buffer, classInfoHolder, header);
    } else {
      return getClassInfo((short) (header >> 1));
    }
  }

  private ClassInfo readClassInfoByCache(
      MemoryBuffer buffer, ClassInfo classInfoCache, int header) {
    if (metaContextShareEnabled) {
      return readClassInfoWithMetaShare(buffer, fory.getSerializationContext().getMetaContext());
    }
    return readClassInfoFromBytes(buffer, classInfoCache, header);
  }

  private ClassInfo readClassInfoFromBytes(
      MemoryBuffer buffer, ClassInfoHolder classInfoHolder, int header) {
    if (metaContextShareEnabled) {
      return readClassInfoWithMetaShare(buffer, fory.getSerializationContext().getMetaContext());
    }
    ClassInfo classInfo = readClassInfoFromBytes(buffer, classInfoHolder.classInfo, header);
    classInfoHolder.classInfo = classInfo;
    return classInfo;
  }

  private ClassInfo readClassInfoFromBytes(
      MemoryBuffer buffer, ClassInfo classInfoCache, int header) {
    MetaStringBytes typeNameBytesCache = classInfoCache.typeNameBytes;
    MetaStringBytes namespaceBytes;
    MetaStringBytes simpleClassNameBytes;
    if (typeNameBytesCache != null) {
      MetaStringBytes packageNameBytesCache = classInfoCache.namespaceBytes;
      namespaceBytes =
          metaStringResolver.readMetaStringBytesWithFlag(buffer, packageNameBytesCache, header);
      assert packageNameBytesCache != null;
      simpleClassNameBytes = metaStringResolver.readMetaStringBytes(buffer, typeNameBytesCache);
      if (typeNameBytesCache.hashCode == simpleClassNameBytes.hashCode
          && packageNameBytesCache.hashCode == namespaceBytes.hashCode) {
        return classInfoCache;
      }
    } else {
      namespaceBytes = metaStringResolver.readMetaStringBytesWithFlag(buffer, header);
      simpleClassNameBytes = metaStringResolver.readMetaStringBytes(buffer);
    }
    ClassInfo classInfo = loadBytesToClassInfo(namespaceBytes, simpleClassNameBytes);
    if (classInfo.serializer == null) {
      return getClassInfo(classInfo.cls);
    }
    return classInfo;
  }

  ClassInfo loadBytesToClassInfo(
      MetaStringBytes packageBytes, MetaStringBytes simpleClassNameBytes) {
    TypeNameBytes typeNameBytes =
        new TypeNameBytes(packageBytes.hashCode, simpleClassNameBytes.hashCode);
    ClassInfo classInfo = compositeNameBytes2ClassInfo.get(typeNameBytes);
    if (classInfo == null) {
      classInfo = populateBytesToClassInfo(typeNameBytes, packageBytes, simpleClassNameBytes);
    }
    return classInfo;
  }

  private ClassInfo populateBytesToClassInfo(
      TypeNameBytes typeNameBytes,
      MetaStringBytes packageBytes,
      MetaStringBytes simpleClassNameBytes) {
    String packageName = packageBytes.decode(PACKAGE_DECODER);
    String className = simpleClassNameBytes.decode(TYPE_NAME_DECODER);
    ClassSpec classSpec = Encoders.decodePkgAndClass(packageName, className);
    MetaStringBytes fullClassNameBytes =
        metaStringResolver.getOrCreateMetaStringBytes(
            PACKAGE_ENCODER.encode(classSpec.entireClassName, MetaString.Encoding.UTF_8));
    Class<?> cls = loadClass(classSpec.entireClassName, classSpec.isEnum, classSpec.dimension);
    ClassInfo classInfo =
        new ClassInfo(
            cls,
            fullClassNameBytes,
            packageBytes,
            simpleClassNameBytes,
            false,
            null,
            NO_CLASS_ID,
            NOT_SUPPORT_XLANG);
    if (NonexistentClass.class.isAssignableFrom(TypeUtils.getComponentIfArray(cls))) {
      classInfo.serializer =
          NonexistentClassSerializers.getSerializer(fory, classSpec.entireClassName, cls);
    } else {
      // don't create serializer here, if the class is an interface,
      // there won't be serializer since interface has no instance.
      if (!classInfoMap.containsKey(cls)) {
        classInfoMap.put(cls, classInfo);
      }
    }
    compositeNameBytes2ClassInfo.put(typeNameBytes, classInfo);
    return classInfo;
  }

  public Class<?> getCurrentReadClass() {
    return currentReadClass;
  }

  private Class<?> loadClass(ClassSpec classSpec) {
    return loadClass(classSpec.entireClassName, classSpec.isEnum, classSpec.dimension);
  }

  private Class<?> loadClass(String className, boolean isEnum, int arrayDims) {
    return loadClass(className, isEnum, arrayDims, fory.getConfig().deserializeNonexistentClass());
  }

  private Class<?> loadClass(
      String className, boolean isEnum, int arrayDims, boolean deserializeNonexistentClass) {
    extRegistry.classChecker.checkClass(this, className);
    Class<?> cls = extRegistry.registeredClasses.get(className);
    if (cls != null) {
      return cls;
    }
    try {
      return Class.forName(className, false, fory.getClassLoader());
    } catch (ClassNotFoundException e) {
      try {
        return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
      } catch (ClassNotFoundException ex) {
        String msg =
            String.format(
                "Class %s not found from classloaders [%s, %s]",
                className, fory.getClassLoader(), Thread.currentThread().getContextClassLoader());
        if (deserializeNonexistentClass) {
          LOG.warn(msg);
          return NonexistentClass.getNonexistentClass(
              className, isEnum, arrayDims, metaContextShareEnabled);
        }
        throw new IllegalStateException(msg, ex);
      }
    }
  }

  public void reset() {
    resetRead();
    resetWrite();
  }

  public void resetRead() {}

  public void resetWrite() {}

  @CodegenInvoke
  public GenericType getGenericTypeInStruct(Class<?> cls, String genericTypeStr) {
    Map<String, GenericType> map =
        extRegistry.classGenericTypes.computeIfAbsent(cls, k -> new HashMap<>());
    GenericType genericType = map.get(genericTypeStr);
    if (genericType == null) {
      for (Field field : ReflectionUtils.getFields(cls, true)) {
        Type type = field.getGenericType();
        TypeRef<Object> typeRef = TypeRef.of(type);
        genericType = buildGenericType(typeRef);
        map.put(type.getTypeName(), genericType);
      }
    }
    return genericType;
  }

  @Override
  public GenericType buildGenericType(TypeRef<?> typeRef) {
    return GenericType.build(
        typeRef,
        t -> {
          if (t.getClass() == Class.class) {
            return isMonomorphic((Class<?>) t);
          } else {
            return isMonomorphic(getRawType(t));
          }
        });
  }

  @Override
  public GenericType buildGenericType(Type type) {
    GenericType genericType = extRegistry.genericTypes.get(type);
    if (genericType != null) {
      return genericType;
    }
    return populateGenericType(type);
  }

  private GenericType populateGenericType(Type type) {
    GenericType genericType =
        GenericType.build(
            type,
            t -> {
              if (t.getClass() == Class.class) {
                return isMonomorphic((Class<?>) t);
              } else {
                return isMonomorphic(getRawType(t));
              }
            });
    extRegistry.genericTypes.put(type, genericType);
    return genericType;
  }

  public GenericType getObjectGenericType() {
    return extRegistry.objectGenericType;
  }

  public ClassInfo newClassInfo(Class<?> cls, Serializer<?> serializer, short classId) {
    return new ClassInfo(this, cls, serializer, classId, NOT_SUPPORT_XLANG);
  }

  // Invoked by fory JIT.
  @Override
  public ClassInfo nilClassInfo() {
    return new ClassInfo(this, null, null, NO_CLASS_ID, NOT_SUPPORT_XLANG);
  }

  @Override
  public ClassInfoHolder nilClassInfoHolder() {
    return new ClassInfoHolder(nilClassInfo());
  }

  public boolean isPrimitive(short classId) {
    return classId >= PRIMITIVE_VOID_CLASS_ID && classId <= PRIMITIVE_DOUBLE_CLASS_ID;
  }

  public MetaStringResolver getMetaStringResolver() {
    return metaStringResolver;
  }

  public CodeGenerator getCodeGenerator(ClassLoader... loaders) {
    List<ClassLoader> loaderList = new ArrayList<>(loaders.length);
    Collections.addAll(loaderList, loaders);
    return extRegistry.codeGeneratorMap.get(loaderList);
  }

  public void setCodeGenerator(ClassLoader loader, CodeGenerator codeGenerator) {
    setCodeGenerator(new ClassLoader[] {loader}, codeGenerator);
  }

  public void setCodeGenerator(ClassLoader[] loaders, CodeGenerator codeGenerator) {
    extRegistry.codeGeneratorMap.put(Arrays.asList(loaders), codeGenerator);
  }

  public DescriptorGrouper createDescriptorGrouper(
      Collection<Descriptor> descriptors, boolean descriptorsGroupedOrdered) {
    return createDescriptorGrouper(descriptors, descriptorsGroupedOrdered, null);
  }

  public DescriptorGrouper createDescriptorGrouper(
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator) {
    if (fory.isCrossLanguage()) {
      return DescriptorGrouper.createDescriptorGrouper(
          this::isMonomorphic,
          descriptors,
          descriptorsGroupedOrdered,
          descriptorUpdator,
          fory.compressInt(),
          fory.compressLong(),
          (o1, o2) -> {
            int xtypeId = getXtypeId(o1.getRawType());
            int xtypeId2 = getXtypeId(o2.getRawType());
            if (xtypeId == xtypeId2) {
              return o1.getSnakeCaseName().compareTo(o2.getSnakeCaseName());
            } else {
              return xtypeId - xtypeId2;
            }
          });
    }
    return DescriptorGrouper.createDescriptorGrouper(
        fory.getClassResolver()::isMonomorphic,
        descriptors,
        descriptorsGroupedOrdered,
        descriptorUpdator,
        fory.compressInt(),
        fory.compressLong(),
        DescriptorGrouper.COMPARATOR_BY_TYPE_AND_NAME);
  }

  private static final int UNKNOWN_TYPE_ID = -1;

  private int getXtypeId(Class<?> cls) {
    if (isCollection(cls)) {
      return Types.LIST;
    }
    if (cls.isArray() && !cls.getComponentType().isPrimitive()) {
      return Types.LIST;
    }
    if (isMap(cls)) {
      return Types.MAP;
    }
    if (fory.getXtypeResolver().isRegistered(cls)) {
      return fory.getXtypeResolver().getClassInfo(cls).getXtypeId();
    } else {
      if (ReflectionUtils.isMonomorphic(cls)) {
        throw new UnsupportedOperationException(cls + " is not supported for xlang serialization");
      }
      return UNKNOWN_TYPE_ID;
    }
  }

  @Override
  public Fory getFory() {
    return fory;
  }

  /**
   * Ensure all compilation for serializers and accessors even for lazy initialized serializers.
   * This method will block until all compilation is done.
   */
  public void ensureSerializersCompiled() {
    try {
      fory.getJITContext().lock();
      Serializers.newSerializer(fory, LambdaSerializer.STUB_LAMBDA_CLASS, LambdaSerializer.class);
      Serializers.newSerializer(
          fory, JdkProxySerializer.SUBT_PROXY.getClass(), JdkProxySerializer.class);
      classInfoMap.forEach(
          (cls, classInfo) -> {
            if (classInfo.serializer == null) {
              getSerializer(classInfo.cls, isSerializable(classInfo.cls));
            }
          });
      if (GraalvmSupport.isGraalBuildtime()) {
        classInfoMap.forEach(
            (cls, classInfo) -> {
              if (classInfo.serializer != null
                  && !extRegistry.initialClassInfos.contains(classInfo)) {
                classInfo.serializer = null;
              }
            });
      }
    } finally {
      fory.getJITContext().unlock();
    }
  }

  private static final ConcurrentMap<Integer, GraalvmClassRegistry> GRAALVM_REGISTRY =
      new ConcurrentHashMap<>();

  // CHECKSTYLE.OFF:MethodName
  public static void _addGraalvmClassRegistry(int foryConfigHash, ClassResolver classResolver) {
    // CHECKSTYLE.ON:MethodName
    if (GraalvmSupport.isGraalBuildtime()) {
      GraalvmClassRegistry registry =
          GRAALVM_REGISTRY.computeIfAbsent(foryConfigHash, k -> new GraalvmClassRegistry());
      registry.resolvers.add(classResolver);
    }
  }

  private static class GraalvmClassRegistry {
    private final List<ClassResolver> resolvers;
    private final Map<Class<?>, Class<? extends Serializer>> serializerClassMap;
    private final Map<Long, Class<? extends Serializer>> deserializerClassMap;

    private GraalvmClassRegistry() {
      resolvers = Collections.synchronizedList(new ArrayList<>());
      serializerClassMap = new ConcurrentHashMap<>();
      deserializerClassMap = new ConcurrentHashMap<>();
    }
  }

  private GraalvmClassRegistry getGraalvmClassRegistry() {
    return GRAALVM_REGISTRY.computeIfAbsent(
        fory.getConfig().getConfigHash(), k -> new GraalvmClassRegistry());
  }

  private Class<? extends Serializer> getGraalvmSerializerClass(Serializer serializer) {
    if (serializer instanceof GraalvmSerializerHolder) {
      return ((GraalvmSerializerHolder) serializer).getSerializerClass();
    }
    return serializer.getClass();
  }

  private Class<? extends Serializer> getSerializerClassFromGraalvmRegistry(Class<?> cls) {
    GraalvmClassRegistry registry = getGraalvmClassRegistry();
    List<ClassResolver> classResolvers = registry.resolvers;
    if (classResolvers.isEmpty()) {
      return null;
    }
    for (ClassResolver classResolver : classResolvers) {
      if (classResolver != this) {
        ClassInfo classInfo = classResolver.classInfoMap.get(cls);
        if (classInfo != null && classInfo.serializer != null) {
          return classInfo.serializer.getClass();
        }
      }
    }
    Class<? extends Serializer> serializerClass = registry.serializerClassMap.get(cls);
    // noinspection Duplicates
    if (serializerClass != null) {
      return serializerClass;
    }
    if (GraalvmSupport.isGraalRuntime()) {
      if (Functions.isLambda(cls) || ReflectionUtils.isJdkProxy(cls)) {
        return null;
      }
      throw new RuntimeException(String.format("Class %s is not registered", cls));
    }
    return null;
  }

  private Class<? extends Serializer> getMetaSharedDeserializerClassFromGraalvmRegistry(
      Class<?> cls, ClassDef classDef) {
    GraalvmClassRegistry registry = getGraalvmClassRegistry();
    List<ClassResolver> classResolvers = registry.resolvers;
    if (classResolvers.isEmpty()) {
      return null;
    }
    Class<? extends Serializer> deserializerClass =
        registry.deserializerClassMap.get(classDef.getId());
    // noinspection Duplicates
    if (deserializerClass != null) {
      return deserializerClass;
    }
    if (GraalvmSupport.isGraalRuntime()) {
      if (Functions.isLambda(cls) || ReflectionUtils.isJdkProxy(cls)) {
        return null;
      }
      throw new RuntimeException(
          String.format(
              "Class %s is not registered, registered classes: %s",
              cls, registry.deserializerClassMap));
    }
    return null;
  }
}
