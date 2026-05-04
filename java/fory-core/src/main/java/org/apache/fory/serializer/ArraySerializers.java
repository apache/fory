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

package org.apache.fory.serializer;

import java.lang.reflect.Array;
import org.apache.fory.Fory;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.RefMode;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeInfoHolder;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.collection.CollectionFlags;
import org.apache.fory.type.BFloat16;
import org.apache.fory.type.Float16;
import org.apache.fory.type.Types;
import org.apache.fory.util.Preconditions;

/**
 * Serializers for object-array types.
 *
 * <p>Primitive dense-array carriers live in {@link PrimitiveArraySerializers}. The serializers in
 * this file intentionally use the normal list payload protocol while staying array-owned so hot
 * object-array paths avoid adapter allocation.
 */
public final class ArraySerializers {
  private ArraySerializers() {}

  /**
   * Returns the object-array serializer for {@code cls}.
   *
   * <p>Keep the exact-class branches below. They are deliberate optimized fast paths for common
   * object arrays and still write the list protocol; replacing them with the generic fallback adds
   * avoidable type-header work to hot paths.
   */
  public static Serializer<?> newObjectArraySerializer(TypeResolver typeResolver, Class<?> cls) {
    if (cls == Boolean[].class) {
      return new BooleanArraySerializer(typeResolver);
    } else if (cls == Byte[].class) {
      return new ByteArraySerializer(typeResolver);
    } else if (cls == Character[].class) {
      return new CharArraySerializer(typeResolver);
    } else if (cls == Short[].class) {
      return new ShortArraySerializer(typeResolver);
    } else if (cls == Integer[].class) {
      return new IntArraySerializer(typeResolver);
    } else if (cls == Long[].class) {
      return new LongArraySerializer(typeResolver);
    } else if (cls == Float[].class) {
      return new FloatArraySerializer(typeResolver);
    } else if (cls == Double[].class) {
      return new DoubleArraySerializer(typeResolver);
    } else if (cls == Float16[].class) {
      return new Float16ArraySerializer(typeResolver);
    } else if (cls == BFloat16[].class) {
      return new BFloat16ArraySerializer(typeResolver);
    } else if (cls == String[].class) {
      return new StringArraySerializer(typeResolver);
    }
    return new ObjectArraySerializer(typeResolver, cls);
  }

  /** Serializer for object arrays using the list payload protocol. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static final class ObjectArraySerializer extends Serializer<Object[]> {
    private final TypeResolver typeResolver;
    private final TypeInfoHolder elementTypeInfoHolder;

    public ObjectArraySerializer(TypeResolver typeResolver, Class<?> cls) {
      super(typeResolver.getConfig(), (Class) cls);
      this.typeResolver = typeResolver;
      if (typeResolver instanceof ClassResolver) {
        typeResolver.setSerializer((Class) cls, this);
      }
      Preconditions.checkArgument(cls.isArray() && !cls.getComponentType().isPrimitive());
      elementTypeInfoHolder = typeResolver.nilTypeInfoHolder();
    }

    @Override
    public void write(WriteContext writeContext, Object[] value) {
      writeArrayPayload(writeContext, typeResolver, elementTypeInfoHolder, value);
    }

    @Override
    public Object[] copy(CopyContext copyContext, Object[] originArray) {
      int length = originArray.length;
      Object[] newArray = newArray(length);
      copyContext.reference(originArray, newArray);
      TypeInfoHolder holder = elementTypeInfoHolder;
      for (int i = 0; i < length; i++) {
        Object element = originArray[i];
        if (element != null) {
          TypeInfo typeInfo = typeResolver.getTypeInfo(element.getClass(), holder);
          Serializer serializer = typeInfo.getSerializer();
          if (!serializer.isImmutable()) {
            element = copyContext.copyObject(element, serializer);
          }
        }
        newArray[i] = element;
      }
      return newArray;
    }

    @Override
    public Object[] read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = buffer.readVarUInt32Small7();
      Object[] value = newArray(numElements);
      readContext.reference(value);
      if (numElements != 0) {
        readArrayElements(
            readContext,
            typeResolver,
            elementTypeInfoHolder,
            type.getComponentType(),
            value,
            numElements);
      }
      return value;
    }

    private Object[] newArray(int numElements) {
      return (Object[]) Array.newInstance(type.getComponentType(), numElements);
    }
  }

  /**
   * Base serializer for exact object-array element types.
   *
   * <p>These serializers are not dense-array serializers. They keep {@code Boolean[]}, {@code
   * Integer[]}, {@code String[]}, {@code Float16[]}, and similar object arrays on the list protocol
   * while avoiding collection adapters, generic metadata pushes, per-array element type scans, and
   * repeated element type metadata.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private abstract static class SameTypeObjectArraySerializer extends Serializer<Object[]> {
    private final TypeResolver typeResolver;
    private final Class<?> componentType;
    private final Serializer elementSerializer;
    private final TypeInfoHolder elementTypeInfoHolder;

    SameTypeObjectArraySerializer(
        TypeResolver typeResolver, Class<?> arrayType, Class<?> componentType) {
      super(typeResolver.getConfig(), (Class) arrayType);
      this.typeResolver = typeResolver;
      this.componentType = componentType;
      if (typeResolver instanceof ClassResolver) {
        typeResolver.setSerializer((Class) arrayType, this);
      }
      elementSerializer = typeResolver.getSerializer(componentType);
      elementTypeInfoHolder = typeResolver.nilTypeInfoHolder();
    }

    @Override
    public void write(WriteContext writeContext, Object[] value) {
      if (typeResolver.isCrossLanguage()) {
        writeArrayPayload(writeContext, typeResolver, elementTypeInfoHolder, value);
      } else {
        writeDeclaredArrayPayload(writeContext, elementSerializer, value);
      }
    }

    @Override
    public Object[] copy(CopyContext copyContext, Object[] originArray) {
      int length = originArray.length;
      Object[] newArray = newArray(length);
      copyContext.reference(originArray, newArray);
      if (elementSerializer.isImmutable()) {
        System.arraycopy(originArray, 0, newArray, 0, length);
      } else {
        Serializer serializer = elementSerializer;
        for (int i = 0; i < length; i++) {
          Object element = originArray[i];
          if (element != null) {
            element = copyContext.copyObject(element, serializer);
          }
          newArray[i] = element;
        }
      }
      return newArray;
    }

    @Override
    public Object[] read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = buffer.readVarUInt32Small7();
      Object[] value = newArray(numElements);
      readContext.reference(value);
      if (numElements != 0) {
        if (typeResolver.isCrossLanguage()) {
          readArrayElements(
              readContext, typeResolver, elementTypeInfoHolder, componentType, value, numElements);
        } else {
          readDeclaredArrayElements(readContext, elementSerializer, value, numElements);
        }
      }
      return value;
    }

    private Object[] newArray(int numElements) {
      return (Object[]) Array.newInstance(componentType, numElements);
    }
  }

  /** Optimized list-protocol serializer for {@code Boolean[]}. Do not remove. */
  public static final class BooleanArraySerializer extends SameTypeObjectArraySerializer {
    public BooleanArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, Boolean[].class, Boolean.class);
    }
  }

  /** Optimized list-protocol serializer for {@code Byte[]}. Do not remove. */
  public static final class ByteArraySerializer extends SameTypeObjectArraySerializer {
    public ByteArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, Byte[].class, Byte.class);
    }
  }

  /** Optimized list-protocol serializer for {@code Character[]}. Do not remove. */
  public static final class CharArraySerializer extends SameTypeObjectArraySerializer {
    public CharArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, Character[].class, Character.class);
    }
  }

  /** Optimized list-protocol serializer for {@code Short[]}. Do not remove. */
  public static final class ShortArraySerializer extends SameTypeObjectArraySerializer {
    public ShortArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, Short[].class, Short.class);
    }
  }

  /** Optimized list-protocol serializer for {@code Integer[]}. Do not remove. */
  public static final class IntArraySerializer extends SameTypeObjectArraySerializer {
    public IntArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, Integer[].class, Integer.class);
    }
  }

  /** Optimized list-protocol serializer for {@code Long[]}. Do not remove. */
  public static final class LongArraySerializer extends SameTypeObjectArraySerializer {
    public LongArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, Long[].class, Long.class);
    }
  }

  /** Optimized list-protocol serializer for {@code Float[]}. Do not remove. */
  public static final class FloatArraySerializer extends SameTypeObjectArraySerializer {
    public FloatArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, Float[].class, Float.class);
    }
  }

  /** Optimized list-protocol serializer for {@code Double[]}. Do not remove. */
  public static final class DoubleArraySerializer extends SameTypeObjectArraySerializer {
    public DoubleArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, Double[].class, Double.class);
    }
  }

  /**
   * Optimized list-protocol serializer for {@code Float16[]}. This is an object-array serializer;
   * dense {@code Float16Array} is handled by {@link
   * PrimitiveArraySerializers.Float16ArraySerializer}.
   */
  public static final class Float16ArraySerializer extends SameTypeObjectArraySerializer {
    public Float16ArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, Float16[].class, Float16.class);
    }
  }

  /**
   * Optimized list-protocol serializer for {@code BFloat16[]}. This is an object-array serializer;
   * dense {@code BFloat16Array} is handled by {@link
   * PrimitiveArraySerializers.BFloat16ArraySerializer}.
   */
  public static final class BFloat16ArraySerializer extends SameTypeObjectArraySerializer {
    public BFloat16ArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, BFloat16[].class, BFloat16.class);
    }
  }

  /** Optimized list-protocol serializer for {@code String[]}. Do not remove. */
  public static final class StringArraySerializer extends SameTypeObjectArraySerializer {
    public StringArraySerializer(TypeResolver typeResolver) {
      super(typeResolver, String[].class, String.class);
    }
  }

  private static void writeArrayPayload(
      WriteContext writeContext,
      TypeResolver typeResolver,
      TypeInfoHolder elementTypeInfoHolder,
      Object[] value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    int numElements = value.length;
    buffer.writeVarUInt32Small7(numElements);
    if (numElements == 0) {
      return;
    }
    int flags = writeArrayElementHeader(writeContext, typeResolver, elementTypeInfoHolder, value);
    if ((flags & CollectionFlags.IS_SAME_TYPE) == CollectionFlags.IS_SAME_TYPE) {
      writeSameTypeArrayElements(writeContext, elementTypeInfoHolder.getSerializer(), flags, value);
    } else {
      writeDifferentTypeArrayElements(writeContext, flags, value);
    }
  }

  private static void writeDeclaredArrayPayload(
      WriteContext writeContext, Serializer elementSerializer, Object[] value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    int numElements = value.length;
    buffer.writeVarUInt32Small7(numElements);
    if (numElements == 0) {
      return;
    }
    int flags = CollectionFlags.IS_DECL_ELEMENT_TYPE | CollectionFlags.IS_SAME_TYPE;
    if (elementSerializer.needToWriteRef()) {
      flags |= CollectionFlags.TRACKING_REF;
    } else {
      for (Object elem : value) {
        if (elem == null) {
          flags |= CollectionFlags.HAS_NULL;
          break;
        }
      }
    }
    buffer.writeByte(flags);
    writeSameTypeArrayElements(writeContext, elementSerializer, flags, value);
  }

  private static int writeArrayElementHeader(
      WriteContext writeContext,
      TypeResolver typeResolver,
      TypeInfoHolder elementTypeInfoHolder,
      Object[] value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    int flags = 0;
    boolean containsNull = false;
    boolean hasDifferentClass = false;
    Class<?> elemClass = null;
    for (Object elem : value) {
      if (elem == null) {
        containsNull = true;
      } else if (elemClass == null) {
        elemClass = elem.getClass();
      } else if (!hasDifferentClass && elem.getClass() != elemClass) {
        hasDifferentClass = true;
      }
    }
    if (containsNull) {
      flags |= CollectionFlags.HAS_NULL;
    }
    if (typeResolver.getConfig().trackingRef()) {
      if (hasDifferentClass) {
        flags |= CollectionFlags.TRACKING_REF;
        buffer.writeByte(flags);
        return flags;
      }
      if (elemClass == null) {
        elemClass = void.class;
      }
      flags |= CollectionFlags.IS_SAME_TYPE;
      TypeInfo typeInfo = typeResolver.getTypeInfo(elemClass, elementTypeInfoHolder);
      if (typeInfo.getSerializer().needToWriteRef()) {
        flags |= CollectionFlags.TRACKING_REF;
      }
      buffer.writeByte(flags);
      typeResolver.writeTypeInfo(writeContext, typeInfo);
      return flags;
    }
    if (hasDifferentClass) {
      buffer.writeByte(flags);
      return flags;
    }
    if (elemClass == null) {
      elemClass = Object.class;
    }
    flags |= CollectionFlags.IS_SAME_TYPE;
    TypeInfo typeInfo = typeResolver.getTypeInfo(elemClass, elementTypeInfoHolder);
    buffer.writeByte(flags);
    typeResolver.writeTypeInfo(writeContext, typeInfo);
    return flags;
  }

  private static void writeSameTypeArrayElements(
      WriteContext writeContext, Serializer serializer, int flags, Object[] value) {
    writeContext.increaseDepth();
    if ((flags & CollectionFlags.TRACKING_REF) == CollectionFlags.TRACKING_REF) {
      for (Object elem : value) {
        if (!writeContext.writeRefOrNull(elem)) {
          serializer.write(writeContext, elem);
        }
      }
    } else if ((flags & CollectionFlags.HAS_NULL) != CollectionFlags.HAS_NULL) {
      for (Object elem : value) {
        serializer.write(writeContext, elem);
      }
    } else {
      MemoryBuffer buffer = writeContext.getBuffer();
      for (Object elem : value) {
        if (elem == null) {
          buffer.writeByte(Fory.NULL_FLAG);
        } else {
          buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
          serializer.write(writeContext, elem);
        }
      }
    }
    writeContext.decreaseDepth();
  }

  private static void writeDifferentTypeArrayElements(
      WriteContext writeContext, int flags, Object[] value) {
    if ((flags & CollectionFlags.TRACKING_REF) == CollectionFlags.TRACKING_REF) {
      for (Object elem : value) {
        writeContext.writeRef(elem);
      }
    } else if ((flags & CollectionFlags.HAS_NULL) != CollectionFlags.HAS_NULL) {
      for (Object elem : value) {
        writeContext.writeNonRef(elem);
      }
    } else {
      MemoryBuffer buffer = writeContext.getBuffer();
      for (Object elem : value) {
        if (elem == null) {
          buffer.writeByte(Fory.NULL_FLAG);
        } else {
          buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
          writeContext.writeNonRef(elem);
        }
      }
    }
  }

  private static void readArrayElements(
      ReadContext readContext,
      TypeResolver typeResolver,
      TypeInfoHolder elementTypeInfoHolder,
      Class<?> declaredElementType,
      Object[] value,
      int numElements) {
    MemoryBuffer buffer = readContext.getBuffer();
    int flags = buffer.readByte();
    if ((flags & CollectionFlags.IS_SAME_TYPE) == CollectionFlags.IS_SAME_TYPE) {
      Serializer serializer;
      if ((flags & CollectionFlags.IS_DECL_ELEMENT_TYPE) == CollectionFlags.IS_DECL_ELEMENT_TYPE) {
        serializer = typeResolver.getSerializer(declaredElementType);
      } else {
        TypeInfo elementTypeInfo = typeResolver.readTypeInfo(readContext, elementTypeInfoHolder);
        serializer = resolveElementSerializer(typeResolver, declaredElementType, elementTypeInfo);
      }
      readSameTypeArrayElements(readContext, serializer, flags, value, numElements);
    } else {
      readDifferentTypeArrayElements(
          readContext,
          typeResolver,
          elementTypeInfoHolder,
          declaredElementType,
          flags,
          value,
          numElements);
    }
  }

  private static void readDeclaredArrayElements(
      ReadContext readContext, Serializer elementSerializer, Object[] value, int numElements) {
    int flags = readContext.getBuffer().readByte();
    Preconditions.checkState(
        (flags & CollectionFlags.IS_SAME_TYPE) == CollectionFlags.IS_SAME_TYPE
            && (flags & CollectionFlags.IS_DECL_ELEMENT_TYPE)
                == CollectionFlags.IS_DECL_ELEMENT_TYPE,
        "Declared object-array serializer expected list payload with declared element type");
    readSameTypeArrayElements(readContext, elementSerializer, flags, value, numElements);
  }

  private static void readSameTypeArrayElements(
      ReadContext readContext, Serializer serializer, int flags, Object[] value, int numElements) {
    readContext.increaseDepth();
    if ((flags & CollectionFlags.TRACKING_REF) == CollectionFlags.TRACKING_REF) {
      for (int i = 0; i < numElements; i++) {
        value[i] = serializer.read(readContext, RefMode.TRACKING);
      }
    } else if ((flags & CollectionFlags.HAS_NULL) != CollectionFlags.HAS_NULL) {
      for (int i = 0; i < numElements; i++) {
        value[i] = serializer.read(readContext, RefMode.NONE);
      }
    } else {
      MemoryBuffer buffer = readContext.getBuffer();
      for (int i = 0; i < numElements; i++) {
        if (buffer.readByte() == Fory.NULL_FLAG) {
          value[i] = null;
        } else {
          value[i] = serializer.read(readContext, RefMode.NONE);
        }
      }
    }
    readContext.decreaseDepth();
  }

  private static void readDifferentTypeArrayElements(
      ReadContext readContext,
      TypeResolver typeResolver,
      TypeInfoHolder elementTypeInfoHolder,
      Class<?> declaredElementType,
      int flags,
      Object[] value,
      int numElements) {
    if ((flags & CollectionFlags.TRACKING_REF) == CollectionFlags.TRACKING_REF) {
      Preconditions.checkState(
          readContext.getConfig().trackingRef(), "Reference tracking is not enabled");
      for (int i = 0; i < numElements; i++) {
        value[i] =
            readRefElement(readContext, typeResolver, elementTypeInfoHolder, declaredElementType);
      }
    } else if ((flags & CollectionFlags.HAS_NULL) != CollectionFlags.HAS_NULL) {
      for (int i = 0; i < numElements; i++) {
        value[i] =
            readNonRefElement(
                readContext, typeResolver, elementTypeInfoHolder, declaredElementType);
      }
    } else {
      MemoryBuffer buffer = readContext.getBuffer();
      for (int i = 0; i < numElements; i++) {
        if (buffer.readByte() == Fory.NULL_FLAG) {
          value[i] = null;
        } else {
          value[i] =
              readNonRefElement(
                  readContext, typeResolver, elementTypeInfoHolder, declaredElementType);
        }
      }
    }
  }

  private static Object readRefElement(
      ReadContext readContext,
      TypeResolver typeResolver,
      TypeInfoHolder elementTypeInfoHolder,
      Class<?> declaredElementType) {
    int nextReadRefId = readContext.tryPreserveRefId();
    if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
      Object element =
          readNonRefElement(readContext, typeResolver, elementTypeInfoHolder, declaredElementType);
      readContext.setReadRef(nextReadRefId, element);
      return element;
    }
    return readContext.getReadRef();
  }

  private static Object readNonRefElement(
      ReadContext readContext,
      TypeResolver typeResolver,
      TypeInfoHolder elementTypeInfoHolder,
      Class<?> declaredElementType) {
    TypeInfo elementTypeInfo = typeResolver.readTypeInfo(readContext, elementTypeInfoHolder);
    Serializer serializer =
        resolveElementSerializer(typeResolver, declaredElementType, elementTypeInfo);
    if (serializer == elementTypeInfo.getSerializer()) {
      return readContext.readNonRef(elementTypeInfo);
    }
    return readContext.readNonRef(serializer);
  }

  private static Serializer resolveElementSerializer(
      TypeResolver typeResolver, Class<?> declaredElementType, TypeInfo elementTypeInfo) {
    if (declaredElementType.isArray() && elementTypeInfo.getTypeId() == Types.LIST) {
      return typeResolver.getSerializer(declaredElementType);
    }
    return elementTypeInfo.getSerializer();
  }

  public static void registerDefaultSerializers(TypeResolver resolver) {
    // Exact object-array serializers below are intentional list-protocol fast paths. Do not replace
    // them with ObjectArraySerializer unless the replacement preserves the same allocation and type
    // metadata profile.
    resolver.registerInternalSerializer(
        Object[].class, new ObjectArraySerializer(resolver, Object[].class));
    resolver.registerInternalSerializer(
        Class[].class, new ObjectArraySerializer(resolver, Class[].class));
    resolver.registerInternalSerializer(Byte[].class, new ByteArraySerializer(resolver));
    resolver.registerInternalSerializer(Character[].class, new CharArraySerializer(resolver));
    resolver.registerInternalSerializer(Short[].class, new ShortArraySerializer(resolver));
    resolver.registerInternalSerializer(Integer[].class, new IntArraySerializer(resolver));
    resolver.registerInternalSerializer(Long[].class, new LongArraySerializer(resolver));
    resolver.registerInternalSerializer(Float[].class, new FloatArraySerializer(resolver));
    resolver.registerInternalSerializer(Double[].class, new DoubleArraySerializer(resolver));
    resolver.registerInternalSerializer(Float16[].class, new Float16ArraySerializer(resolver));
    resolver.registerInternalSerializer(BFloat16[].class, new BFloat16ArraySerializer(resolver));
    resolver.registerInternalSerializer(Boolean[].class, new BooleanArraySerializer(resolver));
    resolver.registerInternalSerializer(String[].class, new StringArraySerializer(resolver));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static final class UnknownArraySerializer extends Serializer<Object[]> {
    private final String className;
    private final TypeResolver typeResolver;
    private final TypeInfoHolder elementTypeInfoHolder;

    public UnknownArraySerializer(TypeResolver typeResolver, Class<?> cls) {
      this(typeResolver, "Unknown", cls);
    }

    public UnknownArraySerializer(TypeResolver typeResolver, String className, Class<?> cls) {
      super(typeResolver.getConfig(), (Class) cls);
      Preconditions.checkArgument(cls.isArray() && !cls.getComponentType().isPrimitive());
      this.className = className;
      this.typeResolver = typeResolver;
      elementTypeInfoHolder = typeResolver.nilTypeInfoHolder();
    }

    @Override
    public void write(WriteContext writeContext, Object[] value) {
      throw new UnsupportedOperationException(
          "Serialization isn't supported for unknown array class " + className);
    }

    @Override
    public Object[] read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = buffer.readVarUInt32Small7();
      Object[] value = newArray(numElements);
      readContext.reference(value);
      if (numElements != 0) {
        readArrayElements(
            readContext,
            typeResolver,
            elementTypeInfoHolder,
            type.getComponentType(),
            value,
            numElements);
      }
      return value;
    }

    private Object[] newArray(int numElements) {
      return (Object[]) Array.newInstance(type.getComponentType(), numElements);
    }
  }
}
