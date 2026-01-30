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

import static org.apache.fory.type.TypeUtils.getSizeOfPrimitiveType;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.annotation.CodegenInvoke;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.Internal;
import org.apache.fory.builder.CodecUtils;
import org.apache.fory.builder.Generated.GeneratedMetaSharedSerializer;
import org.apache.fory.builder.Generated.GeneratedObjectSerializer;
import org.apache.fory.builder.JITContext;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.collection.IdentityMap;
import org.apache.fory.collection.IdentityObjectIntMap;
import org.apache.fory.collection.LongMap;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.exception.ForyException;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.ClassDef;
import org.apache.fory.meta.ClassSpec;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.CodegenSerializer;
import org.apache.fory.serializer.CodegenSerializer.LazyInitBeanSerializer;
import org.apache.fory.serializer.MetaSharedSerializer;
import org.apache.fory.serializer.NonexistentClass;
import org.apache.fory.serializer.NonexistentClass.NonexistentMetaShared;
import org.apache.fory.serializer.NonexistentClass.NonexistentSkip;
import org.apache.fory.serializer.NonexistentClassSerializers;
import org.apache.fory.serializer.NonexistentClassSerializers.NonexistentClassSerializer;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.SerializerFactory;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorBuilder;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.ScalaTypes;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.GraalvmSupport.GraalvmSerializerHolder;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.function.Functions;

// Internal type dispatcher.
// Do not use this interface outside fory package
@Internal
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class TypeResolver {
  private static final Logger LOG = LoggerFactory.getLogger(ClassResolver.class);

  static final ClassInfo NIL_CLASS_INFO =
      new ClassInfo(null, null, null, null, false, null, Types.UNKNOWN);
  // use a lower load factor to minimize hash collision
  static final float foryMapLoadFactor = 0.25f;
  static final int estimatedNumRegistered = 150;
  static final String SET_META__CONTEXT_MSG =
      "Meta context must be set before serialization, "
          + "please set meta context by SerializationContext.setMetaContext";
  private static final GenericType OBJECT_GENERIC_TYPE = GenericType.build(Object.class);
  private static final float TYPE_ID_MAP_LOAD_FACTOR = 0.5f;

  final Fory fory;
  final boolean metaContextShareEnabled;
  final MetaStringResolver metaStringResolver;
  // IdentityMap has better lookup performance, when loadFactor is 0.05f, performance is better
  final IdentityMap<Class<?>, ClassInfo> classInfoMap = new IdentityMap<>(64, foryMapLoadFactor);
  final ExtRegistry extRegistry;
  final Map<Class<?>, ClassDef> classDefMap = new HashMap<>();
  // Map for internal type ids (non-user-defined).
  ClassInfo[] typeIdToClassInfo = new ClassInfo[] {};
  // Map for user-registered type ids, keyed by user id.
  final LongMap<ClassInfo> userTypeIdToClassInfo = new LongMap<>(4, TYPE_ID_MAP_LOAD_FACTOR);
  // Cache for readClassInfo(MemoryBuffer) - persists between calls to avoid reloading
  // dynamically created classes that can't be found by Class.forName
  private ClassInfo classInfoCache;

  protected TypeResolver(Fory fory) {
    this.fory = fory;
    metaContextShareEnabled = fory.getConfig().isMetaShareEnabled();
    extRegistry = new ExtRegistry();
    metaStringResolver = fory.getMetaStringResolver();
  }

  protected final void checkRegisterAllowed() {
    if (fory.getDepth() >= 0) {
      throw new ForyException(
          "Cannot register class/serializer after serialization/deserialization has started. "
              + "Please register all classes before invoking `serialize/deserialize` methods of Fory.");
    }
  }

  /**
   * Registers a class with an auto-assigned user ID.
   *
   * @param type the class to register
   */
  public abstract void register(Class<?> type);

  /**
   * Registers a class with a user-specified ID. Valid ID range is [0, 32510].
   *
   * @param type the class to register
   * @param id the user ID to assign (0-based)
   */
  public abstract void register(Class<?> type, int id);

  /**
   * Registers a class with a namespace and type name for cross-language serialization.
   *
   * @param type the class to register
   * @param namespace the namespace (can be empty if type name has no conflict)
   * @param typeName the type name
   */
  public abstract void register(Class<?> type, String namespace, String typeName);

  /** Registers a class by name with an auto-assigned user ID. */
  public void register(String className) {
    register(loadClass(className));
  }

  /** Registers a class by name with a user-specified ID. */
  public void register(String className, int classId) {
    register(loadClass(className), classId);
  }

  /** Registers a class by name with a namespace and type name. */
  public void register(String className, String namespace, String typeName) {
    register(loadClass(className), namespace, typeName);
  }

  /**
   * Registers a union type with a user-specified ID and serializer.
   *
   * @param type the union class to register
   * @param id the user ID to assign (0-based)
   * @param serializer serializer for the union
   */
  public abstract void registerUnion(Class<?> type, int id, Serializer<?> serializer);

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
    if (!fory.trackingRef()) {
      return false;
    }
    Class<?> cls = typeRef.getRawType();
    if (cls == String.class && !fory.isCrossLanguage()) {
      // for string, ignore `TypeExtMeta` for java native mode
      return !fory.getConfig().isStringRefIgnored();
    }
    TypeExtMeta meta = typeRef.getTypeExtMeta();
    if (meta != null) {
      return meta.trackingRef();
    }

    ClassInfo classInfo = classInfoMap.get(cls);
    if (classInfo == null || classInfo.serializer == null) {
      // TODO group related logic together for extendability and consistency.
      return !cls.isEnum();
    } else {
      return classInfo.serializer.needToWriteRef();
    }
  }

  public final boolean needToWriteClassDef(Serializer serializer) {
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

  public abstract boolean isRegistered(Class<?> cls);

  public abstract boolean isRegisteredById(Class<?> cls);

  public abstract boolean isRegisteredByName(Class<?> cls);

  public abstract boolean isBuildIn(Descriptor descriptor);

  public abstract boolean isMonomorphic(Descriptor descriptor);

  public abstract boolean isMonomorphic(Class<?> clz);

  public abstract ClassInfo getClassInfo(Class<?> cls);

  public abstract ClassInfo getClassInfo(Class<?> cls, boolean createIfAbsent);

  public abstract ClassInfo getClassInfo(Class<?> cls, ClassInfoHolder classInfoHolder);

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
  public final void writeClassInfo(MemoryBuffer buffer, ClassInfo classInfo) {
    int typeId = classInfo.getTypeId();
    int internalTypeId = typeId & 0xff;
    buffer.writeVarUint32Small7(typeId);

    switch (internalTypeId) {
      case Types.NAMED_ENUM:
      case Types.NAMED_STRUCT:
      case Types.NAMED_EXT:
      case Types.NAMED_UNION:
      case Types.NAMED_COMPATIBLE_STRUCT:
        if (!metaContextShareEnabled) {
          Preconditions.checkNotNull(classInfo.namespaceBytes);
          metaStringResolver.writeMetaStringBytes(buffer, classInfo.namespaceBytes);
          Preconditions.checkNotNull(classInfo.typeNameBytes);
          metaStringResolver.writeMetaStringBytes(buffer, classInfo.typeNameBytes);
        } else {
          writeSharedClassMeta(buffer, classInfo);
        }
        break;
      case Types.COMPATIBLE_STRUCT:
        if (metaContextShareEnabled && classInfo.cls != NonexistentMetaShared.class) {
          writeSharedClassMeta(buffer, classInfo);
        }
        break;
      default:
        break;
    }
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

  /**
   * Writes shared class metadata using the meta-share protocol. Protocol: If class already written,
   * writes (index << 1) | 1 (reference). If new class, writes (index << 1) followed by ClassDef
   * bytes.
   *
   * <p>This method is shared between XtypeResolver and ClassResolver.
   */
  protected final void writeSharedClassMeta(MemoryBuffer buffer, ClassInfo classInfo) {
    MetaContext metaContext = fory.getSerializationContext().getMetaContext();
    assert metaContext != null : SET_META__CONTEXT_MSG;
    IdentityObjectIntMap<Class<?>> classMap = metaContext.classMap;
    int newId = classMap.size;
    int id = classMap.putOrGet(classInfo.cls, newId);
    if (id >= 0) {
      // Reference to previously written type: (index << 1) | 1, LSB=1
      buffer.writeVarUint32((id << 1) | 1);
    } else {
      // New type: index << 1, LSB=0, followed by ClassDef bytes inline
      buffer.writeVarUint32(newId << 1);
      ClassDef classDef = classInfo.classDef;
      if (classDef == null) {
        classDef = buildClassDef(classInfo);
      }
      buffer.writeBytes(classDef.getEncoded());
    }
  }

  /**
   * Build ClassDef for the given ClassInfo. Used by writeSharedClassMeta when the classDef is not
   * yet created.
   */
  protected abstract ClassDef buildClassDef(ClassInfo classInfo);

  /**
   * Reads class info from buffer using the unified type system. This is the single implementation
   * shared by both ClassResolver and XtypeResolver.
   *
   * <p>Note: {@link #readClassInfo(MemoryBuffer, ClassInfo)} is faster since it uses a non-global
   * class info cache.
   */
  public final ClassInfo readClassInfo(MemoryBuffer buffer) {
    int header = buffer.readVarUint32Small14();
    int internalTypeId = header & 0xff;
    ClassInfo classInfo;
    switch (internalTypeId) {
      case Types.NAMED_ENUM:
      case Types.NAMED_STRUCT:
      case Types.NAMED_EXT:
      case Types.NAMED_UNION:
      case Types.NAMED_COMPATIBLE_STRUCT:
        if (!metaContextShareEnabled) {
          classInfo = readClassInfoFromBytes(buffer, classInfoCache, header);
        } else {
          classInfo = readSharedClassMeta(buffer);
        }
        break;
      case Types.COMPATIBLE_STRUCT:
        if (metaContextShareEnabled) {
          classInfo = readSharedClassMeta(buffer);
        } else {
          classInfo = requireUserTypeInfoByTypeId(header);
        }
        break;
      case Types.ENUM:
      case Types.STRUCT:
      case Types.EXT:
      case Types.UNION:
      case Types.TYPED_UNION:
        classInfo = requireUserTypeInfoByTypeId(header);
        break;
      case Types.LIST:
        classInfo = getListClassInfo();
        break;
      case Types.TIMESTAMP:
        classInfo = getTimestampClassInfo();
        break;
      default:
        classInfo = requireInternalTypeInfoByTypeId(header);
    }
    if (classInfo.serializer == null) {
      classInfo = ensureSerializerForClassInfo(classInfo);
    }
    classInfoCache = classInfo;
    return classInfo;
  }

  /**
   * Read class info from buffer using a target class. This is used by java serialization APIs that
   * pass an expected class for meta share resolution.
   */
  public final ClassInfo readClassInfo(MemoryBuffer buffer, Class<?> targetClass) {
    int header = buffer.readVarUint32Small14();
    int internalTypeId = header & 0xff;
    ClassInfo classInfo;
    switch (internalTypeId) {
      case Types.NAMED_ENUM:
      case Types.NAMED_STRUCT:
      case Types.NAMED_EXT:
      case Types.NAMED_UNION:
      case Types.NAMED_COMPATIBLE_STRUCT:
        if (!metaContextShareEnabled) {
          classInfo = readClassInfoFromBytes(buffer, classInfoCache, header);
        } else {
          classInfo = readSharedClassMeta(buffer, targetClass);
        }
        break;
      case Types.COMPATIBLE_STRUCT:
        if (metaContextShareEnabled) {
          classInfo = readSharedClassMeta(buffer, targetClass);
        } else {
          classInfo = requireUserTypeInfoByTypeId(header);
        }
        break;
      case Types.ENUM:
      case Types.STRUCT:
      case Types.EXT:
      case Types.UNION:
      case Types.TYPED_UNION:
        classInfo = requireUserTypeInfoByTypeId(header);
        break;
      case Types.LIST:
        classInfo = getListClassInfo();
        break;
      case Types.TIMESTAMP:
        classInfo = getTimestampClassInfo();
        break;
      default:
        classInfo = requireInternalTypeInfoByTypeId(header);
    }
    if (classInfo.serializer == null) {
      classInfo = ensureSerializerForClassInfo(classInfo);
    }
    classInfoCache = classInfo;
    return classInfo;
  }

  /**
   * Read class info from buffer with ClassInfo cache. This version is faster than {@link
   * #readClassInfo(MemoryBuffer)} because it uses the provided classInfoCache to reduce map lookups
   * when reading class from binary.
   *
   * @param buffer the buffer to read from
   * @param classInfoCache cache for class info to speed up repeated reads
   * @return the ClassInfo read from buffer
   */
  @CodegenInvoke
  public final ClassInfo readClassInfo(MemoryBuffer buffer, ClassInfo classInfoCache) {
    int header = buffer.readVarUint32Small14();
    int internalTypeId = header & 0xff;
    ClassInfo classInfo;
    switch (internalTypeId) {
      case Types.NAMED_ENUM:
      case Types.NAMED_STRUCT:
      case Types.NAMED_EXT:
      case Types.NAMED_UNION:
      case Types.NAMED_COMPATIBLE_STRUCT:
        if (!metaContextShareEnabled) {
          classInfo = readClassInfoFromBytes(buffer, classInfoCache, header);
        } else {
          classInfo = readSharedClassMeta(buffer);
        }
        break;
      case Types.COMPATIBLE_STRUCT:
        if (metaContextShareEnabled) {
          classInfo = readSharedClassMeta(buffer);
        } else {
          classInfo = requireUserTypeInfoByTypeId(header);
        }
        break;
      case Types.ENUM:
      case Types.STRUCT:
      case Types.EXT:
      case Types.UNION:
      case Types.TYPED_UNION:
        classInfo = requireUserTypeInfoByTypeId(header);
        break;
      case Types.LIST:
        classInfo = getListClassInfo();
        break;
      case Types.TIMESTAMP:
        classInfo = getTimestampClassInfo();
        break;
      default:
        classInfo = requireInternalTypeInfoByTypeId(header);
    }
    if (classInfo.serializer == null) {
      classInfo = ensureSerializerForClassInfo(classInfo);
    }
    return classInfo;
  }

  /**
   * Read class info from buffer with ClassInfoHolder cache. This version updates the
   * classInfoHolder if the cache doesn't hit, allowing callers to maintain the cache across calls.
   *
   * @param buffer the buffer to read from
   * @param classInfoHolder holder containing cache, will be updated on cache miss
   * @return the ClassInfo read from buffer
   */
  @CodegenInvoke
  public final ClassInfo readClassInfo(MemoryBuffer buffer, ClassInfoHolder classInfoHolder) {
    int header = buffer.readVarUint32Small14();
    int internalTypeId = header & 0xff;
    ClassInfo classInfo;
    boolean updateCache = false;
    switch (internalTypeId) {
      case Types.NAMED_ENUM:
      case Types.NAMED_STRUCT:
      case Types.NAMED_EXT:
      case Types.NAMED_UNION:
      case Types.NAMED_COMPATIBLE_STRUCT:
        if (!metaContextShareEnabled) {
          classInfo = readClassInfoFromBytes(buffer, classInfoHolder.classInfo, header);
          updateCache = true;
        } else {
          classInfo = readSharedClassMeta(buffer);
        }
        break;
      case Types.COMPATIBLE_STRUCT:
        if (metaContextShareEnabled) {
          classInfo = readSharedClassMeta(buffer);
        } else {
          classInfo = requireUserTypeInfoByTypeId(header);
        }
        break;
      case Types.ENUM:
      case Types.STRUCT:
      case Types.EXT:
      case Types.UNION:
      case Types.TYPED_UNION:
        classInfo = requireUserTypeInfoByTypeId(header);
        break;
      case Types.LIST:
        classInfo = getListClassInfo();
        break;
      case Types.TIMESTAMP:
        classInfo = getTimestampClassInfo();
        break;
      default:
        classInfo = requireInternalTypeInfoByTypeId(header);
    }
    if (classInfo.serializer == null) {
      classInfo = ensureSerializerForClassInfo(classInfo);
    }
    if (updateCache) {
      classInfoHolder.classInfo = classInfo;
    }
    return classInfo;
  }

  /**
   * Read class info using the provided cache. Returns cached ClassInfo if the namespace and type
   * name bytes match.
   */
  protected final ClassInfo readClassInfoByCache(
      MemoryBuffer buffer, ClassInfo classInfoCache, int header) {
    return readClassInfoFromBytes(buffer, classInfoCache, header);
  }

  /**
   * Read class info from bytes with cache optimization. Uses the cached namespace and type name
   * bytes to avoid map lookups when the class is the same as the cached one (hash comparison).
   */
  protected final ClassInfo readClassInfoFromBytes(
      MemoryBuffer buffer, ClassInfo classInfoCache, int header) {
    MetaStringBytes typeNameBytesCache =
        classInfoCache != null ? classInfoCache.typeNameBytes : null;
    MetaStringBytes namespaceBytes;
    MetaStringBytes simpleClassNameBytes;

    if (typeNameBytesCache != null) {
      // Use cache for faster comparison
      MetaStringBytes packageNameBytesCache = classInfoCache.namespaceBytes;
      namespaceBytes = metaStringResolver.readMetaStringBytes(buffer, packageNameBytesCache);
      assert packageNameBytesCache != null;
      simpleClassNameBytes = metaStringResolver.readMetaStringBytes(buffer, typeNameBytesCache);

      // Fast path: if hashes match, return cached ClassInfo (already has serializer)
      if (typeNameBytesCache.hashCode == simpleClassNameBytes.hashCode
          && packageNameBytesCache.hashCode == namespaceBytes.hashCode) {
        return classInfoCache;
      }
    } else {
      // No cache available, read fresh
      namespaceBytes = metaStringResolver.readMetaStringBytes(buffer);
      simpleClassNameBytes = metaStringResolver.readMetaStringBytes(buffer);
    }

    // Load class info from bytes (subclass-specific).
    return loadBytesToClassInfo(namespaceBytes, simpleClassNameBytes);
  }

  /**
   * Reads shared class metadata from buffer. This is the shared implementation used by both
   * ClassResolver and XtypeResolver.
   */
  protected final ClassInfo readSharedClassMeta(MemoryBuffer buffer) {
    MetaContext metaContext = fory.getSerializationContext().getMetaContext();
    assert metaContext != null : SET_META__CONTEXT_MSG;
    int indexMarker = buffer.readVarUint32Small14();
    boolean isRef = (indexMarker & 1) == 1;
    int index = indexMarker >>> 1;
    ClassInfo classInfo;
    if (isRef) {
      // Reference to previously read type in this stream
      classInfo = metaContext.readClassInfos.get(index);
    } else {
      // New type in stream - but may already be known from registry
      long id = buffer.readInt64();
      Tuple2<ClassDef, ClassInfo> tuple2 = extRegistry.classIdToDef.get(id);
      if (tuple2 != null) {
        // Already known - skip the ClassDef bytes, reuse existing ClassInfo
        ClassDef.skipClassDef(buffer, id);
        classInfo = tuple2.f1;
        if (classInfo == null) {
          classInfo = buildMetaSharedClassInfo(tuple2, tuple2.f0);
        }
      } else {
        // Unknown - read ClassDef and create ClassInfo
        tuple2 = readClassDef(buffer, id);
        classInfo = tuple2.f1;
        if (classInfo == null) {
          classInfo = buildMetaSharedClassInfo(tuple2, tuple2.f0);
        }
      }
      // index == readClassInfos.size() since types are written sequentially
      metaContext.readClassInfos.add(classInfo);
    }
    return classInfo;
  }

  public final ClassInfo readSharedClassMeta(MemoryBuffer buffer, Class<?> targetClass) {
    ClassInfo classInfo = readSharedClassMeta(buffer);
    Class<?> readClass = classInfo.getCls();
    // replace target class if needed
    if (targetClass != readClass) {
      Tuple2<Class<?>, Class<?>> key = Tuple2.of(readClass, targetClass);
      ClassInfo newClassInfo = extRegistry.transformedClassInfo.get(key);
      if (newClassInfo == null) {
        // similar to create serializer for `NonexistentMetaShared`
        newClassInfo =
            getMetaSharedClassInfo(
                classInfo.classDef.replaceRootClassTo((ClassResolver) this, targetClass),
                targetClass);
        extRegistry.transformedClassInfo.put(key, newClassInfo);
      }
      return newClassInfo;
    }
    return classInfo;
  }

  /**
   * Load class info from namespace and type name bytes. Subclasses implement this to resolve the
   * class and create/lookup ClassInfo.
   *
   * <p>Note: This method should NOT create serializers. It's used by both readClassInfo (which
   * needs serializers) and readClassInternal (which doesn't need serializers). Use {@link
   * #ensureSerializerForClassInfo} after calling this if a serializer is needed.
   */
  protected abstract ClassInfo loadBytesToClassInfo(
      MetaStringBytes namespaceBytes, MetaStringBytes simpleClassNameBytes);

  /**
   * Ensure the ClassInfo has a serializer set. Called after loading class info for deserialization.
   * If the class is abstract/interface or can't be serialized, this may throw an exception.
   *
   * @param classInfo the class info to ensure has a serializer
   * @return the ClassInfo with serializer set (may be the same instance or a different one)
   */
  protected abstract ClassInfo ensureSerializerForClassInfo(ClassInfo classInfo);

  protected ClassInfo getListClassInfo() {
    return requireInternalTypeInfoByTypeId(Types.LIST);
  }

  protected ClassInfo getTimestampClassInfo() {
    return requireInternalTypeInfoByTypeId(Types.TIMESTAMP);
  }

  protected final ClassInfo getInternalTypeInfoByTypeId(int typeId) {
    if (typeId < 0 || typeId >= typeIdToClassInfo.length) {
      return null;
    }
    return typeIdToClassInfo[typeId];
  }

  protected final ClassInfo getUserTypeInfoByTypeId(int typeId) {
    int userId = typeId >>> 8;
    ClassInfo classInfo = userTypeIdToClassInfo.get(userId);
    if (classInfo == null) {
      return null;
    }
    int internalTypeId = typeId & 0xff;
    int registeredInternalTypeId = classInfo.typeId & 0xff;
    if (registeredInternalTypeId != internalTypeId) {
      if (classInfo.serializer == null) {
        classInfo.typeId = typeId;
      } else {
        return null;
      }
    }
    return classInfo;
  }

  protected final ClassInfo requireUserTypeInfoByTypeId(int typeId) {
    ClassInfo classInfo = getUserTypeInfoByTypeId(typeId);
    if (classInfo == null) {
      throw new IllegalStateException(String.format("Type id %s not registered", typeId));
    }
    return classInfo;
  }

  protected final ClassInfo requireInternalTypeInfoByTypeId(int typeId) {
    ClassInfo classInfo = getInternalTypeInfoByTypeId(typeId);
    if (classInfo == null) {
      throw new IllegalStateException(String.format("Type id %s not registered", typeId));
    }
    return classInfo;
  }

  protected final void putInternalTypeInfo(int typeId, ClassInfo classInfo) {
    if (typeIdToClassInfo.length <= typeId) {
      ClassInfo[] tmp = new ClassInfo[(typeId + 1) * 2];
      System.arraycopy(typeIdToClassInfo, 0, tmp, 0, typeIdToClassInfo.length);
      typeIdToClassInfo = tmp;
    }
    typeIdToClassInfo[typeId] = classInfo;
  }

  protected final void putUserTypeInfo(int userId, ClassInfo classInfo) {
    userTypeIdToClassInfo.put(userId, classInfo);
  }

  protected final boolean containsUserTypeId(int userId) {
    return userTypeIdToClassInfo.containsKey(userId);
  }

  final ClassInfo buildMetaSharedClassInfo(
      Tuple2<ClassDef, ClassInfo> classDefTuple, ClassDef classDef) {
    ClassInfo classInfo;
    if (classDefTuple != null) {
      classDef = classDefTuple.f0;
    }
    Class<?> cls = loadClass(classDef.getClassSpec());
    // For nonexistent classes, always create a new ClassInfo with the correct classDef,
    // even if the classDef has no fields meta. This ensures the NonexistentClassSerializer
    // has access to the classDef for proper deserialization.
    if (!classDef.hasFieldsMeta()
        && !NonexistentClass.class.isAssignableFrom(TypeUtils.getComponentIfArray(cls))) {
      classInfo = getClassInfo(cls);
    } else if (ClassResolver.useReplaceResolveSerializer(cls)) {
      // For classes with writeReplace/readResolve, use their natural serializer
      // (ReplaceResolveSerializer) instead of MetaSharedSerializer
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
    int typeId;
    if (classId != null) {
      ClassInfo registeredInfo = classInfoMap.get(cls);
      if (registeredInfo == null) {
        registeredInfo = getClassInfo(cls);
      }
      typeId = registeredInfo.typeId;
    } else {
      ClassInfo cachedInfo = classInfoMap.get(cls);
      if (cachedInfo != null) {
        typeId = cachedInfo.typeId;
      } else {
        typeId = buildUnregisteredTypeId(cls, null);
      }
    }
    ClassInfo classInfo = new ClassInfo(this, cls, null, typeId);
    classInfo.classDef = classDef;
    if (NonexistentClass.class.isAssignableFrom(TypeUtils.getComponentIfArray(cls))) {
      if (cls == NonexistentMetaShared.class) {
        classInfo.setSerializer(this, new NonexistentClassSerializer(fory, classDef));
        // Ensure NonexistentMetaShared is registered so writeClassInfo emits a placeholder typeId
        // that NonexistentClassSerializer can rewrite to the original typeId.
        if (!fory.isCrossLanguage()) {
          Preconditions.checkNotNull(classId);
        }
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

  protected int buildUnregisteredTypeId(Class<?> cls, Serializer<?> serializer) {
    if (cls.isEnum()) {
      return Types.NAMED_ENUM;
    }
    if (serializer != null && !isStructSerializer(serializer)) {
      return Types.NAMED_EXT;
    }
    if (fory.isCompatible()) {
      return Types.NAMED_COMPATIBLE_STRUCT;
    }
    return Types.NAMED_STRUCT;
  }

  protected static boolean isStructSerializer(Serializer<?> serializer) {
    return serializer instanceof GeneratedObjectSerializer
        || serializer instanceof GeneratedMetaSharedSerializer
        || serializer instanceof LazyInitBeanSerializer
        || serializer instanceof ObjectSerializer
        || serializer instanceof MetaSharedSerializer;
  }

  protected Tuple2<ClassDef, ClassInfo> readClassDef(MemoryBuffer buffer, long header) {
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

  final Class<?> loadClass(ClassSpec classSpec) {
    if (classSpec.type != null) {
      return classSpec.type;
    }
    return loadClass(classSpec.entireClassName, classSpec.isEnum, classSpec.dimension);
  }

  final Class<?> loadClass(String className, boolean isEnum, int arrayDims) {
    return loadClass(className, isEnum, arrayDims, fory.getConfig().deserializeNonexistentClass());
  }

  final Class<?> loadClass(String className) {
    return loadClass(className, false, -1, false);
  }

  final Class<?> loadClass(
      String className, boolean isEnum, int arrayDims, boolean deserializeNonexistentClass) {
    extRegistry.typeChecker.checkType(this, className);
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

  public abstract <T> Serializer<T> getSerializer(Class<T> cls);

  public final Serializer<?> getSerializer(TypeRef<?> typeRef) {
    if (!fory.isCrossLanguage()) {
      return getSerializer(typeRef.getRawType());
    }
    Class<?> rawType = typeRef.getRawType();
    return getSerializer(rawType);
  }

  public abstract Serializer<?> getRawSerializer(Class<?> cls);

  public abstract <T> void setSerializer(Class<T> cls, Serializer<T> serializer);

  public abstract <T> void setSerializerIfAbsent(Class<T> cls, Serializer<T> serializer);

  public final Serializer<?> getSerializerByTypeId(int typeId) {
    int internalTypeId = typeId & 0xFF;
    if (Types.isUserDefinedType((byte) internalTypeId)) {
      int userId = typeId >>> 8;
      if (userId != 0) {
        return requireUserTypeInfoByTypeId(userId).getSerializer();
      }
    }
    return requireInternalTypeInfoByTypeId(internalTypeId).getSerializer();
  }

  public final ClassInfo nilClassInfo() {
    return NIL_CLASS_INFO;
  }

  public final ClassInfoHolder nilClassInfoHolder() {
    return new ClassInfoHolder(NIL_CLASS_INFO);
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

  public final ClassDef getTypeDef(Class<?> cls, boolean resolveParent) {
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

  public final boolean isSerializable(Class<?> cls) {
    // Enums are always serializable, even if abstract (enums with abstract methods)
    if (cls.isEnum()) {
      return true;
    }
    if (ReflectionUtils.isAbstract(cls) || cls.isInterface()) {
      return false;
    }
    try {
      ClassInfo classInfo = classInfoMap.get(cls);
      Serializer<?> serializer = null;
      if (classInfo != null) {
        serializer = classInfo.serializer;
      }
      getSerializerClass(cls, false);
      if (classInfo != null && serializer == null) {
        classInfo.serializer = null;
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
    if (codegen) {
      if (extRegistry.getClassCtx.contains(cls)) {
        // avoid potential recursive call for seq codec generation.
        return CodegenSerializer.LazyInitBeanSerializer.class;
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
                          () -> CodegenSerializer.loadCodegenSerializer(fory, cls),
                          callback);
              return sc;
            case COMPATIBLE:
              // Always use ObjectSerializer for compatible mode.
              // Class definition will be sent to peer to create serializer for deserialization.
              sc =
                  fory.getJITContext()
                      .registerSerializerJITCallback(
                          () -> ObjectSerializer.class,
                          () -> CodegenSerializer.loadCodegenSerializer(fory, cls),
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
      // Always use ObjectSerializer for both modes
      return ObjectSerializer.class;
    }
  }

  public final boolean isCollection(Class<?> cls) {
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

  public final boolean isSet(Class<?> cls) {
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

  public final boolean isMap(Class<?> cls) {
    if (cls == NonexistentMetaShared.class) {
      return false;
    }
    return Map.class.isAssignableFrom(cls)
        || (fory.getConfig().isScalaOptimizationEnabled()
            && ScalaTypes.getScalaMapType().isAssignableFrom(cls));
  }

  public final DescriptorGrouper createDescriptorGrouper(
      Collection<Descriptor> descriptors, boolean descriptorsGroupedOrdered) {
    return createDescriptorGrouper(descriptors, descriptorsGroupedOrdered, null);
  }

  public abstract DescriptorGrouper createDescriptorGrouper(
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator);

  public List<Descriptor> getFieldDescriptors(Class<?> clz, boolean searchParent) {
    SortedMap<Member, Descriptor> allDescriptors = getAllDescriptorsMap(clz, searchParent);
    List<Descriptor> result = new ArrayList<>(allDescriptors.size());

    Map<Member, Descriptor> newDescriptorMap = new HashMap<>();
    boolean globalRefTracking = fory.trackingRef();
    boolean isXlang = fory.isCrossLanguage();

    for (Map.Entry<Member, Descriptor> entry : allDescriptors.entrySet()) {
      Member member = entry.getKey();
      Descriptor descriptor = entry.getValue();
      if (!(member instanceof Field)) {
        continue;
      }
      boolean hasForyField = descriptor.getForyField() != null;
      // Compute the final isTrackingRef value:
      // For xlang mode: "Reference tracking is disabled by default" (xlang spec)
      //   - Only enable ref tracking if explicitly set via @ForyField(ref=true)
      // For Java mode:
      //   - If global ref tracking is enabled and no @ForyField, use global setting
      //   - If @ForyField(ref=true) is set, use that (but can be overridden if global is off)
      boolean ref = globalRefTracking;
      if (globalRefTracking) {
        if (isXlang) {
          // In xlang mode, only track refs if explicitly annotated with @ForyField(ref=true)
          ref = hasForyField && descriptor.isTrackingRef();
        } else {
          if (hasForyField) {
            ref = descriptor.isTrackingRef();
          } else {
            ref = needToWriteRef(descriptor.getTypeRef());
          }
        }
      }
      boolean nullable = isFieldNullable(descriptor);
      boolean needsUpdate =
          ref != descriptor.isTrackingRef() || nullable != descriptor.isNullable();

      if (needsUpdate) {
        Descriptor newDescriptor =
            new DescriptorBuilder(descriptor).trackingRef(ref).nullable(nullable).build();
        result.add(newDescriptor);
        newDescriptorMap.put(member, newDescriptor);
      } else {
        result.add(descriptor);
      }
    }
    return result;
  }

  /**
   * Gets the sort key for a field descriptor.
   *
   * <p>If the field has a {@link ForyField} annotation with id >= 0, returns the id as a string.
   * Otherwise, returns the snake_case field name. This ensures fields are sorted by tag ID when
   * configured, matching the fingerprint computation order.
   *
   * @param descriptor the field descriptor
   * @return the sort key (tag ID as string or snake_case name)
   */
  protected static String getFieldSortKey(Descriptor descriptor) {
    ForyField foryField = descriptor.getForyField();
    if (foryField != null && foryField.id() >= 0) {
      return String.valueOf(foryField.id());
    }
    return descriptor.getSnakeCaseName();
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
      Class<?> t1 = TypeUtils.unwrap(d1.getRawType());
      Class<?> t2 = TypeUtils.unwrap(d2.getRawType());
      int typeId1 = Types.getDescriptorTypeId(fory, d1);
      int typeId2 = Types.getDescriptorTypeId(fory, d2);
      boolean t1Compress = Types.isCompressedType(typeId1);
      boolean t2Compress = Types.isCompressedType(typeId2);
      if ((t1Compress && t2Compress) || (!t1Compress && !t2Compress)) {
        int c = getSizeOfPrimitiveType(t2) - getSizeOfPrimitiveType(t1);
        if (c == 0) {
          c = typeId2 - typeId1;
          // noinspection Duplicates
          if (c == 0) {
            c = getFieldSortKey(d1).compareTo(getFieldSortKey(d2));
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

  /**
   * Get the nullable flag for a field, respecting xlang mode.
   *
   * <p>For xlang mode (SERIALIZATION): use xlang defaults unless @ForyField annotation overrides:
   *
   * <ul>
   *   <li>If @ForyField annotation is present: use its nullable() value
   *   <li>Otherwise: return true only for Optional types, false for all other non-primitives
   * </ul>
   *
   * <p>For native mode: use descriptor's nullable which defaults to true for non-primitives.
   *
   * <p>Important: This ensures the serialization format matches what the TypeDef metadata says. The
   * TypeDef uses xlang defaults (nullable=false except for Optional types), so the actual
   * serialization must use the same defaults to ensure consistency across languages.
   */
  private boolean isFieldNullable(Descriptor descriptor) {
    Class<?> rawType = descriptor.getTypeRef().getRawType();
    if (rawType.isPrimitive()) {
      return false;
    }
    if (fory.isCrossLanguage()) {
      // For xlang mode: apply xlang defaults
      // This must match what TypeDefEncoder.buildFieldType uses for TypeDef metadata
      ForyField foryField = descriptor.getForyField();
      if (foryField != null) {
        // Use explicit annotation value
        return foryField.nullable();
      }
      // Default for xlang: false for all non-primitives, except Optional types
      return TypeUtils.isOptionalType(rawType);
    }
    // For native mode: use descriptor's nullable (true for non-primitives by default)
    return descriptor.isNullable();
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
      Type type = field.getGenericType();
      GenericType genericType = buildGenericType(type);
      AnnotatedType annotatedType = field.getAnnotatedType();
      TypeUtils.applyRefTrackingOverride(genericType, annotatedType, fory.trackingRef());
      buildGenericMap(map, genericType);
      TypeRef<?> typeRef = TypeRef.of(type);
      buildGenericMap(map2, typeRef);
    }
    for (Map.Entry<String, GenericType> entry : map2.entrySet()) {
      map.putIfAbsent(entry.getKey(), entry.getValue());
    }
    return map;
  }

  private void buildGenericMap(Map<String, GenericType> map, TypeRef<?> typeRef) {
    if (map.containsKey(typeRef.getType().getTypeName())) {
      return;
    }
    map.put(typeRef.getType().getTypeName(), buildGenericType(typeRef));
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
    if (map.containsKey(genericType.getType().getTypeName())) {
      return;
    }
    map.put(genericType.getType().getTypeName(), genericType);
    for (GenericType t : genericType.getTypeParameters()) {
      buildGenericMap(map, t);
    }
  }

  public void setTypeChecker(TypeChecker typeChecker) {
    extRegistry.typeChecker = typeChecker;
  }

  // CHECKSTYLE.OFF:MethodName
  public static void _addGraalvmClassRegistry(int foryConfigHash, ClassResolver classResolver) {
    // CHECKSTYLE.ON:MethodName
    if (GraalvmSupport.isGraalBuildtime()) {
      GraalvmSupport.GraalvmClassRegistry registry =
          GraalvmSupport.getClassRegistry(foryConfigHash);
      registry.resolvers.add(classResolver);
    }
  }

  final GraalvmSupport.GraalvmClassRegistry getGraalvmClassRegistry() {
    return GraalvmSupport.getClassRegistry(fory.getConfig().getConfigHash());
  }

  final Class<? extends Serializer> getGraalvmSerializerClass(Serializer serializer) {
    if (serializer instanceof GraalvmSerializerHolder) {
      return ((GraalvmSerializerHolder) serializer).getSerializerClass();
    }
    return serializer.getClass();
  }

  final Class<? extends Serializer> getSerializerClassFromGraalvmRegistry(Class<?> cls) {
    GraalvmSupport.GraalvmClassRegistry registry = getGraalvmClassRegistry();
    List<TypeResolver> resolvers = registry.resolvers;
    if (resolvers.isEmpty()) {
      return null;
    }
    for (TypeResolver resolver : resolvers) {
      if (resolver != this) {
        ClassInfo classInfo = getClassInfo(cls, false);
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
    GraalvmSupport.GraalvmClassRegistry registry = getGraalvmClassRegistry();
    List<TypeResolver> resolvers = registry.resolvers;
    if (resolvers.isEmpty()) {
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

  public final Fory getFory() {
    return fory;
  }

  public final MetaStringResolver getMetaStringResolver() {
    return metaStringResolver;
  }

  static class ExtRegistry {
    // Here we set it to 1 to avoid calculating it again in `register(Class<?> cls)`.
    short classIdGenerator = 1;
    short userIdGenerator = 0;
    SerializerFactory serializerFactory;
    final IdentityMap<Class<?>, Short> registeredClassIdMap =
        new IdentityMap<>(estimatedNumRegistered);
    final BiMap<String, Class<?>> registeredClasses = HashBiMap.create(estimatedNumRegistered);
    // cache absClassInfo, support customized serializer for abstract or interface.
    final IdentityMap<Class<?>, ClassInfo> absClassInfo =
        new IdentityMap<>(estimatedNumRegistered, foryMapLoadFactor);
    // avoid potential recursive call for seq codec generation.
    // ex. A->field1: B, B.field1: A
    final Set<Class<?>> getClassCtx = new HashSet<>();
    final LongMap<Tuple2<ClassDef, ClassInfo>> classIdToDef = new LongMap<>();
    final Map<Class<?>, ClassDef> currentLayerClassDef = new HashMap<>();
    // Tuple2<Class, Class>: Tuple2<From Class, To Class>
    final Map<Tuple2<Class<?>, Class<?>>, ClassInfo> transformedClassInfo = new HashMap<>();
    // TODO(chaokunyang) Better to  use soft reference, see ObjectStreamClass.
    final ConcurrentHashMap<Tuple2<Class<?>, Boolean>, SortedMap<Member, Descriptor>>
        descriptorsCache = new ConcurrentHashMap<>();
    static final TypeChecker DEFAULT_TYPE_CHECKER = (resolver, className) -> true;
    TypeChecker typeChecker = DEFAULT_TYPE_CHECKER;
    GenericType objectGenericType;
    final IdentityMap<Type, GenericType> genericTypes = new IdentityMap<>();
    final Map<Class, Map<String, GenericType>> classGenericTypes = new HashMap<>();
    final Map<List<ClassLoader>, CodeGenerator> codeGeneratorMap = new HashMap<>();
    final Set<ClassInfo> registeredClassInfos = new HashSet<>();
    boolean ensureSerializersCompiled;

    public boolean isTypeCheckerSet() {
      return typeChecker != DEFAULT_TYPE_CHECKER;
    }
  }
}
