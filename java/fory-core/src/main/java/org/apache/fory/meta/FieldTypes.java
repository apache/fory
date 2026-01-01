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

import static org.apache.fory.type.TypeUtils.COLLECTION_TYPE;
import static org.apache.fory.type.TypeUtils.MAP_TYPE;
import static org.apache.fory.type.TypeUtils.collectionOf;
import static org.apache.fory.type.TypeUtils.getArrayComponentInfo;
import static org.apache.fory.type.TypeUtils.getArrayDimensions;
import static org.apache.fory.type.TypeUtils.isOptionalType;
import static org.apache.fory.type.TypeUtils.mapOf;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.resolver.XtypeResolver;
import org.apache.fory.serializer.NonexistentClass;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.FinalObjectTypeStub;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.Preconditions;

public class FieldTypes {
  private static final Logger LOG = LoggerFactory.getLogger(FieldTypes.class);

  /** Returns true if can use current field type. */
  static boolean useFieldType(Class<?> parsedType, Descriptor descriptor) {
    if (parsedType.isEnum()
        || parsedType.isAssignableFrom(descriptor.getRawType())
        || parsedType == FinalObjectTypeStub.class) {
      return true;
    }
    if (parsedType.isArray()) {
      Tuple2<Class<?>, Integer> info = getArrayComponentInfo(parsedType);
      Field field = descriptor.getField();
      if (!field.getType().isArray() || getArrayDimensions(field.getType()) != info.f1) {
        return false;
      }
      return info.f0 == FinalObjectTypeStub.class || info.f0.isEnum();
    }
    return false;
  }

  /** Build field type from generics, nested generics will be extracted too. */
  static FieldType buildFieldType(TypeResolver resolver, Field field) {
    Preconditions.checkNotNull(field);
    GenericType genericType = resolver.buildGenericType(field.getGenericType());
    return buildFieldType(resolver, field, genericType);
  }

  /** Build field type from generics, nested generics will be extracted too. */
  private static FieldType buildFieldType(
      TypeResolver resolver, Field field, GenericType genericType) {
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
    // For xlang: ref tracking is false by default (no shared ownership like Rust's Rc/Arc)
    // For native: use the type's default tracking behavior
    boolean trackingRef = isXlang ? false : genericType.trackingRef(resolver);
    // For xlang: nullable is false by default (aligned with all languages)
    // Exception: Optional types are nullable (like Rust's Option<T>)
    // For native: non-primitive types are nullable by default
    boolean nullable;
    if (isXlang) {
      // Only Optional types and boxed types are nullable by default in xlang mode
      nullable = isOptionalType(rawType) || TypeUtils.isBoxed(rawType);
    } else {
      // For nested types (field=null), nullable defaults to true to match decoding behavior
      // since the encoding doesn't persist nullable for nested types (see FieldType.read())
      nullable = field == null || !genericType.getCls().isPrimitive();
    }

    // Apply @ForyField annotation if present
    if (field != null) {
      ForyField foryField = field.getAnnotation(ForyField.class);
      if (foryField != null) {
        nullable = foryField.nullable();
        trackingRef = foryField.ref();
      }
    }

    if (COLLECTION_TYPE.isSupertypeOf(genericType.getTypeRef())) {
      return new CollectionFieldType(
          xtypeId,
          isMonomorphic,
          nullable,
          trackingRef,
          buildFieldType(
              resolver,
              null, // nested fields don't have Field reference
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
              null, // nested fields don't have Field reference
              genericType.getTypeParameter0() == null
                  ? GenericType.build(Object.class)
                  : genericType.getTypeParameter0()),
          buildFieldType(
              resolver,
              null, // nested fields don't have Field reference
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
                buildFieldType(resolver, null, GenericType.build(elemType)));
          }
          Tuple2<Class<?>, Integer> info = getArrayComponentInfo(rawType);
          return new ArrayFieldType(
              xtypeId,
              isMonomorphic,
              nullable,
              trackingRef,
              buildFieldType(resolver, null, GenericType.build(info.f0)),
              info.f1);
        }
        return new ObjectFieldType(xtypeId, isMonomorphic, nullable, trackingRef);
      }
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
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FieldType fieldType = (FieldType) o;
      return isMonomorphic == fieldType.isMonomorphic
          && trackingRef == fieldType.trackingRef
          && nullable == fieldType.nullable;
    }

    @Override
    public int hashCode() {
      return Objects.hash(isMonomorphic, nullable, trackingRef);
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
      // For nested types (in collections/maps), nullable defaults to true
      return read(buffer, resolver, isMonomorphic, true, trackingRef, header >>> 2);
    }

    /** Read field type info. */
    public static FieldType read(
        MemoryBuffer buffer,
        TypeResolver resolver,
        boolean isFinal,
        boolean nullable,
        boolean trackingRef,
        int typeId) {
      if (typeId == 0) {
        return new ObjectFieldType(-1, isFinal, nullable, trackingRef);
      } else if (typeId == 1) {
        return new MapFieldType(
            -1, isFinal, nullable, trackingRef, read(buffer, resolver), read(buffer, resolver));
      } else if (typeId == 2) {
        return new CollectionFieldType(-1, isFinal, nullable, trackingRef, read(buffer, resolver));
      } else if (typeId == 3) {
        int dims = buffer.readVarUint32Small7();
        return new ArrayFieldType(-1, isFinal, nullable, trackingRef, read(buffer, resolver), dims);
      } else if (typeId == 4) {
        return new EnumFieldType(nullable, -1);
      } else {
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
      // Use the original xtypeId for the switch (not the one with flags)
      switch (this.xtypeId & 0xff) {
        case Types.LIST:
        case Types.SET:
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
        case Types.UNION:
          return new UnionFieldType(nullable, trackingRef);
        case Types.UNKNOWN:
          return new ObjectFieldType(xtypeId, false, nullable, trackingRef);
        default:
          {
            if (!Types.isUserDefinedType((byte) xtypeId)) {
              ClassInfo classInfo = resolver.getXtypeInfo(xtypeId);
              if (classInfo == null) {
                // Type not registered locally - this can happen in compatible mode
                // when remote sends a type ID that's not registered here.
                // Fall back to ObjectFieldType to handle gracefully.
                LOG.warn("Type {} not registered locally, treating as ObjectFieldType", xtypeId);
                return new ObjectFieldType(xtypeId, false, nullable, trackingRef);
              }
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
          if (declared == null) {
            // For primitive types, ensure we use the correct primitive/boxed form
            // based on the nullable flag, not the declared type
            if (!nullable) {
              // nullable=false means the source was primitive, use primitive type
              cls = TypeUtils.unwrap(cls);
            } else {
              // nullable=true means the source was boxed, use boxed type
              cls = TypeUtils.wrap(cls);
            }
          } else {
            if (TypeUtils.unwrap(declared.getRawType()) == TypeUtils.unwrap(cls)) {
              // we still need correct type, the `read/write` should use `nullable` of `Descriptor`
              // for serialization
              return declared;
            }
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
          + ", nullable="
          + nullable()
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
      Class<?> declaredClass;
      TypeRef<?> declElementType;
      if (declared == null) {
        declaredClass = null;
        declElementType = null;
      } else {
        declaredClass = declared.getRawType();
        if (declaredClass.isArray()) {
          declElementType = TypeRef.of(declaredClass.getComponentType());
        } else {
          declElementType = TypeUtils.getElementType(declared);
        }
        if (declElementType.hasWildcard()) {
          // handle generic bound
          declElementType = declElementType.resolveAllWildcards();
        }
      }
      TypeRef<?> elementType = this.elementType.toTypeToken(classResolver, declElementType);
      if (declared == null) {
        return collectionOf(elementType, new TypeExtMeta(nullable, trackingRef));
      }
      TypeRef<? extends Collection<?>> collectionTypeRef =
          collectionOf(declaredClass, elementType, new TypeExtMeta(nullable, trackingRef));
      if (!declaredClass.isArray()) {
        if (declElementType.equals(elementType)) {
          return declared;
        }
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
          + ", nullable="
          + nullable()
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
      TypeRef<?> keyDecl = null;
      TypeRef<?> valueDecl = null;
      if (declared != null) {
        Tuple2<TypeRef<?>, TypeRef<?>> mapKeyValueType = TypeUtils.getMapKeyValueType(declared);
        keyDecl = mapKeyValueType.f0;
        valueDecl = mapKeyValueType.f1;
        if (keyDecl.hasWildcard()) {
          // handle generic bound
          keyDecl = keyDecl.resolveAllWildcards();
        }
        if (valueDecl.hasWildcard()) {
          // handle generic bound
          valueDecl = keyDecl.resolveAllWildcards();
        }
        return mapOf(
            declared.getRawType(),
            keyType.toTypeToken(classResolver, keyDecl),
            valueType.toTypeToken(classResolver, valueDecl),
            new TypeExtMeta(nullable, trackingRef));
      }
      return mapOf(
          keyType.toTypeToken(classResolver, keyDecl),
          valueType.toTypeToken(classResolver, valueDecl),
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
          + ", nullable="
          + nullable()
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
      if (declared != null && declared.getRawType().isEnum()) {
        return declared;
      }
      return TypeRef.of(NonexistentClass.NonexistentEnum.class);
    }

    @Override
    public String toString() {
      return "EnumFieldType{" + "xtypeId=" + xtypeId + ", nullable=" + nullable + '}';
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
          + ", nullable="
          + nullable
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

    @Override
    public String toString() {
      return "ObjectFieldType{"
          + "xtypeId="
          + xtypeId
          + ", isMonomorphic="
          + isMonomorphic
          + ", nullable="
          + nullable
          + ", trackingRef="
          + trackingRef
          + '}';
    }
  }

  /** Class for Union field type. Union types are always monomorphic and use declared type. */
  public static class UnionFieldType extends FieldType {

    public UnionFieldType(boolean nullable, boolean trackingRef) {
      super(Types.UNION, true, nullable, trackingRef);
    }

    @Override
    public TypeRef<?> toTypeToken(TypeResolver classResolver, TypeRef<?> declared) {
      // Union types use the declared field type directly
      if (declared != null) {
        return declared;
      }
      // Fallback to base Union class if no declared type
      return TypeRef.of(
          org.apache.fory.type.union.Union.class, new TypeExtMeta(nullable, trackingRef));
    }

    @Override
    public boolean equals(Object o) {
      return super.equals(o);
    }

    @Override
    public int hashCode() {
      return super.hashCode();
    }

    @Override
    public String toString() {
      return "UnionFieldType{" + "nullable=" + nullable + ", trackingRef=" + trackingRef + '}';
    }
  }
}
