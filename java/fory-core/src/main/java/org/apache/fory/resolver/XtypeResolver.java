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
import static org.apache.fory.builder.Generated.GeneratedSerializer;
import static org.apache.fory.meta.Encoders.GENERIC_ENCODER;
import static org.apache.fory.meta.Encoders.PACKAGE_DECODER;
import static org.apache.fory.meta.Encoders.PACKAGE_ENCODER;
import static org.apache.fory.meta.Encoders.TYPE_NAME_DECODER;
import static org.apache.fory.serializer.collection.MapSerializers.HashMapSerializer;
import static org.apache.fory.type.TypeUtils.qualifiedName;
import static org.apache.fory.type.Types.INVALID_USER_TYPE_ID;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.BoolList;
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
import org.apache.fory.config.Config;
import org.apache.fory.exception.ClassUnregisteredException;
import org.apache.fory.exception.SerializerUnregisteredException;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.meta.Encoders;
import org.apache.fory.meta.MetaString;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.serializer.ArraySerializers;
import org.apache.fory.serializer.DeferedLazySerializer;
import org.apache.fory.serializer.DeferedLazySerializer.DeferredLazyObjectSerializer;
import org.apache.fory.serializer.EnumSerializer;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.PrimitiveSerializers;
import org.apache.fory.serializer.SerializationUtils;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.serializer.StringSerializer;
import org.apache.fory.serializer.TimeSerializers;
import org.apache.fory.serializer.UnionSerializer;
import org.apache.fory.serializer.UnknownClass;
import org.apache.fory.serializer.UnknownClass.UnknownEnum;
import org.apache.fory.serializer.UnknownClass.UnknownStruct;
import org.apache.fory.serializer.UnknownClassSerializers;
import org.apache.fory.serializer.UnknownClassSerializers.UnknownStructSerializer;
import org.apache.fory.serializer.UnsignedSerializers;
import org.apache.fory.serializer.collection.CollectionLikeSerializer;
import org.apache.fory.serializer.collection.CollectionSerializer;
import org.apache.fory.serializer.collection.CollectionSerializers.ArrayListSerializer;
import org.apache.fory.serializer.collection.CollectionSerializers.HashSetSerializer;
import org.apache.fory.serializer.collection.CollectionSerializers.XlangListDefaultSerializer;
import org.apache.fory.serializer.collection.CollectionSerializers.XlangSetDefaultSerializer;
import org.apache.fory.serializer.collection.MapLikeSerializer;
import org.apache.fory.serializer.collection.MapSerializer;
import org.apache.fory.serializer.collection.MapSerializers.XlangMapSerializer;
import org.apache.fory.serializer.collection.PrimitiveListSerializers;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.Generics;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.type.union.Union;
import org.apache.fory.type.unsigned.Uint16;
import org.apache.fory.type.unsigned.Uint32;
import org.apache.fory.type.unsigned.Uint8;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.Preconditions;

@SuppressWarnings({"unchecked", "rawtypes"})
// TODO(chaokunyang) Abstract type resolver for java/xlang type resolution.
public class XtypeResolver extends TypeResolver {
  private static final Logger LOG = LoggerFactory.getLogger(XtypeResolver.class);

  private static final float loadFactor = 0.5f;
  // Most systems won't have so many types for serialization.
  private static final int MAX_TYPE_ID = 4096;

  private final Config config;
  private final Fory fory;
  private final TypeInfoHolder classInfoCache = new TypeInfoHolder(NIL_TYPE_INFO);
  private final MetaStringResolver metaStringResolver;

  // Every deserialization for unregistered class will query it, performance is important.
  private final ObjectMap<TypeNameBytes, TypeInfo> compositeClassNameBytes2TypeInfo =
      new ObjectMap<>(16, loadFactor);
  private final ObjectMap<String, TypeInfo> qualifiedType2TypeInfo =
      new ObjectMap<>(16, loadFactor);
  // typeDefMap is inherited from TypeResolver
  private final boolean shareMeta;
  private int xtypeIdGenerator = 64;

  private final Generics generics;

  public XtypeResolver(Fory fory) {
    super(fory);
    this.config = fory.getConfig();
    this.fory = fory;
    shareMeta = fory.getConfig().isMetaShareEnabled();
    this.generics = fory.getGenerics();
    this.metaStringResolver = fory.getMetaStringResolver();
  }

  @Override
  public void initialize() {
    registerDefaultTypes();
    if (shareMeta) {
      Serializer serializer = new UnknownStructSerializer(fory, null);
      register(UnknownStruct.class, serializer, "", "unknown_struct", Types.COMPATIBLE_STRUCT, -1);
    }
  }

  @Override
  public void register(Class<?> type) {
    while (containsUserTypeId(xtypeIdGenerator)) {
      xtypeIdGenerator++;
    }
    register(type, xtypeIdGenerator++);
  }

  @Override
  public void register(Class<?> type, long userTypeId) {
    checkRegisterAllowed();
    int checkedUserTypeId = toUserTypeId(userTypeId);
    Preconditions.checkArgument(
        !containsUserTypeId(checkedUserTypeId), "Type id %s has been registered", userTypeId);
    TypeInfo typeInfo = classInfoMap.get(type);
    if (type.isArray()) {
      buildTypeInfo(type);
      GraalvmSupport.registerClass(type, fory.getConfigHash());
      return;
    }
    Serializer<?> serializer = null;
    if (typeInfo != null) {
      serializer = typeInfo.serializer;
      if (typeInfo.typeId != 0) {
        throw new IllegalArgumentException(
            String.format("Type %s has been registered with id %s", type, typeInfo.typeId));
      }
      String prevNamespace = typeInfo.decodeNamespace();
      String prevTypeName = typeInfo.decodeTypeName();
      if (!type.getSimpleName().equals(prevTypeName)) {
        throw new IllegalArgumentException(
            String.format(
                "Type %s has been registered with namespace %s type %s",
                type, prevNamespace, prevTypeName));
      }
    }
    int typeId;
    if (type.isEnum()) {
      typeId = Types.ENUM;
    } else {
      int structTypeId =
          shareMeta && isStructEvolving(type) ? Types.COMPATIBLE_STRUCT : Types.STRUCT;
      if (serializer != null) {
        if (isStructType(serializer)) {
          typeId = structTypeId;
        } else {
          typeId = Types.EXT;
        }
      } else {
        typeId = structTypeId;
      }
    }
    register(
        type,
        serializer,
        ReflectionUtils.getPackage(type),
        ReflectionUtils.getClassNameWithoutPackage(type),
        typeId,
        checkedUserTypeId);
  }

  @Override
  public void register(Class<?> type, String namespace, String typeName) {
    checkRegisterAllowed();
    Preconditions.checkArgument(
        !typeName.contains("."),
        "Typename %s should not contains `.`, please put it into namespace",
        typeName);
    TypeInfo typeInfo = classInfoMap.get(type);
    Serializer<?> serializer = null;
    if (typeInfo != null) {
      serializer = typeInfo.serializer;
      if (typeInfo.typeNameBytes != null) {
        String prevNamespace = typeInfo.decodeNamespace();
        String prevTypeName = typeInfo.decodeTypeName();
        if (!namespace.equals(prevNamespace) || typeName.equals(prevTypeName)) {
          throw new IllegalArgumentException(
              String.format(
                  "Type %s has been registered with namespace %s type %s",
                  type, prevNamespace, prevTypeName));
        }
      }
    }
    short xtypeId;
    if (serializer != null) {
      if (isStructType(serializer)) {
        xtypeId =
            (short)
                (shareMeta && isStructEvolving(type)
                    ? Types.NAMED_COMPATIBLE_STRUCT
                    : Types.NAMED_STRUCT);
      } else if (serializer instanceof EnumSerializer) {
        xtypeId = Types.NAMED_ENUM;
      } else {
        xtypeId = Types.NAMED_EXT;
      }
    } else {
      if (type.isEnum()) {
        xtypeId = Types.NAMED_ENUM;
      } else {
        xtypeId =
            (short)
                (shareMeta && isStructEvolving(type)
                    ? Types.NAMED_COMPATIBLE_STRUCT
                    : Types.NAMED_STRUCT);
      }
    }
    register(type, serializer, namespace, typeName, xtypeId, -1);
  }

  private void register(
      Class<?> type,
      Serializer<?> serializer,
      String namespace,
      String typeName,
      int typeId,
      int userTypeId) {
    TypeInfo typeInfo = newTypeInfo(type, serializer, namespace, typeName, typeId, userTypeId);
    String qualifiedName = qualifiedName(namespace, typeName);
    qualifiedType2TypeInfo.put(qualifiedName, typeInfo);
    extRegistry.registeredClasses.put(qualifiedName, type);
    GraalvmSupport.registerClass(type, fory.getConfigHash());
    if (serializer == null) {
      if (type.isEnum()) {
        typeInfo.serializer = new EnumSerializer(fory, (Class<Enum>) type);
      } else {
        AtomicBoolean updated = new AtomicBoolean(false);
        AtomicReference<Serializer> ref = new AtomicReference(null);
        typeInfo.serializer =
            new DeferedLazySerializer.DeferredLazyObjectSerializer(
                fory,
                type,
                () -> {
                  if (ref.get() == null) {
                    Class<? extends Serializer> c =
                        getObjectSerializerClass(
                            type,
                            shareMeta,
                            fory.getConfig().isCodeGenEnabled(),
                            sc -> ref.set(Serializers.newSerializer(fory, type, sc)));
                    ref.set(Serializers.newSerializer(fory, type, c));
                    if (!fory.getConfig().isAsyncCompilationEnabled()) {
                      updated.set(true);
                    }
                  }
                  return Tuple2.of(updated.get(), ref.get());
                });
      }
    }
    updateTypeInfo(type, typeInfo);
  }

  @Override
  public void registerUnion(Class<?> type, long userTypeId, Serializer<?> serializer) {
    checkRegisterAllowed();
    Preconditions.checkNotNull(serializer);
    int checkedUserTypeId = toUserTypeId(userTypeId);
    Preconditions.checkArgument(
        !containsUserTypeId(checkedUserTypeId), "Type id %s has been registered", userTypeId);
    TypeInfo typeInfo = classInfoMap.get(type);
    if (typeInfo != null && typeInfo.typeId != 0) {
      throw new IllegalArgumentException(
          String.format("Type %s has been registered with id %s", type, typeInfo.typeId));
    }
    int xtypeId = Types.TYPED_UNION;
    register(
        type,
        serializer,
        ReflectionUtils.getPackage(type),
        ReflectionUtils.getClassNameWithoutPackage(type),
        xtypeId,
        checkedUserTypeId);
  }

  @Override
  public void registerUnion(
      Class<?> type, String namespace, String typeName, Serializer<?> serializer) {
    checkRegisterAllowed();
    Preconditions.checkNotNull(serializer);
    Preconditions.checkArgument(
        !typeName.contains("."),
        "Typename %s should not contains `.`, please put it into namespace",
        typeName);
    TypeInfo typeInfo = classInfoMap.get(type);
    if (typeInfo != null && typeInfo.typeNameBytes != null) {
      String prevNamespace = typeInfo.decodeNamespace();
      String prevTypeName = typeInfo.decodeTypeName();
      if (!namespace.equals(prevNamespace) || typeName.equals(prevTypeName)) {
        throw new IllegalArgumentException(
            String.format(
                "Type %s has been registered with namespace %s type %s",
                type, prevNamespace, prevTypeName));
      }
    }
    int xtypeId = Types.NAMED_UNION;
    register(type, serializer, namespace, typeName, xtypeId, -1);
  }

  /**
   * Register type with given type id and serializer for type in fory type system.
   *
   * <p>Do not use this method to register custom type in java type system. Use {@link
   * #register(Class, String, String)} or {@link #register(Class, long)} instead.
   *
   * @param type type to register.
   * @param serializer serializer to register.
   * @param typeId type id to register.
   * @throws IllegalArgumentException if type id is too big.
   */
  @Internal
  public void registerForyType(Class<?> type, Serializer serializer, int typeId) {
    Preconditions.checkArgument(typeId < MAX_TYPE_ID, "Too big type id %s", typeId);
    register(
        type,
        serializer,
        ReflectionUtils.getPackage(type),
        ReflectionUtils.getClassNameWithoutPackage(type),
        typeId,
        -1);
  }

  private boolean isStructType(Serializer serializer) {
    if (serializer instanceof ObjectSerializer || serializer instanceof GeneratedSerializer) {
      return true;
    }
    return serializer instanceof DeferredLazyObjectSerializer;
  }

  private TypeInfo newTypeInfo(Class<?> type, Serializer<?> serializer, int typeId) {
    return newTypeInfo(type, serializer, typeId, INVALID_USER_TYPE_ID);
  }

  private TypeInfo newTypeInfo(
      Class<?> type, Serializer<?> serializer, int typeId, int userTypeId) {
    return newTypeInfo(
        type,
        serializer,
        ReflectionUtils.getPackage(type),
        ReflectionUtils.getClassNameWithoutPackage(type),
        typeId,
        userTypeId);
  }

  private TypeInfo newTypeInfo(
      Class<?> type, Serializer<?> serializer, String namespace, String typeName, int typeId) {
    return newTypeInfo(type, serializer, namespace, typeName, typeId, INVALID_USER_TYPE_ID);
  }

  private TypeInfo newTypeInfo(
      Class<?> type,
      Serializer<?> serializer,
      String namespace,
      String typeName,
      int typeId,
      int userTypeId) {
    MetaStringBytes fullClassNameBytes =
        metaStringResolver.getOrCreateMetaStringBytes(
            GENERIC_ENCODER.encode(type.getName(), MetaString.Encoding.UTF_8));
    MetaStringBytes nsBytes =
        metaStringResolver.getOrCreateMetaStringBytes(Encoders.encodePackage(namespace));
    MetaStringBytes classNameBytes =
        metaStringResolver.getOrCreateMetaStringBytes(Encoders.encodeTypeName(typeName));
    return new TypeInfo(
        type, fullClassNameBytes, nsBytes, classNameBytes, false, serializer, typeId, userTypeId);
  }

  public <T> void registerSerializer(Class<T> type, Class<? extends Serializer> serializerClass) {
    checkRegisterAllowed();
    registerSerializer(type, Serializers.newSerializer(fory, type, serializerClass));
  }

  public void registerSerializer(Class<?> type, Serializer<?> serializer) {
    checkRegisterAllowed();
    TypeInfo typeInfo = checkClassRegistration(type);
    if (!serializer.getClass().getPackage().getName().startsWith("org.apache.fory")) {
      SerializationUtils.validate(type, serializer.getClass());
    }
    int oldTypeId = typeInfo.typeId;
    int foryId = oldTypeId;

    if (foryId != Types.EXT && foryId != Types.NAMED_EXT) {
      if (foryId == Types.STRUCT || foryId == Types.COMPATIBLE_STRUCT) {
        foryId = Types.EXT;
      } else if (foryId == Types.NAMED_STRUCT || foryId == Types.NAMED_COMPATIBLE_STRUCT) {
        foryId = Types.NAMED_EXT;
      } else {
        throw new IllegalArgumentException(
            String.format("Can't register serializer for type %s with id %s", type, oldTypeId));
      }
    }
    typeInfo = typeInfo.copy(foryId);
    typeInfo.serializer = serializer;
    updateTypeInfo(type, typeInfo);
    if (typeInfo.typeNameBytes != null) {
      String qualifiedName = qualifiedName(typeInfo.decodeNamespace(), typeInfo.decodeTypeName());
      qualifiedType2TypeInfo.put(qualifiedName, typeInfo);
      TypeNameBytes typeNameBytes =
          new TypeNameBytes(typeInfo.namespaceBytes.hashCode, typeInfo.typeNameBytes.hashCode);
      compositeClassNameBytes2TypeInfo.put(typeNameBytes, typeInfo);
    }
  }

  @Override
  public void registerInternalSerializer(Class<?> type, Serializer<?> serializer) {
    checkRegisterAllowed();
    Class<?> unwrapped = TypeUtils.unwrap(type);
    if (unwrapped == char.class
        || unwrapped == void.class
        || type == char[].class
        || type == Character[].class) {
      return;
    }
    TypeInfo typeInfo = classInfoMap.get(type);
    if (typeInfo != null) {
      if (typeInfo.serializer == null) {
        typeInfo.serializer = serializer;
      }
      return;
    }
    // Determine appropriate type ID based on the type
    int typeId = determineTypeIdForClass(type);
    typeInfo = newTypeInfo(type, serializer, typeId);
    classInfoMap.put(type, typeInfo);
  }

  /**
   * Determine the appropriate xlang type ID for a class. For collection types, use the
   * collection-specific type IDs. For other types, use NAMED_STRUCT which writes namespace and
   * typename bytes.
   */
  private int determineTypeIdForClass(Class<?> type) {
    if (type.isArray()) {
      Class<?> componentType = type.getComponentType();
      if (componentType.isPrimitive()) {
        int elemTypeId = Types.getTypeId(fory, componentType);
        return Types.getPrimitiveArrayTypeId(elemTypeId);
      }
      return Types.LIST;
    }
    if (List.class.isAssignableFrom(type)) {
      return Types.LIST;
    } else if (Set.class.isAssignableFrom(type)) {
      return Types.SET;
    } else if (Map.class.isAssignableFrom(type)) {
      return Types.MAP;
    } else if (type.isEnum()) {
      return Types.ENUM;
    } else {
      // For unregistered classes, use NAMED_STRUCT so that class name is written
      return Types.NAMED_STRUCT;
    }
  }

  private TypeInfo checkClassRegistration(Class<?> type) {
    TypeInfo typeInfo = classInfoMap.get(type);
    Preconditions.checkArgument(
        typeInfo != null
            && (typeInfo.typeId != 0 || !type.getSimpleName().equals(typeInfo.decodeTypeName())),
        "Type %s should be registered with id or namespace+typename before register serializer",
        type);
    return typeInfo;
  }

  @Override
  public boolean isRegistered(Class<?> cls) {
    return classInfoMap.get(cls) != null;
  }

  @Override
  public boolean isRegisteredById(Class<?> cls) {
    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo == null) {
      return false;
    }
    int typeId = typeInfo.typeId;
    switch (typeId) {
      case Types.NAMED_COMPATIBLE_STRUCT:
      case Types.NAMED_ENUM:
      case Types.NAMED_UNION:
      case Types.NAMED_STRUCT:
      case Types.NAMED_EXT:
        return false;
      default:
        return true;
    }
  }

  @Override
  public boolean isRegisteredByName(Class<?> cls) {
    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo == null) {
      return false;
    }
    int typeId = typeInfo.typeId;
    switch (typeId) {
      case Types.NAMED_COMPATIBLE_STRUCT:
      case Types.NAMED_ENUM:
      case Types.NAMED_UNION:
      case Types.NAMED_STRUCT:
      case Types.NAMED_EXT:
        return true;
      default:
        return false;
    }
  }

  @Override
  public boolean isMonomorphic(Descriptor descriptor) {
    ForyField foryField = descriptor.getForyField();
    ForyField.Dynamic dynamic = foryField != null ? foryField.dynamic() : ForyField.Dynamic.AUTO;
    switch (dynamic) {
      case TRUE:
        return false;
      case FALSE:
        return true;
      default:
        Class<?> rawType = descriptor.getRawType();
        if (TypeUtils.isPrimitiveListClass(rawType)) {
          return true;
        }
        if (rawType == Object.class) {
          return false;
        }
        if (rawType.isEnum()) {
          return true;
        }
        if (Union.class.isAssignableFrom(rawType)) {
          return true;
        }
        if (rawType == UnknownStruct.class) {
          return false;
        }
        byte typeIdByte = getInternalTypeId(rawType);
        if (fory.isCompatible()) {
          return !Types.isUserDefinedType(typeIdByte) && typeIdByte != Types.UNKNOWN;
        }
        return typeIdByte != Types.UNKNOWN;
    }
  }

  @Override
  public boolean isMonomorphic(Class<?> clz) {
    if (TypeUtils.isPrimitiveListClass(clz)) {
      return true;
    }
    if (clz == Object.class) {
      return false;
    }
    if (TypeUtils.unwrap(clz).isPrimitive() || clz.isEnum() || clz == String.class) {
      return true;
    }
    if (clz.isArray()) {
      return true;
    }
    if (clz == UnknownEnum.class) {
      return true;
    }
    if (clz == UnknownStruct.class) {
      return false;
    }
    TypeInfo typeInfo = getTypeInfo(clz, false);
    if (typeInfo != null) {
      Serializer<?> s = typeInfo.serializer;
      if (s instanceof TimeSerializers.TimeSerializer
          || s instanceof MapLikeSerializer
          || s instanceof CollectionLikeSerializer
          || s instanceof UnionSerializer) {
        return true;
      }

      return s instanceof TimeSerializers.ImmutableTimeSerializer;
    }
    if (isMap(clz) || isCollection(clz)) {
      return true;
    }
    return false;
  }

  public boolean isBuildIn(Descriptor descriptor) {
    Class<?> rawType = descriptor.getRawType();
    if (TypeUtils.isPrimitiveListClass(rawType)) {
      return true;
    }
    byte typeIdByte = getInternalTypeId(descriptor);
    if (UnknownClass.class.isAssignableFrom(rawType)) {
      return false;
    }
    return !Types.isUserDefinedType(typeIdByte) && typeIdByte != Types.UNKNOWN;
  }

  @Override
  public TypeInfo getTypeInfo(Class<?> cls) {
    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo == null) {
      typeInfo = buildTypeInfo(cls);
    }
    return typeInfo;
  }

  @Override
  public TypeInfo getTypeInfo(Class<?> cls, boolean createIfAbsent) {
    if (createIfAbsent) {
      return getTypeInfo(cls);
    }
    return classInfoMap.get(cls);
  }

  public TypeInfo getTypeInfo(Class<?> cls, TypeInfoHolder classInfoHolder) {
    TypeInfo typeInfo = classInfoHolder.typeInfo;
    if (typeInfo.getCls() != cls) {
      typeInfo = classInfoMap.get(cls);
      if (typeInfo == null) {
        typeInfo = buildTypeInfo(cls);
      }
      classInfoHolder.typeInfo = typeInfo;
    }
    assert typeInfo.serializer != null;
    return typeInfo;
  }

  public TypeInfo getXtypeInfo(int typeId) {
    return getInternalTypeInfoByTypeId(typeId);
  }

  public TypeInfo getUserTypeInfo(String namespace, String typeName) {
    String name = qualifiedName(namespace, typeName);
    return qualifiedType2TypeInfo.get(name);
  }

  public TypeInfo getUserTypeInfo(int userTypeId) {
    return userTypeIdToTypeInfo.get(userTypeId);
  }

  // buildGenericType methods are inherited from TypeResolver

  private TypeInfo buildTypeInfo(Class<?> cls) {
    Serializer serializer;
    int typeId;
    if (isSet(cls)) {
      if (cls.isAssignableFrom(HashSet.class)) {
        cls = HashSet.class;
        serializer = new HashSetSerializer(fory);
      } else {
        serializer = getCollectionSerializer(cls);
      }
      typeId = Types.SET;
    } else if (isCollection(cls)) {
      if (cls.isAssignableFrom(ArrayList.class)) {
        cls = ArrayList.class;
        serializer = new ArrayListSerializer(fory);
      } else {
        serializer = getCollectionSerializer(cls);
      }
      typeId = Types.LIST;
    } else if (cls.isArray() && !cls.getComponentType().isPrimitive()) {
      serializer = new ArraySerializers.ObjectArraySerializer(fory, cls);
      typeId = Types.LIST;
    } else if (isMap(cls)) {
      if (cls.isAssignableFrom(HashMap.class)) {
        cls = HashMap.class;
        serializer = new HashMapSerializer(fory);
      } else {
        TypeInfo typeInfo = classInfoMap.get(cls);
        if (typeInfo != null
            && typeInfo.serializer != null
            && typeInfo.serializer instanceof MapLikeSerializer
            && ((MapLikeSerializer) typeInfo.serializer).supportCodegenHook()) {
          serializer = typeInfo.serializer;
        } else {
          serializer = new MapSerializer(fory, cls);
        }
      }
      typeId = Types.MAP;
    } else if (UnknownClass.class.isAssignableFrom(cls)) {
      serializer = UnknownClassSerializers.getSerializer(fory, "Unknown", cls);
      if (cls.isEnum()) {
        typeId = Types.ENUM;
      } else {
        typeId = shareMeta ? Types.COMPATIBLE_STRUCT : Types.STRUCT;
      }
    } else if (cls == Object.class) {
      // Object.class is handled as unknown type in xlang
      return getTypeInfo(cls);
    } else {
      Class<Enum> enclosingClass = (Class<Enum>) cls.getEnclosingClass();
      if (enclosingClass != null && enclosingClass.isEnum()) {
        TypeInfo enumInfo = getTypeInfo(enclosingClass);
        classInfoMap.put(cls, enumInfo);
        return enumInfo;
      } else {
        throw new ClassUnregisteredException(cls);
      }
    }
    TypeInfo info = newTypeInfo(cls, serializer, typeId);
    classInfoMap.put(cls, info);
    return info;
  }

  private Serializer<?> getCollectionSerializer(Class<?> cls) {
    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo != null
        && typeInfo.serializer != null
        && typeInfo.serializer instanceof CollectionLikeSerializer
        && ((CollectionLikeSerializer) (typeInfo.serializer)).supportCodegenHook()) {
      return typeInfo.serializer;
    }
    return new CollectionSerializer(fory, cls);
  }

  private void registerDefaultTypes() {
    // Boolean types
    registerType(
        Types.BOOL, Boolean.class, new PrimitiveSerializers.BooleanSerializer(fory, Boolean.class));
    registerType(
        Types.BOOL, boolean.class, new PrimitiveSerializers.BooleanSerializer(fory, boolean.class));
    registerType(Types.BOOL, AtomicBoolean.class, new Serializers.AtomicBooleanSerializer(fory));

    // Byte types
    registerType(
        Types.UINT8, Byte.class, new PrimitiveSerializers.ByteSerializer(fory, Byte.class));
    registerType(
        Types.UINT8, byte.class, new PrimitiveSerializers.ByteSerializer(fory, byte.class));
    registerType(Types.INT8, Byte.class, new PrimitiveSerializers.ByteSerializer(fory, Byte.class));
    registerType(Types.INT8, byte.class, new PrimitiveSerializers.ByteSerializer(fory, byte.class));
    registerType(Types.UINT8, Uint8.class, new UnsignedSerializers.Uint8Serializer(fory));

    // Short types
    registerType(
        Types.UINT16, Short.class, new PrimitiveSerializers.ShortSerializer(fory, Short.class));
    registerType(
        Types.UINT16, short.class, new PrimitiveSerializers.ShortSerializer(fory, short.class));
    registerType(
        Types.INT16, Short.class, new PrimitiveSerializers.ShortSerializer(fory, Short.class));
    registerType(
        Types.INT16, short.class, new PrimitiveSerializers.ShortSerializer(fory, short.class));
    registerType(Types.UINT16, Uint16.class, new UnsignedSerializers.Uint16Serializer(fory));

    // Integer types
    registerType(
        Types.UINT32, Integer.class, new PrimitiveSerializers.IntSerializer(fory, Integer.class));
    registerType(Types.UINT32, int.class, new PrimitiveSerializers.IntSerializer(fory, int.class));
    registerType(Types.UINT32, AtomicInteger.class, new Serializers.AtomicIntegerSerializer(fory));
    registerType(
        Types.INT32, Integer.class, new PrimitiveSerializers.IntSerializer(fory, Integer.class));
    registerType(Types.INT32, int.class, new PrimitiveSerializers.IntSerializer(fory, int.class));
    registerType(Types.INT32, AtomicInteger.class, new Serializers.AtomicIntegerSerializer(fory));
    registerType(
        Types.VAR_UINT32, Integer.class, new PrimitiveSerializers.VarUint32Serializer(fory));
    registerType(Types.VAR_UINT32, int.class, new PrimitiveSerializers.VarUint32Serializer(fory));
    registerType(
        Types.VARINT32, Integer.class, new PrimitiveSerializers.IntSerializer(fory, Integer.class));
    registerType(
        Types.VARINT32, int.class, new PrimitiveSerializers.IntSerializer(fory, int.class));
    registerType(
        Types.VARINT32, AtomicInteger.class, new Serializers.AtomicIntegerSerializer(fory));
    registerType(Types.UINT32, Uint32.class, new UnsignedSerializers.Uint32Serializer(fory));

    // Long types
    registerType(
        Types.UINT64, Long.class, new PrimitiveSerializers.LongSerializer(fory, Long.class));
    registerType(
        Types.UINT64, long.class, new PrimitiveSerializers.LongSerializer(fory, long.class));
    registerType(Types.UINT64, AtomicLong.class, new Serializers.AtomicLongSerializer(fory));
    registerType(
        Types.TAGGED_UINT64, Long.class, new PrimitiveSerializers.LongSerializer(fory, Long.class));
    registerType(
        Types.TAGGED_UINT64, long.class, new PrimitiveSerializers.LongSerializer(fory, long.class));
    registerType(Types.TAGGED_UINT64, AtomicLong.class, new Serializers.AtomicLongSerializer(fory));
    registerType(
        Types.INT64, Long.class, new PrimitiveSerializers.LongSerializer(fory, Long.class));
    registerType(
        Types.INT64, long.class, new PrimitiveSerializers.LongSerializer(fory, long.class));
    registerType(Types.INT64, AtomicLong.class, new Serializers.AtomicLongSerializer(fory));
    registerType(
        Types.TAGGED_INT64, Long.class, new PrimitiveSerializers.LongSerializer(fory, Long.class));
    registerType(
        Types.TAGGED_INT64, long.class, new PrimitiveSerializers.LongSerializer(fory, long.class));
    registerType(Types.TAGGED_INT64, AtomicLong.class, new Serializers.AtomicLongSerializer(fory));
    registerType(Types.VAR_UINT64, Long.class, new PrimitiveSerializers.VarUint64Serializer(fory));
    registerType(Types.VAR_UINT64, long.class, new PrimitiveSerializers.VarUint64Serializer(fory));
    registerType(
        Types.VARINT64, Long.class, new PrimitiveSerializers.LongSerializer(fory, Long.class));
    registerType(
        Types.VARINT64, long.class, new PrimitiveSerializers.LongSerializer(fory, long.class));
    registerType(Types.VARINT64, AtomicLong.class, new Serializers.AtomicLongSerializer(fory));

    // Float types
    registerType(
        Types.FLOAT32, Float.class, new PrimitiveSerializers.FloatSerializer(fory, Float.class));
    registerType(
        Types.FLOAT32, float.class, new PrimitiveSerializers.FloatSerializer(fory, float.class));
    registerType(
        Types.FLOAT64, Double.class, new PrimitiveSerializers.DoubleSerializer(fory, Double.class));
    registerType(
        Types.FLOAT64, double.class, new PrimitiveSerializers.DoubleSerializer(fory, double.class));

    // String types
    registerType(Types.STRING, String.class, new StringSerializer(fory));
    registerType(Types.STRING, StringBuilder.class, new Serializers.StringBuilderSerializer(fory));
    registerType(Types.STRING, StringBuffer.class, new Serializers.StringBufferSerializer(fory));

    // Time types
    registerType(Types.DURATION, Duration.class, new TimeSerializers.DurationSerializer(fory));
    registerType(Types.TIMESTAMP, Instant.class, new TimeSerializers.InstantSerializer(fory));
    registerType(Types.TIMESTAMP, Date.class, new TimeSerializers.DateSerializer(fory));
    registerType(Types.TIMESTAMP, java.sql.Date.class, new TimeSerializers.SqlDateSerializer(fory));
    registerType(Types.TIMESTAMP, Timestamp.class, new TimeSerializers.TimestampSerializer(fory));
    registerType(
        Types.TIMESTAMP, LocalDateTime.class, new TimeSerializers.LocalDateTimeSerializer(fory));
    registerType(Types.DATE, LocalDate.class, new TimeSerializers.LocalDateSerializer(fory));

    // Decimal types
    registerType(Types.DECIMAL, BigDecimal.class, new Serializers.BigDecimalSerializer(fory));
    registerType(Types.DECIMAL, BigInteger.class, new Serializers.BigIntegerSerializer(fory));

    // Binary types
    registerType(Types.BINARY, byte[].class, new ArraySerializers.ByteArraySerializer(fory));
    @SuppressWarnings("unchecked")
    Class<java.nio.ByteBuffer> heapByteBufferClass =
        (Class<java.nio.ByteBuffer>) Platform.HEAP_BYTE_BUFFER_CLASS;
    registerType(
        Types.BINARY,
        Platform.HEAP_BYTE_BUFFER_CLASS,
        new org.apache.fory.serializer.BufferSerializers.ByteBufferSerializer(
            fory, heapByteBufferClass));
    @SuppressWarnings("unchecked")
    Class<java.nio.ByteBuffer> directByteBufferClass =
        (Class<java.nio.ByteBuffer>) Platform.DIRECT_BYTE_BUFFER_CLASS;
    registerType(
        Types.BINARY,
        Platform.DIRECT_BYTE_BUFFER_CLASS,
        new org.apache.fory.serializer.BufferSerializers.ByteBufferSerializer(
            fory, directByteBufferClass));

    // Primitive arrays
    registerType(
        Types.BOOL_ARRAY, boolean[].class, new ArraySerializers.BooleanArraySerializer(fory));
    registerType(Types.INT16_ARRAY, short[].class, new ArraySerializers.ShortArraySerializer(fory));
    registerType(Types.INT32_ARRAY, int[].class, new ArraySerializers.IntArraySerializer(fory));
    registerType(Types.INT64_ARRAY, long[].class, new ArraySerializers.LongArraySerializer(fory));
    registerType(
        Types.FLOAT32_ARRAY, float[].class, new ArraySerializers.FloatArraySerializer(fory));
    registerType(
        Types.FLOAT64_ARRAY, double[].class, new ArraySerializers.DoubleArraySerializer(fory));

    // Primitive lists
    registerType(
        Types.BOOL_ARRAY, BoolList.class, new PrimitiveListSerializers.BoolListSerializer(fory));
    registerType(
        Types.INT8_ARRAY, Int8List.class, new PrimitiveListSerializers.Int8ListSerializer(fory));
    registerType(
        Types.INT16_ARRAY, Int16List.class, new PrimitiveListSerializers.Int16ListSerializer(fory));
    registerType(
        Types.INT32_ARRAY, Int32List.class, new PrimitiveListSerializers.Int32ListSerializer(fory));
    registerType(
        Types.INT64_ARRAY, Int64List.class, new PrimitiveListSerializers.Int64ListSerializer(fory));
    registerType(
        Types.UINT8_ARRAY, Uint8List.class, new PrimitiveListSerializers.Uint8ListSerializer(fory));
    registerType(
        Types.UINT16_ARRAY,
        Uint16List.class,
        new PrimitiveListSerializers.Uint16ListSerializer(fory));
    registerType(
        Types.UINT32_ARRAY,
        Uint32List.class,
        new PrimitiveListSerializers.Uint32ListSerializer(fory));
    registerType(
        Types.UINT64_ARRAY,
        Uint64List.class,
        new PrimitiveListSerializers.Uint64ListSerializer(fory));
    registerType(
        Types.FLOAT32_ARRAY,
        Float32List.class,
        new PrimitiveListSerializers.Float32ListSerializer(fory));
    registerType(
        Types.FLOAT64_ARRAY,
        Float64List.class,
        new PrimitiveListSerializers.Float64ListSerializer(fory));

    // Collections
    registerType(Types.LIST, ArrayList.class, new ArrayListSerializer(fory));
    registerType(
        Types.LIST,
        Object[].class,
        new ArraySerializers.ObjectArraySerializer(fory, Object[].class));
    registerType(Types.LIST, List.class, new XlangListDefaultSerializer(fory, List.class));
    registerType(
        Types.LIST, Collection.class, new XlangListDefaultSerializer(fory, Collection.class));

    // Sets
    registerType(Types.SET, HashSet.class, new HashSetSerializer(fory));
    registerType(
        Types.SET,
        LinkedHashSet.class,
        new org.apache.fory.serializer.collection.CollectionSerializers.LinkedHashSetSerializer(
            fory));
    registerType(Types.SET, Set.class, new XlangSetDefaultSerializer(fory, Set.class));

    // Maps
    registerType(
        Types.MAP,
        HashMap.class,
        new org.apache.fory.serializer.collection.MapSerializers.HashMapSerializer(fory));
    registerType(
        Types.MAP,
        LinkedHashMap.class,
        new org.apache.fory.serializer.collection.MapSerializers.LinkedHashMapSerializer(fory));
    registerType(Types.MAP, Map.class, new XlangMapSerializer(fory, Map.class));

    registerUnionTypes();
  }

  private void registerType(int xtypeId, Class<?> type, Serializer<?> serializer) {
    TypeInfo typeInfo = newTypeInfo(type, serializer, xtypeId, INVALID_USER_TYPE_ID);
    classInfoMap.put(type, typeInfo);
    if (getInternalTypeInfoByTypeId(xtypeId) == null) {
      putInternalTypeInfo(xtypeId, typeInfo);
    }
  }

  private void registerUnionTypes() {
    Class<?>[] unionClasses =
        new Class<?>[] {
          org.apache.fory.type.union.Union.class,
          org.apache.fory.type.union.Union2.class,
          org.apache.fory.type.union.Union3.class,
          org.apache.fory.type.union.Union4.class,
          org.apache.fory.type.union.Union5.class,
          org.apache.fory.type.union.Union6.class
        };
    for (Class<?> cls : unionClasses) {
      @SuppressWarnings("unchecked")
      Class<? extends org.apache.fory.type.union.Union> unionCls =
          (Class<? extends org.apache.fory.type.union.Union>) cls;
      UnionSerializer serializer = new UnionSerializer(fory, unionCls);
      TypeInfo typeInfo = newTypeInfo(cls, serializer, Types.UNION, INVALID_USER_TYPE_ID);
      classInfoMap.put(cls, typeInfo);
    }
    putInternalTypeInfo(Types.UNION, classInfoMap.get(org.apache.fory.type.union.Union.class));
  }

  public TypeInfo writeTypeInfo(MemoryBuffer buffer, Object obj) {
    TypeInfo typeInfo = getTypeInfo(obj.getClass(), classInfoCache);
    writeTypeInfo(buffer, typeInfo);
    return typeInfo;
  }

  @Override
  protected TypeDef buildTypeDef(TypeInfo typeInfo) {
    TypeDef typeDef =
        typeDefMap.computeIfAbsent(typeInfo.cls, cls -> TypeDef.buildTypeDef(fory, cls));
    typeInfo.typeDef = typeDef;
    return typeDef;
  }

  @Override
  public <T> Serializer<T> getSerializer(Class<T> cls) {
    return (Serializer) getTypeInfo(cls).serializer;
  }

  @Override
  public Serializer<?> getRawSerializer(Class<?> cls) {
    return getTypeInfo(cls).serializer;
  }

  @Override
  public <T> void setSerializer(Class<T> cls, Serializer<T> serializer) {
    getTypeInfo(cls).serializer = serializer;
  }

  @Override
  public <T> void setSerializerIfAbsent(Class<T> cls, Serializer<T> serializer) {
    TypeInfo typeInfo = classInfoMap.get(cls);
    Preconditions.checkNotNull(typeInfo);
    Preconditions.checkNotNull(typeInfo.serializer);
  }

  // nilTypeInfo and nilTypeInfoHolder are inherited from TypeResolver

  @Override
  protected TypeInfo getListTypeInfo() {
    fory.incReadDepth();
    GenericType genericType = generics.nextGenericType();
    fory.decDepth();
    if (genericType != null) {
      return getOrBuildTypeInfo(genericType.getCls());
    }
    return getInternalTypeInfoByTypeId(Types.LIST);
  }

  @Override
  protected TypeInfo getTimestampTypeInfo() {
    fory.incReadDepth();
    GenericType genericType = generics.nextGenericType();
    fory.decDepth();
    if (genericType != null) {
      return getOrBuildTypeInfo(genericType.getCls());
    }
    return getInternalTypeInfoByTypeId(Types.TIMESTAMP);
  }

  private TypeInfo getOrBuildTypeInfo(Class<?> cls) {
    TypeInfo typeInfo = classInfoMap.get(cls);
    if (typeInfo == null) {
      typeInfo = buildTypeInfo(cls);
      classInfoMap.put(cls, typeInfo);
    }
    return typeInfo;
  }

  @Override
  protected TypeInfo loadBytesToTypeInfo(
      MetaStringBytes packageBytes, MetaStringBytes simpleClassNameBytes) {
    // Default to NAMED_STRUCT when called without internalTypeId
    return loadBytesToTypeInfoWithTypeId(Types.NAMED_STRUCT, packageBytes, simpleClassNameBytes);
  }

  @Override
  protected TypeInfo loadBytesToTypeInfo(
      int typeId, MetaStringBytes packageBytes, MetaStringBytes simpleClassNameBytes) {
    return loadBytesToTypeInfoWithTypeId(typeId, packageBytes, simpleClassNameBytes);
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
        compositeClassNameBytes2TypeInfo.put(typeNameBytes, newTypeInfo);
      }
      return newTypeInfo;
    }
    return typeInfo;
  }

  private TypeInfo loadBytesToTypeInfoWithTypeId(
      int internalTypeId, MetaStringBytes packageBytes, MetaStringBytes simpleClassNameBytes) {
    TypeNameBytes typeNameBytes =
        new TypeNameBytes(packageBytes.hashCode, simpleClassNameBytes.hashCode);
    TypeInfo typeInfo = compositeClassNameBytes2TypeInfo.get(typeNameBytes);
    if (typeInfo == null) {
      typeInfo =
          populateBytesToTypeInfo(
              internalTypeId, typeNameBytes, packageBytes, simpleClassNameBytes);
    }
    return typeInfo;
  }

  private TypeInfo populateBytesToTypeInfo(
      int typeId,
      TypeNameBytes typeNameBytes,
      MetaStringBytes packageBytes,
      MetaStringBytes simpleClassNameBytes) {
    String namespace = packageBytes.decode(PACKAGE_DECODER);
    String typeName = simpleClassNameBytes.decode(TYPE_NAME_DECODER);
    String qualifiedName = qualifiedName(namespace, typeName);
    TypeInfo typeInfo = qualifiedType2TypeInfo.get(qualifiedName);
    if (typeInfo == null) {
      String msg = String.format("Class %s not registered", qualifiedName);
      Class<?> type = null;
      if (config.deserializeUnknownClass()) {
        LOG.warn(msg);
        switch (typeId) {
          case Types.NAMED_ENUM:
          case Types.NAMED_STRUCT:
          case Types.NAMED_COMPATIBLE_STRUCT:
            type =
                UnknownClass.getUnknowClass(
                    qualifiedName, isEnum(typeId), 0, config.isMetaShareEnabled());
            break;
          case Types.NAMED_EXT:
            throw new SerializerUnregisteredException(qualifiedName);
          default:
            break;
        }
      } else {
        throw new ClassUnregisteredException(qualifiedName);
      }
      MetaStringBytes fullClassNameBytes =
          metaStringResolver.getOrCreateMetaStringBytes(
              PACKAGE_ENCODER.encode(qualifiedName, MetaString.Encoding.UTF_8));
      typeInfo =
          new TypeInfo(
              type,
              fullClassNameBytes,
              packageBytes,
              simpleClassNameBytes,
              false,
              null,
              NOT_SUPPORT_XLANG,
              INVALID_USER_TYPE_ID);
      if (UnknownClass.class.isAssignableFrom(TypeUtils.getComponentIfArray(type))) {
        typeInfo.serializer = UnknownClassSerializers.getSerializer(fory, qualifiedName, type);
      }
    }
    compositeClassNameBytes2TypeInfo.put(typeNameBytes, typeInfo);
    return typeInfo;
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
            (o1, o2) -> {
              int typeId1 = getInternalTypeId(o1);
              int typeId2 = getInternalTypeId(o2);
              if (typeId1 == typeId2) {
                return getFieldSortKey(o1).compareTo(getFieldSortKey(o2));
              } else {
                return typeId1 - typeId2;
              }
            })
        .setOtherDescriptorComparator(Comparator.comparing(TypeResolver::getFieldSortKey))
        .sort();
  }

  private byte getInternalTypeId(Descriptor descriptor) {
    Class<?> cls = descriptor.getRawType();
    if (cls.isArray() && cls.getComponentType().isPrimitive()) {
      return (byte) Types.getDescriptorTypeId(fory, descriptor);
    }
    return getInternalTypeId(cls);
  }

  private byte getInternalTypeId(Class<?> cls) {
    if (cls == Object.class) {
      return Types.UNKNOWN;
    }
    if (isSet(cls)) {
      return Types.SET;
    }
    if (isCollection(cls)) {
      return Types.LIST;
    }
    if (cls.isArray() && !cls.getComponentType().isPrimitive()) {
      return Types.LIST;
    }
    if (isMap(cls)) {
      return Types.MAP;
    }
    if (isRegistered(cls)) {
      return (byte) getTypeInfo(cls).getTypeId();
    } else {
      if (cls.isEnum()) {
        return Types.ENUM;
      }
      if (cls.isArray()) {
        return Types.LIST;
      }
      if (ReflectionUtils.isMonomorphic(cls)) {
        throw new UnsupportedOperationException(cls + " is not supported for xlang serialization");
      }
      return Types.UNKNOWN;
    }
  }

  // getFieldDescriptors is inherited from TypeResolver

  // getTypeDef is inherited from TypeResolver

  @Override
  public Class<? extends Serializer> getSerializerClass(Class<?> cls) {
    return getSerializer(cls).getClass();
  }

  @Override
  public Class<? extends Serializer> getSerializerClass(Class<?> cls, boolean codegen) {
    return getSerializer(cls).getClass();
  }

  private boolean isEnum(int internalTypeId) {
    return internalTypeId == Types.ENUM || internalTypeId == Types.NAMED_ENUM;
  }

  /**
   * Ensure all serializers for registered classes are compiled at GraalVM build time. This method
   * should be called after all classes are registered.
   */
  @Override
  public void ensureSerializersCompiled() {
    classInfoMap.forEach(
        (cls, classInfo) -> {
          GraalvmSupport.registerClass(cls, fory.getConfigHash());
          if (classInfo.serializer != null) {
            // Trigger serializer initialization and resolution for deferred serializers
            if (classInfo.serializer
                instanceof DeferedLazySerializer.DeferredLazyObjectSerializer) {
              ((DeferedLazySerializer.DeferredLazyObjectSerializer) classInfo.serializer)
                  .resolveSerializer();
            } else {
              classInfo.serializer.getClass();
            }
          }
          // For enums at GraalVM build time, also handle anonymous enum value classes
          if (cls.isEnum() && GraalvmSupport.isGraalBuildtime()) {
            for (Object enumConstant : cls.getEnumConstants()) {
              Class<?> enumValueClass = enumConstant.getClass();
              if (enumValueClass != cls) {
                getSerializer(enumValueClass);
              }
            }
          }
        });
  }
}
