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
import static org.apache.fory.serializer.CodegenSerializer.supportCodegenForJavaSerialization;
import static org.apache.fory.type.TypeUtils.OBJECT_TYPE;
import static org.apache.fory.type.TypeUtils.getRawType;

import java.io.Externalizable;
import java.io.IOException;
import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
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
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.ForyCopyable;
import org.apache.fory.annotation.CodegenInvoke;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.Internal;
import org.apache.fory.builder.JITContext;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.codegen.Expression.Literal;
import org.apache.fory.collection.IdentityObjectIntMap;
import org.apache.fory.collection.ObjectMap;
import org.apache.fory.collection.Tuple2;
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
import org.apache.fory.reflect.ObjectCreators;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.ArraySerializers;
import org.apache.fory.serializer.BufferSerializers;
import org.apache.fory.serializer.CodegenSerializer.LazyInitBeanSerializer;
import org.apache.fory.serializer.EnumSerializer;
import org.apache.fory.serializer.ExternalizableSerializer;
import org.apache.fory.serializer.FinalFieldReplaceResolveSerializer;
import org.apache.fory.serializer.ForyCopyableSerializer;
import org.apache.fory.serializer.JavaSerializer;
import org.apache.fory.serializer.JdkProxySerializer;
import org.apache.fory.serializer.LambdaSerializer;
import org.apache.fory.serializer.LocaleSerializer;
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
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.union.Union;
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
 * <p>Fory separates user class IDs from internal class IDs to provide a clean and intuitive API:
 *
 * <ul>
 *   <li><b>User ID space</b>: IDs specified via {@link #register(Class, int)} start from 0. Valid
 *       range is [0, {@value Short#MAX_VALUE} - {@value #USER_ID_BASE} - 1] (i.e., [0, 32510]).
 *   <li><b>Internal ID space</b>: Reserved for Fory's built-in types (primitives, common
 *       collections, etc.). These IDs are in the range [0, {@value #USER_ID_BASE} - 1] and are
 *       completely hidden from users.
 * </ul>
 *
 * <p>When users register a class with ID N, Fory internally stores it as (N + {@value
 * #USER_ID_BASE}). This transformation is transparent to users - they only work with their own
 * 0-based ID space.
 *
 * <h2>Registration Methods</h2>
 *
 * <ul>
 *   <li>{@link #register(Class)} - Auto-assigns the next available user ID
 *   <li>{@link #register(Class, int)} - Registers with a user-specified ID (0-based)
 *   <li>{@link #register(Class, String, String)} - Registers with namespace and type name
 * </ul>
 *
 * @see #register(Class)
 * @see #register(Class, int)
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ClassResolver extends TypeResolver {
  private static final Logger LOG = LoggerFactory.getLogger(ClassResolver.class);

  /** Flag value indicating no class ID has been assigned. */
  public static final short NO_CLASS_ID = TypeResolver.NO_CLASS_ID;

  public static final short LAMBDA_STUB_ID = 1;
  public static final short JDK_PROXY_STUB_ID = 2;
  public static final short REPLACE_STUB_ID = 3;

  /**
   * Base offset for user-registered class IDs. User IDs are internally stored as (userId + {@value
   * #USER_ID_BASE}). The first {@value #USER_ID_BASE} IDs (0 to {@value #USER_ID_BASE} - 1) are
   * reserved for Fory's internal types.
   */
  public static final short USER_ID_BASE = 256;

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

  private final Fory fory;
  XtypeResolver xtypeResolver;
  private ClassInfo[] registeredId2ClassInfo = new ClassInfo[] {};
  private ClassInfo classInfoCache;
  // Every deserialization for unregistered class will query it, performance is important.
  private final ObjectMap<TypeNameBytes, ClassInfo> compositeNameBytes2ClassInfo =
      new ObjectMap<>(16, foryMapLoadFactor);
  private final Map<Class<?>, ClassDef> classDefMap = new HashMap<>();
  private Class<?> currentReadClass;
  // class id of last default registered class.
  private short innerEndClassId;
  private final ShimDispatcher shimDispatcher;

  public ClassResolver(Fory fory) {
    super(fory);
    this.fory = fory;
    classInfoCache = NIL_CLASS_INFO;
    shimDispatcher = new ShimDispatcher(fory);
    _addGraalvmClassRegistry(fory.getConfig().getConfigHash(), this);
  }

  @Override
  public void initialize() {
    extRegistry.objectGenericType = buildGenericType(OBJECT_TYPE);
    registerInternal(LambdaSerializer.ReplaceStub.class, LAMBDA_STUB_ID);
    registerInternal(JdkProxySerializer.ReplaceStub.class, JDK_PROXY_STUB_ID);
    registerInternal(ReplaceResolveSerializer.ReplaceStub.class, REPLACE_STUB_ID);
    registerInternal(void.class, PRIMITIVE_VOID_CLASS_ID);
    registerInternal(boolean.class, PRIMITIVE_BOOLEAN_CLASS_ID);
    registerInternal(byte.class, PRIMITIVE_BYTE_CLASS_ID);
    registerInternal(char.class, PRIMITIVE_CHAR_CLASS_ID);
    registerInternal(short.class, PRIMITIVE_SHORT_CLASS_ID);
    registerInternal(int.class, PRIMITIVE_INT_CLASS_ID);
    registerInternal(float.class, PRIMITIVE_FLOAT_CLASS_ID);
    registerInternal(long.class, PRIMITIVE_LONG_CLASS_ID);
    registerInternal(double.class, PRIMITIVE_DOUBLE_CLASS_ID);
    registerInternal(Void.class, VOID_CLASS_ID);
    registerInternal(Boolean.class, BOOLEAN_CLASS_ID);
    registerInternal(Byte.class, BYTE_CLASS_ID);
    registerInternal(Character.class, CHAR_CLASS_ID);
    registerInternal(Short.class, SHORT_CLASS_ID);
    registerInternal(Integer.class, INTEGER_CLASS_ID);
    registerInternal(Float.class, FLOAT_CLASS_ID);
    registerInternal(Long.class, LONG_CLASS_ID);
    registerInternal(Double.class, DOUBLE_CLASS_ID);
    registerInternal(String.class, STRING_CLASS_ID);
    registerInternal(boolean[].class, PRIMITIVE_BOOLEAN_ARRAY_CLASS_ID);
    registerInternal(byte[].class, PRIMITIVE_BYTE_ARRAY_CLASS_ID);
    registerInternal(char[].class, PRIMITIVE_CHAR_ARRAY_CLASS_ID);
    registerInternal(short[].class, PRIMITIVE_SHORT_ARRAY_CLASS_ID);
    registerInternal(int[].class, PRIMITIVE_INT_ARRAY_CLASS_ID);
    registerInternal(float[].class, PRIMITIVE_FLOAT_ARRAY_CLASS_ID);
    registerInternal(long[].class, PRIMITIVE_LONG_ARRAY_CLASS_ID);
    registerInternal(double[].class, PRIMITIVE_DOUBLE_ARRAY_CLASS_ID);
    registerInternal(String[].class, STRING_ARRAY_CLASS_ID);
    registerInternal(Object[].class, OBJECT_ARRAY_CLASS_ID);
    registerInternal(ArrayList.class, ARRAYLIST_CLASS_ID);
    registerInternal(HashMap.class, HASHMAP_CLASS_ID);
    registerInternal(HashSet.class, HASHSET_CLASS_ID);
    registerInternal(Class.class, CLASS_CLASS_ID);
    registerInternal(Object.class, EMPTY_OBJECT_ID);
    registerCommonUsedClasses();
    registerDefaultClasses();
    addDefaultSerializers();
    shimDispatcher.initialize();
    innerEndClassId = extRegistry.classIdGenerator;
    if (GraalvmSupport.isGraalBuildtime()) {
      classInfoMap.forEach(
          (cls, classInfo) -> {
            if (classInfo.serializer != null) {
              extRegistry.registeredClassInfos.add(classInfo);
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
        registerInternal(NonexistentSkip.class);
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
      while (extRegistry.userIdGenerator + USER_ID_BASE < registeredId2ClassInfo.length
          && registeredId2ClassInfo[extRegistry.userIdGenerator + USER_ID_BASE] != null) {
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
   * @see #register(Class, int)
   */
  @Override
  public void register(String className, int classId) {
    register(loadClass(className, false, 0, false), classId);
  }

  /**
   * Registers a class with a user-specified ID.
   *
   * <p>The ID is in the user ID space, starting from 0. Fory internally transforms this to an
   * internal ID by adding {@link #USER_ID_BASE}. This separation ensures user IDs never conflict
   * with Fory's internal type IDs.
   *
   * <p>Valid user ID range: [0, 32510] (i.e., [0, Short.MAX_VALUE - USER_ID_BASE - 1])
   *
   * <p>Example:
   *
   * <pre>{@code
   * fory.register(MyClass.class, 0);      // User ID 0 -> Internal ID 256
   * fory.register(AnotherClass.class, 1); // User ID 1 -> Internal ID 257
   * }</pre>
   *
   * @param cls the class to register
   * @param id the user ID to assign (0-based)
   * @throws IllegalArgumentException if the ID is out of valid range or already in use
   */
  @Override
  public void register(Class<?> cls, int id) {
    registerImpl(cls, id + USER_ID_BASE);
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
   * #register(Class)} instead. Internal IDs are in the range [0, {@link #USER_ID_BASE} - 1].
   *
   * @param cls the class to register
   */
  public void registerInternal(Class<?> cls) {
    if (!extRegistry.registeredClassIdMap.containsKey(cls)) {
      while (extRegistry.classIdGenerator < registeredId2ClassInfo.length
          && registeredId2ClassInfo[extRegistry.classIdGenerator] != null) {
        extRegistry.classIdGenerator++;
      }
      registerInternal(cls, extRegistry.classIdGenerator);
    }
  }

  /**
   * Registers a class for internal use with a specified internal ID.
   *
   * <p><b>Internal API</b>: This method is for Fory's internal use only. Users should use {@link
   * #register(Class, int)} instead.
   *
   * <p>Internal IDs are reserved for Fory's built-in types and must be in the range [0, {@link
   * #USER_ID_BASE} - 1] (i.e., [0, 255]). User IDs start from {@link #USER_ID_BASE} and above.
   *
   * @param cls the class to register
   * @param classId the internal ID, must be in range [0, {@link #USER_ID_BASE} - 1]
   * @throws IllegalArgumentException if the ID is out of range or already in use
   */
  public void registerInternal(Class<?> cls, int classId) {
    Preconditions.checkArgument(classId >= 0 && classId < USER_ID_BASE);
    registerImpl(cls, classId);
  }

  private void registerImpl(Class<?> cls, int classId) {
    checkRegisterAllowed();
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
    GraalvmSupport.registerClass(cls, fory.getConfig().getConfigHash());
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

  @Override
  public boolean isMonomorphic(Descriptor descriptor) {
    ForyField foryField = descriptor.getForyField();
    if (foryField != null) {
      switch (foryField.morphic()) {
        case POLYMORPHIC:
          return false;
        case FINAL:
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
    if (fory.getConfig().isMetaShareEnabled()) {
      // can't create final map/collection type using TypeUtils.mapOf(TypeToken<K>,
      // TypeToken<V>)
      if (!ReflectionUtils.isMonomorphic(clz)) {
        return false;
      }
      if (Map.class.isAssignableFrom(clz) || Collection.class.isAssignableFrom(clz)) {
        return true;
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
      return (isInnerClass(clz) || clz.isEnum());
    }
    return ReflectionUtils.isMonomorphic(clz);
  }

  public boolean isBuildIn(Descriptor descriptor) {
    return isMonomorphic(descriptor);
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

  @Override
  public void registerSerializer(Class<?> type, Serializer<?> serializer) {
    checkRegisterAllowed();
    if (!serializer.getClass().getPackage().getName().startsWith("org.apache.fory")) {
      SerializationUtils.validate(type, serializer.getClass());
    }
    if (!extRegistry.registeredClassIdMap.containsKey(type) && !fory.isCrossLanguage()) {
      register(type);
    }
    registerSerializerImpl(type, serializer);
  }

  /**
   * If a serializer exists before, it will be replaced by new serializer.
   *
   * @param type class needed to be serialized/deserialized
   * @param serializer serializer for object of {@code type}
   */
  public void registerInternalSerializer(Class<?> type, Serializer<?> serializer) {
    registerSerializerImpl(type, serializer);
  }

  private void registerSerializerImpl(Class<?> type, Serializer<?> serializer) {
    checkRegisterAllowed();
    if (!serializer.getClass().getPackage().getName().startsWith("org.apache.fory")) {
      SerializationUtils.validate(type, serializer.getClass());
    }
    if (!extRegistry.registeredClassIdMap.containsKey(type) && !fory.isCrossLanguage()) {
      registerInternal(type);
    }
    addSerializer(type, serializer);
    ClassInfo classInfo = classInfoMap.get(type);
    classInfoMap.put(type, classInfo);
    extRegistry.registeredClassInfos.add(classInfo);
    // in order to support customized serializer for abstract or interface.
    if (!type.isPrimitive() && (ReflectionUtils.isAbstract(type) || type.isInterface())) {
      extRegistry.absClassInfo.put(type, classInfo);
      extRegistry.registeredClassInfos.add(classInfo);
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
    ClassInfo classInfo = classInfoMap.get(cls);
    if (classInfo != null) {
      classInfo.setSerializer(this, null);
    }
  }

  /** Add serializer for specified class. */
  public void addSerializer(Class<?> type, Serializer<?> serializer) {
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
      if (serializer instanceof ReplaceResolveSerializer
          && !(serializer instanceof FinalFieldReplaceResolveSerializer)) {
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
  @Override
  public Serializer<?> getRawSerializer(Class<?> cls) {
    Preconditions.checkNotNull(cls);
    return getOrUpdateClassInfo(cls).serializer;
  }

  @Override
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

  public Class<? extends Serializer> getObjectSerializerClass(
      Class<?> cls, JITContext.SerializerJITCallback<Class<? extends Serializer>> callback) {
    boolean codegen =
        supportCodegenForJavaSerialization(cls) && fory.getConfig().isCodeGenEnabled();
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
  public ClassInfo getClassInfo(Class<?> cls) {
    ClassInfo classInfo = classInfoMap.get(cls);
    if (classInfo == null || classInfo.serializer == null) {
      addSerializer(cls, createSerializer(cls));
      classInfo = classInfoMap.get(cls);
    }
    return classInfo;
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
    if (!extRegistry.absClassInfo.isEmpty()) {
      Class<?> tmpCls = cls;
      while (tmpCls != null && tmpCls != Object.class) {
        ClassInfo absClass;
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
        classInfoCache = NIL_CLASS_INFO;
        extRegistry.classIdToDef.put(classDef.getId(), Tuple2.of(classDefTuple.f0, null));
      }
    }
    if (GraalvmSupport.isGraalBuildtime()) {
      // Instance for generated class should be hold at graalvm runtime only.
      getGraalvmClassRegistry()
          .serializerClassMap
          .put(cls, getGraalvmSerializerClass(classInfo.serializer));
      classInfoCache = NIL_CLASS_INFO;
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

  @Override
  public ClassInfo readSharedClassMeta(MemoryBuffer buffer, MetaContext metaContext) {
    assert metaContext != null : SET_META__CONTEXT_MSG;
    int header = buffer.readVarUint32Small14();
    int id = header >>> 1;
    if ((header & 0b1) == 0) {
      return getOrUpdateClassInfo((short) id);
    }
    ClassInfo classInfo = metaContext.readClassInfos.get(id);
    if (classInfo == null) {
      classInfo = readSharedClassMeta(metaContext, id);
    }
    return classInfo;
  }

  @Override
  public ClassDef getTypeDef(Class<?> cls, boolean resolveParent) {
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

  // Note: Thread safe for jit thread to call.
  public Expression writeClassExpr(Expression buffer, short classId) {
    Preconditions.checkArgument(classId != NO_CLASS_ID);
    return writeClassExpr(buffer, Literal.ofShort(classId));
  }

  // Note: Thread safe for jit thread to call.
  private Expression writeClassExpr(Expression buffer, Expression classId) {
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
      return readSharedClassMeta(buffer, fory.getSerializationContext().getMetaContext());
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
      return readSharedClassMeta(buffer, fory.getSerializationContext().getMetaContext());
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
      return readSharedClassMeta(buffer, fory.getSerializationContext().getMetaContext());
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
      return readSharedClassMeta(buffer, fory.getSerializationContext().getMetaContext());
    }
    return readClassInfoFromBytes(buffer, classInfoCache, header);
  }

  private ClassInfo readClassInfoFromBytes(
      MemoryBuffer buffer, ClassInfoHolder classInfoHolder, int header) {
    if (metaContextShareEnabled) {
      return readSharedClassMeta(buffer, fory.getSerializationContext().getMetaContext());
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

  public void reset() {
    resetRead();
    resetWrite();
  }

  public void resetRead() {}

  public void resetWrite() {}

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
      if (c == 0) {
        c = DescriptorGrouper.getFieldSortKey(d1).compareTo(DescriptorGrouper.getFieldSortKey(d2));
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
            fory.compressInt(),
            fory.compressLong(),
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
  public void ensureSerializersCompiled() {
    if (extRegistry.ensureSerializersCompiled) {
      return;
    }
    extRegistry.ensureSerializersCompiled = true;
    try {
      fory.getJITContext().lock();
      Serializers.newSerializer(fory, LambdaSerializer.STUB_LAMBDA_CLASS, LambdaSerializer.class);
      Serializers.newSerializer(
          fory, JdkProxySerializer.SUBT_PROXY.getClass(), JdkProxySerializer.class);
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
        classInfoCache = NIL_CLASS_INFO;
        classInfoMap.forEach(
            (cls, classInfo) -> {
              if (classInfo.serializer != null
                  && !extRegistry.registeredClassInfos.contains(classInfo)) {
                classInfo.serializer = null;
              }
            });
      }
    } finally {
      fory.getJITContext().unlock();
    }
  }
}
