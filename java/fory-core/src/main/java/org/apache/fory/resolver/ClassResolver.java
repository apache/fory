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

import static org.apache.fory.meta.Encoders.GENERIC_ENCODER;
import static org.apache.fory.meta.Encoders.PACKAGE_DECODER;
import static org.apache.fory.meta.Encoders.PACKAGE_ENCODER;
import static org.apache.fory.meta.Encoders.TYPE_NAME_DECODER;
import static org.apache.fory.meta.Encoders.encodePackage;
import static org.apache.fory.meta.Encoders.encodeTypeName;
import static org.apache.fory.serializer.CodegenSerializer.loadCodegenSerializer;
import static org.apache.fory.serializer.CodegenSerializer.supportCodegenForJavaSerialization;
import static org.apache.fory.type.TypeUtils.OBJECT_TYPE;
import static org.apache.fory.type.Types.INVALID_USER_TYPE_ID;
import static org.apache.fory.type.Types.isUserTypeRegisteredById;

import java.io.Externalizable;
import java.io.IOException;
import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
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
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.fory.Fory;
import org.apache.fory.ForyCopyable;
import org.apache.fory.annotation.CodegenInvoke;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.Internal;
import org.apache.fory.builder.JITContext;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.collection.BoolList;
import org.apache.fory.collection.Float16List;
import org.apache.fory.collection.Float32List;
import org.apache.fory.collection.Float64List;
import org.apache.fory.collection.Int16List;
import org.apache.fory.collection.Int32List;
import org.apache.fory.collection.Int64List;
import org.apache.fory.collection.Int8List;
import org.apache.fory.collection.ObjectMap;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.collection.Uint16List;
import org.apache.fory.collection.Uint32List;
import org.apache.fory.collection.Uint64List;
import org.apache.fory.collection.Uint8List;
import org.apache.fory.config.Language;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.meta.ClassSpec;
import org.apache.fory.meta.Encoders;
import org.apache.fory.meta.MetaString;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.ObjectCreators;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.serializer.ArraySerializers;
import org.apache.fory.serializer.BufferSerializers;
import org.apache.fory.serializer.CodegenSerializer.LazyInitBeanSerializer;
import org.apache.fory.serializer.EnumSerializer;
import org.apache.fory.serializer.ExternalizableSerializer;
import org.apache.fory.serializer.ForyCopyableSerializer;
import org.apache.fory.serializer.JavaSerializer;
import org.apache.fory.serializer.JdkProxySerializer;
import org.apache.fory.serializer.LambdaSerializer;
import org.apache.fory.serializer.LocaleSerializer;
import org.apache.fory.serializer.NoneSerializer;
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
import org.apache.fory.serializer.UnknownClass;
import org.apache.fory.serializer.UnknownClass.UnknownEmptyStruct;
import org.apache.fory.serializer.UnknownClass.UnknownStruct;
import org.apache.fory.serializer.UnknownClassSerializers;
import org.apache.fory.serializer.UnsignedSerializers;
import org.apache.fory.serializer.collection.ChildContainerSerializers;
import org.apache.fory.serializer.collection.CollectionSerializer;
import org.apache.fory.serializer.collection.CollectionSerializers;
import org.apache.fory.serializer.collection.GuavaCollectionSerializers;
import org.apache.fory.serializer.collection.ImmutableCollectionSerializers;
import org.apache.fory.serializer.collection.MapSerializer;
import org.apache.fory.serializer.collection.MapSerializers;
import org.apache.fory.serializer.collection.PrimitiveListSerializers;
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
import org.apache.fory.type.Float16;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.type.union.Union;
import org.apache.fory.type.unsigned.Uint16;
import org.apache.fory.type.unsigned.Uint32;
import org.apache.fory.type.unsigned.Uint64;
import org.apache.fory.type.unsigned.Uint8;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.function.Functions;
import org.apache.fory.util.record.RecordUtils;

/**
 * Class registry for types of serializing objects, responsible for reading/writing types, setting
 * up relations between serializer and types.
 *
 * <h2>Class ID Space</h2>
 *
 * <p>Fory separates internal IDs (built-in types) from user IDs by storing the internal type tag
 * (ENUM/STRUCT/EXT) in {@code typeId} and keeping the user-registered ID in {@code userTypeId}.
 * User IDs start from 0.
 *
 * <h2>Registration Methods</h2>
 *
 * <ul>
 *   <li>{@link #register(Class)} - Auto-assigns the next available user ID
 *   <li>{@link #register(Class, long)} - Registers with a user-specified ID (0-based)
 *   <li>{@link #register(Class, String, String)} - Registers with namespace and type name
 * </ul>
 *
 * @see #register(Class)
 * @see #register(Class, long)
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ClassResolver extends TypeResolver {
  private static final Logger LOG = LoggerFactory.getLogger(ClassResolver.class);

  // reserved last 5 internal type ids for future use
  private static final int INTERNAL_ID_LIMIT = 250;
  public static final int NATIVE_START_ID = Types.BOUND;
  // reserved 5 small internal type ids for future use
  private static final int INTERNAL_TYPE_START_ID = NATIVE_START_ID + 5;
  public static final int VOID_ID = INTERNAL_TYPE_START_ID;
  public static final int CHAR_ID = INTERNAL_TYPE_START_ID + 1;
  // Note: following pre-defined class id should be continuous, since they may be used based range.
  public static final int PRIMITIVE_VOID_ID = INTERNAL_TYPE_START_ID + 2;
  public static final int PRIMITIVE_BOOL_ID = INTERNAL_TYPE_START_ID + 3;
  public static final int PRIMITIVE_INT8_ID = INTERNAL_TYPE_START_ID + 4;
  public static final int PRIMITIVE_CHAR_ID = INTERNAL_TYPE_START_ID + 5;
  public static final int PRIMITIVE_INT16_ID = INTERNAL_TYPE_START_ID + 6;
  public static final int PRIMITIVE_INT32_ID = INTERNAL_TYPE_START_ID + 7;
  public static final int PRIMITIVE_FLOAT32_ID = INTERNAL_TYPE_START_ID + 8;
  public static final int PRIMITIVE_INT64_ID = INTERNAL_TYPE_START_ID + 9;
  public static final int PRIMITIVE_FLOAT64_ID = INTERNAL_TYPE_START_ID + 10;
  public static final int PRIMITIVE_BOOLEAN_ARRAY_ID = INTERNAL_TYPE_START_ID + 11;
  public static final int PRIMITIVE_BYTE_ARRAY_ID = INTERNAL_TYPE_START_ID + 12;
  public static final int PRIMITIVE_CHAR_ARRAY_ID = INTERNAL_TYPE_START_ID + 13;
  public static final int PRIMITIVE_SHORT_ARRAY_ID = INTERNAL_TYPE_START_ID + 14;
  public static final int PRIMITIVE_INT_ARRAY_ID = INTERNAL_TYPE_START_ID + 15;
  public static final int PRIMITIVE_FLOAT_ARRAY_ID = INTERNAL_TYPE_START_ID + 16;
  public static final int PRIMITIVE_LONG_ARRAY_ID = INTERNAL_TYPE_START_ID + 17;
  public static final int PRIMITIVE_DOUBLE_ARRAY_ID = INTERNAL_TYPE_START_ID + 18;
  public static final int STRING_ARRAY_ID = INTERNAL_TYPE_START_ID + 19;
  public static final int OBJECT_ARRAY_ID = INTERNAL_TYPE_START_ID + 20;
  public static final int ARRAYLIST_ID = INTERNAL_TYPE_START_ID + 21;
  public static final int HASHMAP_ID = INTERNAL_TYPE_START_ID + 22;
  public static final int HASHSET_ID = INTERNAL_TYPE_START_ID + 23;
  public static final int CLASS_ID = INTERNAL_TYPE_START_ID + 24;
  public static final int EMPTY_OBJECT_ID = INTERNAL_TYPE_START_ID + 25;
  public static final short LAMBDA_STUB_ID = INTERNAL_TYPE_START_ID + 26;
  public static final short JDK_PROXY_STUB_ID = INTERNAL_TYPE_START_ID + 27;
  public static final short REPLACE_STUB_ID = INTERNAL_TYPE_START_ID + 28;
  public static final int NONEXISTENT_META_SHARED_ID = REPLACE_STUB_ID + 1;

  private final Fory fory;
  private TypeInfo typeInfoCache;
  // Every deserialization for unregistered class will query it, performance is important.
  private final ObjectMap<TypeNameBytes, TypeInfo> compositeNameBytes2TypeInfo =
      new ObjectMap<>(16, foryMapLoadFactor);
  // typeDefMap is inherited from TypeResolver
  private Class<?> currentReadClass;
  private final ShimDispatcher shimDispatcher;

  public ClassResolver(Fory fory) {
    super(fory);
    this.fory = fory;
    typeInfoCache = NIL_TYPE_INFO;
    extRegistry.classIdGenerator = NONEXISTENT_META_SHARED_ID + 1;
    shimDispatcher = new ShimDispatcher(fory);
    _addGraalvmClassRegistry(fory.getConfig().getConfigHash(), this);
  }

  @Override
  public void initialize() {
    extRegistry.objectGenericType = buildGenericType(OBJECT_TYPE);
    registerInternal(LambdaSerializer.ReplaceStub.class, LAMBDA_STUB_ID);
    registerInternal(JdkProxySerializer.ReplaceStub.class, JDK_PROXY_STUB_ID);
    registerInternal(ReplaceResolveSerializer.ReplaceStub.class, REPLACE_STUB_ID);
    registerInternal(void.class, PRIMITIVE_VOID_ID);
    registerInternal(boolean.class, PRIMITIVE_BOOL_ID);
    registerInternal(byte.class, PRIMITIVE_INT8_ID);
    registerInternal(char.class, PRIMITIVE_CHAR_ID);
    registerInternal(short.class, PRIMITIVE_INT16_ID);
    registerInternal(int.class, PRIMITIVE_INT32_ID);
    registerInternal(float.class, PRIMITIVE_FLOAT32_ID);
    registerInternal(long.class, PRIMITIVE_INT64_ID);
    registerInternal(double.class, PRIMITIVE_FLOAT64_ID);
    registerInternal(Void.class, VOID_ID);
    registerInternal(Boolean.class, Types.BOOL);
    registerInternal(Byte.class, Types.INT8);
    registerInternal(Character.class, CHAR_ID);
    registerInternal(Short.class, Types.INT16);
    registerInternal(Integer.class, Types.INT32);
    registerInternal(Float.class, Types.FLOAT32);
    registerInternal(Long.class, Types.INT64);
    registerInternal(Double.class, Types.FLOAT64);
    registerInternal(Float16.class, Types.FLOAT16);
    registerInternal(String.class, Types.STRING);
    registerInternal(Uint8.class, Types.UINT8);
    registerInternal(Uint16.class, Types.UINT16);
    registerInternal(Uint32.class, Types.UINT32);
    registerInternal(Uint64.class, Types.UINT64);
    registerInternal(boolean[].class, PRIMITIVE_BOOLEAN_ARRAY_ID);
    registerInternal(byte[].class, PRIMITIVE_BYTE_ARRAY_ID);
    registerInternal(char[].class, PRIMITIVE_CHAR_ARRAY_ID);
    registerInternal(short[].class, PRIMITIVE_SHORT_ARRAY_ID);
    registerInternal(int[].class, PRIMITIVE_INT_ARRAY_ID);
    registerInternal(float[].class, PRIMITIVE_FLOAT_ARRAY_ID);
    registerInternal(long[].class, PRIMITIVE_LONG_ARRAY_ID);
    registerInternal(double[].class, PRIMITIVE_DOUBLE_ARRAY_ID);
    registerInternal(Float16[].class);
    registerInternal(String[].class, STRING_ARRAY_ID);
    registerInternal(Object[].class, OBJECT_ARRAY_ID);
    registerInternal(BoolList.class, Types.BOOL_ARRAY);
    registerInternal(Int8List.class, Types.INT8_ARRAY);
    registerInternal(Int16List.class, Types.INT16_ARRAY);
    registerInternal(Int32List.class, Types.INT32_ARRAY);
    registerInternal(Int64List.class, Types.INT64_ARRAY);
    registerInternal(Uint8List.class, Types.UINT8_ARRAY);
    registerInternal(Uint16List.class, Types.UINT16_ARRAY);
    registerInternal(Uint32List.class, Types.UINT32_ARRAY);
    registerInternal(Uint64List.class, Types.UINT64_ARRAY);
    registerInternal(Float32List.class, Types.FLOAT32_ARRAY);
    registerInternal(Float64List.class, Types.FLOAT64_ARRAY);
    registerInternal(Float16List.class, Types.FLOAT16_ARRAY);
    registerInternal(ArrayList.class, ARRAYLIST_ID);
    registerInternal(HashMap.class, HASHMAP_ID);
    registerInternal(HashSet.class, HASHSET_ID);
    registerInternal(Class.class, CLASS_ID);
    registerInternal(Object.class, EMPTY_OBJECT_ID);
    registerCommonUsedClasses();
    registerDefaultClasses();
    addDefaultSerializers();
    shimDispatcher.initialize();
    if (GraalvmSupport.isGraalBuildtime()) {
      classInfoMap.forEach(
          (cls, classInfo) -> {
            if (classInfo.serializer != null) {
              extRegistry.registeredTypeInfos.add(classInfo);
            }
          });
    }
  }

  private void addDefaultSerializers() {
    // primitive types will be boxed.
    addDefaultSerializer(void.class, NoneSerializer.class);
    addDefaultSerializer(String.class, new StringSerializer(fory));
    PrimitiveSerializers.registerDefaultSerializers(fory);
    UnsignedSerializers.registerDefaultSerializers(fory);
    Serializers.registerDefaultSerializers(fory);
    ArraySerializers.registerDefaultSerializers(fory);
    PrimitiveListSerializers.registerDefaultSerializers(fory);
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
    if (fory.getConfig().deserializeUnknownClass()) {
      if (metaContextShareEnabled) {
        registerInternal(UnknownStruct.class, NONEXISTENT_META_SHARED_ID);
        registerInternalSerializer(
            UnknownStruct.class, new UnknownClassSerializers.UnknownStructSerializer(fory, null));
      } else {
        registerInternal(UnknownEmptyStruct.class);
      }
    }
  }

  private void addDefaultSerializer(Class<?> type, Class<? extends Serializer> serializerClass) {
    addDefaultSerializer(type, Serializers.newSerializer(fory, type, serializerClass));
  }

  private void addDefaultSerializer(Class type, Serializer serializer) {
    registerInternalSerializer(type, serializer);
    registerInternal(type);
  }

  /** Register common class ahead to get smaller class id for serialization. */
  private void registerCommonUsedClasses() {
    registerInternal(LinkedList.class, TreeSet.class);
    registerInternal(LinkedHashMap.class, TreeMap.class);
    registerInternal(Date.class, Timestamp.class, LocalDateTime.class, Instant.class);
    registerInternal(BigInteger.class, BigDecimal.class);
    registerInternal(Optional.class, OptionalInt.class);
    registerInternal(Boolean[].class, Byte[].class, Short[].class, Character[].class);
    registerInternal(Integer[].class, Float[].class, Long[].class, Double[].class);
  }

  private void registerDefaultClasses() {
    registerInternal(Platform.HEAP_BYTE_BUFFER_CLASS);
    registerInternal(Platform.DIRECT_BYTE_BUFFER_CLASS);
    registerInternal(Comparator.naturalOrder().getClass());
    registerInternal(Comparator.reverseOrder().getClass());
    registerInternal(ConcurrentHashMap.class);
    registerInternal(ArrayBlockingQueue.class);
    registerInternal(LinkedBlockingQueue.class);
    registerInternal(AtomicBoolean.class);
    registerInternal(AtomicInteger.class);
    registerInternal(AtomicLong.class);
    registerInternal(AtomicReference.class);
    registerInternal(EnumSet.allOf(Language.class).getClass());
    registerInternal(EnumSet.of(Language.JAVA).getClass());
    registerInternal(SerializedLambda.class);
    registerInternal(
        Throwable.class,
        StackTraceElement.class,
        StackTraceElement[].class,
        Exception.class,
        RuntimeException.class);
    registerInternal(NullPointerException.class);
    registerInternal(IOException.class);
    registerInternal(IllegalArgumentException.class);
    registerInternal(IllegalStateException.class);
    registerInternal(IndexOutOfBoundsException.class, ArrayIndexOutOfBoundsException.class);
  }

  /**
   * Registers a class with an auto-assigned user ID.
   *
   * <p>The ID is automatically assigned starting from 0 in the user ID space. Each call assigns the
   * next available ID. If the class is already registered, this method does nothing.
   *
   * <p>Example:
   *
   * <pre>{@code
   * fory.register(MyClass.class);      // Gets user ID 0
   * fory.register(AnotherClass.class); // Gets user ID 1
   * }</pre>
   *
   * @param cls the class to register
   */
  @Override
  public void register(Class<?> cls) {
    if (!extRegistry.registeredClassIdMap.containsKey(cls)) {
      while (containsUserTypeId(extRegistry.userIdGenerator)) {
        extRegistry.userIdGenerator++;
      }
      register(cls, extRegistry.userIdGenerator);
    }
  }

  /**
   * Registers a class by its fully qualified name with an auto-assigned user ID.
   *
   * @param className the fully qualified class name
   * @see #register(Class)
   */
  @Override
  public void register(String className) {
    register(loadClass(className, false, 0, false));
  }

  /**
   * Registers a class by its fully qualified name with a specified user ID.
   *
   * @param className the fully qualified class name
   * @param classId the user ID to assign (0-based, in user ID space)
   * @see #register(Class, long)
   */
  @Override
  public void register(String className, long classId) {
    register(loadClass(className, false, 0, false), classId);
  }

  /**
   * Registers a class with a user-specified ID.
   *
   * <p>The ID is in the user ID space, starting from 0. The class will store the internal type tag
   * (STRUCT/ENUM/EXT/UNION) in {@code typeId} and the provided value in {@code userTypeId}.
   *
   * @param cls the class to register
   * @param id the user ID to assign (0-based, range [0, 0xfffffffe], 0xffffffff reserved)
   * @throws IllegalArgumentException if the ID is out of valid range or already in use
   */
  @Override
  public void register(Class<?> cls, long id) {
    registerUserImpl(cls, toUserTypeId(id));
  }

  /**
   * Register class with specified namespace and name. If a simpler namespace or type name is
   * registered, the serialized class will have smaller payload size. In many cases, it type name
   * has no conflict, namespace can be left as empty.
   */
  @Override
  public void register(Class<?> cls, String namespace, String name) {
    checkRegisterAllowed();
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
    checkRegistration(cls, -1, fullname, false);
    MetaStringBytes fullNameBytes =
        metaStringResolver.getOrCreateMetaStringBytes(
            GENERIC_ENCODER.encode(fullname, MetaString.Encoding.UTF_8));
    MetaStringBytes nsBytes =
        metaStringResolver.getOrCreateMetaStringBytes(encodePackage(namespace));
    MetaStringBytes nameBytes = metaStringResolver.getOrCreateMetaStringBytes(encodeTypeName(name));
    TypeInfo existingInfo = classInfoMap.get(cls);
    int typeId =
        buildUnregisteredTypeId(cls, existingInfo == null ? null : existingInfo.serializer);
    TypeInfo typeInfo =
        new TypeInfo(cls, fullNameBytes, nsBytes, nameBytes, false, null, typeId, -1);
    classInfoMap.put(cls, typeInfo);
    compositeNameBytes2TypeInfo.put(
        new TypeNameBytes(nsBytes.hashCode, nameBytes.hashCode), typeInfo);
    extRegistry.registeredClasses.put(fullname, cls);
    GraalvmSupport.registerClass(cls, fory.getConfig().getConfigHash());
  }

  @Override
  public void registerUnion(Class<?> cls, long userId, Serializer<?> serializer) {
    checkRegisterAllowed();
    int checkedUserId = toUserTypeId(userId);
    Preconditions.checkNotNull(serializer);
    checkRegistration(cls, checkedUserId, cls.getName(), false);
    extRegistry.registeredClassIdMap.put(cls, checkedUserId);
    int typeId = Types.TYPED_UNION;
    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo == null) {
      typeInfo = new TypeInfo(this, cls, serializer, typeId, checkedUserId);
    } else {
      typeInfo = typeInfo.copy(typeId, checkedUserId);
      typeInfo.setSerializer(this, serializer);
    }
    updateTypeInfo(cls, typeInfo);
    extRegistry.registeredClasses.put(cls.getName(), cls);
    GraalvmSupport.registerClass(cls, fory.getConfig().getConfigHash());
  }

  @Override
  public void registerUnion(Class<?> cls, String namespace, String name, Serializer<?> serializer) {
    checkRegisterAllowed();
    Preconditions.checkNotNull(serializer);
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
    checkRegistration(cls, -1, fullname, false);
    MetaStringBytes fullNameBytes =
        metaStringResolver.getOrCreateMetaStringBytes(
            GENERIC_ENCODER.encode(fullname, MetaString.Encoding.UTF_8));
    MetaStringBytes nsBytes =
        metaStringResolver.getOrCreateMetaStringBytes(encodePackage(namespace));
    MetaStringBytes nameBytes = metaStringResolver.getOrCreateMetaStringBytes(encodeTypeName(name));
    int typeId = Types.NAMED_UNION;
    TypeInfo typeInfo =
        new TypeInfo(cls, fullNameBytes, nsBytes, nameBytes, false, serializer, typeId, -1);
    typeInfo.setSerializer(this, serializer);
    classInfoMap.put(cls, typeInfo);
    compositeNameBytes2TypeInfo.put(
        new TypeNameBytes(nsBytes.hashCode, nameBytes.hashCode), typeInfo);
    extRegistry.registeredClasses.put(fullname, cls);
    GraalvmSupport.registerClass(cls, fory.getConfig().getConfigHash());
  }

  /**
   * Registers multiple classes for internal use with auto-assigned internal IDs.
   *
   * <p><b>Internal API</b>: This method is for Fory's internal use only. Users should use {@link
   * #register(Class)} instead.
   *
   * @param classes the classes to register
   */
  public void registerInternal(Class<?>... classes) {
    for (Class<?> cls : classes) {
      registerInternal(cls);
    }
  }

  /**
   * Registers a class for internal use with an auto-assigned internal ID.
   *
   * <p><b>Internal API</b>: This method is for Fory's internal use only. Users should use {@link
   * #register(Class)} instead. Internal IDs are in the range [0, 255].
   *
   * @param cls the class to register
   */
  public void registerInternal(Class<?> cls) {
    if (!extRegistry.registeredClassIdMap.containsKey(cls)) {
      Preconditions.checkArgument(
          extRegistry.classIdGenerator < INTERNAL_ID_LIMIT,
          "Internal type id overflow: %s",
          extRegistry.classIdGenerator);
      while (extRegistry.classIdGenerator < typeIdToTypeInfo.length
          && typeIdToTypeInfo[extRegistry.classIdGenerator] != null) {
        extRegistry.classIdGenerator++;
      }
      Preconditions.checkArgument(
          extRegistry.classIdGenerator < INTERNAL_ID_LIMIT,
          "Internal type id overflow: %s",
          extRegistry.classIdGenerator);
      registerInternal(cls, extRegistry.classIdGenerator);
    }
  }

  /**
   * Registers a class for internal use with a specified internal ID.
   *
   * <p><b>Internal API</b>: This method is for Fory's internal use only. Users should use {@link
   * #register(Class, long)} instead.
   *
   * <p>Internal IDs are reserved for Fory's built-in types and must be in the range [0, 255].
   *
   * @param cls the class to register
   * @param classId the internal ID, must be in range [0, 255]
   * @throws IllegalArgumentException if the ID is out of range or already in use
   */
  public void registerInternal(Class<?> cls, int classId) {
    Preconditions.checkArgument(classId >= 0 && classId < INTERNAL_ID_LIMIT);
    registerInternalImpl(cls, classId);
  }

  private void registerInternalImpl(Class<?> cls, int typeId) {
    checkRegisterAllowed();
    Preconditions.checkArgument(typeId >= 0 && typeId < INTERNAL_ID_LIMIT);
    checkRegistration(cls, typeId, cls.getName(), true);
    extRegistry.registeredClassIdMap.put(cls, typeId);
    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo != null) {
      typeInfo = typeInfo.copy(typeId, INVALID_USER_TYPE_ID);
    } else {
      typeInfo = new TypeInfo(this, cls, null, typeId, INVALID_USER_TYPE_ID);
    }
    updateTypeInfo(cls, typeInfo);
    extRegistry.registeredClasses.put(cls.getName(), cls);
    GraalvmSupport.registerClass(cls, fory.getConfig().getConfigHash());
  }

  private void registerUserImpl(Class<?> cls, int userId) {
    checkRegisterAllowed();
    Preconditions.checkArgument(userId != -1, "User type id 0xffffffff is reserved");
    checkRegistration(cls, userId, cls.getName(), false);
    extRegistry.registeredClassIdMap.put(cls, userId);
    int typeId = buildUserTypeId(cls, null);
    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo != null) {
      typeInfo = typeInfo.copy(typeId, userId);
    } else {
      typeInfo = new TypeInfo(this, cls, null, typeId, userId);
    }
    updateTypeInfo(cls, typeInfo);
    extRegistry.registeredClasses.put(cls.getName(), cls);
    GraalvmSupport.registerClass(cls, fory.getConfig().getConfigHash());
  }

  private int buildUserTypeId(Class<?> cls, Serializer<?> serializer) {
    if (cls.isEnum()) {
      return Types.ENUM;
    } else if (serializer != null && !isStructSerializer(serializer)) {
      return Types.EXT;
    } else {
      return metaContextShareEnabled && isStructEvolving(cls)
          ? Types.COMPATIBLE_STRUCT
          : Types.STRUCT;
    }
  }

  @Override
  protected int buildUnregisteredTypeId(Class<?> cls, Serializer<?> serializer) {
    if (serializer == null && !cls.isEnum() && useReplaceResolveSerializer(cls)) {
      return Types.NAMED_EXT;
    }
    return super.buildUnregisteredTypeId(cls, serializer);
  }

  private void checkRegistration(Class<?> cls, int classId, String name, boolean internal) {
    if (extRegistry.registeredClassIdMap.containsKey(cls)) {
      throw new IllegalArgumentException(
          String.format(
              "Class %s already registered with id %s.",
              cls, extRegistry.registeredClassIdMap.get(cls)));
    }
    if (classId != -1) {
      if (internal) {
        if (classId < typeIdToTypeInfo.length && typeIdToTypeInfo[classId] != null) {
          throw new IllegalArgumentException(
              String.format(
                  "Class %s with id %s has been registered, registering class %s with same id are not allowed.",
                  typeIdToTypeInfo[classId].getCls(), classId, cls.getName()));
        }
      } else {
        TypeInfo existingInfo = userTypeIdToTypeInfo.get(classId);
        if (existingInfo != null) {
          throw new IllegalArgumentException(
              String.format(
                  "Class %s with id %s has been registered, registering class %s with same id are not allowed.",
                  existingInfo.getCls(), classId, cls.getName()));
        }
      }
    }
    if (extRegistry.registeredClasses.containsKey(name)
        || extRegistry.registeredClasses.inverse().containsKey(cls)) {
      throw new IllegalArgumentException(
          String.format(
              "Class %s with name %s has been registered, registering class %s with same name are not allowed.",
              extRegistry.registeredClasses.get(name), name, cls));
    }
  }

  private boolean isInternalRegisteredClassId(Class<?> cls, int classId) {
    if (classId < 0 || classId >= typeIdToTypeInfo.length) {
      return false;
    }
    TypeInfo typeInfo = typeIdToTypeInfo[classId];
    return typeInfo != null && typeInfo.cls == cls;
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
    Preconditions.checkNotNull(name);
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

  public Integer getRegisteredClassId(Class<?> cls) {
    return extRegistry.registeredClassIdMap.get(cls);
  }

  public Class<?> getRegisteredClass(short id) {
    if (id < typeIdToTypeInfo.length) {
      TypeInfo typeInfo = typeIdToTypeInfo[id];
      if (typeInfo != null) {
        return typeInfo.cls;
      }
    }
    return null;
  }

  public Class<?> getRegisteredClass(String className) {
    return extRegistry.registeredClasses.get(className);
  }

  public Class<?> getRegisteredClassByTypeId(int typeId) {
    TypeInfo typeInfo = getRegisteredTypeInfoByTypeId(typeId, -1);
    return typeInfo == null ? null : typeInfo.cls;
  }

  public Class<?> getRegisteredClassByTypeId(int typeId, int userTypeId) {
    TypeInfo typeInfo = getRegisteredTypeInfoByTypeId(typeId, userTypeId);
    return typeInfo == null ? null : typeInfo.cls;
  }

  public TypeInfo getRegisteredTypeInfoByTypeId(int typeId, int userTypeId) {
    if (Types.isNamedType(typeId)) {
      return null;
    }
    if (isUserTypeRegisteredById(typeId)) {
      if (userTypeId == -1) {
        return null;
      }
      TypeInfo typeInfo = userTypeIdToTypeInfo.get(userTypeId);
      if (typeInfo == null) {
        return null;
      }
      if (typeInfo.typeId != typeId) {
        return null;
      }
      return typeInfo;
    }
    if (typeId < 0 || typeId >= typeIdToTypeInfo.length) {
      return null;
    }
    return typeIdToTypeInfo[typeId];
  }

  public List<Class<?>> getRegisteredClasses() {
    List<Class<?>> classes = new ArrayList<>(extRegistry.registeredClassIdMap.size);
    extRegistry.registeredClassIdMap.forEach((cls, id) -> classes.add(cls));
    return classes;
  }

  public String getTypeAlias(Class<?> cls) {
    Integer id = extRegistry.registeredClassIdMap.get(cls);
    if (id != null) {
      return Integer.toUnsignedString(id);
    }
    String name = extRegistry.registeredClasses.inverse().get(cls);
    if (name != null) {
      return name;
    }
    return cls.getName();
  }

  /**
   * Compute the typeId used in TypeDef without forcing serializer creation. This avoids recursive
   * serializer construction while building class metadata.
   */
  public int getTypeIdForTypeDef(Class<?> cls) {
    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo != null) {
      return typeInfo.typeId;
    }
    Integer classId = extRegistry.registeredClassIdMap.get(cls);
    if (classId != null) {
      typeInfo = classInfoMap.get(cls);
      if (typeInfo == null) {
        typeInfo = getTypeInfo(cls);
      }
      return typeInfo.typeId;
    }
    int typeId = buildUnregisteredTypeId(cls, null);
    typeInfo = new TypeInfo(this, cls, null, typeId, INVALID_USER_TYPE_ID);
    classInfoMap.put(cls, typeInfo);
    if (typeInfo.namespaceBytes != null && typeInfo.typeNameBytes != null) {
      TypeNameBytes typeNameBytes =
          new TypeNameBytes(typeInfo.namespaceBytes.hashCode, typeInfo.typeNameBytes.hashCode);
      compositeNameBytes2TypeInfo.put(typeNameBytes, typeInfo);
    }
    return typeId;
  }

  /**
   * Compute the user type id used in TypeDef without forcing serializer creation. Returns -1 when
   * the class isn't registered by numeric id.
   */
  public int getUserTypeIdForTypeDef(Class<?> cls) {
    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo != null) {
      return typeInfo.userTypeId;
    }
    Integer classId = extRegistry.registeredClassIdMap.get(cls);
    if (classId != null && !isInternalRegisteredClassId(cls, classId)) {
      return classId;
    }
    return -1;
  }

  @Override
  public boolean isMonomorphic(Descriptor descriptor) {
    ForyField foryField = descriptor.getForyField();
    if (foryField != null) {
      switch (foryField.dynamic()) {
        case TRUE:
          return false;
        case FALSE:
          return true;
        default:
          return isMonomorphic(descriptor.getRawType());
      }
    }
    return isMonomorphic(descriptor.getRawType());
  }

  /**
   * Mark non-inner registered final types as non-final to write class def for those types. Note if
   * a class is registered but not an inner class with inner serializer, it will still be taken as
   * non-final to write class def, so that it can be deserialized by the peer still.
   */
  @Override
  public boolean isMonomorphic(Class<?> clz) {
    if (TypeUtils.isPrimitiveListClass(clz)) {
      return true;
    }
    if (fory.getConfig().isMetaShareEnabled()) {
      // can't create final map/collection type using TypeUtils.mapOf(TypeToken<K>,
      // TypeToken<V>)
      if (!ReflectionUtils.isMonomorphic(clz)) {
        return false;
      }
      if (clz.isArray()) {
        Class<?> component = TypeUtils.getArrayComponent(clz);
        return isMonomorphic(component);
      }
      // Union types (Union2~6) are final classes, treat them as monomorphic
      // so they don't need to read/write type info
      if (Union.class.isAssignableFrom(clz)) {
        return true;
      }
      // if internal registered and final, then taken as morphic
      return (isInternalRegistered(clz) || clz.isEnum());
    }
    return ReflectionUtils.isMonomorphic(clz);
  }

  public boolean isBuildIn(Descriptor descriptor) {
    if (TypeUtils.isPrimitiveListClass(descriptor.getRawType())) {
      return true;
    }
    return isMonomorphic(descriptor);
  }

  public boolean isInternalRegistered(int classId) {
    if (Types.isUserDefinedType((byte) classId)) {
      return false;
    }
    return classId > 0 && classId < typeIdToTypeInfo.length && typeIdToTypeInfo[classId] != null;
  }

  /** Returns true if <code>cls</code> is fory inner registered class. */
  public boolean isInternalRegistered(Class<?> cls) {
    TypeInfo typeInfo = classInfoMap.get(cls);
    return typeInfo != null && isInternalRegistered(typeInfo.typeId);
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

  @Override
  public void registerSerializer(Class<?> type, Serializer<?> serializer) {
    checkRegisterAllowed();
    if (!serializer.getClass().getPackage().getName().startsWith("org.apache.fory")) {
      SerializationUtils.validate(type, serializer.getClass());
    }
    registerSerializerImpl(type, serializer);
  }

  /**
   * If a serializer exists before, it will be replaced by new serializer.
   *
   * @param type class needed to be serialized/deserialized
   * @param serializer serializer for object of {@code type}
   */
  @Override
  public void registerInternalSerializer(Class<?> type, Serializer<?> serializer) {
    Integer classId = extRegistry.registeredClassIdMap.get(type);
    if (classId != null && !isInternalRegisteredClassId(type, classId)) {
      throw new IllegalArgumentException(
          String.format(
              "Class %s is not registered with an internal id (< %d).", type, INTERNAL_ID_LIMIT));
    }
    if (classId != null) {
      Preconditions.checkArgument(
          classId >= 0 && classId < INTERNAL_ID_LIMIT, "Internal type id overflow: %s", classId);
    }
    if (classId == null) {
      registerInternal(type);
    }
    registerSerializerImpl(type, serializer);
  }

  private void registerSerializerImpl(Class<?> type, Serializer<?> serializer) {
    checkRegisterAllowed();
    if (!serializer.getClass().getPackage().getName().startsWith("org.apache.fory")) {
      SerializationUtils.validate(type, serializer.getClass());
    }
    addSerializer(type, serializer);
    TypeInfo typeInfo = classInfoMap.get(type);
    classInfoMap.put(type, typeInfo);
    extRegistry.registeredTypeInfos.add(typeInfo);
    // in order to support customized serializer for abstract or interface.
    if (!type.isPrimitive() && (ReflectionUtils.isAbstract(type) || type.isInterface())) {
      extRegistry.absTypeInfo.put(type, typeInfo);
      extRegistry.registeredTypeInfos.add(typeInfo);
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
  @Override
  public <T> void setSerializer(Class<T> cls, Serializer<T> serializer) {
    addSerializer(cls, serializer);
  }

  /** Set serializer for class whose name is {@code className}. */
  public void setSerializer(String className, Class<? extends Serializer> serializer) {
    for (Map.Entry<Class<?>, TypeInfo> entry : classInfoMap.iterable()) {
      if (extRegistry.registeredClasses.containsKey(className)) {
        LOG.warn("Skip clear serializer for registered class {}", className);
        return;
      }
      Class<?> cls = entry.getKey();
      if (cls.getName().equals(className)) {
        LOG.info("Clear serializer for class {}.", className);
        entry.getValue().setSerializer(this, Serializers.newSerializer(fory, cls, serializer));
        typeInfoCache = NIL_TYPE_INFO;
        return;
      }
    }
  }

  /** Set serializer for classes starts with {@code classNamePrefix}. */
  public void setSerializers(String classNamePrefix, Class<? extends Serializer> serializer) {
    for (Map.Entry<Class<?>, TypeInfo> entry : classInfoMap.iterable()) {
      Class<?> cls = entry.getKey();
      String className = cls.getName();
      if (extRegistry.registeredClasses.containsKey(className)) {
        continue;
      }
      if (className.startsWith(classNamePrefix)) {
        LOG.info("Clear serializer for class {}.", className);
        entry.getValue().setSerializer(this, Serializers.newSerializer(fory, cls, serializer));
        typeInfoCache = NIL_TYPE_INFO;
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
   * #readSharedClassMeta} and {@link #getSerializer(Class)} which access current creating
   * serializer. This method is used to avoid overwriting existing serializer for class when
   * creating a data serializer for serialization of parts fields of a class.
   */
  @Override
  public <T> void setSerializerIfAbsent(Class<T> cls, Serializer<T> serializer) {
    Serializer<T> s = getSerializer(cls, false);
    if (s == null) {
      setSerializer(cls, serializer);
    }
  }

  /** Clear serializer associated with <code>cls</code> if not null. */
  public void clearSerializer(Class<?> cls) {
    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo != null) {
      typeInfo.setSerializer(this, null);
    }
  }

  /** Add serializer for specified class. */
  public void addSerializer(Class<?> type, Serializer<?> serializer) {
    Preconditions.checkNotNull(serializer);
    TypeInfo typeInfo;
    Integer classId = extRegistry.registeredClassIdMap.get(type);
    boolean registered = classId != null;
    if (registered) {
      int id = classId;
      boolean internal = isInternalRegisteredClassId(type, id);
      int typeId = internal ? id : buildUserTypeId(type, serializer);
      typeInfo = classInfoMap.get(type);
      if (typeInfo == null) {
        typeInfo = new TypeInfo(this, type, null, typeId, internal ? INVALID_USER_TYPE_ID : id);
      } else {
        typeInfo = typeInfo.copy(typeId);
      }
      updateTypeInfo(type, typeInfo);
    } else {
      int typeId = buildUnregisteredTypeId(type, serializer);
      typeInfo = classInfoMap.get(type);
      if (typeInfo == null) {
        typeInfo = new TypeInfo(this, type, null, typeId, INVALID_USER_TYPE_ID);
      } else {
        typeInfo = typeInfo.copy(typeId);
      }
      updateTypeInfo(type, typeInfo);
      // Add to compositeNameBytes2TypeInfo for unregistered classes so that
      // readTypeInfo can find the TypeInfo by name bytes during deserialization.
      // This is important for dynamically created classes that can't be loaded by name.
      if (typeInfo.namespaceBytes != null && typeInfo.typeNameBytes != null) {
        TypeNameBytes typeNameBytes =
            new TypeNameBytes(typeInfo.namespaceBytes.hashCode, typeInfo.typeNameBytes.hashCode);
        compositeNameBytes2TypeInfo.put(typeNameBytes, typeInfo);
      }
    }

    // 2. Set `Serializer` for `TypeInfo`.
    typeInfo.setSerializer(this, serializer);
  }

  @SuppressWarnings("unchecked")
  public <T> Serializer<T> getSerializer(Class<T> cls, boolean createIfNotExist) {
    Preconditions.checkNotNull(cls);
    if (createIfNotExist) {
      return getSerializer(cls);
    }
    TypeInfo typeInfo = classInfoMap.get(cls);
    return typeInfo == null ? null : (Serializer<T>) typeInfo.serializer;
  }

  /** Get or create serializer for <code>cls</code>. */
  @Override
  @SuppressWarnings("unchecked")
  public <T> Serializer<T> getSerializer(Class<T> cls) {
    Preconditions.checkNotNull(cls);
    return (Serializer<T>) getOrUpdateTypeInfo(cls).serializer;
  }

  /**
   * Return serializer without generics for specified class. The cast of Serializer to subclass
   * serializer with generic is easy to raise compiler error for javac, so just use raw type.
   */
  @Internal
  @CodegenInvoke
  @Override
  public Serializer<?> getRawSerializer(Class<?> cls) {
    Preconditions.checkNotNull(cls);
    return getOrUpdateTypeInfo(cls).serializer;
  }

  @Override
  public Class<? extends Serializer> getSerializerClass(Class<?> cls) {
    boolean codegen =
        fory.getConfig().isCodeGenEnabled() && supportCodegenForJavaSerialization(cls);
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
    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo != null && typeInfo.serializer != null) {
      // Note: need to check `classInfo.serializer != null`, because sometimes `cls` is already
      // serialized, which will create a class info with serializer null, see `#writeClassInternal`
      return typeInfo.serializer.getClass();
    } else {
      if (getSerializerFactory() != null) {
        Serializer serializer = getSerializerFactory().createSerializer(fory, cls);
        if (serializer != null) {
          return serializer.getClass();
        }
      }
      if (UnknownClass.isUnknowClass(cls)) {
        return UnknownClassSerializers.getSerializer(fory, "Unknown", cls).getClass();
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
              if (typeInfoCache.cls == clz) {
                typeInfoCache = NIL_TYPE_INFO; // clear class info cache
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

  public Class<? extends Serializer> getObjectSerializerClass(
      Class<?> cls, JITContext.SerializerJITCallback<Class<? extends Serializer>> callback) {
    boolean codegen =
        fory.getConfig().isCodeGenEnabled() && supportCodegenForJavaSerialization(cls);
    return getObjectSerializerClass(cls, false, codegen, callback);
  }

  public Class<? extends Serializer> getObjectSerializerClass(
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
              // Always use ObjectSerializer for compatible mode.
              // Class definition will be sent to peer to create serializer for deserialization.
              sc =
                  fory.getJITContext()
                      .registerSerializerJITCallback(
                          () -> ObjectSerializer.class,
                          () -> loadCodegenSerializer(fory, cls),
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
      // Always use ObjectSerializer for both modes
      return ObjectSerializer.class;
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

  @Deprecated
  public void setClassChecker(ClassChecker classChecker) {
    extRegistry.typeChecker =
        (resolver, className) -> {
          if (resolver instanceof ClassResolver) {
            return classChecker.checkClass((ClassResolver) resolver, className);
          }
          return false;
        };
    if (classChecker instanceof AllowListChecker) {
      ((AllowListChecker) classChecker).addListener(this);
    }
  }

  // Invoked by fory JIT.
  @Override
  public TypeInfo getTypeInfo(Class<?> cls) {
    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo == null || typeInfo.serializer == null) {
      addSerializer(cls, createSerializer(cls));
      typeInfo = classInfoMap.get(cls);
    }
    return typeInfo;
  }

  public TypeInfo getTypeInfo(short classId) {
    TypeInfo typeInfo = typeIdToTypeInfo[classId];
    assert typeInfo != null : classId;
    if (typeInfo.serializer == null) {
      addSerializer(typeInfo.cls, createSerializer(typeInfo.cls));
      typeInfo = classInfoMap.get(typeInfo.cls);
    }
    return typeInfo;
  }

  /** Get classinfo by cache, update cache if miss. */
  @Override
  public TypeInfo getTypeInfo(Class<?> cls, TypeInfoHolder classInfoHolder) {
    TypeInfo typeInfo = classInfoHolder.typeInfo;
    if (typeInfo.getCls() != cls) {
      typeInfo = classInfoMap.get(cls);
      if (typeInfo == null || typeInfo.serializer == null) {
        addSerializer(cls, createSerializer(cls));
        typeInfo = Objects.requireNonNull(classInfoMap.get(cls));
      }
      classInfoHolder.typeInfo = typeInfo;
    }
    assert typeInfo.serializer != null;
    return typeInfo;
  }

  /**
   * Get class information, create class info if not found and `createTypeInfoIfNotFound` is true.
   *
   * @param cls which class to get class info.
   * @param createTypeInfoIfNotFound whether create class info if not found.
   * @return Class info.
   */
  @Override
  public TypeInfo getTypeInfo(Class<?> cls, boolean createTypeInfoIfNotFound) {
    if (createTypeInfoIfNotFound) {
      return getOrUpdateTypeInfo(cls);
    }
    if (extRegistry.getClassCtx.contains(cls)) {
      return null;
    } else {
      return classInfoMap.get(cls);
    }
  }

  @Internal
  public TypeInfo getOrUpdateTypeInfo(Class<?> cls) {
    TypeInfo typeInfo = typeInfoCache;
    if (typeInfo.cls != cls) {
      typeInfo = classInfoMap.get(cls);
      if (typeInfo == null || typeInfo.serializer == null) {
        addSerializer(cls, createSerializer(cls));
        typeInfo = classInfoMap.get(cls);
      }
      typeInfoCache = typeInfo;
    }
    return typeInfo;
  }

  private TypeInfo getOrUpdateTypeInfo(short classId) {
    TypeInfo typeInfo = typeInfoCache;
    TypeInfo internalInfo = classId < typeIdToTypeInfo.length ? typeIdToTypeInfo[classId] : null;
    Preconditions.checkArgument(
        internalInfo != null, "Internal class id %s is not registered", classId);
    if (typeInfo != internalInfo) {
      typeInfo = internalInfo;
      if (typeInfo.serializer == null) {
        addSerializer(typeInfo.cls, createSerializer(typeInfo.cls));
        typeInfo = classInfoMap.get(typeInfo.cls);
      }
      typeInfoCache = typeInfo;
    }
    return typeInfo;
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
          && !shimDispatcher.contains(cls)
          && !extRegistry.isTypeCheckerSet()) {
        LOG.warn(generateSecurityMsg(cls));
      }
    }

    // For enum value classes (anonymous inner classes of abstract enums),
    // reuse the serializer from the declaring enum class
    if (!cls.isEnum() && Enum.class.isAssignableFrom(cls) && cls != Enum.class) {
      Class<?> enclosingClass = cls.getEnclosingClass();
      if (enclosingClass != null && enclosingClass.isEnum()) {
        return getSerializer(enclosingClass);
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
    if (!extRegistry.absTypeInfo.isEmpty()) {
      Class<?> tmpCls = cls;
      while (tmpCls != null && tmpCls != Object.class) {
        TypeInfo absClass;
        if ((absClass = extRegistry.absTypeInfo.get(tmpCls.getSuperclass())) != null) {
          return absClass.serializer;
        }
        for (Class<?> tmpI : tmpCls.getInterfaces()) {
          if ((absClass = extRegistry.absTypeInfo.get(tmpI)) != null) {
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

  private void createSerializer0(Class<?> cls) {
    TypeInfo typeInfo = getTypeInfo(cls);
    TypeInfo deserializationTypeInfo;
    if (metaContextShareEnabled && needToWriteTypeDef(typeInfo.serializer)) {
      TypeDef typeDef = typeInfo.typeDef;
      if (typeDef == null) {
        typeDef = buildTypeDef(typeInfo);
      }
      deserializationTypeInfo = buildMetaSharedTypeInfo(Tuple2.of(typeDef, null), typeDef);
      if (deserializationTypeInfo != null && GraalvmSupport.isGraalBuildtime()) {
        getGraalvmClassRegistry()
            .deserializerClassMap
            .put(typeDef.getId(), getGraalvmSerializerClass(deserializationTypeInfo.serializer));
        Tuple2<TypeDef, TypeInfo> typeDefTuple = extRegistry.classIdToDef.get(typeDef.getId());
        typeInfoCache = NIL_TYPE_INFO;
        extRegistry.classIdToDef.put(typeDef.getId(), Tuple2.of(typeDefTuple.f0, null));
      }
    }
    if (GraalvmSupport.isGraalBuildtime()) {
      // Instance for generated class should be hold at graalvm runtime only.
      getGraalvmClassRegistry()
          .serializerClassMap
          .put(cls, getGraalvmSerializerClass(typeInfo.serializer));
      typeInfoCache = NIL_TYPE_INFO;
      if (RecordUtils.isRecord(cls)) {
        RecordUtils.getRecordConstructor(cls);
        RecordUtils.getRecordComponents(cls);
      }
      ObjectCreators.getObjectCreator(cls);
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
    // For enum value classes (anonymous inner classes of abstract enums),
    // check if the declaring enum class is secure
    if (!cls.isEnum() && Enum.class.isAssignableFrom(cls) && cls != Enum.class) {
      Class<?> enclosingClass = cls.getEnclosingClass();
      if (enclosingClass != null && enclosingClass.isEnum()) {
        return isSecure(enclosingClass);
      }
    }
    if (fory.getConfig().requireClassRegistration()) {
      return Functions.isLambda(cls)
          || ReflectionUtils.isJdkProxy(cls)
          || extRegistry.registeredClassIdMap.containsKey(cls)
          || shimDispatcher.contains(cls);
    } else {
      return extRegistry.typeChecker.checkType(this, cls.getName());
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
   * aligned data to reduce cpu instruction overhead. `writeTypeInfo` is the last step before
   * serializing object, if this writes are aligned, then later serialization will be more
   * efficient.
   */
  public void writeClassAndUpdateCache(MemoryBuffer buffer, Class<?> cls) {
    // fast path for common type
    if (cls == Integer.class) {
      buffer.writeVarUint32Small7(Types.INT32);
    } else if (cls == Long.class) {
      buffer.writeVarUint32Small7(Types.INT64);
    } else {
      writeTypeInfo(buffer, getOrUpdateTypeInfo(cls));
    }
  }

  // The jit-compiled native code for this method will be too big for inline, so we generated
  // `getTypeInfo`
  // in fory-jit, see `BaseSeqCodecBuilder#writeAndGetTypeInfo`
  // public TypeInfo writeTypeInfo(MemoryBuffer buffer, Class<?> cls, TypeInfoHolder
  // classInfoHolder)
  // {
  //   TypeInfo classInfo = getTypeInfo(cls, classInfoHolder);
  //   writeTypeInfo(buffer, classInfo);
  //   return classInfo;
  // }

  @Override
  protected TypeDef buildTypeDef(TypeInfo typeInfo) {
    TypeDef typeDef;
    Serializer<?> serializer = typeInfo.serializer;
    Preconditions.checkArgument(
        serializer.getClass() != UnknownClassSerializers.UnknownStructSerializer.class);
    if (needToWriteTypeDef(serializer)) {
      typeDef = typeDefMap.computeIfAbsent(typeInfo.cls, cls -> TypeDef.buildTypeDef(fory, cls));
    } else {
      // Some type will use other serializers such MapSerializer and so on.
      typeDef =
          typeDefMap.computeIfAbsent(
              typeInfo.cls, cls -> TypeDef.buildTypeDef(this, cls, new ArrayList<>(), false));
    }
    typeInfo.typeDef = typeDef;
    return typeDef;
  }

  /**
   * Write classname for java serialization. Note that the object of provided class can be
   * non-serializable, and class with writeReplace/readResolve defined won't be skipped. For
   * serializable object, {@link #writeTypeInfo(MemoryBuffer, TypeInfo)} should be invoked.
   */
  public void writeClassInternal(MemoryBuffer buffer, Class<?> cls) {
    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo == null) {
      typeInfo = buildClassInfo(cls);
    }
    writeClassInternal(buffer, typeInfo);
  }

  public void writeClassInternal(MemoryBuffer buffer, TypeInfo typeInfo) {
    int typeId = typeInfo.typeId;
    boolean writeById = typeId != REPLACE_STUB_ID && !Types.isNamedType(typeId);
    if (writeById) {
      buffer.writeVarUint32Small7(typeId << 1);
      switch (typeId) {
        case Types.ENUM:
        case Types.STRUCT:
        case Types.COMPATIBLE_STRUCT:
        case Types.EXT:
        case Types.TYPED_UNION:
          buffer.writeVarUint32(typeInfo.userTypeId);
          break;
        default:
          break;
      }
    } else {
      // let the lowermost bit of next byte be set, so the deserialization can know
      // whether need to read class by name in advance
      metaStringResolver.writeMetaStringBytesWithFlag(buffer, typeInfo.namespaceBytes);
      metaStringResolver.writeMetaStringBytes(buffer, typeInfo.typeNameBytes);
    }
  }

  private TypeInfo buildClassInfo(Class<?> cls) {
    TypeInfo typeInfo;
    Integer classId = extRegistry.registeredClassIdMap.get(cls);
    // Don't create serializer in case the object for class is non-serializable,
    // Or class is abstract or interface.
    int typeId;
    if (classId == null) {
      typeId = buildUnregisteredTypeId(cls, null);
    } else {
      boolean internal = isInternalRegisteredClassId(cls, classId);
      typeId = internal ? classId : buildUserTypeId(cls, null);
    }
    typeInfo = new TypeInfo(this, cls, null, typeId, INVALID_USER_TYPE_ID);
    classInfoMap.put(cls, typeInfo);
    return typeInfo;
  }

  /**
   * Read serialized java classname. Note that the object of the class can be non-serializable. For
   * serializable object, {@link #readTypeInfo(MemoryBuffer)} or {@link #readTypeInfo(MemoryBuffer,
   * TypeInfoHolder)} should be invoked.
   */
  public Class<?> readClassInternal(MemoryBuffer buffer) {
    int header = buffer.readVarUint32Small14();
    if ((header & 0b1) != 0) {
      // let the lowermost bit of next byte be set, so the deserialization can know
      // whether need to read class by name in advance
      MetaStringBytes packageBytes = metaStringResolver.readMetaStringBytesWithFlag(buffer, header);
      MetaStringBytes simpleClassNameBytes = metaStringResolver.readMetaStringBytes(buffer);
      return loadBytesToTypeInfo(packageBytes, simpleClassNameBytes).cls;
    }
    int typeId = header >>> 1;
    switch (typeId) {
      case Types.ENUM:
      case Types.STRUCT:
      case Types.COMPATIBLE_STRUCT:
      case Types.EXT:
      case Types.TYPED_UNION:
        return getTypeInfoByTypeIdForReadClassInternal(typeId, buffer.readVarUint32()).cls;
      default:
        TypeInfo internalTypeInfoByTypeId = getInternalTypeInfoByTypeId(typeId);
        Preconditions.checkNotNull(internalTypeInfoByTypeId);
        return internalTypeInfoByTypeId.cls;
    }
  }

  private TypeInfo getTypeInfoByTypeIdForReadClassInternal(int typeId, int userTypeId) {
    TypeInfo typeInfo;
    if (userTypeId != INVALID_USER_TYPE_ID) {
      typeInfo = userTypeIdToTypeInfo.get(userTypeId);
    } else {
      typeInfo = getInternalTypeInfoByTypeId(typeId);
    }
    Preconditions.checkArgument(typeInfo != null, "Type id %s not registered", typeId);
    return typeInfo;
  }

  @Override
  protected TypeInfo loadBytesToTypeInfo(
      MetaStringBytes packageBytes, MetaStringBytes simpleClassNameBytes) {
    TypeNameBytes typeNameBytes =
        new TypeNameBytes(packageBytes.hashCode, simpleClassNameBytes.hashCode);
    TypeInfo typeInfo = compositeNameBytes2TypeInfo.get(typeNameBytes);
    if (typeInfo == null) {
      typeInfo = populateBytesToTypeInfo(typeNameBytes, packageBytes, simpleClassNameBytes);
    }
    // Note: Don't create serializer here - this method is used by both readTypeInfo
    // (which needs serializer) and readClassInternal (which doesn't need serializer).
    // Serializer creation is handled by ensureSerializerForTypeInfo in TypeResolver.
    return typeInfo;
  }

  @Override
  protected TypeInfo ensureSerializerForTypeInfo(TypeInfo typeInfo) {
    if (typeInfo.serializer == null) {
      Class<?> cls = typeInfo.cls;
      if (cls != null && (ReflectionUtils.isAbstract(cls) || cls.isInterface())) {
        return typeInfo;
      }
      // Get or create TypeInfo with serializer
      TypeInfo newTypeInfo = getTypeInfo(typeInfo.cls);
      // Update the cache with the correct TypeInfo that has a serializer
      if (typeInfo.typeNameBytes != null) {
        TypeNameBytes typeNameBytes =
            new TypeNameBytes(typeInfo.namespaceBytes.hashCode, typeInfo.typeNameBytes.hashCode);
        compositeNameBytes2TypeInfo.put(typeNameBytes, newTypeInfo);
      }
      return newTypeInfo;
    }
    return typeInfo;
  }

  private TypeInfo populateBytesToTypeInfo(
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
    int typeId = buildUnregisteredTypeId(cls, null);
    TypeInfo typeInfo =
        new TypeInfo(
            cls,
            fullClassNameBytes,
            packageBytes,
            simpleClassNameBytes,
            false,
            null,
            typeId,
            INVALID_USER_TYPE_ID);
    if (UnknownClass.class.isAssignableFrom(TypeUtils.getComponentIfArray(cls))) {
      typeInfo.serializer =
          UnknownClassSerializers.getSerializer(fory, classSpec.entireClassName, cls);
    } else {
      // don't create serializer here, if the class is an interface,
      // there won't be serializer since interface has no instance.
      if (!classInfoMap.containsKey(cls)) {
        classInfoMap.put(cls, typeInfo);
      }
    }
    compositeNameBytes2TypeInfo.put(typeNameBytes, typeInfo);
    return typeInfo;
  }

  public Class<?> loadClassForMeta(String className, boolean isEnum, int arrayDims) {
    String pkg = ReflectionUtils.getPackage(className);
    String typeName = ReflectionUtils.getClassNameWithoutPackage(className);
    MetaStringBytes pkgBytes = metaStringResolver.getOrCreateMetaStringBytes(encodePackage(pkg));
    MetaStringBytes typeBytes =
        metaStringResolver.getOrCreateMetaStringBytes(encodeTypeName(typeName));
    TypeInfo cachedInfo =
        compositeNameBytes2TypeInfo.get(new TypeNameBytes(pkgBytes.hashCode, typeBytes.hashCode));
    if (cachedInfo != null) {
      return cachedInfo.cls;
    }
    return loadClass(className, isEnum, arrayDims, fory.getConfig().deserializeUnknownClass());
  }

  public Class<?> getCurrentReadClass() {
    return currentReadClass;
  }

  public void reset() {
    resetRead();
    resetWrite();
  }

  public void resetRead() {}

  public void resetWrite() {}

  // buildGenericType, nilTypeInfo, nilTypeInfoHolder are inherited from TypeResolver

  public GenericType getObjectGenericType() {
    return extRegistry.objectGenericType;
  }

  public TypeInfo newTypeInfo(Class<?> cls, Serializer<?> serializer, int typeId) {
    return new TypeInfo(this, cls, serializer, typeId, getUserTypeIdForTypeDef(cls));
  }

  public boolean isPrimitive(int classId) {
    return classId >= PRIMITIVE_VOID_ID && classId <= PRIMITIVE_FLOAT64_ID;
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

  /**
   * Normalize type name for consistent ordering between serialization and deserialization.
   * Collection subtypes (List, Set, etc.) are normalized to "java.util.Collection". Map subtypes
   * are normalized to "java.util.Map". This ensures fields have the same order regardless of
   * whether the peer has the field locally.
   */
  private String getNormalizedTypeName(Descriptor d) {
    Class<?> rawType = d.getRawType();
    if (rawType != null) {
      if (isCollection(rawType)) {
        return "java.util.Collection";
      }
      if (isMap(rawType)) {
        return "java.util.Map";
      }
    }
    return d.getTypeName();
  }

  /**
   * Creates a comparator for sorting descriptors by normalized type name and field name/id. This
   * comparator normalizes Collection/Map types to ensure consistent field ordering between
   * serialization and deserialization, even when peers have different Collection/Map subtypes.
   */
  public Comparator<Descriptor> createTypeAndNameComparator() {
    return (d1, d2) -> {
      // sort by type so that we can hit class info cache more possibly.
      // sort by field id/name to fix order if type is same.
      // Use normalized type name so that Collection/Map subtypes have consistent order
      // between processes even if the field doesn't exist in peer (e.g., List vs Collection).
      int c = getNormalizedTypeName(d1).compareTo(getNormalizedTypeName(d2));
      // noinspection Duplicates
      if (c == 0) {
        c = getFieldSortKey(d1).compareTo(getFieldSortKey(d2));
        if (c == 0) {
          // Field name duplicate in super/child classes.
          c = d1.getDeclaringClass().compareTo(d2.getDeclaringClass());
          if (c == 0) {
            // Final tie-breaker: use actual field name to distinguish fields with same tag ID.
            // This ensures TreeSet never treats different fields as duplicates.
            c = d1.getName().compareTo(d2.getName());
          }
        }
      }
      return c;
    };
  }

  @Override
  public DescriptorGrouper createDescriptorGrouper(
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator) {
    return DescriptorGrouper.createDescriptorGrouper(
            this::isBuildIn,
            descriptors,
            descriptorsGroupedOrdered,
            descriptorUpdator,
            getPrimitiveComparator(),
            createTypeAndNameComparator())
        .sort();
  }

  /**
   * Ensure all compilation for serializers and accessors even for lazy initialized serializers.
   * This method will block until all compilation is done.
   *
   * <p>Note that this method should be invoked after all registrations and invoked only once.
   * Repeated invocations will have no effect.
   */
  @Override
  public void ensureSerializersCompiled() {
    if (extRegistry.ensureSerializersCompiled) {
      return;
    }
    extRegistry.ensureSerializersCompiled = true;
    try {
      fory.getJITContext().lock();
      // Lambda and JdkProxy serializers use java.lang.Class which is not supported in xlang mode
      if (!fory.isCrossLanguage()) {
        Serializers.newSerializer(fory, LambdaSerializer.STUB_LAMBDA_CLASS, LambdaSerializer.class);
        Serializers.newSerializer(
            fory, JdkProxySerializer.SUBT_PROXY.getClass(), JdkProxySerializer.class);
      }
      classInfoMap.forEach(
          (cls, classInfo) -> {
            GraalvmSupport.registerClass(cls, fory.getConfig().getConfigHash());
            if (classInfo.serializer == null) {
              if (isSerializable(classInfo.cls)) {
                createSerializer0(cls);
              }
              if (cls.isArray()) {
                // Also create serializer for the component type if it's serializable
                Class<?> componentType = TypeUtils.getArrayComponent(cls);
                if (isSerializable(componentType)) {
                  createSerializer0(componentType);
                }
              }
            }
            // Always ensure array class serializers and their component type serializers
            // are registered in GraalVM registry, since ObjectArraySerializer needs
            // the component type serializer at construction time
            if (cls.isArray() && GraalvmSupport.isGraalBuildtime()) {
              // First ensure component type serializer is registered if it's serializable
              Class<?> componentType = TypeUtils.getArrayComponent(cls);
              if (isSerializable(componentType)) {
                createSerializer0(componentType);
              }
              // Then register the array serializer
              createSerializer0(cls);
            }
            // For abstract enums, also create and store serializers for enum value classes
            // so they are available at GraalVM runtime
            if (cls.isEnum() && GraalvmSupport.isGraalBuildtime()) {
              for (Object enumConstant : cls.getEnumConstants()) {
                Class<?> enumValueClass = enumConstant.getClass();
                if (enumValueClass != cls) {
                  // Get serializer for the enum value class (will reuse the enum's serializer)
                  getSerializer(enumValueClass);
                }
              }
            }
          });
      if (GraalvmSupport.isGraalBuildtime()) {
        typeInfoCache = NIL_TYPE_INFO;
        classInfoMap.forEach(
            (cls, classInfo) -> {
              if (classInfo.serializer != null
                  && !extRegistry.registeredTypeInfos.contains(classInfo)) {
                classInfo.serializer = null;
              }
            });
      }
    } finally {
      fory.getJITContext().unlock();
    }
  }
}
