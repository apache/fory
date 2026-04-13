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

import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.annotation.Internal;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.collection.BiMap;
import org.apache.fory.collection.ConcurrentIdentityMap;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.meta.EncodedMetaString;
import org.apache.fory.meta.Encoders;
import org.apache.fory.meta.MetaString;
import org.apache.fory.meta.MetaStringEncoder;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.util.GraalvmSupport;

/**
 * Shared caches reused by multiple equivalent {@link org.apache.fory.Fory} instances.
 *
 * <p>A {@code SharedRegistry} is scoped to one effective Fory config/mode family. Equivalent
 * runtimes may share the same registry, but incompatible runtimes must not. In particular, the
 * registered serializer and registered {@link TypeInfo} caches below assume one config scope and
 * therefore are keyed only by class.
 */
@Internal
public final class SharedRegistry {
  private static final int MAX_CACHED_ENCODED_META_STRINGS = 32768;
  private static final int MAX_CACHED_ENCODED_META_STRING_LENGTH = 2048;

  final ConcurrentIdentityMap<Class<?>, TypeDef> typeDefMap = new ConcurrentIdentityMap<>();
  final ConcurrentIdentityMap<Class<?>, TypeDef> currentLayerTypeDef =
      new ConcurrentIdentityMap<>();
  final ConcurrentHashMap<Long, TypeDef> typeDefById = new ConcurrentHashMap<>();
  final ConcurrentHashMap<Tuple2<Class<?>, Boolean>, SortedMap<Member, Descriptor>>
      descriptorsCache = new ConcurrentHashMap<>();
  final ConcurrentHashMap<FieldDescriptorsKey, List<Descriptor>> fieldDescriptorsCache =
      new ConcurrentHashMap<>();
  final ConcurrentHashMap<TypeDefDescriptorsKey, List<Descriptor>> typeDefDescriptorsCache =
      new ConcurrentHashMap<>();
  final ConcurrentHashMap<FieldDescriptorGrouperKey, DescriptorGrouper>
      fieldDescriptorGrouperCache = new ConcurrentHashMap<>();
  final ConcurrentHashMap<TypeDefDescriptorGrouperKey, DescriptorGrouper>
      typeDefDescriptorGrouperCache = new ConcurrentHashMap<>();
  final ConcurrentHashMap<List<ClassLoader>, CodeGenerator> codeGeneratorMap =
      new ConcurrentHashMap<>();
  final ConcurrentHashMap<MetaStringKey, EncodedMetaString> metaStringMap =
      new ConcurrentHashMap<>();
  final ConcurrentHashMap<EncodedMetaStringKey, EncodedMetaString> encodedMetaStringMap =
      new ConcurrentHashMap<>();
  final ConcurrentIdentityMap<Class<?>, TypeInfo> registeredTypeInfoCache =
      new ConcurrentIdentityMap<>();
  final ConcurrentIdentityMap<Class<?>, Serializer<?>> registeredSerializerCache =
      new ConcurrentIdentityMap<>();
  private final Object metaStringCacheLock = new Object();
  volatile IdentityHashMap<Class<?>, Integer> registeredClassIdMap;
  volatile BiMap<String, Class<?>> registeredClasses;

  synchronized void setRegistrationIfAbsent(
      IdentityHashMap<Class<?>, Integer> candidateRegisteredClassIdMap,
      BiMap<String, Class<?>> candidateRegisteredClasses) {
    Objects.requireNonNull(candidateRegisteredClassIdMap);
    Objects.requireNonNull(candidateRegisteredClasses);
    if (registeredClassIdMap == null) {
      registeredClassIdMap = candidateRegisteredClassIdMap;
      registeredClasses = candidateRegisteredClasses;
    }
  }

  synchronized IdentityHashMap<Class<?>, Integer> getRegisteredClassIdMap() {
    return Objects.requireNonNull(registeredClassIdMap);
  }

  synchronized BiMap<String, Class<?>> getRegisteredClasses() {
    return Objects.requireNonNull(registeredClasses);
  }

  Serializer<?> getRegisteredSerializer(Class<?> type) {
    return registeredSerializerCache.get(type);
  }

  Serializer<?> cacheRegisteredSerializer(Class<?> type, Serializer<?> serializer) {
    Serializer<?> existing = registeredSerializerCache.putIfAbsent(type, serializer);
    if (existing == null) {
      return serializer;
    }
    if (existing.getClass() != serializer.getClass()) {
      throw new IllegalArgumentException(
          String.format(
              "Conflicting shareable serializer registration for %s. Existing=%s, new=%s",
              type.getName(), existing, serializer));
    }
    return existing;
  }

  TypeInfo cacheRegisteredTypeInfo(Class<?> type, TypeInfo typeInfo) {
    TypeInfo existing = registeredTypeInfoCache.putIfAbsent(type, typeInfo);
    return existing == null ? typeInfo : existing;
  }

  EncodedMetaString getPackageEncodedMetaString(String string) {
    return getEncodedMetaString(
        string,
        Encoders.PACKAGE_ENCODER,
        Encoders.computePackageEncoding(string),
        Encoders.PACKAGE_ENCODER_TYPE_KEY);
  }

  EncodedMetaString getTypeNameEncodedMetaString(String string) {
    return getEncodedMetaString(
        string,
        Encoders.TYPE_NAME_ENCODER,
        Encoders.computeTypeNameEncoding(string),
        Encoders.TYPE_NAME_ENCODER_TYPE_KEY);
  }

  private EncodedMetaString getEncodedMetaString(
      String string,
      MetaStringEncoder encoder,
      MetaString.Encoding encoding,
      String encoderTypeKey) {
    if (string.isEmpty()) {
      return EncodedMetaString.EMPTY;
    }
    MetaStringKey key = new MetaStringKey(string, encoderTypeKey, encoding);
    EncodedMetaString encodedMetaString = metaStringMap.get(key);
    if (encodedMetaString != null) {
      return encodedMetaString;
    }
    EncodedMetaString candidate = encoder.encodeBinary(string, encoding);
    EncodedMetaStringKey encodedMetaStringKey =
        new EncodedMetaStringKey(candidate.hash, candidate.bytes);
    synchronized (metaStringCacheLock) {
      encodedMetaString = metaStringMap.get(key);
      if (encodedMetaString != null) {
        return encodedMetaString;
      }
      encodedMetaString = encodedMetaStringMap.get(encodedMetaStringKey);
      if (encodedMetaString == null) {
        if (!shouldCacheEncodedMetaString(candidate)) {
          return candidate;
        }
        encodedMetaStringMap.put(encodedMetaStringKey, candidate);
        encodedMetaString = candidate;
      }
      metaStringMap.put(key, encodedMetaString);
      return encodedMetaString;
    }
  }

  public EncodedMetaString getOrCreateEncodedMetaString(byte[] bytes, long hash) {
    if (bytes.length == 0) {
      return EncodedMetaString.EMPTY;
    }
    EncodedMetaStringKey key = new EncodedMetaStringKey(hash, bytes);
    EncodedMetaString encodedMetaString = encodedMetaStringMap.get(key);
    if (encodedMetaString != null) {
      return encodedMetaString;
    }
    if (bytes.length > MAX_CACHED_ENCODED_META_STRING_LENGTH) {
      return new EncodedMetaString(bytes, hash);
    }
    synchronized (metaStringCacheLock) {
      encodedMetaString = encodedMetaStringMap.get(key);
      if (encodedMetaString != null) {
        return encodedMetaString;
      }
      if (encodedMetaStringMap.size() >= MAX_CACHED_ENCODED_META_STRINGS) {
        return new EncodedMetaString(bytes, hash);
      }
      EncodedMetaString candidate = new EncodedMetaString(bytes, hash);
      encodedMetaStringMap.put(key, candidate);
      return candidate;
    }
  }

  private boolean shouldCacheEncodedMetaString(EncodedMetaString encodedMetaString) {
    return shouldCacheEncodedMetaStringLength(encodedMetaString)
        && encodedMetaStringMap.size() < MAX_CACHED_ENCODED_META_STRINGS;
  }

  private boolean shouldCacheEncodedMetaStringLength(EncodedMetaString encodedMetaString) {
    return encodedMetaString.bytes.length <= MAX_CACHED_ENCODED_META_STRING_LENGTH;
  }

  TypeDef getOrCreateTypeDef(TypeDef typeDef) {
    TypeDef existingTypeDef = typeDefById.putIfAbsent(typeDef.getId(), typeDef);
    return existingTypeDef == null ? typeDef : existingTypeDef;
  }

  List<Descriptor> getOrCreateFieldDescriptors(
      Class<?> type, boolean searchParent, java.util.function.Supplier<List<Descriptor>> factory) {
    if (GraalvmSupport.isGraalBuildtime()) {
      return Collections.unmodifiableList(new ArrayList<>(factory.get()));
    }
    FieldDescriptorsKey key = new FieldDescriptorsKey(type, searchParent);
    return fieldDescriptorsCache.computeIfAbsent(
        key, ignored -> Collections.unmodifiableList(new ArrayList<>(factory.get())));
  }

  public List<Descriptor> getOrCreateTypeDefDescriptors(
      TypeDef typeDef, Class<?> type, java.util.function.Supplier<List<Descriptor>> factory) {
    if (GraalvmSupport.isGraalBuildtime()) {
      return Collections.unmodifiableList(new ArrayList<>(factory.get()));
    }
    TypeDefDescriptorsKey key = new TypeDefDescriptorsKey(typeDef.getId(), type);
    return typeDefDescriptorsCache.computeIfAbsent(
        key, ignored -> Collections.unmodifiableList(new ArrayList<>(factory.get())));
  }

  DescriptorGrouper getOrCreateFieldDescriptorGrouper(
      Class<?> type,
      boolean searchParent,
      boolean descriptorsGroupedOrdered,
      java.util.function.Function<Descriptor, Descriptor> descriptorUpdator,
      java.util.function.Supplier<DescriptorGrouper> factory) {
    if (GraalvmSupport.isGraalBuildtime()) {
      return factory.get();
    }
    FieldDescriptorGrouperKey key =
        new FieldDescriptorGrouperKey(
            new FieldDescriptorsKey(type, searchParent),
            descriptorsGroupedOrdered,
            descriptorUpdator);
    return fieldDescriptorGrouperCache.computeIfAbsent(key, ignored -> factory.get());
  }

  DescriptorGrouper getOrCreateTypeDefDescriptorGrouper(
      TypeDef typeDef,
      Class<?> type,
      boolean descriptorsGroupedOrdered,
      java.util.function.Function<Descriptor, Descriptor> descriptorUpdator,
      java.util.function.Supplier<DescriptorGrouper> factory) {
    if (GraalvmSupport.isGraalBuildtime()) {
      return factory.get();
    }
    TypeDefDescriptorGrouperKey key =
        new TypeDefDescriptorGrouperKey(
            new TypeDefDescriptorsKey(typeDef.getId(), type),
            descriptorsGroupedOrdered,
            descriptorUpdator);
    return typeDefDescriptorGrouperCache.computeIfAbsent(key, ignored -> factory.get());
  }

  private static final class MetaStringKey {
    private final String string;
    private final String encoderTypeKey;
    private final MetaString.Encoding encoding;

    private MetaStringKey(String string, String encoderTypeKey, MetaString.Encoding encoding) {
      this.string = Objects.requireNonNull(string);
      this.encoderTypeKey = Objects.requireNonNull(encoderTypeKey);
      this.encoding = Objects.requireNonNull(encoding);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof MetaStringKey)) {
        return false;
      }
      MetaStringKey that = (MetaStringKey) o;
      return string.equals(that.string)
          && encoderTypeKey.equals(that.encoderTypeKey)
          && encoding == that.encoding;
    }

    @Override
    public int hashCode() {
      return Objects.hash(string, encoderTypeKey, encoding);
    }
  }

  private static final class EncodedMetaStringKey {
    private final long hash;
    private final byte[] bytes;

    private EncodedMetaStringKey(long hash, byte[] bytes) {
      this.hash = hash;
      this.bytes = Objects.requireNonNull(bytes);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof EncodedMetaStringKey)) {
        return false;
      }
      EncodedMetaStringKey that = (EncodedMetaStringKey) o;
      return hash == that.hash && java.util.Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
      return 31 * Long.hashCode(hash) + java.util.Arrays.hashCode(bytes);
    }
  }

  private static final class FieldDescriptorsKey {
    private final Class<?> type;
    private final boolean searchParent;

    private FieldDescriptorsKey(Class<?> type, boolean searchParent) {
      this.type = Objects.requireNonNull(type);
      this.searchParent = searchParent;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof FieldDescriptorsKey)) {
        return false;
      }
      FieldDescriptorsKey that = (FieldDescriptorsKey) o;
      return type == that.type && searchParent == that.searchParent;
    }

    @Override
    public int hashCode() {
      return Objects.hash(System.identityHashCode(type), searchParent);
    }
  }

  private static final class TypeDefDescriptorsKey {
    private final long typeDefId;
    private final Class<?> type;

    private TypeDefDescriptorsKey(long typeDefId, Class<?> type) {
      this.typeDefId = typeDefId;
      this.type = Objects.requireNonNull(type);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof TypeDefDescriptorsKey)) {
        return false;
      }
      TypeDefDescriptorsKey that = (TypeDefDescriptorsKey) o;
      return typeDefId == that.typeDefId && type == that.type;
    }

    @Override
    public int hashCode() {
      return 31 * Long.hashCode(typeDefId) + System.identityHashCode(type);
    }
  }

  private static final class FieldDescriptorGrouperKey {
    private final FieldDescriptorsKey fieldDescriptorsKey;
    private final boolean descriptorsGroupedOrdered;
    private final java.util.function.Function<Descriptor, Descriptor> descriptorUpdator;

    private FieldDescriptorGrouperKey(
        FieldDescriptorsKey fieldDescriptorsKey,
        boolean descriptorsGroupedOrdered,
        java.util.function.Function<Descriptor, Descriptor> descriptorUpdator) {
      this.fieldDescriptorsKey = Objects.requireNonNull(fieldDescriptorsKey);
      this.descriptorsGroupedOrdered = descriptorsGroupedOrdered;
      this.descriptorUpdator = descriptorUpdator;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof FieldDescriptorGrouperKey)) {
        return false;
      }
      FieldDescriptorGrouperKey that = (FieldDescriptorGrouperKey) o;
      return descriptorsGroupedOrdered == that.descriptorsGroupedOrdered
          && fieldDescriptorsKey.equals(that.fieldDescriptorsKey)
          && descriptorUpdator == that.descriptorUpdator;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          fieldDescriptorsKey,
          descriptorsGroupedOrdered,
          System.identityHashCode(descriptorUpdator));
    }
  }

  private static final class TypeDefDescriptorGrouperKey {
    private final TypeDefDescriptorsKey typeDefDescriptorsKey;
    private final boolean descriptorsGroupedOrdered;
    private final java.util.function.Function<Descriptor, Descriptor> descriptorUpdator;

    private TypeDefDescriptorGrouperKey(
        TypeDefDescriptorsKey typeDefDescriptorsKey,
        boolean descriptorsGroupedOrdered,
        java.util.function.Function<Descriptor, Descriptor> descriptorUpdator) {
      this.typeDefDescriptorsKey = Objects.requireNonNull(typeDefDescriptorsKey);
      this.descriptorsGroupedOrdered = descriptorsGroupedOrdered;
      this.descriptorUpdator = descriptorUpdator;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof TypeDefDescriptorGrouperKey)) {
        return false;
      }
      TypeDefDescriptorGrouperKey that = (TypeDefDescriptorGrouperKey) o;
      return descriptorsGroupedOrdered == that.descriptorsGroupedOrdered
          && typeDefDescriptorsKey.equals(that.typeDefDescriptorsKey)
          && descriptorUpdator == that.descriptorUpdator;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          typeDefDescriptorsKey,
          descriptorsGroupedOrdered,
          System.identityHashCode(descriptorUpdator));
    }
  }
}
