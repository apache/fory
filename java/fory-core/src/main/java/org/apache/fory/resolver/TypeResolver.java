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

import static org.apache.fory.type.Types.INVALID_USER_TYPE_ID;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.apache.fory.annotation.CodegenInvoke;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.ForyStruct;
import org.apache.fory.annotation.ForyStruct.Evolution;
import org.apache.fory.annotation.Internal;
import org.apache.fory.builder.CodecUtils;
import org.apache.fory.builder.Generated.GeneratedCompatibleSerializer;
import org.apache.fory.builder.Generated.GeneratedObjectSerializer;
import org.apache.fory.builder.Generated.GeneratedStaticCompatibleSerializer;
import org.apache.fory.builder.JITContext;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.collection.BiMap;
import org.apache.fory.collection.ConcurrentIdentityMap;
import org.apache.fory.collection.IdentityMap;
import org.apache.fory.collection.IdentityObjectIntMap;
import org.apache.fory.collection.LongMap;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.config.Config;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.MetaStringReader;
import org.apache.fory.context.MetaStringWriter;
import org.apache.fory.context.MetaWriteContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.ForyException;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.exception.SerializerUnregisteredException;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.ClassSpec;
import org.apache.fory.meta.EncodedMetaString;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.reflect.ObjectInstantiator;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.CodegenSerializer;
import org.apache.fory.serializer.CodegenSerializer.LazyInitBeanSerializer;
import org.apache.fory.serializer.CompatibleSerializer;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.PrimitiveSerializers;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.SerializerFactory;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.serializer.StaticGeneratedStructSerializer;
import org.apache.fory.serializer.UnknownClass;
import org.apache.fory.serializer.UnknownClass.UnknownEmptyStruct;
import org.apache.fory.serializer.UnknownClass.UnknownStruct;
import org.apache.fory.serializer.UnknownClassSerializers;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorBuilder;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.ScalaTypes;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.function.Functions;

// Internal type dispatcher.
// Do not use this interface outside fory package
@Internal
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class TypeResolver {
  private static final Logger LOG = LoggerFactory.getLogger(ClassResolver.class);

  static final TypeInfo NIL_TYPE_INFO =
      new TypeInfo(null, null, null, null, Types.UNKNOWN, INVALID_USER_TYPE_ID);
  // use a lower load factor to minimize hash collision
  static final float foryMapLoadFactor = 0.5f;
  static final String SET_META_WRITE_CONTEXT_MSG =
      "Meta write context must be set on the active write context before serialization.";
  static final String SET_META_READ_CONTEXT_MSG =
      "Meta read context must be set on the active read context before deserialization.";
  // reserved last 5 internal type ids for future use
  static final int INTERNAL_NATIVE_ID_LIMIT = 250;
  private static final GenericType OBJECT_GENERIC_TYPE = GenericType.build(Object.class);
  private static final float TYPE_ID_MAP_LOAD_FACTOR = 0.5f;
  private static final int MAX_CACHED_TYPE_DEFS = 8192;
  static final long MAX_USER_TYPE_ID = 0xffff_fffEL;

  private static final class TransformedTypeInfo {
    final Class<?> readClass;
    final long typeDefId;
    final TypeInfo typeInfo;

    TransformedTypeInfo(Class<?> readClass, long typeDefId, TypeInfo typeInfo) {
      this.readClass = readClass;
      this.typeDefId = typeDefId;
      this.typeInfo = typeInfo;
    }
  }

  final Config config;
  final boolean metaContextShareEnabled;
  final SharedRegistry sharedRegistry;
  final JITContext jitContext;
  // IdentityMap has better lookup performance, when loadFactor is 0.05f, performance is better
  final IdentityMap<Class<?>, TypeInfo> classInfoMap = new IdentityMap<>(64, foryMapLoadFactor);
  final ExtRegistry extRegistry;
  final ConcurrentIdentityMap<Class<?>, TypeDef> typeDefMap;
  // Map for internal type ids (non-user-defined).
  final TypeInfo[] typeIdToTypeInfo;
  // Map for user-registered type ids, keyed by user id.
  final LongMap<TypeInfo> userTypeIdToTypeInfo = new LongMap<>(4, TYPE_ID_MAP_LOAD_FACTOR);
  // Cache for readTypeInfo(MemoryBuffer) - persists between calls to avoid reloading
  // dynamically created classes that can't be found by Class.forName
  private TypeInfo typeInfoCache;
  private boolean registrationFinished;

  protected TypeResolver(
      Config config,
      ClassLoader classLoader,
      SharedRegistry sharedRegistry,
      JITContext jitContext) {
    this.config = config;
    this.sharedRegistry = sharedRegistry;
    this.jitContext = jitContext;
    metaContextShareEnabled = config.isMetaShareEnabled();
    extRegistry = new ExtRegistry(classLoader, sharedRegistry);
    typeDefMap = sharedRegistry.typeDefMap;
    int length = isCrossLanguage() ? Types.BOUND : INTERNAL_NATIVE_ID_LIMIT;
    typeIdToTypeInfo = new TypeInfo[length];
  }

  public final Config getConfig() {
    return config;
  }

  public final ClassLoader getClassLoader() {
    return extRegistry.classLoader;
  }

  @Internal
  public void checkClassForDeserialization(Class<?> cls) {}

  public final SharedRegistry getSharedRegistry() {
    return sharedRegistry;
  }

  public final JITContext getJITContext() {
    return jitContext;
  }

  public final boolean isRegistrationFinished() {
    return registrationFinished;
  }

  protected final void setRegistrationFinished() {
    registrationFinished = true;
  }

  public final boolean isCrossLanguage() {
    return config.isXlang();
  }

  public final boolean isCompatible() {
    return config.isCompatible();
  }

  public final boolean isShareMeta() {
    return metaContextShareEnabled;
  }

  public final boolean trackingRef() {
    return config.trackingRef();
  }

  public final boolean isStringRefIgnored() {
    return config.isStringRefIgnored();
  }

  public final boolean checkClassVersion() {
    return config.checkClassVersion();
  }

  public final Class<? extends Serializer> getDefaultJDKStreamSerializerType() {
    return config.getDefaultJDKStreamSerializerType();
  }

  protected final void checkRegisterAllowed() {
    if (registrationFinished) {
      throw new ForyException(
          "Cannot register class/serializer after registration has been frozen. Please register "
              + "all classes before invoking top-level `serialize/deserialize/copy` methods of "
              + "Fory.");
    }
  }

  /**
   * Registers a class with an auto-assigned user ID.
   *
   * @param type the class to register
   */
  public abstract void register(Class<?> type);

  /**
   * Registers a class with a user-specified ID. Valid ID range is [0, 0xfffffffe] (0xffffffff is
   * reserved for "unset").
   *
   * @param type the class to register
   * @param id the user ID to assign (0-based)
   */
  public abstract void register(Class<?> type, long id);

  /**
   * Registers a class with a namespace and type name for cross-language serialization.
   *
   * @param type the class to register
   * @param namespace the namespace (can be empty if type name has no conflict)
   * @param typeName the type name; must not contain {@code .}
   */
  public abstract void register(Class<?> type, String namespace, String typeName);

  /** Registers a class by name with an auto-assigned user ID. */
  public void register(String className) {
    register(loadClass(className));
  }

  /** Registers a class by name with a user-specified ID. */
  public void register(String className, long classId) {
    register(loadClass(className), classId);
  }

  /**
   * Registers a class by name with a namespace and type name. The type name must not contain `.`.
   */
  public void register(String className, String namespace, String typeName) {
    register(loadClass(className), namespace, typeName);
  }

  /**
   * Registers {@code runtimeType} to use the already-registered metadata and serializer of {@code
   * canonicalType}.
   *
   * <p>This is only for language runtimes where one source type has multiple JVM runtime classes,
   * such as enum or ADT case classes. The resolver owns the runtime class lookup; the caller owns
   * deciding which runtime classes are aliases.
   */
  @Internal
  public final void registerRuntimeTypeAlias(Class<?> runtimeType, Class<?> canonicalType) {
    Preconditions.checkNotNull(runtimeType, "runtimeType");
    Preconditions.checkNotNull(canonicalType, "canonicalType");
    if (runtimeType == canonicalType) {
      return;
    }
    checkRegisterAllowed();
    TypeInfo canonicalInfo = classInfoMap.get(canonicalType);
    Preconditions.checkArgument(
        canonicalInfo != null,
        "Canonical type must be registered before registering a runtime type alias: "
            + canonicalType.getName());
    TypeInfo existingInfo = classInfoMap.get(runtimeType);
    Preconditions.checkArgument(
        existingInfo == null || existingInfo == canonicalInfo,
        "Runtime type is already registered with different type metadata: "
            + runtimeType.getName());
    String runtimeTypeName = runtimeType.getName();
    Class<?> registeredType = extRegistry.registeredClasses.get(runtimeTypeName);
    Preconditions.checkArgument(
        registeredType == null || registeredType == runtimeType,
        "Runtime type alias name is already registered with different class: " + runtimeTypeName);
    String registeredName = extRegistry.registeredClasses.inverse().get(runtimeType);
    Preconditions.checkArgument(
        registeredName == null || registeredName.equals(runtimeTypeName),
        "Runtime type alias is already registered with different name: " + registeredName);
    classInfoMap.put(runtimeType, canonicalInfo);
    extRegistry.registeredClasses.put(runtimeTypeName, runtimeType);
    registerGraalvmClass(runtimeType);
  }

  /**
   * Registers a union type with a user-specified ID and serializer.
   *
   * @param type the union class to register
   * @param id the user ID to assign (0-based)
   * @param serializer serializer for the union
   */
  public abstract void registerUnion(Class<?> type, long id, Serializer<?> serializer);

  /**
   * Registers a union type with a namespace and type name and serializer.
   *
   * @param type the union class to register
   * @param namespace the namespace (can be empty if type name has no conflict)
   * @param typeName the type name
   * @param serializer serializer for the union
   */
  public abstract void registerUnion(
      Class<?> type, String namespace, String typeName, Serializer<?> serializer);

  /** Registers a non-Java enum type with a user-specified ID and serializer. */
  @Internal
  public abstract void registerEnum(Class<?> type, long id, Serializer<?> serializer);

  /** Registers a non-Java enum type with a namespace, type name, and serializer. */
  @Internal
  public abstract void registerEnum(
      Class<?> type, String namespace, String typeName, Serializer<?> serializer);

  /**
   * Returns the runtime-scoped object instantiator for {@code type}.
   *
   * <p>The instantiator follows the normal Java object model used by Fory serializers. Records use
   * their canonical constructor, while ordinary classes use supported empty construction followed
   * by field restoration.
   */
  @Internal
  public final <T> ObjectInstantiator<T> getObjectInstantiator(Class<T> type) {
    return sharedRegistry.getObjectInstantiator(type);
  }

  /**
   * Registers a custom serializer for a type.
   *
   * @param type the class to register
   * @param serializer the serializer instance to use
   */
  public abstract void registerSerializer(Class<?> type, Serializer<?> serializer);

  /**
   * Registers a custom serializer class for a type.
   *
   * @param type the class to register
   * @param serializerClass the serializer class (will be instantiated by Fory)
   */
  public abstract <T> void registerSerializer(
      Class<T> type, Class<? extends Serializer> serializerClass);

  /**
   * Registers a serializer for internal types (those with fixed IDs in the type system). This
   * method is used for built-in types like ArrayList, HashMap, etc.
   *
   * @param type the class to register
   * @param serializer the serializer to use
   */
  public abstract void registerInternalSerializer(Class<?> type, Serializer<?> serializer);

  /**
   * Freezes the mutable registration phase and switches this resolver to the shared read-only
   * registration maps.
   *
   * <p>Before this method runs, registration data lives in the local mutable maps inside {@link
   * ExtRegistry}. The first caller publishes shared registration maps to {@link SharedRegistry};
   * later callers adopt those same maps. This method is idempotent so top-level runtime entry
   * points can call it defensively.
   */
  public final void finishRegistration() {
    if (registrationFinished) {
      return;
    }
    sharedRegistry.setRegistrationIfAbsent(
        extRegistry.registeredClassIdMap, extRegistry.registeredClasses);
    extRegistry.finishRegistration(
        sharedRegistry.getRegisteredClassIdMap(), sharedRegistry.getRegisteredClasses());
    setRegistrationFinished();
  }

  /**
   * Registers a type (if not already registered) and then registers the serializer class.
   *
   * @param type the class to register
   * @param serializerClass the serializer class (will be instantiated by Fory)
   * @param <T> type of class
   */
  public <T> void registerSerializerAndType(
      Class<T> type, Class<? extends Serializer> serializerClass) {
    if (!isRegistered(type)) {
      register(type);
    }
    registerSerializer(type, serializerClass);
  }

  /**
   * Registers a type (if not already registered) and then registers the serializer instance.
   *
   * @param type the class to register
   * @param serializer the serializer instance to use
   */
  public void registerSerializerAndType(Class<?> type, Serializer<?> serializer) {
    if (!isRegistered(type)) {
      register(type);
    }
    registerSerializer(type, serializer);
  }

  /**
   * Whether to track reference for this type. If false, reference tracing of subclasses may be
   * ignored too.
   */
  public final boolean needToWriteRef(TypeRef<?> typeRef) {
    if (!trackingRef()) {
      return false;
    }
    Class<?> cls = typeRef.getRawType();
    if (cls == String.class && !isCrossLanguage()) {
      // for string, ignore `TypeExtMeta` for java native mode
      return !isStringRefIgnored();
    }
    TypeExtMeta meta = typeRef.getTypeExtMeta();
    if (meta != null) {
      return meta.trackingRef();
    }

    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo == null || typeInfo.serializer == null) {
      // TODO group related logic together for extendability and consistency.
      return !cls.isEnum();
    } else {
      return typeInfo.serializer.needToWriteRef();
    }
  }

  public final boolean needToWriteTypeDef(Serializer serializer) {
    if (!isCompatible()) {
      return false;
    }
    return isStructSerializer(serializer);
  }

  public final boolean needToWriteTypeDef(Class<? extends Serializer> serializerClass) {
    if (!isCompatible()) {
      return false;
    }
    return isStructSerializerClass(serializerClass);
  }

  public abstract boolean isRegistered(Class<?> cls);

  public abstract boolean isRegisteredById(Class<?> cls);

  public abstract boolean isRegisteredByName(Class<?> cls);

  public abstract boolean isBuildIn(Descriptor descriptor);

  @Internal
  public abstract Comparator<Descriptor> getDescriptorComparator();

  protected DescriptorGrouper configureDescriptorGrouper(DescriptorGrouper descriptorGrouper) {
    return descriptorGrouper;
  }

  public boolean usesPrimitiveFieldOrdering(Descriptor descriptor) {
    Class<?> rawType = descriptor.getRawType();
    return TypeUtils.isPrimitive(rawType) || TypeUtils.isBoxed(rawType);
  }

  public boolean isCollectionDescriptor(Descriptor descriptor) {
    return isCollection(descriptor.getRawType());
  }

  public boolean isMapDescriptor(Descriptor descriptor) {
    return isMap(descriptor.getRawType());
  }

  public abstract boolean isMonomorphic(Descriptor descriptor);

  public abstract boolean isMonomorphic(Class<?> clz);

  public abstract TypeInfo getTypeInfo(Class<?> cls);

  public abstract TypeInfo getTypeInfo(Class<?> cls, boolean createIfAbsent);

  public abstract TypeInfo getTypeInfo(Class<?> cls, TypeInfoHolder classInfoHolder);

  /**
   * Writes class info to buffer using the unified type system. This is the single implementation
   * shared by both ClassResolver and XtypeResolver.
   *
   * <p>Encoding:
   *
   * <ul>
   *   <li>NAMED_ENUM/NAMED_STRUCT/NAMED_EXT/NAMED_UNION: namespace + typename bytes (or meta-share
   *       if enabled)
   *   <li>NAMED_COMPATIBLE_STRUCT: namespace + typename bytes (or meta-share if enabled)
   *   <li>COMPATIBLE_STRUCT: meta-share when enabled, otherwise only type ID
   *   <li>Other types: just the type ID
   * </ul>
   */
  public final void writeTypeInfo(WriteContext writeContext, TypeInfo typeInfo) {
    MemoryBuffer buffer = writeContext.getBuffer();
    int typeId = typeInfo.getTypeId();
    buffer.writeUInt8(typeId);
    switch (typeId) {
      case Types.ENUM:
      case Types.STRUCT:
      case Types.EXT:
      case Types.TYPED_UNION:
        buffer.writeVarUInt32(typeInfo.userTypeId);
        break;
      case Types.COMPATIBLE_STRUCT:
      case Types.NAMED_COMPATIBLE_STRUCT:
        writeSharedClassMeta(writeContext, typeInfo);
        break;
      case Types.NAMED_ENUM:
      case Types.NAMED_STRUCT:
      case Types.NAMED_EXT:
      case Types.NAMED_UNION:
        if (!metaContextShareEnabled) {
          Preconditions.checkNotNull(typeInfo.namespace);
          MetaStringWriter metaStringWriter = writeContext.getMetaStringWriter();
          metaStringWriter.writeMetaString(buffer, typeInfo.namespace);
          Preconditions.checkNotNull(typeInfo.typeName);
          metaStringWriter.writeMetaString(buffer, typeInfo.typeName);
        } else {
          writeSharedClassMeta(writeContext, typeInfo);
        }
        break;
      default:
        break;
    }
  }

  static int toUserTypeId(long userTypeId) {
    Preconditions.checkArgument(
        userTypeId >= 0 && userTypeId <= MAX_USER_TYPE_ID,
        "User type id must be in range [0, 0xfffffffe]");
    return (int) userTypeId;
  }

  /**
   * Native code for ClassResolver.writeTypeInfo is too big to inline, so inline it manually.
   *
   * <p>See `already compiled into a big method` in <a
   * href="https://wiki.openjdk.org/display/HotSpot/Server+Compiler+Inlining+Messages">Server+Compiler+Inlining+Messages</a>
   */
  // Note: Thread safe for jit thread to call.
  public Expression writeClassExpr(
      Expression classResolverRef, Expression buffer, Expression classInfo) {
    return new Invoke(classResolverRef, "writeTypeInfo", buffer, classInfo);
  }

  /**
   * Writes shared class metadata using the meta-share protocol. Protocol: If class already written,
   * writes {@code (index << 1) | 1} (reference). If new class, writes {@code (index << 1)} followed
   * by TypeDef bytes.
   *
   * <p>This method is shared between XtypeResolver and ClassResolver.
   */
  protected final void writeSharedClassMeta(WriteContext writeContext, TypeInfo typeInfo) {
    MemoryBuffer buffer = writeContext.getBuffer();
    MetaWriteContext metaWriteContext = writeContext.getMetaWriteContext();
    assert metaWriteContext != null : SET_META_WRITE_CONTEXT_MSG;
    IdentityObjectIntMap<Class<?>> classMap = metaWriteContext.classMap;
    int newId = classMap.size;
    int id = classMap.putOrGet(typeInfo.type, newId);
    if (id >= 0) {
      // Reference to previously written type: (index << 1) | 1, LSB=1
      buffer.writeVarUInt32((id << 1) | 1);
    } else {
      // New type: index << 1, LSB=0, followed by TypeDef bytes inline
      buffer.writeVarUInt32(newId << 1);
      TypeDef typeDef = typeInfo.typeDef;
      if (typeDef == null) {
        typeDef = buildTypeDef(typeInfo);
      }
      buffer.writeBytes(typeDef.getEncoded());
    }
  }

  /**
   * Build TypeDef for the given TypeInfo. Used by writeSharedClassMeta when the typeDef is not yet
   * created.
   */
  protected abstract TypeDef buildTypeDef(TypeInfo typeInfo);

  /**
   * Reads class info from buffer using the unified type system. This is the single implementation
   * shared by both ClassResolver and XtypeResolver.
   *
   * <p>Note: {@link #readTypeInfo(ReadContext, TypeInfo)} is faster since it uses a non-global
   * class info cache.
   */
  public final TypeInfo readTypeInfo(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    int typeId = buffer.readUInt8();
    TypeInfo typeInfo;
    switch (typeId) {
      case Types.ENUM:
      case Types.STRUCT:
      case Types.EXT:
      case Types.TYPED_UNION:
        typeInfo = Objects.requireNonNull(userTypeIdToTypeInfo.get(buffer.readVarUInt32()));
        break;
      case Types.COMPATIBLE_STRUCT:
      case Types.NAMED_COMPATIBLE_STRUCT:
        typeInfo = readSharedClassMeta(readContext);
        break;
      case Types.NAMED_ENUM:
      case Types.NAMED_STRUCT:
      case Types.NAMED_EXT:
      case Types.NAMED_UNION:
        if (!metaContextShareEnabled) {
          typeInfo = readTypeInfoFromBytes(readContext, typeInfoCache, typeId);
        } else {
          typeInfo = readSharedClassMeta(readContext);
        }
        break;
      case Types.LIST:
        typeInfo = readListTypeInfo(readContext);
        break;
      case Types.TIMESTAMP:
        typeInfo = readTimestampTypeInfo(readContext);
        break;
      default:
        typeInfo = Objects.requireNonNull(getInternalTypeInfoByTypeId(typeId));
    }
    if (typeInfo.serializer == null) {
      typeInfo = ensureSerializerForTypeInfo(typeInfo);
    }
    typeInfoCache = typeInfo;
    return typeInfo;
  }

  /**
   * Read class info from buffer using a target class. This is used by java serialization APIs that
   * pass an expected class for meta share resolution.
   */
  public final TypeInfo readTypeInfo(ReadContext readContext, Class<?> targetClass) {
    MemoryBuffer buffer = readContext.getBuffer();
    int typeId = buffer.readUInt8();
    TypeInfo typeInfo;
    switch (typeId) {
      case Types.ENUM:
      case Types.STRUCT:
      case Types.EXT:
      case Types.TYPED_UNION:
        typeInfo = Objects.requireNonNull(userTypeIdToTypeInfo.get(buffer.readVarUInt32()));
        break;
      case Types.COMPATIBLE_STRUCT:
      case Types.NAMED_COMPATIBLE_STRUCT:
        typeInfo = readSharedClassMeta(readContext, targetClass);
        break;
      case Types.NAMED_ENUM:
      case Types.NAMED_STRUCT:
      case Types.NAMED_EXT:
      case Types.NAMED_UNION:
        if (!metaContextShareEnabled) {
          typeInfo = readTypeInfoFromBytes(readContext, typeInfoCache, typeId);
        } else {
          typeInfo = readSharedClassMeta(readContext, targetClass);
        }
        break;
      case Types.LIST:
        typeInfo = readListTypeInfo(readContext);
        break;
      case Types.TIMESTAMP:
        typeInfo = readTimestampTypeInfo(readContext);
        break;
      default:
        typeInfo = Objects.requireNonNull(getInternalTypeInfoByTypeId(typeId));
    }
    if (typeInfo.serializer == null) {
      typeInfo = ensureSerializerForTypeInfo(typeInfo);
    }
    typeInfoCache = typeInfo;
    return typeInfo;
  }

  /**
   * Read class info from buffer with TypeInfo cache. This version is faster than {@link
   * #readTypeInfo(ReadContext)} because it uses the provided classInfoCache to reduce map lookups
   * when reading class from binary.
   *
   * @param typeInfoCache cache for class info to speed up repeated reads
   * @return the TypeInfo read from buffer
   */
  @CodegenInvoke
  public final TypeInfo readTypeInfo(ReadContext readContext, TypeInfo typeInfoCache) {
    MemoryBuffer buffer = readContext.getBuffer();
    int typeId = buffer.readUInt8();
    TypeInfo typeInfo;
    switch (typeId) {
      case Types.ENUM:
      case Types.STRUCT:
      case Types.EXT:
      case Types.TYPED_UNION:
        typeInfo = Objects.requireNonNull(userTypeIdToTypeInfo.get(buffer.readVarUInt32()));
        break;
      case Types.COMPATIBLE_STRUCT:
      case Types.NAMED_COMPATIBLE_STRUCT:
        typeInfo = readSharedClassMeta(readContext);
        break;
      case Types.NAMED_ENUM:
      case Types.NAMED_STRUCT:
      case Types.NAMED_EXT:
      case Types.NAMED_UNION:
        if (!metaContextShareEnabled) {
          typeInfo = readTypeInfoFromBytes(readContext, typeInfoCache, typeId);
        } else {
          typeInfo = readSharedClassMeta(readContext);
        }
        break;
      case Types.LIST:
        typeInfo = readListTypeInfo(readContext);
        break;
      case Types.TIMESTAMP:
        typeInfo = readTimestampTypeInfo(readContext);
        break;
      default:
        typeInfo = Objects.requireNonNull(getInternalTypeInfoByTypeId(typeId));
    }
    if (typeInfo.serializer == null) {
      typeInfo = ensureSerializerForTypeInfo(typeInfo);
    }
    return typeInfo;
  }

  /**
   * Read class info from buffer with TypeInfoHolder cache. This version updates the classInfoHolder
   * if the cache doesn't hit, allowing callers to maintain the cache across calls.
   *
   * @param classInfoHolder holder containing cache, will be updated on cache miss
   * @return the TypeInfo read from buffer
   */
  @CodegenInvoke
  public final TypeInfo readTypeInfo(ReadContext readContext, TypeInfoHolder classInfoHolder) {
    MemoryBuffer buffer = readContext.getBuffer();
    int typeId = buffer.readUInt8();
    TypeInfo typeInfo;
    boolean updateCache = false;
    switch (typeId) {
      case Types.ENUM:
      case Types.STRUCT:
      case Types.EXT:
      case Types.TYPED_UNION:
        typeInfo = Objects.requireNonNull(userTypeIdToTypeInfo.get(buffer.readVarUInt32()));
        break;
      case Types.COMPATIBLE_STRUCT:
      case Types.NAMED_COMPATIBLE_STRUCT:
        typeInfo = readSharedClassMeta(readContext);
        break;
      case Types.NAMED_ENUM:
      case Types.NAMED_STRUCT:
      case Types.NAMED_EXT:
      case Types.NAMED_UNION:
        if (!metaContextShareEnabled) {
          typeInfo = readTypeInfoFromBytes(readContext, classInfoHolder.typeInfo, typeId);
          updateCache = true;
        } else {
          typeInfo = readSharedClassMeta(readContext);
        }
        break;
      case Types.LIST:
        typeInfo = readListTypeInfo(readContext);
        break;
      case Types.TIMESTAMP:
        typeInfo = readTimestampTypeInfo(readContext);
        break;
      default:
        typeInfo = Objects.requireNonNull(getInternalTypeInfoByTypeId(typeId));
    }
    if (typeInfo.serializer == null) {
      typeInfo = ensureSerializerForTypeInfo(typeInfo);
    }
    if (updateCache) {
      classInfoHolder.typeInfo = typeInfo;
    }
    return typeInfo;
  }

  /**
   * Read class info using the provided cache. Returns cached TypeInfo if the namespace and type
   * name bytes match.
   */
  protected final TypeInfo readTypeInfoByCache(
      ReadContext readContext, TypeInfo typeInfoCache, int header) {
    return readTypeInfoFromBytes(readContext, typeInfoCache, header);
  }

  /**
   * Read class info from bytes with cache optimization. Uses the cached namespace and type name
   * bytes to avoid map lookups when the class is the same as the cached one.
   */
  protected final TypeInfo readTypeInfoFromBytes(
      ReadContext readContext, TypeInfo typeInfoCache, int header) {
    MemoryBuffer buffer = readContext.getBuffer();
    EncodedMetaString typeNameBytesCache = typeInfoCache != null ? typeInfoCache.typeName : null;
    EncodedMetaString namespaceBytes;
    EncodedMetaString simpleClassNameBytes;

    MetaStringReader metaStringReader = readContext.getMetaStringReader();
    if (typeNameBytesCache != null) {
      // Use cache for faster comparison
      EncodedMetaString packageNameBytesCache = typeInfoCache.namespace;
      namespaceBytes = metaStringReader.readMetaString(buffer, packageNameBytesCache);
      assert packageNameBytesCache != null;
      simpleClassNameBytes = metaStringReader.readMetaString(buffer, typeNameBytesCache);

      // MetaStringReader returns the provided cache object only when the wire identity matches. For
      // big meta strings, metadata-hash validation happens before the entry is first cached.
      if (typeNameBytesCache == simpleClassNameBytes && packageNameBytesCache == namespaceBytes) {
        return typeInfoCache;
      }
    } else {
      // No cache available, read fresh
      namespaceBytes = metaStringReader.readMetaString(buffer);
      simpleClassNameBytes = metaStringReader.readMetaString(buffer);
    }

    // Load class info from bytes (subclass-specific).
    return loadBytesToTypeInfo(header, namespaceBytes, simpleClassNameBytes);
  }

  /**
   * Reads shared class metadata from buffer. This is the shared implementation used by both
   * ClassResolver and XtypeResolver.
   */
  protected final TypeInfo readSharedClassMeta(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    MetaReadContext metaReadContext = readContext.getMetaReadContext();
    assert metaReadContext != null : SET_META_READ_CONTEXT_MSG;
    int indexMarker = buffer.readVarUInt32Small14();
    boolean isRef = (indexMarker & 1) == 1;
    int index = indexMarker >>> 1;
    TypeInfo typeInfo;
    if (isRef) {
      // Reference to previously read type in this stream
      typeInfo = getMetaReadTypeInfo(metaReadContext, index);
    } else {
      // New type in stream, with optimized reuse by validated TypeDef header. A header-cache
      // hit intentionally skips the body without rehashing: entries are published only after the
      // TypeDef body has parsed successfully and matched the 52-bit metadata hash.
      long id = buffer.readInt64();
      typeInfo = extRegistry.typeInfoByTypeDefId.get(id);
      if (typeInfo != null) {
        TypeDef.skipTypeDef(buffer, id);
      } else {
        TypeDef typeDef = sharedRegistry.typeDefById.get(id);
        if (typeDef != null) {
          TypeDef.skipTypeDef(buffer, id);
        } else {
          typeDef = readTypeDef(buffer, id);
        }
        typeInfo = buildMetaSharedTypeInfo(typeDef);
      }
      // index == readTypeInfos.size() since types are written sequentially
      metaReadContext.readTypeInfos.add(typeInfo);
    }
    return typeInfo;
  }

  public final TypeInfo readSharedClassMeta(ReadContext readContext, Class<?> targetClass) {
    TypeInfo typeInfo = readSharedClassMeta(readContext);
    Class<?> readClass = typeInfo.getType();
    // replace target class if needed
    if (targetClass != readClass) {
      return getTargetTypeInfo(typeInfo, targetClass);
    }
    return typeInfo;
  }

  private static TypeInfo getMetaReadTypeInfo(MetaReadContext metaReadContext, int index) {
    if (index < 0 || index >= metaReadContext.readTypeInfos.size) {
      throw new ForyException("Invalid class metadata reference id " + index);
    }
    TypeInfo typeInfo = metaReadContext.readTypeInfos.get(index);
    if (typeInfo == null) {
      throw new ForyException("Invalid class metadata reference id " + index);
    }
    return typeInfo;
  }

  private TypeInfo getTargetTypeInfo(TypeInfo typeInfo, Class<?> targetClass) {
    TransformedTypeInfo[] infos = extRegistry.transformedTypeInfo.get(targetClass);
    Class<?> readClass = typeInfo.getType();
    long typeDefId = transformCacheTypeDefId(typeInfo);
    if (infos != null) {
      // It's ok to use loop here since most of case the array size will be 1.
      for (TransformedTypeInfo info : infos) {
        if (info.readClass == readClass && info.typeDefId == typeDefId) {
          return info.typeInfo;
        }
      }
    }
    return transformTypeInfo(typeInfo, targetClass, typeDefId);
  }

  private static long transformCacheTypeDefId(TypeInfo typeInfo) {
    TypeDef typeDef = typeInfo.getTypeDef();
    return typeDef == null ? 0 : typeDef.getId();
  }

  private TypeInfo transformTypeInfo(TypeInfo typeInfo, Class<?> targetClass, long typeDefId) {
    Class<?> readClass = typeInfo.getType();
    TypeInfo newTypeInfo;
    if (targetClass.isAssignableFrom(readClass)) {
      newTypeInfo = typeInfo;
    } else {
      // similar to create serializer for `UnknownStruct`
      newTypeInfo =
          getMetaSharedTypeInfo(
              typeInfo.typeDef.replaceRootClassTo(this, targetClass), targetClass);
    }
    TransformedTypeInfo[] infos = extRegistry.transformedTypeInfo.get(targetClass);
    int size = infos == null ? 0 : infos.length;
    if (size >= MAX_CACHED_TYPE_DEFS) {
      return newTypeInfo;
    }
    TransformedTypeInfo[] newInfos = new TransformedTypeInfo[size + 1];
    if (size > 0) {
      System.arraycopy(infos, 0, newInfos, 0, size);
    }
    newInfos[size] = new TransformedTypeInfo(readClass, typeDefId, newTypeInfo);
    extRegistry.transformedTypeInfo.put(targetClass, newInfos);
    return newTypeInfo;
  }

  /**
   * Load class info from namespace and type name bytes. Subclasses implement this to resolve the
   * class and create/lookup TypeInfo.
   *
   * <p>Note: This method should NOT create serializers. It's used by both readTypeInfo (which needs
   * serializers) and readClassInternal (which doesn't need serializers). Use {@link
   * #ensureSerializerForTypeInfo} after calling this if a serializer is needed.
   */
  protected abstract TypeInfo loadBytesToTypeInfo(
      EncodedMetaString namespaceBytes, EncodedMetaString simpleClassNameBytes);

  /**
   * Load class info from namespace and type name bytes, with the type id from the stream.
   * Subclasses can override this to use the incoming type id (e.g. NAMED_EXT/NAMED_ENUM).
   */
  protected TypeInfo loadBytesToTypeInfo(
      int typeId, EncodedMetaString namespaceBytes, EncodedMetaString simpleClassNameBytes) {
    return loadBytesToTypeInfo(namespaceBytes, simpleClassNameBytes);
  }

  /**
   * Ensure the TypeInfo has a serializer set. Called after loading class info for deserialization.
   * If the class is abstract/interface or can't be serialized, this may throw an exception.
   *
   * @param typeInfo the class info to ensure has a serializer
   * @return the TypeInfo with serializer set (may be the same instance or a different one)
   */
  protected abstract TypeInfo ensureSerializerForTypeInfo(TypeInfo typeInfo);

  protected TypeInfo readListTypeInfo(ReadContext readContext) {
    return getInternalTypeInfoByTypeId(Types.LIST);
  }

  protected TypeInfo readTimestampTypeInfo(ReadContext readContext) {
    return getInternalTypeInfoByTypeId(Types.TIMESTAMP);
  }

  protected final TypeInfo getInternalTypeInfoByTypeId(int typeId) {
    if (typeId < 0 || typeId >= typeIdToTypeInfo.length) {
      return null;
    }
    return typeIdToTypeInfo[typeId];
  }

  protected void updateTypeInfo(Class<?> cls, TypeInfo typeInfo) {
    // make `extRegistry.registeredClassIdMap` and `classInfoMap` share same classInfo
    // instances.
    classInfoMap.put(cls, typeInfo);
    if (typeInfo.userTypeId != INVALID_USER_TYPE_ID) {
      // serializer will be set lazily in `addSerializer` method if it's null.
      putUserTypeInfo(typeInfo.userTypeId, typeInfo);
    } else {
      if (Types.isUserDefinedType((byte) typeInfo.typeId)) {
        return;
      }
      putInternalTypeInfo(typeInfo.typeId, typeInfo);
    }
  }

  protected final void putInternalTypeInfo(int typeId, TypeInfo typeInfo) {
    typeIdToTypeInfo[typeId] = typeInfo;
  }

  protected final void putUserTypeInfo(int userId, TypeInfo typeInfo) {
    userTypeIdToTypeInfo.put(userId, typeInfo);
  }

  protected final boolean containsUserTypeId(int userId) {
    return userTypeIdToTypeInfo.containsKey(userId);
  }

  final TypeInfo buildMetaSharedTypeInfo(TypeDef typeDef) {
    TypeInfo typeInfo = extRegistry.typeInfoByTypeDefId.get(typeDef.getId());
    if (typeInfo != null) {
      return typeInfo;
    }
    Class<?> cls = loadClass(typeDef.getClassSpec());
    // A wire TypeDef may create a compatible serializer; admit the class before caching it by id.
    checkClassForDeserialization(cls);
    if (!typeDef.isStructSchemaKind()
        && !UnknownClass.class.isAssignableFrom(TypeUtils.getComponentIfArray(cls))) {
      typeInfo = getTypeInfo(cls);
    } else if (ClassResolver.useReplaceResolveSerializer(cls)) {
      // For classes with writeReplace/readResolve, use their natural serializer
      // (ReplaceResolveSerializer) instead of CompatibleSerializer
      typeInfo = getTypeInfo(cls);
    } else {
      typeInfo = getMetaSharedTypeInfo(typeDef, cls);
    }
    if (extRegistry.typeInfoByTypeDefId.size < MAX_CACHED_TYPE_DEFS) {
      extRegistry.typeInfoByTypeDefId.put(typeDef.getId(), typeInfo);
    }
    return typeInfo;
  }

  // TODO(chaokunyang) if TypeDef is consistent with class in this process,
  //  use existing serializer instead.
  private TypeInfo getMetaSharedTypeInfo(TypeDef typeDef, Class<?> clz) {
    if (clz == UnknownEmptyStruct.class) {
      clz = UnknownStruct.class;
    }
    Class<?> cls = clz;
    int streamTypeId = typeDef.getClassSpec().typeId;
    if (Types.isExtType(streamTypeId) || Types.isUnionType(streamTypeId) || cls.isEnum()) {
      TypeInfo localTypeInfo = getTypeInfo(cls);
      if ((Types.isExtType(streamTypeId) && !Types.isExtType(localTypeInfo.typeId))
          || (Types.isUnionType(streamTypeId) && !Types.isUnionType(localTypeInfo.typeId))) {
        throw new SerializerUnregisteredException(typeDef.getClassName());
      }
      return localTypeInfo;
    }
    Integer classId = extRegistry.registeredClassIdMap.get(cls);
    int typeId;
    if (classId != null) {
      TypeInfo registeredInfo = classInfoMap.get(cls);
      if (registeredInfo == null) {
        registeredInfo = getTypeInfo(cls);
      }
      typeId = registeredInfo.typeId;
    } else {
      TypeInfo cachedInfo = classInfoMap.get(cls);
      if (cachedInfo != null) {
        typeId = cachedInfo.typeId;
      } else {
        typeId = buildUnregisteredTypeId(cls, null);
      }
    }
    TypeInfo typeInfo = new TypeInfo(this, cls, null, typeId, typeDef.getClassSpec().userTypeId);
    typeInfo.typeDef = typeDef;
    if (UnknownClass.class.isAssignableFrom(TypeUtils.getComponentIfArray(cls))) {
      if (cls == UnknownStruct.class) {
        typeInfo.setSerializer(
            this, new UnknownClassSerializers.UnknownStructSerializer(this, typeDef));
        // Ensure UnknownStruct is registered so writeTypeInfo emits a placeholder typeId
        // that UnknownStructSerializer can rewrite to the original typeId.
        if (!isCrossLanguage()) {
          Preconditions.checkNotNull(classId);
        }
      } else {
        typeInfo.serializer =
            UnknownClassSerializers.getSerializer(this, typeDef.getClassName(), cls);
      }
      return typeInfo;
    }
    if (clz.isArray() || cls.isEnum()) {
      return getTypeInfo(cls);
    }
    if (ReflectionUtils.isAbstract(cls) || cls.isInterface()) {
      // Compatible serializers allocate their root type during read. A meta-share TypeDef may name
      // an abstract declared field type, but the actual value must be read through concrete runtime
      // type metadata or a concrete target-class transformation.
      return typeInfo;
    }
    StaticGeneratedStructSerializer<?> copiedStaticSerializer =
        copyRegisteredStaticGeneratedStructSerializer(cls, typeDef);
    if (copiedStaticSerializer != null) {
      typeInfo.setSerializer(this, copiedStaticSerializer);
      return typeInfo;
    }
    Class<? extends Serializer> sc =
        getCompatibleDeserializerClassFromGraalvmRegistry(cls, typeDef);
    if (sc == null) {
      if (GraalvmSupport.isGraalBuildTime() && config.isCodeGenEnabled()) {
        sc = loadGraalvmCompatibleDeserializerClass(cls, typeDef);
      } else if (AndroidSupport.IS_ANDROID
          || !config.isCodeGenEnabled()
          || shouldPreferStaticGeneratedSerializer(cls)) {
        sc = getStaticGeneratedStructSerializerClass(cls);
      }
      if (sc == null && requiresStaticGeneratedSerializer(cls)) {
        throw missingStaticGeneratedSerializer(cls);
      }
      if (sc == null && AndroidSupport.IS_ANDROID) {
        sc = CompatibleSerializer.class;
      } else if (sc == null && GraalvmSupport.isGraalRuntime()) {
        sc = CompatibleSerializer.class;
        LOG.warn(
            "Can't generate class at runtime in graalvm for class def {}, use {} instead",
            typeDef,
            sc);
      } else if (sc == null && config.isCodeGenEnabled()) {
        sc =
            jitContext.registerSerializerJITCallback(
                () -> CompatibleSerializer.class,
                () -> CodecUtils.loadOrGenCompatibleCodecClass(this, cls, typeDef),
                c -> typeInfo.setSerializer(this, Serializers.newSerializer(this, cls, c)));
      } else if (sc == null) {
        sc = CompatibleSerializer.class;
      }
    }
    if (GraalvmSupport.isGraalBuildTime()
        && GeneratedCompatibleSerializer.class.isAssignableFrom(sc)) {
      getGraalvmClassRegistry().putIfAbsentDeserializerClass(typeDef.getId(), sc);
      typeInfo.setSerializer(this, new CompatibleSerializer(this, cls, typeDef));
      return typeInfo;
    }
    if (GraalvmSupport.isGraalBuildTime()
        && GeneratedStaticCompatibleSerializer.class.isAssignableFrom(sc)) {
      getGraalvmClassRegistry().putCompatibleDeserializerClass(cls, sc);
      typeInfo.setSerializer(this, new CompatibleSerializer(this, cls, typeDef));
      return typeInfo;
    }
    if (StaticGeneratedStructSerializer.class.isAssignableFrom(sc)) {
      typeInfo.setSerializer(this, newStaticGeneratedStructSerializer(sc, cls, typeDef));
    } else if (sc == CompatibleSerializer.class) {
      typeInfo.setSerializer(this, new CompatibleSerializer(this, cls, typeDef));
    } else {
      typeInfo.setSerializer(this, Serializers.newSerializer(this, cls, sc));
    }
    return typeInfo;
  }

  private Class<? extends Serializer> loadGraalvmCompatibleDeserializerClass(
      Class<?> cls, TypeDef typeDef) {
    if (typeDef.getId() == TypeDef.buildTypeDef(this, cls).getId()) {
      return CodecUtils.loadOrGenCompatibleCodecClass(this, cls, typeDef);
    }
    return CodecUtils.loadOrGenStaticCompatibleCodecClass(this, cls, typeDef);
  }

  protected int buildUnregisteredTypeId(Class<?> cls, Serializer<?> serializer) {
    if (cls.isEnum()) {
      return Types.NAMED_ENUM;
    }
    if (serializer != null && !isStructSerializer(serializer)) {
      return Types.NAMED_EXT;
    }
    if (useStructEvolution(cls, isCompatible() && metaContextShareEnabled)) {
      return Types.NAMED_COMPATIBLE_STRUCT;
    }
    return Types.NAMED_STRUCT;
  }

  protected boolean useStructEvolution(Class<?> cls, boolean inheritedEvolutionEnabled) {
    Evolution evolution = getStructEvolution(cls);
    if (evolution == Evolution.DISABLED) {
      return false;
    }
    if (inheritedEvolutionEnabled) {
      return true;
    }
    if (evolution == Evolution.ENABLED) {
      throw new IllegalStateException(
          String.format(
              "Class %s is annotated with @ForyStruct(evolution = ENABLED), but this Fory "
                  + "instance is not configured to write schema evolution metadata",
              cls.getName()));
    }
    return false;
  }

  private Evolution getStructEvolution(Class<?> cls) {
    if (cls == null) {
      return Evolution.INHERIT;
    }
    ForyStruct annotation = cls.getAnnotation(ForyStruct.class);
    if (annotation == null) {
      return Evolution.INHERIT;
    }
    if (annotation.evolution() != Evolution.INHERIT) {
      return annotation.evolution();
    }
    return annotation.evolving() ? Evolution.INHERIT : Evolution.DISABLED;
  }

  protected static boolean isStructSerializer(Serializer<?> serializer) {
    return serializer instanceof GeneratedObjectSerializer
        || serializer instanceof GeneratedCompatibleSerializer
        || serializer instanceof LazyInitBeanSerializer
        || serializer instanceof ObjectSerializer
        || serializer instanceof CompatibleSerializer
        || serializer instanceof StaticGeneratedStructSerializer;
  }

  protected static boolean isStructSerializerClass(Class<? extends Serializer> serializerClass) {
    return GeneratedObjectSerializer.class.isAssignableFrom(serializerClass)
        || GeneratedCompatibleSerializer.class.isAssignableFrom(serializerClass)
        || LazyInitBeanSerializer.class.isAssignableFrom(serializerClass)
        || ObjectSerializer.class.isAssignableFrom(serializerClass)
        || CompatibleSerializer.class.isAssignableFrom(serializerClass)
        || StaticGeneratedStructSerializer.class.isAssignableFrom(serializerClass);
  }

  protected TypeDef readTypeDef(MemoryBuffer buffer, long header) {
    TypeDef readTypeDef = TypeDef.readTypeDef(this, buffer, header);
    return cacheTypeDef(readTypeDef);
  }

  final Class<?> loadClass(ClassSpec classSpec) {
    if (classSpec.type != null) {
      return classSpec.type;
    }
    return loadClass(classSpec.entireClassName, classSpec.isEnum, classSpec.dimension);
  }

  final Class<?> loadClass(String className, boolean isEnum, int arrayDims) {
    return loadClass(className, isEnum, arrayDims, config.deserializeUnknownClass());
  }

  final Class<?> loadClass(String className) {
    return loadClass(className, false, -1, false);
  }

  final Class<?> loadClass(
      String className, boolean isEnum, int arrayDims, boolean deserializeUnknownClass) {
    if (!extRegistry.typeChecker.checkType(this, className)) {
      throw new InsecureException(
          String.format("Class %s is forbidden for serialization.", className));
    }
    Class<?> cls = extRegistry.registeredClasses.get(className);
    if (cls != null) {
      return cls;
    }
    try {
      return Class.forName(className, false, extRegistry.classLoader);
    } catch (ClassNotFoundException e) {
      try {
        return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
      } catch (ClassNotFoundException ex) {
        String msg =
            String.format(
                "Class %s not found from classloaders [%s, %s]",
                className, extRegistry.classLoader, Thread.currentThread().getContextClassLoader());
        if (deserializeUnknownClass) {
          LOG.warn(msg);
          return UnknownClass.getUnknowClass(className, isEnum, arrayDims, metaContextShareEnabled);
        }
        throw new IllegalStateException(msg, ex);
      }
    }
  }

  public abstract <T> Serializer<T> getSerializer(Class<T> cls);

  public final Serializer<?> getSerializer(TypeRef<?> typeRef) {
    Class<?> rawType = typeRef.getRawType();
    TypeExtMeta typeExtMeta = typeRef.getTypeExtMeta();
    if (typeExtMeta != null
        && typeExtMeta.typeId() != Types.UNKNOWN
        && !Types.isUserDefinedType(typeExtMeta.typeId())) {
      if (isCrossLanguage()) {
        TypeInfo typeInfo = getInternalTypeInfoByTypeId(typeExtMeta.typeId());
        if (typeInfo != null && typeInfo.getSerializer() != null) {
          return typeInfo.getSerializer();
        }
      } else {
        Serializer<?> serializer = getNativeTypedValueSerializer(typeExtMeta.typeId(), rawType);
        if (serializer != null) {
          return serializer;
        }
      }
    }
    return getSerializer(rawType);
  }

  private Serializer<?> getNativeTypedValueSerializer(int typeId, Class<?> rawType) {
    // Native TypeExtMeta on a field wrapper is schema metadata, but on primitive-list elements it
    // is the element wire type. These type ids are shared by native and xlang modes; wider built-in
    // ids such as DECIMAL/BINARY are intentionally left to the native declared/raw type serializer.
    switch (typeId) {
      case Types.BOOL:
      case Types.STRING:
        return getSerializer(rawType);
      case Types.INT8:
        return new PrimitiveSerializers.ByteSerializer(config, rawType);
      case Types.UINT8:
        return new PrimitiveSerializers.UInt8Serializer(config);
      case Types.INT16:
        return new PrimitiveSerializers.ShortSerializer(config, rawType);
      case Types.UINT16:
        return new PrimitiveSerializers.UInt16Serializer(config);
      case Types.INT32:
        return new PrimitiveSerializers.FixedInt32Serializer(config);
      case Types.VARINT32:
        return new PrimitiveSerializers.VarInt32Serializer(config);
      case Types.UINT32:
        return new PrimitiveSerializers.FixedUInt32Serializer(config);
      case Types.VAR_UINT32:
        return new PrimitiveSerializers.VarUInt32Serializer(config);
      case Types.INT64:
        return new PrimitiveSerializers.FixedInt64Serializer(config);
      case Types.VARINT64:
        return new PrimitiveSerializers.VarInt64Serializer(config);
      case Types.TAGGED_INT64:
        return new PrimitiveSerializers.TaggedInt64Serializer(config);
      case Types.UINT64:
        return new PrimitiveSerializers.FixedUInt64Serializer(config);
      case Types.VAR_UINT64:
        return new PrimitiveSerializers.VarUInt64Serializer(config);
      case Types.TAGGED_UINT64:
        return new PrimitiveSerializers.TaggedUInt64Serializer(config);
      case Types.FLOAT16:
        return new PrimitiveSerializers.Float16Serializer(config, rawType);
      case Types.BFLOAT16:
        return new PrimitiveSerializers.BFloat16Serializer(config, rawType);
      case Types.FLOAT32:
        return new PrimitiveSerializers.FloatSerializer(config, rawType);
      case Types.FLOAT64:
        return new PrimitiveSerializers.DoubleSerializer(config, rawType);
      default:
        return null;
    }
  }

  public abstract Serializer<?> getRawSerializer(Class<?> cls);

  public abstract <T> void setSerializer(Class<T> cls, Serializer<T> serializer);

  public abstract <T> void setSerializerIfAbsent(Class<T> cls, Serializer<T> serializer);

  /**
   * Reset serializer if {@code serializer} is not null, otherwise clear serializer for {@code cls}.
   */
  public <T> void resetSerializer(Class<T> cls, Serializer<T> serializer) {
    if (serializer == null) {
      TypeInfo typeInfo = getTypeInfo(cls, false);
      if (typeInfo != null) {
        typeInfo.setSerializer(this, null);
      }
    } else {
      setSerializer(cls, serializer);
    }
  }

  public final TypeInfo getTypeInfoByTypeId(int typeId) {
    if (Types.isUserDefinedType((byte) typeId)) {
      throw new IllegalArgumentException(
          "User type id must be provided to resolve user-defined type " + typeId);
    }
    return getInternalTypeInfoByTypeId(typeId);
  }

  public final Serializer<?> getSerializerByTypeId(int typeId) {
    return getTypeInfoByTypeId(typeId).getSerializer();
  }

  public final TypeInfo nilTypeInfo() {
    return NIL_TYPE_INFO;
  }

  public final TypeInfoHolder nilTypeInfoHolder() {
    return new TypeInfoHolder(NIL_TYPE_INFO);
  }

  public final GenericType buildGenericType(TypeRef<?> typeRef) {
    return GenericType.build(
        typeRef,
        t -> {
          if (t.getClass() == Class.class) {
            return isMonomorphic((Class<?>) t);
          } else {
            return isMonomorphic(TypeUtils.getRawType(t));
          }
        });
  }

  public final GenericType buildGenericType(Type type) {
    GenericType genericType = extRegistry.genericTypes.get(type);
    if (genericType != null) {
      return genericType;
    }
    GenericType newGenericType =
        GenericType.build(
            type,
            t -> {
              if (t.getClass() == Class.class) {
                return isMonomorphic((Class<?>) t);
              } else {
                return isMonomorphic(TypeUtils.getRawType(t));
              }
            });
    extRegistry.genericTypes.put(type, newGenericType);
    return newGenericType;
  }

  @CodegenInvoke
  public GenericType getGenericTypeInStruct(Class<?> cls, String genericTypeStr) {
    Map<String, GenericType> map =
        extRegistry.classGenericTypes.computeIfAbsent(cls, this::buildGenericMap);
    return map.getOrDefault(genericTypeStr, OBJECT_GENERIC_TYPE);
  }

  public abstract void initialize();

  public abstract void ensureSerializersCompiled();

  public final TypeDef getTypeDef(Class<?> cls, boolean resolveParent) {
    if (resolveParent) {
      return cacheTypeDef(typeDefMap.computeIfAbsent(cls, k -> TypeDef.buildTypeDef(this, cls)));
    }
    return cacheTypeDef(
        extRegistry.currentLayerTypeDef.computeIfAbsent(
            cls, key -> TypeDef.buildTypeDef(this, key, false)));
  }

  public final TypeDef cacheTypeDef(TypeDef typeDef) {
    return sharedRegistry.getOrCreateTypeDef(typeDef);
  }

  public final boolean isSerializable(Class<?> cls) {
    // Enums are always serializable, even if abstract (enums with abstract methods)
    if (cls.isEnum()) {
      return true;
    }
    if (ReflectionUtils.isAbstract(cls) || cls.isInterface()) {
      return false;
    }
    try {
      TypeInfo typeInfo = classInfoMap.get(cls);
      Serializer<?> serializer = null;
      if (typeInfo != null) {
        serializer = typeInfo.serializer;
      }
      getSerializerClass(cls, false);
      if (typeInfo != null && serializer == null) {
        typeInfo.serializer = null;
      }
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  public abstract Class<? extends Serializer> getSerializerClass(Class<?> cls);

  public abstract Class<? extends Serializer> getSerializerClass(Class<?> cls, boolean codegen);

  /**
   * Get the serializer class for object serialization with JIT support. This is used by both
   * ClassResolver and XtypeResolver for creating object serializers.
   */
  public Class<? extends Serializer> getObjectSerializerClass(
      Class<?> cls,
      boolean shareMeta,
      boolean codegen,
      JITContext.SerializerJITCallback<Class<? extends Serializer>> callback) {
    Class<? extends Serializer> staticGeneratedSerializer =
        getStaticGeneratedStructSerializerClass(cls);
    if (staticGeneratedSerializer != null && shouldPreferStaticGeneratedSerializer(cls)) {
      return staticGeneratedSerializer;
    }
    if (requiresStaticGeneratedSerializer(cls)) {
      throw missingStaticGeneratedSerializer(cls);
    }
    if (codegen) {
      if (extRegistry.getClassCtx.contains(cls)) {
        // avoid potential recursive call for seq codec generation.
        return CodegenSerializer.LazyInitBeanSerializer.class;
      } else {
        try {
          extRegistry.getClassCtx.add(cls);
          return jitContext.registerSerializerJITCallback(
              () -> ObjectSerializer.class,
              () -> CodegenSerializer.loadCodegenSerializer(this, cls),
              callback);
        } finally {
          extRegistry.getClassCtx.remove(cls);
        }
      }
    } else {
      return staticGeneratedSerializer == null ? ObjectSerializer.class : staticGeneratedSerializer;
    }
  }

  public final boolean isCollection(Class<?> cls) {
    if (TypeUtils.isPrimitiveListClass(cls)) {
      return false;
    }
    if (Collection.class.isAssignableFrom(cls)) {
      return true;
    }
    if (ScalaTypes.SCALA_AVAILABLE) {
      // Scala map is scala iterable too.
      if (ScalaTypes.getScalaMapType().isAssignableFrom(cls)) {
        return false;
      }
      return ScalaTypes.getScalaIterableType().isAssignableFrom(cls);
    } else {
      return false;
    }
  }

  public final boolean isSet(Class<?> cls) {
    if (Set.class.isAssignableFrom(cls)) {
      return true;
    }
    if (ScalaTypes.SCALA_AVAILABLE) {
      // Scala map is scala iterable too.
      if (ScalaTypes.getScalaMapType().isAssignableFrom(cls)) {
        return false;
      }
      return ScalaTypes.getScalaSetType().isAssignableFrom(cls);
    } else {
      return false;
    }
  }

  public final boolean isMap(Class<?> cls) {
    if (cls == UnknownStruct.class) {
      return false;
    }
    return Map.class.isAssignableFrom(cls)
        || (ScalaTypes.SCALA_AVAILABLE && ScalaTypes.getScalaMapType().isAssignableFrom(cls));
  }

  @Internal
  public final DescriptorGrouper createDescriptorGrouper(TypeDef typeDef, Class<?> cls) {
    return createDescriptorGrouper(typeDef, cls, false, null);
  }

  @Internal
  public final DescriptorGrouper createDescriptorGrouper(
      TypeDef typeDef,
      Class<?> cls,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator) {
    return sharedRegistry.getOrCreateTypeDefDescriptorGrouper(
        typeDef,
        cls,
        descriptorsGroupedOrdered,
        descriptorUpdator,
        () ->
            buildDescriptorGrouper(
                typeDef.getDescriptors(this, cls), descriptorsGroupedOrdered, descriptorUpdator));
  }

  @Internal
  public final DescriptorGrouper getFieldDescriptorGrouper(
      Class<?> clz, boolean searchParent, boolean descriptorsGroupedOrdered) {
    return getFieldDescriptorGrouper(clz, searchParent, descriptorsGroupedOrdered, null);
  }

  @Internal
  public final DescriptorGrouper getFieldDescriptorGrouper(
      Class<?> clz,
      boolean searchParent,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator) {
    return sharedRegistry.getOrCreateFieldDescriptorGrouper(
        clz,
        searchParent,
        descriptorsGroupedOrdered,
        descriptorUpdator,
        () ->
            buildDescriptorGrouper(
                getFieldDescriptors(clz, searchParent),
                descriptorsGroupedOrdered,
                descriptorUpdator));
  }

  @Internal
  public List<Descriptor> getFieldDescriptors(Class<?> clz, boolean searchParent) {
    return sharedRegistry.getOrCreateFieldDescriptors(
        clz, searchParent, () -> buildFieldDescriptors(clz, searchParent));
  }

  private DescriptorGrouper buildDescriptorGrouper(
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator) {
    return configureDescriptorGrouper(
            DescriptorGrouper.createDescriptorGrouper(
                this::usesPrimitiveFieldOrdering,
                this::isBuildIn,
                this::isCollectionDescriptor,
                this::isMapDescriptor,
                descriptors,
                descriptorsGroupedOrdered,
                descriptorUpdator,
                getPrimitiveComparator(),
                getDescriptorComparator()))
        .sort();
  }

  @Internal
  public final DescriptorGrouper groupDescriptors(
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator) {
    return buildDescriptorGrouper(descriptors, descriptorsGroupedOrdered, descriptorUpdator);
  }

  private List<Descriptor> buildFieldDescriptors(Class<?> clz, boolean searchParent) {
    List<Descriptor> registeredStaticDescriptors =
        getRegisteredStaticGeneratedStructDescriptors(clz);
    if (registeredStaticDescriptors != null) {
      return normalizeFieldDescriptors(clz, searchParent, registeredStaticDescriptors);
    }
    if (shouldPreferStaticGeneratedSerializer(clz)) {
      List<Descriptor> staticDescriptors = getStaticGeneratedStructDescriptors(clz);
      if (staticDescriptors != null) {
        return normalizeFieldDescriptors(clz, searchParent, staticDescriptors);
      }
    }
    if (requiresStaticGeneratedSerializer(clz)) {
      throw missingStaticGeneratedSerializer(clz);
    }
    SortedMap<Member, Descriptor> allDescriptors = getAllDescriptorsMap(clz, searchParent);
    List<Descriptor> descriptors = new ArrayList<>(allDescriptors.size());
    for (Map.Entry<Member, Descriptor> entry : allDescriptors.entrySet()) {
      Member member = entry.getKey();
      if (member instanceof Field) {
        descriptors.add(entry.getValue());
      }
    }
    return buildFieldDescriptors(clz, searchParent, descriptors);
  }

  private List<Descriptor> buildFieldDescriptors(
      Class<?> clz, boolean searchParent, List<Descriptor> descriptors) {
    List<Descriptor> result = new ArrayList<>(descriptors.size());
    boolean globalRefTracking = trackingRef();
    boolean isXlang = isCrossLanguage();

    for (Descriptor descriptor : descriptors) {
      if (!searchParent && !descriptor.getDeclaringClass().equals(clz.getName())) {
        continue;
      }
      // Compute the final isTrackingRef value:
      // For xlang mode: reference tracking is disabled by default and only @Ref enables it.
      // For Java mode: @Ref explicitly overrides the type-based default in both directions.
      boolean ref = globalRefTracking;
      if (globalRefTracking) {
        if (isXlang) {
          ref = descriptor.isTrackingRef();
        } else {
          if (descriptor.hasTrackingRefMetadata()) {
            ref = descriptor.isTrackingRef();
          } else {
            ref = needToWriteRef(TypeRef.of(descriptor.getRawType()));
          }
        }
      }
      boolean nullable = isFieldNullable(descriptor);
      boolean needsUpdate =
          ref != descriptor.isTrackingRef() || nullable != descriptor.isNullable();

      if (needsUpdate) {
        Descriptor newDescriptor =
            new DescriptorBuilder(descriptor).inferredTrackingRef(ref).nullable(nullable).build();
        result.add(newDescriptor);
      } else {
        result.add(descriptor);
      }
    }
    return result;
  }

  @Internal
  public final List<Descriptor> normalizeFieldDescriptors(
      Class<?> clz, boolean searchParent, List<Descriptor> descriptors) {
    return buildFieldDescriptors(clz, searchParent, descriptors);
  }

  protected final Class<? extends Serializer> getStaticGeneratedStructSerializerClass(
      Class<?> cls) {
    if (GraalvmSupport.isGraalBuildTime() || GraalvmSupport.isGraalRuntime()) {
      return null;
    }
    Class<? extends StaticGeneratedStructSerializer> serializerClass =
        sharedRegistry.staticGeneratedSerializerRegistry.getSerializerClass(cls, isCrossLanguage());
    return serializerClass == null ? null : serializerClass.asSubclass(Serializer.class);
  }

  protected final StaticGeneratedStructSerializer<?> newStaticGeneratedStructSerializer(
      Class<? extends Serializer> serializerClass, Class<?> cls, TypeDef typeDef) {
    StaticGeneratedStructSerializer<?> serializer =
        sharedRegistry.staticGeneratedSerializerRegistry.newSerializer(
            this, cls, serializerClass.asSubclass(StaticGeneratedStructSerializer.class), typeDef);
    if (serializer != null) {
      return serializer;
    }
    if (requiresStaticGeneratedSerializer(cls) || shouldPreferStaticGeneratedSerializer(cls)) {
      throw new ForyException(
          "Static generated serializer for "
              + cls.getName()
              + " was required but "
              + serializerClass.getName()
              + " could not be constructed");
    }
    if (GeneratedStaticCompatibleSerializer.class.isAssignableFrom(serializerClass)) {
      return newRuntimeStaticCompatibleSerializer(serializerClass, cls, typeDef);
    }
    throw new ForyException(
        "Static generated serializer for "
            + cls.getName()
            + " resolved to "
            + serializerClass.getName()
            + " but could not be constructed");
  }

  private StaticGeneratedStructSerializer<?> newRuntimeStaticCompatibleSerializer(
      Class<? extends Serializer> serializerClass, Class<?> cls, TypeDef typeDef) {
    try {
      Constructor<? extends Serializer> constructor =
          serializerClass.getDeclaredConstructor(TypeResolver.class, Class.class, TypeDef.class);
      constructor.setAccessible(true);
      return (StaticGeneratedStructSerializer<?>) constructor.newInstance(this, cls, typeDef);
    } catch (ReflectiveOperationException e) {
      throw new ForyException(
          "Failed to create runtime static compatible serializer "
              + serializerClass.getName()
              + " for "
              + cls.getName(),
          e);
    }
  }

  protected final Serializer<?> newSerializer(
      Class<?> cls, Class<? extends Serializer> serializerClass) {
    if (StaticGeneratedStructSerializer.class.isAssignableFrom(serializerClass)) {
      return newStaticGeneratedStructSerializer(
          serializerClass, cls, isShareMeta() ? getTypeDef(cls, true) : null);
    }
    return Serializers.newSerializer(this, cls, serializerClass);
  }

  private List<Descriptor> getStaticGeneratedStructDescriptors(Class<?> cls) {
    if (GraalvmSupport.isGraalBuildTime() || GraalvmSupport.isGraalRuntime()) {
      return null;
    }
    return sharedRegistry.staticGeneratedSerializerRegistry.getGeneratedDescriptors(
        cls, isCrossLanguage());
  }

  private List<Descriptor> getRegisteredStaticGeneratedStructDescriptors(Class<?> cls) {
    TypeInfo typeInfo = getTypeInfo(cls, false);
    if (typeInfo == null
        || !(typeInfo.getSerializer() instanceof StaticGeneratedStructSerializer)) {
      return null;
    }
    return ((StaticGeneratedStructSerializer<?>) typeInfo.getSerializer())
        .getGeneratedDescriptors();
  }

  private StaticGeneratedStructSerializer<?> copyRegisteredStaticGeneratedStructSerializer(
      Class<?> cls, TypeDef typeDef) {
    TypeInfo typeInfo = getTypeInfo(cls, false);
    if (typeInfo == null
        || !(typeInfo.getSerializer() instanceof StaticGeneratedStructSerializer)) {
      return null;
    }
    return ((StaticGeneratedStructSerializer<?>) typeInfo.getSerializer())
        .copySerializer(this, cls, typeDef);
  }

  protected final boolean shouldPreferStaticGeneratedSerializer(Class<?> cls) {
    return AndroidSupport.IS_ANDROID || isKotlinClass(cls);
  }

  private boolean requiresStaticGeneratedSerializer(Class<?> cls) {
    // Kotlin xlang descriptor metadata is owned by KSP/static serializers. Java reflection cannot
    // recover Kotlin source nullability or nested @Ref metadata, including mutable IDL classes.
    return isCrossLanguage() && isKotlinClass(cls);
  }

  private ForyException missingStaticGeneratedSerializer(Class<?> cls) {
    return new ForyException(
        "Static generated serializer for Kotlin struct "
            + cls.getName()
            + " was required but "
            + StaticGeneratedSerializerRegistry.generatedSerializerBinaryName(
                cls, StaticGeneratedSerializerRegistry.Mode.XLANG)
            + " was not found. Ensure annotation processing or KSP ran and generated R8 rules "
            + "were packaged.");
  }

  private static boolean isKotlinClass(Class<?> cls) {
    for (java.lang.annotation.Annotation annotation : cls.getAnnotations()) {
      if (annotation.annotationType().getName().equals("kotlin.Metadata")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Gets the sort key for a field descriptor.
   *
   * <p>If the field has a {@link ForyField} annotation with id &gt;= 0, returns the id as text.
   * Otherwise, returns the snake_case field name. {@link #compareFieldSortKey} performs the
   * protocol comparison so tagged fields sort numerically before name-based fields.
   *
   * @param descriptor the field descriptor
   * @return the sort key text (tag ID as text or snake_case name)
   */
  protected static String getFieldSortKey(Descriptor descriptor) {
    if (descriptor.hasForyFieldId()) {
      return String.valueOf(descriptor.getForyFieldId());
    }
    String name = descriptor.getName();
    if (name != null && name.startsWith("$tag")) {
      String tagId = name.substring(4);
      if (!tagId.isEmpty()) {
        return tagId;
      }
    }
    return descriptor.getSnakeCaseName();
  }

  protected static int compareFieldSortKey(Descriptor d1, Descriptor d2) {
    Integer id1 = getFieldSortId(d1);
    Integer id2 = getFieldSortId(d2);
    int c;
    if (id1 != null && id2 != null) {
      c = Integer.compare(id1, id2);
    } else if (id1 != null) {
      c = -1;
    } else if (id2 != null) {
      c = 1;
    } else {
      c = getFieldSortKey(d1).compareTo(getFieldSortKey(d2));
    }
    if (c == 0) {
      // Field name duplicate in super/child classes.
      c = d1.getDeclaringClass().compareTo(d2.getDeclaringClass());
      if (c == 0) {
        // Final tie-breaker: use actual field name to distinguish fields with same tag ID.
        // This ensures Comparator contract is satisfied (returns 0 only for same object).
        c = d1.getName().compareTo(d2.getName());
      }
    }
    return c;
  }

  protected static boolean hasFieldSortId(Descriptor descriptor) {
    return getFieldSortId(descriptor) != null;
  }

  private static Integer getFieldSortId(Descriptor descriptor) {
    if (descriptor.hasForyFieldId()) {
      return descriptor.getForyFieldId();
    }
    String name = descriptor.getName();
    if (name != null && name.startsWith("$tag")) {
      String tagId = name.substring(4);
      if (!tagId.isEmpty()) {
        try {
          return Integer.parseInt(tagId);
        } catch (NumberFormatException ignored) {
          // Fall back to string sorting for non-numeric synthetic tag names.
        }
      }
    }
    return null;
  }

  /**
   * When compress disabled, sort primitive descriptors from largest to smallest, if size is the
   * same, sort by field name to fix order.
   *
   * <p>When compress enabled, sort primitive descriptors from largest to smallest but let compress
   * fields ends in tail. if size is the same, sort by field name to fix order.
   */
  public Comparator<Descriptor> getPrimitiveComparator() {
    return (d1, d2) -> {
      int typeId1 = Types.getDescriptorTypeId(this, d1);
      int typeId2 = Types.getDescriptorTypeId(this, d2);
      boolean t1Compress = Types.isCompressedType(typeId1);
      boolean t2Compress = Types.isCompressedType(typeId2);
      if ((t1Compress && t2Compress) || (!t1Compress && !t2Compress)) {
        int c = getPrimitiveFieldSize(d2) - getPrimitiveFieldSize(d1);
        if (c == 0) {
          c = typeId1 - typeId2;
          // noinspection Duplicates
          if (c == 0) {
            c = compareFieldSortKey(d1, d2);
            if (c == 0) {
              // Field name duplicate in super/child classes.
              c = d1.getDeclaringClass().compareTo(d2.getDeclaringClass());
              if (c == 0) {
                // Final tie-breaker: use actual field name to distinguish fields with same tag ID.
                // This ensures Comparator contract is satisfied (returns 0 only for same object).
                c = d1.getName().compareTo(d2.getName());
              }
            }
          }
          return c;
        }
        return c;
      }
      if (t1Compress) {
        return 1;
      }
      // t2 compress
      return -1;
    };
  }

  private int getPrimitiveFieldSize(Descriptor descriptor) {
    int typeId = Types.getDescriptorTypeId(this, descriptor);
    if (Types.isPrimitiveType(typeId)) {
      return Types.getPrimitiveTypeSize(typeId);
    }
    Class<?> rawType = descriptor.getRawType();
    if (TypeUtils.isPrimitive(rawType) || TypeUtils.isBoxed(rawType)) {
      return TypeUtils.getSizeOfPrimitiveType(TypeUtils.unwrap(rawType));
    }
    return Types.getPrimitiveTypeSize(typeId);
  }

  /**
   * Get the nullable flag for a field, respecting xlang mode.
   *
   * <p>For xlang mode (SERIALIZATION): use xlang defaults unless type-use metadata overrides:
   *
   * <ul>
   *   <li>If @Nullable is present on the field type: return true
   *   <li>Otherwise: return true only for Optional types, false for all other non-primitives
   * </ul>
   *
   * <p>Descriptors without a backing Java {@link Field} already carry schema-owned nullability, for
   * example TypeDef descriptors and static descriptors emitted by annotation processors or Scala
   * macro derivation.
   *
   * <p>For native reflected descriptors: value fields are nullable by default.
   *
   * <p>Important: this must match the TypeDef metadata for the same descriptor source. Xlang local
   * descriptors use xlang defaults, native reflected descriptors use native nullable-by-default
   * semantics, and descriptors rebuilt from TypeDef preserve the remote schema bit.
   */
  private boolean isFieldNullable(Descriptor descriptor) {
    Class<?> rawType = descriptor.getTypeRef().getRawType();
    if (rawType.isPrimitive()) {
      return false;
    }
    TypeExtMeta typeExtMeta = descriptor.getTypeRef().getTypeExtMeta();
    if (typeExtMeta != null) {
      return typeExtMeta.nullable();
    }
    if (descriptor.getField() == null) {
      return descriptor.isNullable();
    }
    if (isCrossLanguage()) {
      // For xlang mode: apply xlang defaults
      // This must match what TypeDefEncoder.buildFieldType uses for TypeDef metadata
      // Default for xlang: false for all non-primitives, except Optional types
      return TypeUtils.isOptionalType(rawType);
    }
    return descriptor.hasForyField() ? descriptor.isNullable() : true;
  }

  // thread safe
  private SortedMap<Member, Descriptor> getAllDescriptorsMap(Class<?> clz, boolean searchParent) {
    // when jit thread query this, it is already built by serialization main thread.
    return extRegistry.descriptorsCache.computeIfAbsent(
        Tuple2.of(clz, searchParent), t -> Descriptor.getAllDescriptorsMap(clz, searchParent));
  }

  /**
   * Build a map of nested generic type name to generic type for all fields in the class.
   *
   * @param cls the class to build the map of nested generic type name to generic type for all
   *     fields in the class
   * @return a map of nested generic type name to generic type for all fields in the class
   */
  protected final Map<String, GenericType> buildGenericMap(Class<?> cls) {
    Map<String, GenericType> map = new HashMap<>();
    Map<String, GenericType> map2 = new HashMap<>();
    for (Field field : ReflectionUtils.getFields(cls, true)) {
      TypeRef<?> typeRef = TypeUtils.getFieldTypeRef(field);
      GenericType genericType = buildGenericType(typeRef);
      TypeUtils.applyFieldRefTrackingOverride(genericType, field, trackingRef());
      buildGenericMap(map, genericType);
      buildGenericMap(map2, typeRef);
    }
    for (Map.Entry<String, GenericType> entry : map2.entrySet()) {
      map.putIfAbsent(entry.getKey(), entry.getValue());
    }
    return map;
  }

  private void buildGenericMap(Map<String, GenericType> map, TypeRef<?> typeRef) {
    String typeKey = typeRef.getTypeKey();
    if (map.containsKey(typeKey)) {
      return;
    }
    map.put(typeKey, buildGenericType(typeRef));
    Class<?> rawType = typeRef.getRawType();
    if (TypeUtils.isMap(rawType)) {
      Tuple2<TypeRef<?>, TypeRef<?>> kvTypes = TypeUtils.getMapKeyValueType(typeRef);
      buildGenericMap(map, kvTypes.f0);
      buildGenericMap(map, kvTypes.f1);
    } else if (TypeUtils.isCollection(rawType)) {
      TypeRef<?> elementType = TypeUtils.getElementType(typeRef);
      buildGenericMap(map, elementType);
    } else if (rawType.isArray()) {
      TypeRef<?> arrayComponent = TypeUtils.getArrayComponent(typeRef);
      buildGenericMap(map, arrayComponent);
    }
  }

  private void buildGenericMap(Map<String, GenericType> map, GenericType genericType) {
    String typeKey = genericType.getTypeRef().getTypeKey();
    if (map.containsKey(typeKey)) {
      return;
    }
    map.put(typeKey, genericType);
    for (GenericType t : genericType.getTypeParameters()) {
      buildGenericMap(map, t);
    }
  }

  public void setTypeChecker(TypeChecker typeChecker) {
    extRegistry.typeChecker = typeChecker == null ? DEFAULT_TYPE_CHECKER : typeChecker;
    if (extRegistry.typeChecker instanceof AllowListChecker && this instanceof ClassResolver) {
      ((AllowListChecker) extRegistry.typeChecker).addListener((ClassResolver) this);
    }
  }

  public void registerSerializerFactory(SerializerFactory serializerFactory) {
    extRegistry.serializerFactories.add(Preconditions.checkNotNull(serializerFactory));
  }

  public CodeGenerator getCodeGenerator(ClassLoader... loaders) {
    return extRegistry.codeGeneratorMap.get(Arrays.asList(loaders));
  }

  public CodeGenerator getOrCreateCodeGenerator(
      ClassLoader[] loaders, Function<ClassLoader[], CodeGenerator> factory) {
    ClassLoader[] keyLoaders = Arrays.copyOf(loaders, loaders.length);
    List<ClassLoader> key = Arrays.asList(keyLoaders);
    return extRegistry.codeGeneratorMap.computeIfAbsent(key, unused -> factory.apply(keyLoaders));
  }

  public void setCodeGenerator(ClassLoader loader, CodeGenerator codeGenerator) {
    setCodeGenerator(new ClassLoader[] {loader}, codeGenerator);
  }

  public void setCodeGenerator(ClassLoader[] loaders, CodeGenerator codeGenerator) {
    extRegistry.codeGeneratorMap.putIfAbsent(
        Arrays.asList(Arrays.copyOf(loaders, loaders.length)), codeGenerator);
  }

  protected final Serializer<?> createSerializerFromFactory(Class<?> cls) {
    ArrayList<SerializerFactory> serializerFactories = extRegistry.serializerFactories;
    for (int i = 0; i < serializerFactories.size(); i++) {
      Serializer<?> serializer = serializerFactories.get(i).createSerializer(this, cls);
      if (serializer != null) {
        return serializer;
      }
    }
    return null;
  }

  public void resetRead() {}

  public void resetWrite() {}

  protected final void clearGraalvmTypeInfoSerializer(TypeInfo typeInfo) {
    if (typeInfo != null
        && typeInfo.serializer != null
        && !extRegistry.registeredTypeInfos.contains(typeInfo)) {
      typeInfo.serializer = null;
    }
  }

  protected final void clearGraalvmGeneratedTypeInfoSerializers() {
    classInfoMap.forEach((cls, typeInfo) -> clearGraalvmTypeInfoSerializer(typeInfo));
    for (TypeInfo typeInfo : typeIdToTypeInfo) {
      clearGraalvmTypeInfoSerializer(typeInfo);
    }
    userTypeIdToTypeInfo.forEach((id, typeInfo) -> clearGraalvmTypeInfoSerializer(typeInfo));
    extRegistry.typeInfoByTypeDefId.forEach(
        (typeDefId, typeInfo) -> clearGraalvmTypeInfoSerializer(typeInfo));
  }

  protected final void registerGraalvmClass(Class<?> cls) {
    GraalvmSupport.registerClass(cls);
  }

  protected final boolean hasGraalvmSerializerClass(Class<?> cls) {
    return GraalvmSupport.isGraalRuntime() && getGraalvmClassRegistry().hasSerializerClass(cls);
  }

  protected final Class<? extends Serializer> getObjectSerializerClassFromGraalvmRegistry(
      Class<?> cls) {
    return getGraalvmClassRegistry().getObjectSerializerClass(cls);
  }

  // CHECKSTYLE.OFF:MethodName
  public static void _addGraalvmClassRegistry(int foryConfigHash, ClassResolver classResolver) {
    // CHECKSTYLE.ON:MethodName
    if (GraalvmSupport.isGraalBuildTime()) {
      GraalvmSupport.GraalvmClassRegistry registry =
          GraalvmSupport.getClassRegistry(foryConfigHash);
      registry.addResolver(classResolver);
    }
  }

  final GraalvmSupport.GraalvmClassRegistry getGraalvmClassRegistry() {
    return GraalvmSupport.getClassRegistry(config.getConfigHash());
  }

  final Class<? extends Serializer> getGraalvmSerializerClass(Serializer serializer) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE && serializer instanceof LazyInitBeanSerializer) {
      return ((CodegenSerializer.LazyInitBeanSerializer<?>) serializer)
          .loadGeneratedSerializerClass();
    }
    return serializer.getClass();
  }

  final Class<? extends Serializer> getSerializerClassFromGraalvmRegistry(Class<?> cls) {
    GraalvmSupport.GraalvmClassRegistry registry = getGraalvmClassRegistry();
    Class<? extends Serializer> serializerClass = registry.getSerializerClass(cls);
    if (serializerClass != null) {
      return serializerClass;
    }
    List<TypeResolver> resolvers = registry.getResolvers();
    if (!resolvers.isEmpty()) {
      for (TypeResolver resolver : resolvers) {
        if (resolver != this) {
          TypeInfo typeInfo = resolver.getTypeInfo(cls, false);
          if (typeInfo != null && typeInfo.serializer != null) {
            return resolver.getGraalvmSerializerClass(typeInfo.serializer);
          }
        }
      }
    }
    if (GraalvmSupport.isGraalRuntime()) {
      if (cls.isArray() || Functions.isLambda(cls) || ReflectionUtils.isJdkProxy(cls)) {
        return null;
      }
      throw new RuntimeException(String.format("Class %s is not registered", cls));
    }
    return null;
  }

  protected final Class<? extends Serializer> getCompatibleDeserializerClassFromGraalvmRegistry(
      Class<?> cls, TypeDef typeDef) {
    GraalvmSupport.GraalvmClassRegistry registry = getGraalvmClassRegistry();
    Class<? extends Serializer> deserializerClass = registry.getDeserializerClass(typeDef.getId());
    if (deserializerClass != null) {
      return deserializerClass;
    }
    deserializerClass = registry.getCompatibleDeserializerClass(cls);
    if (deserializerClass != null
        && (!GraalvmSupport.isGraalBuildTime()
            || typeDef.getId() != TypeDef.buildTypeDef(this, cls).getId())) {
      return deserializerClass;
    }
    if (!registry.hasResolvers()) {
      return null;
    }
    if (GraalvmSupport.isGraalRuntime()) {
      if (Functions.isLambda(cls) || ReflectionUtils.isJdkProxy(cls)) {
        return null;
      }
      throw new RuntimeException(
          String.format(
              "Class %s is not registered, registered classes: %s",
              cls, registry.getDeserializerClasses()));
    }
    return null;
  }

  public GenericType getObjectGenericType() {
    return extRegistry.objectGenericType;
  }

  static final TypeChecker DEFAULT_TYPE_CHECKER = (resolver, className) -> true;

  class ExtRegistry {
    // Here we set it to 1 to avoid calculating it again in `register(Class<?> cls)`.
    int classIdGenerator = 1;
    int userIdGenerator = 0;
    final ArrayList<SerializerFactory> serializerFactories = new ArrayList<>();
    final LongMap<TypeInfo> typeInfoByTypeDefId = new LongMap<>(2, 0.5f);
    // cache absTypeInfo, support customized serializer for abstract or interface.
    // IdentityHashMap is more memory efficient than fory IdentityMap, and this is not in hotpath
    // for query
    final IdentityHashMap<Class<?>, TypeInfo> abstractTypeInfo = new IdentityHashMap<>();
    final IdentityHashMap<Class<?>, TransformedTypeInfo[]> transformedTypeInfo =
        new IdentityHashMap<>();
    // avoid potential recursive call for seq codec generation.
    // ex. A->field1: B, B.field1: A
    final Set<Class<?>> getClassCtx = new HashSet<>();
    TypeChecker typeChecker = DEFAULT_TYPE_CHECKER;
    GenericType objectGenericType;

    final Map<Type, GenericType> genericTypes = new HashMap<>();
    final Map<Class, Map<String, GenericType>> classGenericTypes = new HashMap<>();
    // will be clear after ensureSerializersCompiled
    final Set<TypeInfo> registeredTypeInfos = new HashSet<>(isCrossLanguage() ? 4 : 180);
    boolean ensureSerializersCompiled;

    // shared across multiple fory instances.
    IdentityHashMap<Class<?>, Integer> registeredClassIdMap =
        new IdentityHashMap<>(isCrossLanguage() ? 4 : 200);
    BiMap<String, Class<?>> registeredClasses =
        BiMap.newHashIdentityBiMap(isCrossLanguage() ? 4 : 200);
    final ConcurrentIdentityMap<Class<?>, TypeDef> currentLayerTypeDef;
    // TODO(chaokunyang) Better to  use soft reference, see ObjectStreamClass.
    final ConcurrentHashMap<Tuple2<Class<?>, Boolean>, SortedMap<Member, Descriptor>>
        descriptorsCache;
    final ConcurrentHashMap<List<ClassLoader>, CodeGenerator> codeGeneratorMap;
    final ClassLoader classLoader;

    ExtRegistry(ClassLoader classLoader, SharedRegistry sharedRegistry) {
      this.classLoader = classLoader;
      currentLayerTypeDef = sharedRegistry.currentLayerTypeDef;
      descriptorsCache = sharedRegistry.descriptorsCache;
      codeGeneratorMap = sharedRegistry.codeGeneratorMap;
    }

    void finishRegistration(
        IdentityHashMap<Class<?>, Integer> sharedRegisteredClassIdMap,
        BiMap<String, Class<?>> sharedRegisteredClasses) {
      registeredClassIdMap = sharedRegisteredClassIdMap;
      registeredClasses = sharedRegisteredClasses;
    }

    public boolean isTypeCheckerSet() {
      return typeChecker != DEFAULT_TYPE_CHECKER;
    }
  }
}
