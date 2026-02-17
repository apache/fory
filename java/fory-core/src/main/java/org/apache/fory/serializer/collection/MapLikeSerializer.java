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

package org.apache.fory.serializer.collection;

import static org.apache.fory.serializer.collection.MapFlags.KEY_DECL_TYPE;
import static org.apache.fory.serializer.collection.MapFlags.KEY_HAS_NULL;
import static org.apache.fory.serializer.collection.MapFlags.KV_NULL;
import static org.apache.fory.serializer.collection.MapFlags.NULL_KEY_VALUE_DECL_TYPE;
import static org.apache.fory.serializer.collection.MapFlags.NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF;
import static org.apache.fory.serializer.collection.MapFlags.NULL_VALUE_KEY_DECL_TYPE;
import static org.apache.fory.serializer.collection.MapFlags.NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF;
import static org.apache.fory.serializer.collection.MapFlags.TRACKING_KEY_REF;
import static org.apache.fory.serializer.collection.MapFlags.TRACKING_VALUE_REF;
import static org.apache.fory.serializer.collection.MapFlags.VALUE_DECL_TYPE;
import static org.apache.fory.serializer.collection.MapFlags.VALUE_HAS_NULL;
import static org.apache.fory.type.TypeUtils.MAP_TYPE;

import com.google.common.collect.ImmutableMap.Builder;
import java.lang.invoke.MethodHandle;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.fory.Fory;
import org.apache.fory.annotation.CodegenInvoke;
import org.apache.fory.collection.IdentityMap;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeInfoHolder;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.Generics;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.Preconditions;

/** Serializer for all map-like objects. */
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class MapLikeSerializer<T> extends Serializer<T> {
  public static final int MAX_CHUNK_SIZE = 255;

  protected MethodHandle constructor;
  protected final boolean supportCodegenHook;
  protected final TypeInfoHolder keyTypeInfoWriteCache;
  protected final TypeInfoHolder keyTypeInfoReadCache;
  protected final TypeInfoHolder valueTypeInfoWriteCache;
  protected final TypeInfoHolder valueTypeInfoReadCache;
  // support map subclass whose key or value generics only are available,
  // or one of types is already instantiated in subclass, ex: `Subclass<T> implements Map<String,
  // T>`
  private final IdentityMap<GenericType, GenericType> partialGenericKVTypeMap;
  private final GenericType objType;
  // For subclass whose kv type are instantiated already, such as
  // `Subclass implements Map<String, Long>`. If declared `Map` doesn't specify
  // instantiated kv type, then the serialization will need to write those kv
  // types. Although we can extract this generics when creating the serializer,
  // we can't do it when jit `Serializer` for some class which contains one of such map
  // field. So we will write those extra kv classes to keep protocol consistency between
  // interpreter and jit mode although it seems unnecessary.
  // With kv header in future, we can write this kv classes only once, the cost won't be too much.
  private int numElements;
  private final TypeResolver typeResolver;
  protected final SerializationBinding binding;
  private boolean trackRef;

  public MapLikeSerializer(Fory fory, Class<T> cls) {
    this(fory, cls, !ReflectionUtils.isDynamicGeneratedCLass(cls));
  }

  public MapLikeSerializer(Fory fory, Class<T> cls, boolean supportCodegenHook) {
    this(fory, cls, supportCodegenHook, false);
  }

  public MapLikeSerializer(Fory fory, Class<T> cls, boolean supportCodegenHook, boolean immutable) {
    super(fory, cls, immutable);
    this.typeResolver = fory.isCrossLanguage() ? fory.getXtypeResolver() : fory.getClassResolver();
    trackRef = fory.trackingRef();
    this.supportCodegenHook = supportCodegenHook;
    keyTypeInfoWriteCache = typeResolver.nilTypeInfoHolder();
    keyTypeInfoReadCache = typeResolver.nilTypeInfoHolder();
    valueTypeInfoWriteCache = typeResolver.nilTypeInfoHolder();
    valueTypeInfoReadCache = typeResolver.nilTypeInfoHolder();
    partialGenericKVTypeMap = new IdentityMap<>();
    objType = typeResolver.buildGenericType(Object.class);
    binding = SerializationBinding.createBinding(fory);
  }

  @Override
  public void write(MemoryBuffer buffer, T value) {
    Map map = onMapWrite(buffer, value);
    if (map.isEmpty()) {
      return;
    }
    TypeResolver classResolver = typeResolver;
    Iterator<Entry<Object, Object>> iterator = map.entrySet().iterator();
    Entry<Object, Object> entry = iterator.next();
    Generics generics = fory.getGenerics();
    while (entry != null) {
      GenericType genericType = generics.nextGenericType();
      if (genericType == null) {
        entry = writeJavaNullChunk(buffer, entry, iterator, null, null);
        if (entry != null) {
          entry = writeJavaChunk(classResolver, buffer, entry, iterator, null, null);
        }
      } else {
        if (genericType.getTypeParametersCount() < 2) {
          genericType = getKVGenericType(genericType);
        }
        GenericType keyGenericType = genericType.getTypeParameter0();
        GenericType valueGenericType = genericType.getTypeParameter1();
        entry =
            writeJavaNullChunkGeneric(buffer, entry, iterator, keyGenericType, valueGenericType);
        if (entry != null) {
          entry =
              writeJavaChunkGeneric(classResolver, generics, genericType, buffer, entry, iterator);
        }
      }
    }
    onMapWriteFinish(map);
  }

  @Override
  public void xwrite(MemoryBuffer buffer, T value) {
    write(buffer, value);
  }

  public final Entry writeJavaNullChunk(
      MemoryBuffer buffer,
      Entry entry,
      Iterator<Entry<Object, Object>> iterator,
      Serializer keySerializer,
      Serializer valueSerializer) {
    while (true) {
      Object key = entry.getKey();
      Object value = entry.getValue();
      if (key != null) {
        if (value != null) {
          return entry;
        }
        writeNullValueChunk(buffer, keySerializer, key);
      } else {
        writeNullKeyChunk(buffer, valueSerializer, value);
      }
      if (iterator.hasNext()) {
        entry = iterator.next();
      } else {
        return null;
      }
    }
  }

  private void writeNullValueChunk(MemoryBuffer buffer, Serializer keySerializer, Object key) {
    // noinspection Duplicates
    if (keySerializer != null) {
      if (keySerializer.needToWriteRef()) {
        buffer.writeByte(NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF);
        binding.writeRef(buffer, key, keySerializer);
      } else {
        buffer.writeByte(NULL_VALUE_KEY_DECL_TYPE);
        binding.write(buffer, keySerializer, key);
      }
    } else {
      buffer.writeByte(VALUE_HAS_NULL | TRACKING_KEY_REF);
      binding.writeRef(buffer, key, keyTypeInfoWriteCache);
    }
  }

  /**
   * Write chunk of size 1, the key is null. Since we can have at most one key whose value is null,
   * this method is not in critical path, make it as a separate method to let caller eligible for
   * jit inline.
   */
  private void writeNullKeyChunk(MemoryBuffer buffer, Serializer valueSerializer, Object value) {
    if (value != null) {
      // noinspection Duplicates
      if (valueSerializer != null) {
        if (valueSerializer.needToWriteRef()) {
          buffer.writeByte(NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF);
          binding.writeRef(buffer, value, valueSerializer);
        } else {
          buffer.writeByte(NULL_KEY_VALUE_DECL_TYPE);
          binding.write(buffer, valueSerializer, value);
        }
      } else {
        buffer.writeByte(KEY_HAS_NULL | TRACKING_VALUE_REF);
        binding.writeRef(buffer, value, valueTypeInfoWriteCache);
      }
    } else {
      buffer.writeByte(KV_NULL);
    }
  }

  @CodegenInvoke
  public final Entry writeNullChunkKVFinalNoRef(
      MemoryBuffer buffer,
      Entry entry,
      Iterator<Entry<Object, Object>> iterator,
      Serializer keySerializer,
      Serializer valueSerializer) {
    while (true) {
      Object key = entry.getKey();
      Object value = entry.getValue();
      if (key != null) {
        if (value != null) {
          return entry;
        }
        buffer.writeByte(NULL_VALUE_KEY_DECL_TYPE);
        binding.write(buffer, keySerializer, key);
      } else {
        writeNullKeyChunk(buffer, valueSerializer, value);
      }
      if (iterator.hasNext()) {
        entry = iterator.next();
      } else {
        return null;
      }
    }
  }

  public final Entry writeJavaNullChunkGeneric(
      MemoryBuffer buffer,
      Entry entry,
      Iterator<Entry<Object, Object>> iterator,
      GenericType keyType,
      GenericType valueType) {
    while (true) {
      Object key = entry.getKey();
      Object value = entry.getValue();
      if (key != null) {
        if (value != null) {
          return entry;
        }
        writeKeyForNullValueChunkGeneric(buffer, key, keyType);
      } else {
        writeValueForNullKeyChunkGeneric(buffer, value, valueType);
      }
      if (iterator.hasNext()) {
        entry = iterator.next();
      } else {
        return null;
      }
    }
  }

  private void writeKeyForNullValueChunkGeneric(
      MemoryBuffer buffer, Object key, GenericType keyType) {
    boolean trackingRef = keyType.trackingRef(typeResolver);
    if (!keyType.isMonomorphic()) {
      if (trackingRef) {
        buffer.writeByte(VALUE_HAS_NULL | TRACKING_KEY_REF);
        binding.writeRef(buffer, key, keyTypeInfoWriteCache);
      } else {
        buffer.writeByte(VALUE_HAS_NULL);
        binding.writeNonRef(buffer, key);
      }
      return;
    }
    Serializer serializer = keyType.getSerializer(typeResolver);
    if (keyType.hasGenericParameters()) {
      fory.getGenerics().pushGenericType(keyType);
      fory.incDepth(1);
    }
    if (trackingRef && serializer.needToWriteRef()) {
      buffer.writeByte(NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF);
      binding.writeRef(buffer, key, serializer);
    } else {
      buffer.writeByte(NULL_VALUE_KEY_DECL_TYPE);
      binding.write(buffer, serializer, key);
    }
    if (keyType.hasGenericParameters()) {
      fory.incDepth(-1);
      fory.getGenerics().popGenericType();
    }
  }

  private void writeValueForNullKeyChunkGeneric(
      MemoryBuffer buffer, Object value, GenericType valueType) {
    boolean trackingRef = valueType.trackingRef(typeResolver);
    if (!valueType.isMonomorphic()) {
      if (trackingRef) {
        buffer.writeByte(KEY_HAS_NULL | TRACKING_VALUE_REF);
        binding.writeRef(buffer, value, valueTypeInfoWriteCache);
      } else {
        buffer.writeByte(KEY_HAS_NULL);
        binding.writeNonRef(buffer, value);
      }
      return;
    }
    Serializer serializer = valueType.getSerializer(typeResolver);
    if (valueType.hasGenericParameters()) {
      fory.getGenerics().pushGenericType(valueType);
      fory.incDepth(1);
    }
    if (trackingRef && serializer.needToWriteRef()) {
      buffer.writeByte(NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF);
      binding.writeRef(buffer, value, serializer);
    } else {
      buffer.writeByte(NULL_KEY_VALUE_DECL_TYPE);
      binding.write(buffer, serializer, value);
    }
    if (valueType.hasGenericParameters()) {
      fory.incDepth(-1);
      fory.getGenerics().popGenericType();
    }
  }

  // Make byte code of this method smaller than 325 for better jit inline
  private Entry writeJavaChunk(
      TypeResolver classResolver,
      MemoryBuffer buffer,
      Entry<Object, Object> entry,
      Iterator<Entry<Object, Object>> iterator,
      Serializer keySerializer,
      Serializer valueSerializer) {
    Object key = entry.getKey();
    Object value = entry.getValue();
    Class keyType = key.getClass();
    Class valueType = value.getClass();
    // place holder for chunk header and size.
    buffer.writeInt16((short) -1);
    int chunkSizeOffset = buffer.writerIndex() - 1;
    int chunkHeader = 0;
    if (keySerializer != null) {
      chunkHeader |= KEY_DECL_TYPE;
    } else {
      keySerializer = writeKeyTypeInfo(classResolver, keyType, buffer);
    }
    if (valueSerializer != null) {
      chunkHeader |= VALUE_DECL_TYPE;
    } else {
      valueSerializer = writeValueTypeInfo(classResolver, valueType, buffer);
    }
    // noinspection Duplicates
    boolean keyWriteRef = keySerializer.needToWriteRef();
    boolean valueWriteRef = valueSerializer.needToWriteRef();
    if (keyWriteRef) {
      chunkHeader |= TRACKING_KEY_REF;
    }
    if (valueWriteRef) {
      chunkHeader |= TRACKING_VALUE_REF;
    }
    buffer.putByte(chunkSizeOffset - 1, (byte) chunkHeader);
    RefResolver refResolver = fory.getRefResolver();
    // Use int to make chunk size representable for 0~255 instead of 0~127.
    int chunkSize = 0;
    while (true) {
      if (key == null
          || value == null
          || (key.getClass() != keyType)
          || (value.getClass() != valueType)) {
        break;
      }
      if (!keyWriteRef || !refResolver.writeRefOrNull(buffer, key)) {
        binding.write(buffer, keySerializer, key);
      }
      if (!valueWriteRef || !refResolver.writeRefOrNull(buffer, value)) {
        binding.write(buffer, valueSerializer, value);
      }
      // noinspection Duplicates
      ++chunkSize;
      if (iterator.hasNext()) {
        entry = iterator.next();
        key = entry.getKey();
        value = entry.getValue();
      } else {
        entry = null;
        break;
      }
      if (chunkSize == MAX_CHUNK_SIZE) {
        break;
      }
    }
    buffer.putByte(chunkSizeOffset, (byte) chunkSize);
    return entry;
  }

  private Serializer writeKeyTypeInfo(
      TypeResolver classResolver, Class keyType, MemoryBuffer buffer) {
    TypeInfo typeInfo = classResolver.getTypeInfo(keyType, keyTypeInfoWriteCache);
    classResolver.writeTypeInfo(buffer, typeInfo);
    return typeInfo.getSerializer();
  }

  private Serializer writeValueTypeInfo(
      TypeResolver classResolver, Class valueType, MemoryBuffer buffer) {
    TypeInfo typeInfo = classResolver.getTypeInfo(valueType, valueTypeInfoWriteCache);
    classResolver.writeTypeInfo(buffer, typeInfo);
    return typeInfo.getSerializer();
  }

  @CodegenInvoke
  public Entry writeJavaChunkGeneric(
      TypeResolver classResolver,
      Generics generics,
      GenericType genericType,
      MemoryBuffer buffer,
      Entry<Object, Object> entry,
      Iterator<Entry<Object, Object>> iterator) {
    // type parameters count for `Map field` will be 0;
    // type parameters count for `SubMap<V> field` which SubMap is
    // `SubMap<V> implements Map<String, V>` will be 1;
    if (genericType.getTypeParametersCount() < 2) {
      genericType = getKVGenericType(genericType);
    }
    GenericType keyGenericType = genericType.getTypeParameter0();
    GenericType valueGenericType = genericType.getTypeParameter1();
    if (keyGenericType == objType && valueGenericType == objType) {
      return writeJavaChunk(classResolver, buffer, entry, iterator, null, null);
    }
    // Can't avoid push generics repeatedly in loop by stack depth, because push two
    // generic type changed generics stack top, which is depth index, update stack top
    // and depth will have some cost too.
    // Stack depth to avoid push generics repeatedly in loop.
    // Note push two generic type changed generics stack top, which is depth index,
    // stack top should be updated when using for serialization k/v.
    // int depth = fory.getDepth();
    // // depth + 1 to leave a slot for value generics, otherwise value generics will
    // // be overwritten by nested key generics.
    // fory.setDepth(depth + 1);
    // generics.pushGenericType(keyGenericType);
    // fory.setDepth(depth);
    // generics.pushGenericType(valueGenericType);
    boolean keyGenericTypeFinal = keyGenericType.isMonomorphic();
    boolean valueGenericTypeFinal = valueGenericType.isMonomorphic();
    Object key = entry.getKey();
    Object value = entry.getValue();
    Class keyType = key.getClass();
    Class valueType = value.getClass();
    Serializer keySerializer, valueSerializer;
    // place holder for chunk header and size.
    buffer.writeInt16((short) -1);
    int chunkSizeOffset = buffer.writerIndex() - 1;
    int chunkHeader = 0;
    // noinspection Duplicates
    if (keyGenericTypeFinal) {
      chunkHeader |= KEY_DECL_TYPE;
      keySerializer = keyGenericType.getSerializer(classResolver);
    } else {
      keySerializer = writeKeyTypeInfo(classResolver, keyType, buffer);
    }
    if (valueGenericTypeFinal) {
      chunkHeader |= VALUE_DECL_TYPE;
      valueSerializer = valueGenericType.getSerializer(classResolver);
    } else {
      valueSerializer = writeValueTypeInfo(classResolver, valueType, buffer);
    }
    boolean trackingKeyRef = keyGenericType.trackingRef(typeResolver);
    boolean trackingValueRef = valueGenericType.trackingRef(typeResolver);
    boolean keyWriteRef = trackingKeyRef && keySerializer.needToWriteRef();
    if (keyWriteRef) {
      chunkHeader |= TRACKING_KEY_REF;
    }
    boolean valueWriteRef = trackingValueRef && valueSerializer.needToWriteRef();
    if (valueWriteRef) {
      chunkHeader |= TRACKING_VALUE_REF;
    }
    buffer.putByte(chunkSizeOffset - 1, (byte) chunkHeader);
    RefResolver refResolver = fory.getRefResolver();
    // Use int to make chunk size representable for 0~255 instead of 0~127.
    int chunkSize = 0;
    while (true) {
      if (key == null
          || value == null
          || (key.getClass() != keyType)
          || (value.getClass() != valueType)) {
        break;
      }
      generics.pushGenericType(keyGenericType);
      if (!keyWriteRef || !refResolver.writeRefOrNull(buffer, key)) {
        fory.incDepth(1);
        binding.write(buffer, keySerializer, key);
        fory.incDepth(-1);
      }
      generics.popGenericType();
      generics.pushGenericType(valueGenericType);
      if (!valueWriteRef || !refResolver.writeRefOrNull(buffer, value)) {
        fory.incDepth(1);
        binding.write(buffer, valueSerializer, value);
        fory.incDepth(-1);
      }
      generics.popGenericType();
      ++chunkSize;
      // noinspection Duplicates
      if (iterator.hasNext()) {
        entry = iterator.next();
        key = entry.getKey();
        value = entry.getValue();
      } else {
        entry = null;
        break;
      }
      if (chunkSize == MAX_CHUNK_SIZE) {
        break;
      }
    }
    buffer.putByte(chunkSizeOffset, (byte) chunkSize);
    return entry;
  }

  private GenericType getKVGenericType(GenericType genericType) {
    GenericType mapGenericType = partialGenericKVTypeMap.get(genericType);
    if (mapGenericType == null) {
      TypeRef<?> typeRef = genericType.getTypeRef();
      if (!MAP_TYPE.isSupertypeOf(typeRef)) {
        mapGenericType = GenericType.build(TypeUtils.mapOf(Object.class, Object.class));
        partialGenericKVTypeMap.put(genericType, mapGenericType);
        return mapGenericType;
      }
      Tuple2<TypeRef<?>, TypeRef<?>> mapKeyValueType = TypeUtils.getMapKeyValueType(typeRef);
      mapGenericType = GenericType.build(TypeUtils.mapOf(mapKeyValueType.f0, mapKeyValueType.f1));
      partialGenericKVTypeMap.put(genericType, mapGenericType);
    }
    return mapGenericType;
  }

  @Override
  public T xread(MemoryBuffer buffer) {
    return read(buffer);
  }

  protected <K, V> void copyEntry(Map<K, V> originMap, Map<K, V> newMap) {
    TypeResolver classResolver = typeResolver;
    for (Map.Entry<K, V> entry : originMap.entrySet()) {
      K key = entry.getKey();
      if (key != null) {
        TypeInfo typeInfo = classResolver.getTypeInfo(key.getClass(), keyTypeInfoWriteCache);
        if (!typeInfo.getSerializer().isImmutable()) {
          key = fory.copyObject(key, typeInfo.getTypeId());
        }
      }
      V value = entry.getValue();
      if (value != null) {
        TypeInfo typeInfo = classResolver.getTypeInfo(value.getClass(), valueTypeInfoWriteCache);
        if (!typeInfo.getSerializer().isImmutable()) {
          value = fory.copyObject(value, typeInfo.getTypeId());
        }
      }
      newMap.put(key, value);
    }
  }

  protected <K, V> void copyEntry(Map<K, V> originMap, Builder<K, V> builder) {
    TypeResolver classResolver = typeResolver;
    for (Entry<K, V> entry : originMap.entrySet()) {
      K key = entry.getKey();
      if (key != null) {
        TypeInfo typeInfo = classResolver.getTypeInfo(key.getClass(), keyTypeInfoWriteCache);
        if (!typeInfo.getSerializer().isImmutable()) {
          key = fory.copyObject(key, typeInfo.getTypeId());
        }
      }
      V value = entry.getValue();
      if (value != null) {
        TypeInfo typeInfo = classResolver.getTypeInfo(value.getClass(), valueTypeInfoWriteCache);
        if (!typeInfo.getSerializer().isImmutable()) {
          value = fory.copyObject(value, typeInfo.getTypeId());
        }
      }
      builder.put(key, value);
    }
  }

  protected <K, V> void copyEntry(Map<K, V> originMap, Object[] elements) {
    TypeResolver classResolver = typeResolver;
    int index = 0;
    for (Entry<K, V> entry : originMap.entrySet()) {
      K key = entry.getKey();
      if (key != null) {
        TypeInfo typeInfo = classResolver.getTypeInfo(key.getClass(), keyTypeInfoWriteCache);
        if (!typeInfo.getSerializer().isImmutable()) {
          key = fory.copyObject(key, typeInfo.getTypeId());
        }
      }
      V value = entry.getValue();
      if (value != null) {
        TypeInfo typeInfo = classResolver.getTypeInfo(value.getClass(), valueTypeInfoWriteCache);
        if (!typeInfo.getSerializer().isImmutable()) {
          value = fory.copyObject(value, typeInfo.getTypeId());
        }
      }
      elements[index++] = key;
      elements[index++] = value;
    }
  }

  @Override
  public T read(MemoryBuffer buffer) {
    Map map = newMap(buffer);
    int size = getAndClearNumElements();
    readElements(buffer, size, map);
    return onMapRead(map);
  }

  public void readElements(MemoryBuffer buffer, int size, Map map) {
    int chunkHeader = 0;
    if (size != 0) {
      chunkHeader = buffer.readUnsignedByte();
    }
    Generics generics = fory.getGenerics();
    while (size > 0) {
      long sizeAndHeader = readJavaNullChunk(buffer, map, chunkHeader, size, null, null);
      chunkHeader = (int) (sizeAndHeader & 0xff);
      size = (int) (sizeAndHeader >>> 8);
      if (size == 0) {
        break;
      }
      GenericType genericType = generics.nextGenericType();
      if (genericType == null) {
        sizeAndHeader = readJavaChunk(fory, buffer, map, size, chunkHeader, null, null);
      } else {
        sizeAndHeader =
            readJavaChunkGeneric(fory, generics, genericType, buffer, map, size, chunkHeader);
      }
      chunkHeader = (int) (sizeAndHeader & 0xff);
      size = (int) (sizeAndHeader >>> 8);
    }
  }

  public long readJavaNullChunk(
      MemoryBuffer buffer,
      Map map,
      int chunkHeader,
      long size,
      Serializer keySerializer,
      Serializer valueSerializer) {
    while (true) {
      boolean keyHasNull = (chunkHeader & KEY_HAS_NULL) != 0;
      boolean valueHasNull = (chunkHeader & VALUE_HAS_NULL) != 0;
      if (!keyHasNull) {
        if (!valueHasNull) {
          return (size << 8) | chunkHeader;
        } else {
          boolean trackKeyRef = (chunkHeader & TRACKING_KEY_REF) != 0;
          Object key;
          if ((chunkHeader & KEY_DECL_TYPE) != 0) {
            if (keySerializer == null) {
              key = readNonEmptyValueFromNullChunk(buffer, trackKeyRef, true);
            } else {
              fory.incReadDepth();
              if (trackKeyRef) {
                key = binding.readRef(buffer, keySerializer);
              } else {
                key = binding.read(buffer, keySerializer);
              }
              fory.decDepth();
            }
          } else {
            if (trackKeyRef) {
              key = binding.readRef(buffer, keyTypeInfoReadCache);
            } else {
              key = binding.readNonRef(buffer, keyTypeInfoReadCache);
            }
          }
          map.put(key, null);
        }
      } else {
        readNullKeyChunk(buffer, map, chunkHeader, valueSerializer, valueHasNull);
      }
      if (--size == 0) {
        return 0;
      } else {
        chunkHeader = buffer.readUnsignedByte();
      }
    }
  }

  /**
   * Read chunk of size 1, the key is null. Since we can have at most one key whose value is null,
   * this method is not in critical path, make it as a separate method to let caller eligible for
   * jit inline.
   */
  private void readNullKeyChunk(
      MemoryBuffer buffer,
      Map map,
      int chunkHeader,
      Serializer valueSerializer,
      boolean valueHasNull) {
    if (!valueHasNull) {
      Object value;
      boolean trackValueRef = (chunkHeader & TRACKING_VALUE_REF) != 0;
      if ((chunkHeader & VALUE_DECL_TYPE) != 0) {
        if (valueSerializer == null) {
          value = readNonEmptyValueFromNullChunk(buffer, trackValueRef, false);
        } else {
          fory.incReadDepth();
          if (trackValueRef) {
            value = binding.readRef(buffer, valueSerializer);
          } else {
            value = binding.read(buffer, valueSerializer);
          }
          fory.decDepth();
        }
      } else {
        if (trackValueRef) {
          value = binding.readRef(buffer, valueTypeInfoReadCache);
        } else {
          value = binding.readNonRef(buffer, valueTypeInfoReadCache);
        }
      }
      map.put(null, value);
    } else {
      map.put(null, null);
    }
  }

  private Object readNonEmptyValueFromNullChunk(
      MemoryBuffer buffer, boolean trackRef, boolean isKey) {
    Generics generics = fory.getGenerics();
    GenericType genericType = generics.nextGenericType();
    if (genericType.getTypeParametersCount() < 2) {
      genericType = getKVGenericType(genericType);
    }
    GenericType type = isKey ? genericType.getTypeParameter0() : genericType.getTypeParameter1();
    generics.pushGenericType(type);
    Serializer<?> serializer = type.getSerializer(typeResolver);
    Object v;
    fory.incReadDepth();
    if (trackRef) {
      v = binding.readRef(buffer, serializer);
    } else {
      v = binding.read(buffer, serializer);
    }
    fory.decDepth();
    generics.popGenericType();
    return v;
  }

  @CodegenInvoke
  public long readNullChunkKVFinalNoRef(
      MemoryBuffer buffer,
      Map map,
      int chunkHeader,
      long size,
      Serializer keySerializer,
      Serializer valueSerializer) {
    while (true) {
      boolean keyHasNull = (chunkHeader & KEY_HAS_NULL) != 0;
      boolean valueHasNull = (chunkHeader & VALUE_HAS_NULL) != 0;
      if (!keyHasNull) {
        if (!valueHasNull) {
          return (size << 8) | chunkHeader;
        } else {
          fory.incReadDepth();
          Object key = binding.read(buffer, keySerializer);
          map.put(key, null);
          fory.decDepth();
        }
      } else {
        readNullKeyChunk(buffer, map, chunkHeader, valueSerializer, valueHasNull);
      }
      if (--size == 0) {
        return 0;
      } else {
        chunkHeader = buffer.readUnsignedByte();
      }
    }
  }

  @CodegenInvoke
  public long readJavaChunk(
      Fory fory,
      MemoryBuffer buffer,
      Map map,
      long size,
      int chunkHeader,
      Serializer keySerializer,
      Serializer valueSerializer) {
    // noinspection Duplicates
    boolean trackKeyRef = (chunkHeader & TRACKING_KEY_REF) != 0;
    boolean trackValueRef = (chunkHeader & TRACKING_VALUE_REF) != 0;
    if (trackKeyRef || trackValueRef) {
      Preconditions.checkState(trackRef, "Ref tracking is not enabled");
    }
    boolean keyIsDeclaredType = (chunkHeader & KEY_DECL_TYPE) != 0;
    boolean valueIsDeclaredType = (chunkHeader & VALUE_DECL_TYPE) != 0;
    int chunkSize = buffer.readUnsignedByte();
    if (!keyIsDeclaredType) {
      keySerializer = typeResolver.readTypeInfo(buffer, keyTypeInfoReadCache).getSerializer();
    }
    if (!valueIsDeclaredType) {
      valueSerializer = typeResolver.readTypeInfo(buffer, valueTypeInfoReadCache).getSerializer();
    }
    fory.incReadDepth();
    for (int i = 0; i < chunkSize; i++) {
      Object key =
          trackKeyRef
              ? binding.readRef(buffer, keySerializer)
              : binding.read(buffer, keySerializer);
      Object value =
          trackValueRef
              ? binding.readRef(buffer, valueSerializer)
              : binding.read(buffer, valueSerializer);
      map.put(key, value);
      size--;
    }
    fory.decDepth();
    return size > 0 ? (size << 8) | buffer.readUnsignedByte() : 0;
  }

  private long readJavaChunkGeneric(
      Fory fory,
      Generics generics,
      GenericType genericType,
      MemoryBuffer buffer,
      Map map,
      long size,
      int chunkHeader) {
    // type parameters count for `Map field` will be 0;
    // type parameters count for `SubMap<V> field` which SubMap is
    // `SubMap<V> implements Map<String, V>` will be 1;
    if (genericType.getTypeParametersCount() < 2) {
      genericType = getKVGenericType(genericType);
    }
    GenericType keyGenericType = genericType.getTypeParameter0();
    GenericType valueGenericType = genericType.getTypeParameter1();
    // noinspection Duplicates
    boolean trackKeyRef = (chunkHeader & TRACKING_KEY_REF) != 0;
    boolean trackValueRef = (chunkHeader & TRACKING_VALUE_REF) != 0;
    if (trackKeyRef || trackValueRef) {
      Preconditions.checkState(trackRef, "Ref tracking is not enabled");
    }
    boolean keyIsDeclaredType = (chunkHeader & KEY_DECL_TYPE) != 0;
    boolean valueIsDeclaredType = (chunkHeader & VALUE_DECL_TYPE) != 0;
    int chunkSize = buffer.readUnsignedByte();
    Serializer keySerializer, valueSerializer;
    if (!keyIsDeclaredType) {
      keySerializer = typeResolver.readTypeInfo(buffer, keyTypeInfoReadCache).getSerializer();
    } else {
      keySerializer = keyGenericType.getSerializer(typeResolver);
    }
    if (!valueIsDeclaredType) {
      valueSerializer = typeResolver.readTypeInfo(buffer, valueTypeInfoReadCache).getSerializer();
    } else {
      valueSerializer = valueGenericType.getSerializer(typeResolver);
    }
    for (int i = 0; i < chunkSize; i++) {
      generics.pushGenericType(keyGenericType);
      fory.incReadDepth();
      Object key =
          trackKeyRef
              ? binding.readRef(buffer, keySerializer)
              : binding.read(buffer, keySerializer);
      fory.decDepth();
      generics.popGenericType();
      generics.pushGenericType(valueGenericType);
      fory.incReadDepth();
      Object value =
          trackValueRef
              ? binding.readRef(buffer, valueSerializer)
              : binding.read(buffer, valueSerializer);
      fory.decDepth();
      generics.popGenericType();
      map.put(key, value);
      size--;
    }
    return size > 0 ? (size << 8) | buffer.readUnsignedByte() : 0;
  }

  /**
   * Hook for java serialization codegen, read/write key/value by entrySet.
   *
   * <p>For key/value type which is final, using codegen may get a big performance gain
   *
   * @return true if read/write key/value support calling entrySet method
   */
  public final boolean supportCodegenHook() {
    return supportCodegenHook;
  }

  /**
   * Write data except size and elements.
   *
   * <ol>
   *   In codegen, follows is call order:
   *   <li>write map class if not final
   *   <li>write map size
   *   <li>onCollectionWrite
   *   <li>write keys/values
   * </ol>
   */
  public abstract Map onMapWrite(MemoryBuffer buffer, T value);

  public void onMapWriteFinish(Map map) {}

  /**
   * Read data except size and elements, return empty map to be filled.
   *
   * <ol>
   *   In codegen, follows is call order:
   *   <li>read map class if not final
   *   <li>newMap: read and set map size, read map header and create map.
   *   <li>read keys/values
   * </ol>
   *
   * <p>Map must have default constructor to be invoked by fory, otherwise created object can't be
   * used to adding elements. For example:
   *
   * <pre>{@code new ArrayList<Integer> {add(1);}}</pre>
   *
   * <p>without default constructor, created list will have elementData as null, adding elements
   * will raise NPE.
   */
  public Map newMap(MemoryBuffer buffer) {
    numElements = buffer.readVarUint32Small7();
    if (constructor == null) {
      constructor = ReflectionUtils.getCtrHandle(type, true);
    }
    try {
      Map instance = (Map) constructor.invoke();
      fory.getRefResolver().reference(instance);
      return instance;
    } catch (Throwable e) {
      throw new IllegalArgumentException(
          "Please provide public no arguments constructor for class " + type, e);
    }
  }

  /** Create a new empty map for copy. */
  public Map newMap(Map map) {
    numElements = map.size();
    if (constructor == null) {
      constructor = ReflectionUtils.getCtrHandle(type, true);
    }
    try {
      return (Map) constructor.invoke();
    } catch (Throwable e) {
      throw new IllegalArgumentException(
          "Please provide public no arguments constructor for class " + type, e);
    }
  }

  /**
   * Get and reset numElements of deserializing collection. Should be called after {@link
   * #newMap(MemoryBuffer buffer)}. Nested read may overwrite this element, reset is necessary to
   * avoid use wrong value by mistake.
   */
  public int getAndClearNumElements() {
    int size = numElements;
    numElements = -1; // nested read may overwrite this element.
    return size;
  }

  public void setNumElements(int numElements) {
    this.numElements = numElements;
  }

  public abstract T onMapCopy(Map map);

  public abstract T onMapRead(Map map);
}
