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

package org.apache.fory.meta;

import static org.apache.fory.meta.ClassDefEncoder.buildFields;
import static org.apache.fory.type.TypeUtils.COLLECTION_TYPE;
import static org.apache.fory.type.TypeUtils.MAP_TYPE;
import static org.apache.fory.type.TypeUtils.collectionOf;
import static org.apache.fory.type.TypeUtils.mapOf;

import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.builder.MetaSharedCodecBuilder;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.resolver.XtypeResolver;
import org.apache.fory.serializer.CompatibleSerializer;
import org.apache.fory.serializer.NonexistentClass;
import org.apache.fory.serializer.converter.FieldConverter;
import org.apache.fory.serializer.converter.FieldConverters;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.FinalObjectTypeStub;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.Preconditions;

/**
 * Serializable class definition to be sent to other process. So if sender peer and receiver peer
 * has different class definition for same class, such as add/remove fields, we can use this
 * definition to create different serializer to support back/forward compatibility.
 *
 * <p>Note that:
 * <li>If a class is already registered, this definition will contain class id only.
 * <li>Sending class definition is not cheap, should be sent with some kind of meta share mechanism.
 * <li>{@link ObjectStreamClass} doesn't contain any non-primitive field type info, which is not
 *     enough to create serializer in receiver.
 *
 * @see MetaSharedCodecBuilder
 * @see CompatibleMode#COMPATIBLE
 * @see CompatibleSerializer
 * @see ForyBuilder#withMetaShare
 * @see ReflectionUtils#getFieldOffset
 */
public class ClassDef implements Serializable {
  static final int COMPRESS_META_FLAG = 0b1 << 13;
  static final int HAS_FIELDS_META_FLAG = 0b1 << 12;
  // low 12 bits
  static final int META_SIZE_MASKS = 0xfff;
  static final int NUM_HASH_BITS = 50;
  private static final Logger LOG = LoggerFactory.getLogger(ClassDef.class);

  // TODO use field offset to sort field, which will hit l1-cache more. Since
  // `objectFieldOffset` is not part of jvm-specification, it may change between different jdk
  // vendor. But the deserialization peer use the class definition to create deserializer, it's OK
  // even field offset or fields order change between jvm process.
  public static final Comparator<Field> FIELD_COMPARATOR =
      (f1, f2) -> {
        long offset1 = Platform.objectFieldOffset(f1);
        long offset2 = Platform.objectFieldOffset(f2);
        long diff = offset1 - offset2;
        if (diff != 0) {
          return (int) diff;
        } else {
          if (!f1.equals(f2)) {
            LOG.warn(
                "Field {} has same offset with {}, please an issue with jdk info to fory", f1, f2);
          }
          int compare = f1.getDeclaringClass().getName().compareTo(f2.getName());
          if (compare != 0) {
            return compare;
          }
          return f1.getName().compareTo(f2.getName());
        }
      };

  private final ClassSpec classSpec;
  private final List<FieldInfo> fieldsInfo;
  private final boolean hasFieldsMeta;
  // Unique id for class def. If class def are same between processes, then the id will
  // be same too.
  private final long id;
  private final byte[] encoded;
  private transient List<Descriptor> descriptors;

  ClassDef(
      ClassSpec classSpec,
      List<FieldInfo> fieldsInfo,
      boolean hasFieldsMeta,
      long id,
      byte[] encoded) {
    this.classSpec = classSpec;
    this.fieldsInfo = fieldsInfo;
    this.hasFieldsMeta = hasFieldsMeta;
    this.id = id;
    this.encoded = encoded;
  }

  public static void skipClassDef(MemoryBuffer buffer, long id) {
    int size = (int) (id & META_SIZE_MASKS);
    if (size == META_SIZE_MASKS) {
      size += buffer.readVarUint32Small14();
    }
    buffer.increaseReaderIndex(size);
  }

  /**
   * Returns class name.
   *
   * @see Class#getName()
   */
  public String getClassName() {
    return classSpec.entireClassName;
  }

  public ClassSpec getClassSpec() {
    return classSpec;
  }

  /** Contain all fields info including all parent classes. */
  public List<FieldInfo> getFieldsInfo() {
    return fieldsInfo;
  }

  /** Returns ext meta for the class. */
  public boolean hasFieldsMeta() {
    return hasFieldsMeta;
  }

  /**
   * Returns an unique id for class def. If class def are same between processes, then the id will
   * be same too.
   */
  public long getId() {
    return id;
  }

  public byte[] getEncoded() {
    return encoded;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ClassDef classDef = (ClassDef) o;
    return hasFieldsMeta == classDef.hasFieldsMeta
        && id == classDef.id
        && Objects.equals(classSpec, classDef.classSpec)
        && Objects.equals(fieldsInfo, classDef.fieldsInfo);
  }

  @Override
  public int hashCode() {
    return Objects.hash(classSpec.entireClassName, fieldsInfo, id);
  }

  @Override
  public String toString() {
    return "ClassDef{"
        + "className='"
        + classSpec.entireClassName
        + '\''
        + ", fieldsInfo="
        + fieldsInfo
        + ", hasFieldsMeta="
        + hasFieldsMeta
        + ", id="
        + id
        + '}';
  }

  /** Write class definition to buffer. */
  public void writeClassDef(MemoryBuffer buffer) {
    buffer.writeBytes(encoded, 0, encoded.length);
  }

  /** Read class definition from buffer. */
  public static ClassDef readClassDef(Fory fory, MemoryBuffer buffer) {
    if (fory.isCrossLanguage()) {
      return TypeDefDecoder.decodeClassDef(fory.getXtypeResolver(), buffer, buffer.readInt64());
    }
    return ClassDefDecoder.decodeClassDef(fory.getClassResolver(), buffer, buffer.readInt64());
  }

  /** Read class definition from buffer. */
  public static ClassDef readClassDef(Fory fory, MemoryBuffer buffer, long header) {
    if (fory.isCrossLanguage()) {
      return TypeDefDecoder.decodeClassDef(fory.getXtypeResolver(), buffer, header);
    }
    return ClassDefDecoder.decodeClassDef(fory.getClassResolver(), buffer, header);
  }

  /**
   * Consolidate fields of <code>classDef</code> with <code>cls</code>. If some field exists in
   * <code>cls</code> but not in <code>classDef</code>, it won't be returned in final collection. If
   * some field exists in <code>classDef</code> but not in <code> cls</code>, it will be added to
   * final collection.
   *
   * @param cls class load in current process.
   */
  public List<Descriptor> getDescriptors(TypeResolver resolver, Class<?> cls) {
    if (descriptors == null) {
      SortedMap<Member, Descriptor> allDescriptorsMap =
          resolver.getFory().getClassResolver().getAllDescriptorsMap(cls, true);
      Map<String, Descriptor> descriptorsMap = new HashMap<>();
      for (Map.Entry<Member, Descriptor> e : allDescriptorsMap.entrySet()) {
        if (descriptorsMap.put(
                e.getKey().getDeclaringClass().getName() + "." + e.getKey().getName(), e.getValue())
            != null) {
          throw new IllegalStateException("Duplicate key");
        }
      }
      descriptors = new ArrayList<>(fieldsInfo.size());
      for (FieldInfo fieldInfo : fieldsInfo) {
        Descriptor descriptor =
            descriptorsMap.get(fieldInfo.getDefinedClass() + "." + fieldInfo.getFieldName());
        Descriptor newDesc = fieldInfo.toDescriptor(resolver, descriptor);
        Class<?> rawType = newDesc.getRawType();
        FieldType fieldType = fieldInfo.getFieldType();
        if (fieldType instanceof RegisteredFieldType) {
          String typeAlias = String.valueOf(((RegisteredFieldType) fieldType).getClassId());
          if (!typeAlias.equals(newDesc.getTypeName())) {
            newDesc = newDesc.copyWithTypeName(typeAlias);
          }
        }
        if (descriptor != null) {
          // Make DescriptorGrouper have consistent order whether field exist or not
          // fory builtin types skip
          if (useFieldType(rawType, descriptor)) {
            descriptor = descriptor.copyWithTypeName(newDesc.getTypeName());
            descriptors.add(descriptor);
          } else {
            FieldConverter<?> converter =
                FieldConverters.getConverter(rawType, descriptor.getField());
            if (converter != null) {
              newDesc.setFieldConverter(converter);
            }
            descriptors.add(newDesc);
          }
        } else {
          descriptors.add(newDesc);
        }
      }
    }
    return descriptors;
  }

  /** Returns true if can use current field type. */
  private static boolean useFieldType(Class<?> parsedType, Descriptor descriptor) {
    if (parsedType.isEnum()
        || parsedType.isAssignableFrom(descriptor.getRawType())
        || parsedType == FinalObjectTypeStub.class) {
      return true;
    }
    if (parsedType.isArray()) {
      Tuple2<Class<?>, Integer> info = TypeUtils.getArrayComponentInfo(parsedType);
      Field field = descriptor.getField();
      if (!field.getType().isArray() || TypeUtils.getArrayDimensions(field.getType()) != info.f1) {
        return false;
      }
      return info.f0 == FinalObjectTypeStub.class || info.f0.isEnum();
    }
    return false;
  }

  /**
   * FieldInfo contains all necessary info of a field to execute serialization/deserialization
   * logic.
   */
  public static class FieldInfo implements Serializable {
    /** where are current field defined. */
    private final String definedClass;

    /** Name of a field. */
    private final String fieldName;

    private final FieldType fieldType;

    FieldInfo(String definedClass, String fieldName, FieldType fieldType) {
      this.definedClass = definedClass;
      this.fieldName = fieldName;
      this.fieldType = fieldType;
    }

    /** Returns classname of current field defined. */
    public String getDefinedClass() {
      return definedClass;
    }

    /** Returns name of current field. */
    public String getFieldName() {
      return fieldName;
    }

    /** Returns whether field is annotated by an unsigned int id. */
    public boolean hasTag() {
      return false;
    }

    /** Returns annotated tag id for the field. */
    public short getTag() {
      return -1;
    }

    /** Returns type of current field. */
    public FieldType getFieldType() {
      return fieldType;
    }

    /**
     * Convert this field into a {@link Descriptor}, the corresponding {@link Field} field will be
     * null. Don't invoke this method if class does have <code>fieldName</code> field. In such case,
     * reflection should be used to get the descriptor.
     */
    Descriptor toDescriptor(TypeResolver resolver, Descriptor descriptor) {
      TypeRef<?> declared = descriptor != null ? descriptor.getTypeRef() : null;
      TypeRef<?> typeRef = fieldType.toTypeToken(resolver, declared);
      if (descriptor != null) {
        if (typeRef.equals(declared)) {
          return descriptor;
        } else {
          descriptor.copyWithTypeName(typeRef.getType().getTypeName());
        }
      }
      // This field doesn't exist in peer class, so any legal modifier will be OK.
      int stubModifiers = ReflectionUtils.getField(getClass(), "fieldName").getModifiers();
      return new Descriptor(typeRef, fieldName, stubModifiers, definedClass);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FieldInfo fieldInfo = (FieldInfo) o;
      return Objects.equals(definedClass, fieldInfo.definedClass)
          && Objects.equals(fieldName, fieldInfo.fieldName)
          && Objects.equals(fieldType, fieldInfo.fieldType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(definedClass, fieldName, fieldType);
    }

    @Override
    public String toString() {
      return "FieldInfo{"
          + "definedClass='"
          + definedClass
          + '\''
          + ", fieldName='"
          + fieldName
          + '\''
          + ", fieldType="
          + fieldType
          + '}';
    }
  }

  public abstract static class FieldType implements Serializable {
    protected final int xtypeId;
    protected final boolean isMonomorphic;
    protected final boolean nullable;
    protected final boolean trackingRef;

    public FieldType(int xtypeId, boolean isMonomorphic, boolean nullable, boolean trackingRef) {
      this.isMonomorphic = isMonomorphic;
      this.trackingRef = trackingRef;
      this.nullable = nullable;
      this.xtypeId = xtypeId;
    }

    public boolean isMonomorphic() {
      return isMonomorphic;
    }

    public boolean trackingRef() {
      return trackingRef;
    }

    public boolean nullable() {
      return nullable;
    }

    /**
     * Convert a serializable field type to type token. If field type is a generic type with
     * generics, the generics will be built up recursively. The final leaf object type will be built
     * from class id or class stub.
     *
     * @see FinalObjectTypeStub
     */
    public abstract TypeRef<?> toTypeToken(TypeResolver classResolver, TypeRef<?> declared);

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FieldType fieldType = (FieldType) o;
      return isMonomorphic == fieldType.isMonomorphic && trackingRef == fieldType.trackingRef;
    }

    @Override
    public int hashCode() {
      return Objects.hash(isMonomorphic, trackingRef);
    }

    /** Write field type info. */
    public void write(MemoryBuffer buffer, boolean writeHeader) {
      byte header = (byte) ((isMonomorphic ? 1 : 0) << 1);
      // header of nested generic fields in collection/map will be written independently
      header |= (byte) (trackingRef ? 1 : 0);
      if (this instanceof RegisteredFieldType) {
        short classId = ((RegisteredFieldType) this).getClassId();
        buffer.writeVarUint32Small7(writeHeader ? ((5 + classId) << 2) | header : 5 + classId);
      } else if (this instanceof EnumFieldType) {
        buffer.writeVarUint32Small7(writeHeader ? ((4) << 2) | header : 4);
      } else if (this instanceof ArrayFieldType) {
        ArrayFieldType arrayFieldType = (ArrayFieldType) this;
        buffer.writeVarUint32Small7(writeHeader ? ((3) << 2) | header : 3);
        buffer.writeVarUint32Small7(arrayFieldType.getDimensions());
        (arrayFieldType).getComponentType().write(buffer);
      } else if (this instanceof CollectionFieldType) {
        buffer.writeVarUint32Small7(writeHeader ? ((2) << 2) | header : 2);
        // TODO remove it when new collection deserialization jit finished.
        ((CollectionFieldType) this).getElementType().write(buffer);
      } else if (this instanceof MapFieldType) {
        buffer.writeVarUint32Small7(writeHeader ? ((1) << 2) | header : 1);
        // TODO remove it when new map deserialization jit finished.
        MapFieldType mapFieldType = (MapFieldType) this;
        mapFieldType.getKeyType().write(buffer);
        mapFieldType.getValueType().write(buffer);
      } else {
        Preconditions.checkArgument(this instanceof ObjectFieldType);
        buffer.writeVarUint32Small7(writeHeader ? header : 0);
      }
    }

    public void write(MemoryBuffer buffer) {
      write(buffer, true);
    }

    public static FieldType read(MemoryBuffer buffer, TypeResolver resolver) {
      int header = buffer.readVarUint32Small7();
      boolean isMonomorphic = (header & 0b10) != 0;
      boolean trackingRef = (header & 0b1) != 0;
      return read(buffer, resolver, isMonomorphic, trackingRef, header >>> 2);
    }

    /** Read field type info. */
    public static FieldType read(
        MemoryBuffer buffer,
        TypeResolver resolver,
        boolean isFinal,
        boolean trackingRef,
        int typeId) {
      if (typeId == 0) {
        return new ObjectFieldType(-1, isFinal, true, trackingRef);
      } else if (typeId == 1) {
        return new MapFieldType(
            -1, isFinal, true, trackingRef, read(buffer, resolver), read(buffer, resolver));
      } else if (typeId == 2) {
        return new CollectionFieldType(-1, isFinal, true, trackingRef, read(buffer, resolver));
      } else if (typeId == 3) {
        int dims = buffer.readVarUint32Small7();
        return new ArrayFieldType(isFinal, trackingRef, read(buffer, resolver), dims);
      } else if (typeId == 4) {
        return new EnumFieldType(true, -1);
      } else {
        boolean nullable = ((ClassResolver) resolver).isPrimitive((short) typeId);
        return new RegisteredFieldType(isFinal, nullable, trackingRef, (typeId - 5));
      }
    }

    public final void xwrite(MemoryBuffer buffer, boolean writeFlags) {
      int xtypeId = this.xtypeId;
      if (writeFlags) {
        xtypeId = (xtypeId << 2);
        if (nullable) {
          xtypeId |= 0b10;
        }
        if (trackingRef) {
          xtypeId |= 0b1;
        }
      }
      buffer.writeVarUint32Small7(xtypeId);
      switch (xtypeId) {
        case Types.LIST:
          ((CollectionFieldType) this).getElementType().xwrite(buffer, true);
          break;
        case Types.MAP:
          MapFieldType mapFieldType = (MapFieldType) this;
          mapFieldType.getKeyType().xwrite(buffer, true);
          mapFieldType.getValueType().xwrite(buffer, true);
          break;
        default:
          {
          }
      }
    }

    public static FieldType xread(MemoryBuffer buffer, XtypeResolver resolver) {
      int xtypeId = buffer.readVarUint32Small7();
      boolean trackingRef = (xtypeId & 0b1) != 0;
      boolean nullable = (xtypeId & 0b10) != 0;
      xtypeId = xtypeId >>> 2;
      return xread(buffer, resolver, xtypeId, nullable, trackingRef);
    }

    public static FieldType xread(
        MemoryBuffer buffer,
        XtypeResolver resolver,
        int xtypeId,
        boolean nullable,
        boolean trackingRef) {
      switch (xtypeId & 0xff) {
        case Types.LIST:
        case Types.SET:
          return new CollectionFieldType(
              xtypeId, true, nullable, trackingRef, xread(buffer, resolver));
        case Types.MAP:
          return new MapFieldType(
              xtypeId,
              true,
              nullable,
              trackingRef,
              xread(buffer, resolver),
              xread(buffer, resolver));
        case Types.ENUM:
        case Types.NAMED_ENUM:
          return new EnumFieldType(nullable, xtypeId);
        case Types.UNKNOWN:
          return new ObjectFieldType(xtypeId, false, nullable, trackingRef);
        default:
          {
            if (!Types.isUserDefinedType((byte) xtypeId)) {
              ClassInfo classInfo = resolver.getXtypeInfo(xtypeId);
              Preconditions.checkNotNull(classInfo);
              Class<?> cls = classInfo.getCls();
              return new RegisteredFieldType(
                  resolver.isMonomorphic(cls), nullable, trackingRef, xtypeId);
            } else {
              return new ObjectFieldType(xtypeId, false, nullable, trackingRef);
            }
          }
      }
    }
  }

  /** Class for field type which is registered. */
  public static class RegisteredFieldType extends FieldType {
    private final short classId;

    public RegisteredFieldType(
        boolean isFinal, boolean nullable, boolean trackingRef, int classId) {
      super(classId, isFinal, nullable, trackingRef);
      this.classId = (short) classId;
    }

    public short getClassId() {
      return classId;
    }

    @Override
    public TypeRef<?> toTypeToken(TypeResolver resolver, TypeRef<?> declared) {
      Class<?> cls;
      if (resolver instanceof XtypeResolver) {
        cls = ((XtypeResolver) resolver).getXtypeInfo(classId).getCls();
        if (Types.isPrimitiveType(classId)) {
          // For primitive types, ensure we use the correct primitive/boxed form
          // based on the nullable flag, not the declared type
          if (!nullable) {
            // nullable=false means the source was primitive, use primitive type
            cls = TypeUtils.unwrap(cls);
          } else {
            // nullable=true means the source was boxed, use boxed type
            cls = TypeUtils.wrap(cls);
          }
        }
      } else {
        cls = ((ClassResolver) resolver).getRegisteredClass(classId);
      }
      if (cls == null) {
        LOG.warn("Class {} not registered, take it as Struct type for deserialization.", classId);
        cls = NonexistentClass.NonexistentMetaShared.class;
      }
      return TypeRef.of(cls, new TypeExtMeta(nullable, trackingRef));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      if (!super.equals(o)) {
        return false;
      }
      RegisteredFieldType that = (RegisteredFieldType) o;
      return classId == that.classId;
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), classId);
    }

    @Override
    public String toString() {
      return "RegisteredFieldType{"
          + "isMonomorphic="
          + isMonomorphic()
          + ", trackingRef="
          + trackingRef()
          + ", classId="
          + classId
          + '}';
    }
  }

  /**
   * Class for collection field type, which store collection element type information. Nested
   * collection/map generics example:
   *
   * <pre>{@code
   * new TypeToken<Collection<Map<String, String>>>() {}
   * }</pre>
   */
  public static class CollectionFieldType extends FieldType {
    private final FieldType elementType;

    public CollectionFieldType(
        int xtypeId,
        boolean isFinal,
        boolean nullable,
        boolean trackingRef,
        FieldType elementType) {
      super(xtypeId, isFinal, nullable, trackingRef);
      this.elementType = elementType;
    }

    public FieldType getElementType() {
      return elementType;
    }

    @Override
    public TypeRef<?> toTypeToken(TypeResolver classResolver, TypeRef<?> declared) {
      // TODO support preserve element TypeExtMeta
      TypeRef<? extends Collection<?>> collectionTypeRef =
          collectionOf(
              elementType.toTypeToken(classResolver, declared),
              new TypeExtMeta(nullable, trackingRef));
      if (declared == null) {
        return collectionTypeRef;
      }
      Class<?> declaredClass = declared.getRawType();
      if (!declaredClass.isArray()) {
        return collectionTypeRef;
      }
      Tuple2<Class<?>, Integer> info = TypeUtils.getArrayComponentInfo(declaredClass);
      List<TypeRef<?>> typeRefs = new ArrayList<>(info.f1 + 1);
      typeRefs.add(collectionTypeRef);
      for (int i = 0; i < info.f1; i++) {
        typeRefs.add(TypeUtils.getElementType(typeRefs.get(i)));
      }
      Collections.reverse(typeRefs);
      for (int i = 1; i < typeRefs.size(); i++) {
        TypeRef<?> arrayType = typeRefs.get(i - 1);
        TypeRef<?> typeRef =
            TypeRef.of(
                Array.newInstance(arrayType.getRawType(), 1).getClass(),
                typeRefs.get(i).getExtInfo());
        typeRefs.set(i, typeRef);
      }
      return typeRefs.get(typeRefs.size() - 1);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      if (!super.equals(o)) {
        return false;
      }
      CollectionFieldType that = (CollectionFieldType) o;
      return Objects.equals(elementType, that.elementType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), elementType);
    }

    @Override
    public String toString() {
      return "CollectionFieldType{"
          + "elementType="
          + elementType
          + ", isFinal="
          + isMonomorphic()
          + ", trackingRef="
          + trackingRef()
          + '}';
    }
  }

  /**
   * Class for map field type, which store map key/value type information. Nested map generics
   * example:
   *
   * <pre>{@code
   * new TypeToken<Map<List<String>>, String>() {}
   * }</pre>
   */
  public static class MapFieldType extends FieldType {
    private final FieldType keyType;
    private final FieldType valueType;

    public MapFieldType(
        int xtypeId,
        boolean isFinal,
        boolean nullable,
        boolean trackingRef,
        FieldType keyType,
        FieldType valueType) {
      super(xtypeId, isFinal, nullable, trackingRef);
      this.keyType = keyType;
      this.valueType = valueType;
    }

    public FieldType getKeyType() {
      return keyType;
    }

    public FieldType getValueType() {
      return valueType;
    }

    @Override
    public TypeRef<?> toTypeToken(TypeResolver classResolver, TypeRef<?> declared) {
      // TODO support preserve element TypeExtMeta, it will be lost when building other TypeRef
      return mapOf(
          keyType.toTypeToken(classResolver, declared),
          valueType.toTypeToken(classResolver, declared),
          new TypeExtMeta(nullable, trackingRef));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      if (!super.equals(o)) {
        return false;
      }
      MapFieldType that = (MapFieldType) o;
      return Objects.equals(keyType, that.keyType) && Objects.equals(valueType, that.valueType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), keyType, valueType);
    }

    @Override
    public String toString() {
      return "MapFieldType{"
          + "keyType="
          + keyType
          + ", valueType="
          + valueType
          + ", isFinal="
          + isMonomorphic()
          + ", trackingRef="
          + trackingRef()
          + '}';
    }
  }

  public static class EnumFieldType extends FieldType {
    private EnumFieldType(boolean nullable, int xtypeId) {
      super(xtypeId, true, nullable, false);
    }

    @Override
    public TypeRef<?> toTypeToken(TypeResolver classResolver, TypeRef<?> declared) {
      return TypeRef.of(NonexistentClass.NonexistentEnum.class);
    }
  }

  public static class ArrayFieldType extends FieldType {
    private final FieldType componentType;
    private final int dimensions;

    public ArrayFieldType(
        boolean isMonomorphic, boolean trackingRef, FieldType componentType, int dimensions) {
      this(-1, isMonomorphic, true, trackingRef, componentType, dimensions);
    }

    public ArrayFieldType(
        int xtypeId,
        boolean isMonomorphic,
        boolean nullable,
        boolean trackingRef,
        FieldType componentType,
        int dimensions) {
      super(xtypeId, isMonomorphic, nullable, trackingRef);
      this.componentType = componentType;
      this.dimensions = dimensions;
    }

    @Override
    public TypeRef<?> toTypeToken(TypeResolver classResolver, TypeRef<?> declared) {
      while (declared != null && declared.isArray()) {
        declared = declared.getComponentType();
      }
      TypeRef<?> componentTypeRef = componentType.toTypeToken(classResolver, declared);
      Class<?> componentRawType = componentTypeRef.getRawType();
      if (NonexistentClass.class.isAssignableFrom(componentRawType)) {
        return TypeRef.of(
            // We embed `isMonomorphic` flag in ObjectArraySerializer, so this flag can be ignored
            // here.
            NonexistentClass.getNonexistentClass(
                componentType instanceof EnumFieldType, dimensions, true),
            new TypeExtMeta(nullable, trackingRef));
      } else {
        return TypeRef.of(
            Array.newInstance(componentRawType, new int[dimensions]).getClass(),
            new TypeExtMeta(nullable, trackingRef));
      }
    }

    public int getDimensions() {
      return dimensions;
    }

    public FieldType getComponentType() {
      return componentType;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      if (!super.equals(o)) {
        return false;
      }
      ArrayFieldType that = (ArrayFieldType) o;
      return dimensions == that.dimensions && Objects.equals(componentType, that.componentType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), componentType, dimensions);
    }

    @Override
    public String toString() {
      return "ArrayFieldType{"
          + "componentType="
          + componentType
          + ", dimensions="
          + dimensions
          + ", isMonomorphic="
          + isMonomorphic
          + ", trackingRef="
          + trackingRef
          + '}';
    }
  }

  /** Class for field type which isn't registered and not collection/map type too. */
  public static class ObjectFieldType extends FieldType {

    public ObjectFieldType(int xtypeId, boolean isFinal, boolean nullable, boolean trackingRef) {
      super(xtypeId, isFinal, nullable, trackingRef);
    }

    @Override
    public TypeRef<?> toTypeToken(TypeResolver classResolver, TypeRef<?> declared) {
      return isMonomorphic()
          ? TypeRef.of(FinalObjectTypeStub.class, new TypeExtMeta(nullable, trackingRef))
          : TypeRef.of(Object.class, new TypeExtMeta(nullable, trackingRef));
    }

    @Override
    public boolean equals(Object o) {
      return super.equals(o);
    }

    @Override
    public int hashCode() {
      return super.hashCode();
    }
  }

  /** Build field type from generics, nested generics will be extracted too. */
  static FieldType buildFieldType(TypeResolver resolver, Field field) {
    Preconditions.checkNotNull(field);
    GenericType genericType = resolver.buildGenericType(field.getGenericType());
    return buildFieldType(resolver, genericType);
  }

  /** Build field type from generics, nested generics will be extracted too. */
  private static FieldType buildFieldType(TypeResolver resolver, GenericType genericType) {
    Preconditions.checkNotNull(genericType);
    Class<?> rawType = genericType.getCls();
    boolean isXlang = resolver.getFory().isCrossLanguage();
    int xtypeId = -1;
    if (isXlang) {
      ClassInfo info = resolver.getClassInfo(genericType.getCls(), false);
      if (info != null) {
        xtypeId = info.getXtypeId();
      } else {
        xtypeId = Types.UNKNOWN;
      }
    }
    boolean isMonomorphic = genericType.isMonomorphic();
    boolean trackingRef = genericType.trackingRef(resolver);
    // TODO support @Nullable/ForyField annotation
    boolean nullable = !genericType.getCls().isPrimitive();
    if (COLLECTION_TYPE.isSupertypeOf(genericType.getTypeRef())) {
      return new CollectionFieldType(
          xtypeId,
          isMonomorphic,
          nullable,
          trackingRef,
          buildFieldType(
              resolver,
              genericType.getTypeParameter0() == null
                  ? GenericType.build(Object.class)
                  : genericType.getTypeParameter0()));
    } else if (MAP_TYPE.isSupertypeOf(genericType.getTypeRef())) {
      return new MapFieldType(
          xtypeId,
          isMonomorphic,
          nullable,
          trackingRef,
          buildFieldType(
              resolver,
              genericType.getTypeParameter0() == null
                  ? GenericType.build(Object.class)
                  : genericType.getTypeParameter0()),
          buildFieldType(
              resolver,
              genericType.getTypeParameter1() == null
                  ? GenericType.build(Object.class)
                  : genericType.getTypeParameter1()));
    } else {
      if (isXlang
          && !Types.isUserDefinedType((byte) xtypeId)
          && resolver.isRegisteredById(rawType)) {
        return new RegisteredFieldType(isMonomorphic, nullable, trackingRef, xtypeId);
      } else if (!isXlang && resolver.isRegisteredById(rawType)) {
        Short classId = ((ClassResolver) resolver).getRegisteredClassId(rawType);
        return new RegisteredFieldType(isMonomorphic, nullable, trackingRef, classId);
      } else {
        if (rawType.isEnum()) {
          return new EnumFieldType(nullable, xtypeId);
        }
        if (rawType.isArray()) {
          Class<?> elemType = rawType.getComponentType();
          while (elemType.isArray()) {
            elemType = elemType.getComponentType();
          }
          if (isXlang && !elemType.isPrimitive()) {
            return new CollectionFieldType(
                xtypeId,
                isMonomorphic,
                nullable,
                trackingRef,
                buildFieldType(resolver, GenericType.build(elemType)));
          }
          Tuple2<Class<?>, Integer> info = TypeUtils.getArrayComponentInfo(rawType);
          return new ArrayFieldType(
              xtypeId,
              isMonomorphic,
              nullable,
              trackingRef,
              buildFieldType(resolver, GenericType.build(info.f0)),
              info.f1);
        }
        return new ObjectFieldType(xtypeId, isMonomorphic, nullable, trackingRef);
      }
    }
  }

  public static ClassDef buildClassDef(Fory fory, Class<?> cls) {
    return buildClassDef(fory, cls, true);
  }

  public static ClassDef buildClassDef(Fory fory, Class<?> cls, boolean resolveParent) {
    if (fory.isCrossLanguage()) {
      return TypeDefEncoder.buildTypeDef(fory, cls);
    }
    return ClassDefEncoder.buildClassDef(
        fory.getClassResolver(), cls, buildFields(fory, cls, resolveParent), true);
  }

  /** Build class definition from fields of class. */
  static ClassDef buildClassDef(ClassResolver classResolver, Class<?> type, List<Field> fields) {
    return buildClassDef(classResolver, type, fields, true);
  }

  public static ClassDef buildClassDef(
      ClassResolver classResolver, Class<?> type, List<Field> fields, boolean hasFieldsMeta) {
    return ClassDefEncoder.buildClassDef(classResolver, type, fields, hasFieldsMeta);
  }

  public ClassDef replaceRootClassTo(ClassResolver classResolver, Class<?> targetCls) {
    String name = targetCls.getName();
    List<FieldInfo> fieldInfos =
        fieldsInfo.stream()
            .map(
                fieldInfo -> {
                  if (fieldInfo.definedClass.equals(classSpec.entireClassName)) {
                    return new FieldInfo(name, fieldInfo.fieldName, fieldInfo.fieldType);
                  } else {
                    return fieldInfo;
                  }
                })
            .collect(Collectors.toList());
    return ClassDefEncoder.buildClassDefWithFieldInfos(
        classResolver, targetCls, fieldInfos, hasFieldsMeta);
  }
}
