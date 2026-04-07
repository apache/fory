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

import java.lang.invoke.MethodHandle;
import java.util.Collection;
import org.apache.fory.Fory;
import org.apache.fory.annotation.CodegenInvoke;
import org.apache.fory.config.Config;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.RefMode;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeInfoHolder;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.type.GenericType;
import org.apache.fory.util.Preconditions;

/**
 * Serializer for all collection like object. All collection serializer should extend this class.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class CollectionLikeSerializer<T> extends Serializer<T> {
  private MethodHandle constructor;
  private int numElements;
  protected final Config config;
  protected final boolean supportCodegenHook;
  protected final TypeInfoHolder elementTypeInfoHolder;
  protected final TypeResolver typeResolver;

  // For subclass whose element type are instantiated already, such as
  // `Subclass extends ArrayList<String>`. If declared `Collection` doesn't specify
  // instantiated element type, then the serialization will need to write this element
  // type. Although we can extract this generics when creating the serializer,
  // we can't do it when jit `Serializer` for some class which contains one of such collection
  // field. So we will write this extra element class to keep protocol consistency between
  // interpreter and jit mode although it seems unnecessary.
  // With elements header, we can write this element class only once, the cost won't be too much.

  public CollectionLikeSerializer(TypeResolver typeResolver, Class<T> cls) {
    this(typeResolver, cls, !ReflectionUtils.isDynamicGeneratedCLass(cls));
  }

  public CollectionLikeSerializer(
      TypeResolver typeResolver, Class<T> cls, boolean supportCodegenHook) {
    super(typeResolver.getConfig(), cls);
    this.config = typeResolver.getConfig();
    this.supportCodegenHook = supportCodegenHook;
    elementTypeInfoHolder = typeResolver.nilTypeInfoHolder();
    this.typeResolver = typeResolver;
  }

  public CollectionLikeSerializer(
      TypeResolver typeResolver, Class<T> cls, boolean supportCodegenHook, boolean immutable) {
    super(typeResolver.getConfig(), cls, immutable);
    this.config = typeResolver.getConfig();
    this.supportCodegenHook = supportCodegenHook;
    elementTypeInfoHolder = typeResolver.nilTypeInfoHolder();
    this.typeResolver = typeResolver;
  }

  private GenericType getElementGenericType(ReadContext readContext, int depth) {
    GenericType genericType = readContext.getGenerics().nextGenericType(depth);
    GenericType elemGenericType = null;
    if (genericType != null) {
      elemGenericType = genericType.getTypeParameter0();
    }
    return elemGenericType;
  }

  private GenericType getElementGenericType(WriteContext writeContext, int depth) {
    GenericType genericType = writeContext.getGenerics().nextGenericType(depth);
    GenericType elemGenericType = null;
    if (genericType != null) {
      elemGenericType = genericType.getTypeParameter0();
    }
    return elemGenericType;
  }

  /**
   * Hook for java serialization codegen, read/write elements will call collection.get/add methods.
   *
   * <p>For key/value type which is final, using codegen may get a big performance gain
   *
   * @return true if read/write elements support calling collection.get/add methods
   */
  public final boolean supportCodegenHook() {
    return supportCodegenHook;
  }

  /**
   * Write data except size and elements.
   *
   * <ol>
   *   In codegen, follows is call order:
   *   <li>write collection class if not final
   *   <li>write collection size
   *   <li>onCollectionWrite
   *   <li>write elements
   *   <li>onCollectionWriteFinish
   * </ol>
   */
  public abstract Collection onCollectionWrite(WriteContext writeContext, T value);

  public void onCollectionWriteFinish(Collection map) {}

  /**
   * Write elements data header. Keep this consistent with
   * `BaseObjectCodecBuilder#writeElementsHeader`.
   *
   * @return a bitmap, higher 24 bits are reserved.
   */
  protected final int writeElementsHeader(WriteContext writeContext, Collection value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    GenericType elemGenericType = getElementGenericType(writeContext, writeContext.getDepth());
    if (elemGenericType != null) {
      boolean trackingRef = elemGenericType.trackingRef(typeResolver);
      if (elemGenericType.isMonomorphic()) {
        if (trackingRef) {
          buffer.writeByte(CollectionFlags.DECL_SAME_TYPE_TRACKING_REF);
          return CollectionFlags.DECL_SAME_TYPE_TRACKING_REF;
        } else {
          return writeNullabilityHeader(buffer, value);
        }
      } else {
        if (trackingRef) {
          return writeTypeHeader(
              writeContext, value, elemGenericType.getCls(), elementTypeInfoHolder);
        } else {
          return writeTypeNullabilityHeader(
              writeContext, value, elemGenericType.getCls(), elementTypeInfoHolder);
        }
      }
    } else {
      if (config.trackingRef()) {
        return writeTypeHeader(writeContext, value, elementTypeInfoHolder);
      } else {
        return writeTypeNullabilityHeader(writeContext, value, null, elementTypeInfoHolder);
      }
    }
  }

  /** Element type is final, write whether any elements is null. */
  @CodegenInvoke
  public int writeNullabilityHeader(MemoryBuffer buffer, Collection value) {
    for (Object elem : value) {
      if (elem == null) {
        buffer.writeByte(CollectionFlags.DECL_SAME_TYPE_HAS_NULL);
        return CollectionFlags.DECL_SAME_TYPE_HAS_NULL;
      }
    }
    buffer.writeByte(CollectionFlags.DECL_SAME_TYPE_NOT_HAS_NULL);
    return CollectionFlags.DECL_SAME_TYPE_NOT_HAS_NULL;
  }

  /**
   * Need to track elements ref, declared element type is not morphic, can't check elements
   * nullability.
   */
  @CodegenInvoke
  public int writeTypeHeader(
      WriteContext writeContext,
      Collection value,
      Class<?> declareElementType,
      TypeInfoHolder cache) {
    MemoryBuffer buffer = writeContext.getBuffer();
    int bitmap = CollectionFlags.TRACKING_REF;
    boolean hasDifferentClass = false;
    Class<?> elemClass = null;
    for (Object elem : value) {
      if (elem != null) {
        if (elemClass == null) {
          elemClass = elem.getClass();
          continue;
        }
        if (elemClass != elem.getClass()) {
          hasDifferentClass = true;
          break;
        }
      }
    }
    if (hasDifferentClass) {
      buffer.writeByte(bitmap);
    } else {
      if (elemClass == null) {
        elemClass = void.class;
      }
      bitmap |= CollectionFlags.IS_SAME_TYPE;
      // Write class in case peer doesn't have this class.
      if (!config.isMetaShareEnabled() && elemClass == declareElementType) {
        bitmap |= CollectionFlags.IS_DECL_ELEMENT_TYPE;
        buffer.writeByte(bitmap);
      } else {
        buffer.writeByte(bitmap);
        // Update classinfo, the caller will use it.
        TypeResolver typeResolver = this.typeResolver;
        typeResolver.writeTypeInfo(writeContext, typeResolver.getTypeInfo(elemClass, cache));
      }
    }
    return bitmap;
  }

  /** Maybe track elements ref, or write elements nullability. */
  @CodegenInvoke
  public int writeTypeHeader(WriteContext writeContext, Collection value, TypeInfoHolder cache) {
    MemoryBuffer buffer = writeContext.getBuffer();
    int bitmap = 0;
    boolean hasDifferentClass = false;
    Class<?> elemClass = null;
    boolean containsNull = false;
    for (Object elem : value) {
      if (elem == null) {
        containsNull = true;
      } else if (elemClass == null) {
        elemClass = elem.getClass();
      } else {
        if (!hasDifferentClass && elem.getClass() != elemClass) {
          hasDifferentClass = true;
        }
      }
    }
    if (containsNull) {
      bitmap |= CollectionFlags.HAS_NULL;
    }
    if (hasDifferentClass) {
      bitmap |= CollectionFlags.TRACKING_REF;
      buffer.writeByte(bitmap);
    } else {
      TypeResolver typeResolver = this.typeResolver;
      // When serialize a collection with all elements null directly, the declare type
      // will be equal to element type: null
      if (elemClass == null) {
        elemClass = void.class;
      }
      bitmap |= CollectionFlags.IS_SAME_TYPE;
      TypeInfo typeInfo = typeResolver.getTypeInfo(elemClass, cache);
      if (typeInfo.getSerializer().needToWriteRef()) {
        bitmap |= CollectionFlags.TRACKING_REF;
      }
      buffer.writeByte(bitmap);
      typeResolver.writeTypeInfo(writeContext, typeInfo);
    }
    return bitmap;
  }

  /**
   * Element type is not final by {@link ClassResolver#isMonomorphic}, need to write element type.
   * Elements ref tracking is disabled, write whether any elements is null.
   */
  @CodegenInvoke
  public int writeTypeNullabilityHeader(
      WriteContext writeContext,
      Collection value,
      Class<?> declareElementType,
      TypeInfoHolder cache) {
    MemoryBuffer buffer = writeContext.getBuffer();
    int bitmap = 0;
    boolean containsNull = false;
    boolean hasDifferentClass = false;
    Class<?> elemClass = null;
    for (Object elem : value) {
      if (elem == null) {
        containsNull = true;
      } else if (elemClass == null) {
        elemClass = elem.getClass();
      } else {
        if (!hasDifferentClass && elem.getClass() != elemClass) {
          hasDifferentClass = true;
        }
      }
    }
    if (containsNull) {
      bitmap |= CollectionFlags.HAS_NULL;
    }
    if (hasDifferentClass) {
      buffer.writeByte(bitmap);
    } else {
      // When serialize a collection with all elements null directly, the declare type
      // will be equal to element type: null
      if (elemClass == null) {
        elemClass = Object.class;
      }
      bitmap |= CollectionFlags.IS_SAME_TYPE;
      // Write class in case peer doesn't have this class.
      if (!config.isMetaShareEnabled() && elemClass == declareElementType) {
        bitmap |= CollectionFlags.IS_DECL_ELEMENT_TYPE;
        buffer.writeByte(bitmap);
      } else {
        buffer.writeByte(bitmap);
        TypeResolver typeResolver = this.typeResolver;
        TypeInfo typeInfo = typeResolver.getTypeInfo(elemClass, cache);
        typeResolver.writeTypeInfo(writeContext, typeInfo);
      }
    }
    return bitmap;
  }

  @Override
  public void write(WriteContext writeContext, T value) {
    Collection collection = onCollectionWrite(writeContext, value);
    int len = collection.size();
    if (len != 0) {
      writeElements(writeContext, collection);
    }
    onCollectionWriteFinish(collection);
  }

  protected final void writeElements(WriteContext writeContext, Collection value) {
    int flags = writeElementsHeader(writeContext, value);
    GenericType elemGenericType = getElementGenericType(writeContext, writeContext.getDepth());
    if (elemGenericType != null) {
      javaWriteWithGenerics(writeContext, value, elemGenericType, flags);
    } else {
      generalJavaWrite(writeContext, value, null, flags);
    }
  }

  private void javaWriteWithGenerics(
      WriteContext writeContext, Collection collection, GenericType elemGenericType, int flags) {
    boolean hasGenericParameters = elemGenericType.hasGenericParameters();
    org.apache.fory.type.Generics generics = writeContext.getGenerics();
    if (hasGenericParameters) {
      generics.pushGenericType(elemGenericType, writeContext.getDepth());
    }
    // Note: ObjectSerializer should mark `FinalElemType` in `Collection<FinalElemType>`
    // as non-final to write class def when meta share is enabled.
    if (elemGenericType.isMonomorphic()) {
      Serializer serializer = elemGenericType.getSerializer(typeResolver);
      writeSameTypeElements(writeContext, serializer, flags, collection);
    } else {
      generalJavaWrite(writeContext, collection, elemGenericType, flags);
    }
    if (hasGenericParameters) {
      generics.popGenericType(writeContext.getDepth());
    }
  }

  private void generalJavaWrite(
      WriteContext writeContext, Collection collection, GenericType elemGenericType, int flags) {
    if ((flags & CollectionFlags.IS_SAME_TYPE) == CollectionFlags.IS_SAME_TYPE) {
      Serializer serializer;
      if ((flags & CollectionFlags.IS_DECL_ELEMENT_TYPE) == CollectionFlags.IS_DECL_ELEMENT_TYPE) {
        Preconditions.checkNotNull(elemGenericType);
        serializer = elemGenericType.getSerializer(typeResolver);
      } else {
        serializer = elementTypeInfoHolder.getSerializer();
      }
      writeSameTypeElements(writeContext, serializer, flags, collection);
    } else {
      writeDifferentTypeElements(writeContext, flags, collection);
    }
  }

  private <T extends Collection> void writeSameTypeElements(
      WriteContext writeContext, Serializer serializer, int flags, T collection) {
    writeContext.increaseDepth();
    if ((flags & CollectionFlags.TRACKING_REF) == CollectionFlags.TRACKING_REF) {
      for (Object elem : collection) {
        if (!writeContext.writeRefOrNull(elem)) {
          serializer.write(writeContext, elem);
        }
      }
    } else {
      if ((flags & CollectionFlags.HAS_NULL) != CollectionFlags.HAS_NULL) {
        for (Object elem : collection) {
          serializer.write(writeContext, elem);
        }
      } else {
        MemoryBuffer buffer = writeContext.getBuffer();
        for (Object elem : collection) {
          if (elem == null) {
            buffer.writeByte(Fory.NULL_FLAG);
          } else {
            buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
            serializer.write(writeContext, elem);
          }
        }
      }
    }
    writeContext.decreaseDepth();
  }

  private <T extends Collection> void writeDifferentTypeElements(
      WriteContext writeContext, int flags, T collection) {
    if ((flags & CollectionFlags.TRACKING_REF) == CollectionFlags.TRACKING_REF) {
      for (Object elem : collection) {
        writeContext.writeRef(elem);
      }
    } else {
      if ((flags & CollectionFlags.HAS_NULL) != CollectionFlags.HAS_NULL) {
        for (Object elem : collection) {
          writeContext.writeNonRef(elem);
        }
      } else {
        MemoryBuffer buffer = writeContext.getBuffer();
        for (Object elem : collection) {
          if (elem == null) {
            buffer.writeByte(Fory.NULL_FLAG);
          } else {
            buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
            writeContext.writeNonRef(elem);
          }
        }
      }
    }
  }

  @Override
  public T read(ReadContext readContext) {
    Collection collection = newCollection(readContext);
    int numElements = getAndClearNumElements();
    if (numElements != 0) {
      readElements(readContext, collection, numElements);
    }
    return onCollectionRead(collection);
  }

  /**
   * Read data except size and elements, return empty collection to be filled.
   *
   * <ol>
   *   In codegen, follows is call order:
   *   <li>read collection class if not final
   *   <li>newCollection: read and set collection size, read collection header and create
   *       collection.
   *   <li>read elements
   * </ol>
   *
   * <p>Collection must have default constructor to be invoked by fory, otherwise created object
   * can't be used to adding elements. For example:
   *
   * <pre>{@code new ArrayList<Integer> {add(1);}}</pre>
   *
   * <p>without default constructor, created list will have elementData as null, adding elements
   * will raise NPE.
   */
  public Collection newCollection(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    numElements = buffer.readVarUint32Small7();
    if (constructor == null) {
      constructor = ReflectionUtils.getCtrHandle(type, true);
    }
    try {
      T instance = (T) constructor.invoke();
      readContext.reference(instance);
      return (Collection) instance;
    } catch (Throwable e) {
      // reduce code size of critical path.
      throw buildException(e);
    }
  }

  public Collection newCollection(CopyContext copyContext, Collection collection) {
    return newCollection(collection);
  }

  /** Create a new empty collection for copy. */
  public Collection newCollection(Collection collection) {
    numElements = collection.size();
    if (constructor == null) {
      constructor = ReflectionUtils.getCtrHandle(type, true);
    }
    try {
      return (Collection) constructor.invoke();
    } catch (Throwable e) {
      // reduce code size of critical path.
      throw buildException(e);
    }
  }

  public void copyElements(
      CopyContext copyContext, Collection originCollection, Collection newCollection) {
    for (Object element : originCollection) {
      if (element != null) {
        TypeInfo typeInfo = typeResolver.getTypeInfo(element.getClass(), elementTypeInfoHolder);
        if (!typeInfo.getSerializer().isImmutable()) {
          element = copyContext.copyObject(element, typeInfo.getTypeId());
        }
      }
      newCollection.add(element);
    }
  }

  public void copyElements(
      CopyContext copyContext, Collection originCollection, Object[] elements) {
    int index = 0;
    for (Object element : originCollection) {
      if (element != null) {
        TypeInfo typeInfo = typeResolver.getTypeInfo(element.getClass(), elementTypeInfoHolder);
        if (!typeInfo.getSerializer().isImmutable()) {
          element = copyContext.copyObject(element, typeInfo.getSerializer());
        }
      }
      elements[index++] = element;
    }
  }

  private RuntimeException buildException(Throwable e) {
    return new IllegalArgumentException(
        "Please provide public no arguments constructor for class " + type, e);
  }

  /**
   * Get and reset numElements of deserializing collection. Should be called after {@link
   * #newCollection(ReadContext)}. Nested read may overwrite this element, reset is necessary to
   * avoid use wrong value by mistake.
   */
  public int getAndClearNumElements() {
    int size = numElements;
    numElements = -1; // nested read may overwrite this element.
    return size;
  }

  protected void setNumElements(int numElements) {
    this.numElements = numElements;
  }

  public abstract T onCollectionRead(Collection collection);

  protected void readElements(ReadContext readContext, Collection collection, int numElements) {
    MemoryBuffer buffer = readContext.getBuffer();
    int flags = buffer.readByte();
    GenericType elemGenericType = getElementGenericType(readContext, readContext.getDepth());
    if (elemGenericType != null) {
      javaReadWithGenerics(readContext, collection, numElements, elemGenericType, flags);
    } else {
      generalJavaRead(readContext, collection, numElements, flags, null);
    }
  }

  private void javaReadWithGenerics(
      ReadContext readContext,
      Collection collection,
      int numElements,
      GenericType elemGenericType,
      int flags) {
    boolean hasGenericParameters = elemGenericType.hasGenericParameters();
    org.apache.fory.type.Generics generics = readContext.getGenerics();
    if (hasGenericParameters) {
      generics.pushGenericType(elemGenericType, readContext.getDepth());
    }
    if (elemGenericType.isMonomorphic()) {
      Serializer serializer = elemGenericType.getSerializer(typeResolver);
      readSameTypeElements(readContext, serializer, flags, collection, numElements);
    } else {
      generalJavaRead(readContext, collection, numElements, flags, elemGenericType);
    }
    if (hasGenericParameters) {
      generics.popGenericType(readContext.getDepth());
    }
  }

  private void generalJavaRead(
      ReadContext readContext,
      Collection collection,
      int numElements,
      int flags,
      GenericType elemGenericType) {
    if ((flags & CollectionFlags.IS_SAME_TYPE) == CollectionFlags.IS_SAME_TYPE) {
      Serializer serializer;
      TypeResolver typeResolver = this.typeResolver;
      if ((flags & CollectionFlags.IS_DECL_ELEMENT_TYPE) != CollectionFlags.IS_DECL_ELEMENT_TYPE) {
        serializer = typeResolver.readTypeInfo(readContext, elementTypeInfoHolder).getSerializer();
      } else {
        serializer = elemGenericType.getSerializer(typeResolver);
      }
      readSameTypeElements(readContext, serializer, flags, collection, numElements);
    } else {
      readDifferentTypeElements(readContext, flags, collection, numElements);
    }
  }

  /** Read elements whose type are same. */
  private <T extends Collection> void readSameTypeElements(
      ReadContext readContext, Serializer serializer, int flags, T collection, int numElements) {
    readContext.increaseDepth();
    if ((flags & CollectionFlags.TRACKING_REF) == CollectionFlags.TRACKING_REF) {
      for (int i = 0; i < numElements; i++) {
        collection.add(serializer.read(readContext, RefMode.TRACKING));
      }
    } else {
      if ((flags & CollectionFlags.HAS_NULL) != CollectionFlags.HAS_NULL) {
        for (int i = 0; i < numElements; i++) {
          collection.add(serializer.read(readContext, RefMode.NONE));
        }
      } else {
        MemoryBuffer buffer = readContext.getBuffer();
        for (int i = 0; i < numElements; i++) {
          if (buffer.readByte() == Fory.NULL_FLAG) {
            collection.add(null);
          } else {
            collection.add(serializer.read(readContext, RefMode.NONE));
          }
        }
      }
    }
    readContext.decreaseDepth();
  }

  /** Read elements whose type are different. */
  private <T extends Collection> void readDifferentTypeElements(
      ReadContext readContext, int flags, T collection, int numElements) {
    if ((flags & CollectionFlags.TRACKING_REF) == CollectionFlags.TRACKING_REF) {
      Preconditions.checkState(config.trackingRef(), "Reference tracking is not enabled");
      for (int i = 0; i < numElements; i++) {
        collection.add(readContext.readRef());
      }
    } else {
      if ((flags & CollectionFlags.HAS_NULL) != CollectionFlags.HAS_NULL) {
        for (int i = 0; i < numElements; i++) {
          collection.add(readContext.readNonRef());
        }
      } else {
        MemoryBuffer buffer = readContext.getBuffer();
        for (int i = 0; i < numElements; i++) {
          byte headFlag = buffer.readByte();
          if (headFlag == Fory.NULL_FLAG) {
            collection.add(null);
          } else {
            collection.add(readContext.readNonRef());
          }
        }
      }
    }
  }
}
