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
import java.util.Objects;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.collection.BoolList;
import org.apache.fory.collection.Float32List;
import org.apache.fory.collection.Float64List;
import org.apache.fory.collection.Int16List;
import org.apache.fory.collection.Int32List;
import org.apache.fory.collection.Int64List;
import org.apache.fory.collection.Int8List;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.collection.Uint16List;
import org.apache.fory.collection.Uint32List;
import org.apache.fory.collection.Uint64List;
import org.apache.fory.collection.Uint8List;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.resolver.XtypeResolver;
import org.apache.fory.serializer.UnknownClass;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.type.union.Union;
import org.apache.fory.util.Preconditions;

public class FieldTypes {
  private static final Logger LOG = LoggerFactory.getLogger(FieldTypes.class);

  /** Returns true if can use current field type. */
  static boolean useFieldType(Class<?> parsedType, Descriptor descriptor) {
    if (parsedType.isEnum() || parsedType.isAssignableFrom(descriptor.getRawType())) {
      return true;
    }
    if (parsedType.isArray()) {
      Tuple2<Class<?>, Integer> info = getArrayComponentInfo(parsedType);
      Field field = descriptor.getField();
      if (!field.getType().isArray() || getArrayDimensions(field.getType()) != info.f1) {
        return false;
      }
      return info.f0.isEnum();
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
    // Get type ID for both xlang and native mode
    // This supports unsigned types and field-configurable compression in both modes
    int typeId;
    if (TypeUtils.unwrap(rawType).isPrimitive()) {
      if (field != null) {
        typeId = Types.getDescriptorTypeId(resolver.getFory(), field);
      } else {
        typeId = Types.getTypeId(resolver.getFory(), rawType);
      }
    } else if (rawType.isArray() && rawType.getComponentType().isPrimitive() && field != null) {
      // For primitive arrays with type annotations, use getDescriptorTypeId to parse annotation
      // This allows @Uint8ArrayType etc. to override the default INT8_ARRAY type
      typeId = Types.getDescriptorTypeId(resolver.getFory(), field);
    } else {
      TypeInfo info =
          isXlang && rawType == Object.class ? null : resolver.getTypeInfo(rawType, false);
      if (info != null) {
        typeId = info.getTypeId();
        if (Types.isEnumType(typeId)) {
          typeId = Types.ENUM;
        }
      } else if (isXlang) {
        if (rawType.isArray()) {
          Class<?> componentType = rawType.getComponentType();
          if (componentType.isPrimitive()) {
            int elemTypeId = Types.getTypeId(resolver.getFory(), componentType);
            typeId = Types.getPrimitiveArrayTypeId(elemTypeId);
          } else {
            typeId = Types.LIST;
          }
        } else if (rawType.isEnum()) {
          typeId = Types.ENUM;
        } else if (resolver.isSet(rawType)) {
          typeId = Types.SET;
        } else if (resolver.isCollection(rawType)) {
          typeId = Types.LIST;
        } else if (resolver.isMap(rawType)) {
          typeId = Types.MAP;
        } else {
          typeId = Types.UNKNOWN;
        }
      } else if (resolver instanceof ClassResolver) {
        typeId = ((ClassResolver) resolver).getTypeIdForTypeDef(rawType);
      } else {
        typeId = Types.UNKNOWN;
      }
    }
    // For xlang: ref tracking is false by default (no shared ownership like Rust's Rc/Arc)
    // For native: use the type's default tracking behavior
    boolean trackingRef = !isXlang && genericType.trackingRef(resolver);
    // For xlang: nullable is false by default for top-level fields.
    // Nested element types are nullable by default to align with cross-language collection
    // semantics.
    // Optional types are nullable (like Rust's Option<T>).
    // For native: non-primitive types are nullable by default.
    boolean nullable;
    if (isXlang) {
      boolean nestedType = field == null;
      nullable = nestedType || isOptionalType(rawType);
    } else {
      // Primitives are never nullable, non-primitives are nullable by default
      // This applies to both top-level fields and nested types (in arrays, collections, maps)
      nullable = !genericType.getCls().isPrimitive();
    }

    // Apply @ForyField annotation if present
    if (field != null) {
      ForyField foryField = field.getAnnotation(ForyField.class);
      if (foryField != null) {
        nullable = foryField.nullable();
        trackingRef = foryField.ref();
      }
    }

    boolean isUnionType = Types.isUnionType(typeId);
    if (isUnionType) {
      typeId = Types.UNION;
    }

    if (Types.isPrimitiveArray(typeId)) {
      return new RegisteredFieldType(nullable, trackingRef, typeId, -1);
    }

    if (COLLECTION_TYPE.isSupertypeOf(genericType.getTypeRef())) {
      return new CollectionFieldType(
          typeId,
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
          typeId,
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
    } else if (isUnionType || Union.class.isAssignableFrom(rawType)) {
      return new UnionFieldType(nullable, trackingRef);
    } else if (TypeUtils.unwrap(rawType).isPrimitive()) {
      // unified basic types for xlang and native mode
      return new RegisteredFieldType(nullable, trackingRef, typeId, -1);
    } else {
      if (rawType.isEnum()) {
        return new EnumFieldType(nullable, Types.ENUM, -1);
      }
      if (rawType.isArray()) {
        Class<?> elemType = rawType.getComponentType();
        if (isXlang) {
          if (elemType.isPrimitive()) {
            // For xlang mode, use the typeId we already computed above
            // which respects @Uint8ArrayType etc. annotations
            return new RegisteredFieldType(nullable, trackingRef, typeId, -1);
          }
          return new CollectionFieldType(
              typeId,
              nullable,
              trackingRef,
              buildFieldType(resolver, null, GenericType.build(elemType)));
        } else {
          // For native mode, use Java class IDs for arrays
          if (resolver.isRegisteredById(rawType)) {
            return new RegisteredFieldType(nullable, trackingRef, typeId, -1);
          }
          Tuple2<Class<?>, Integer> arrayComponentInfo = getArrayComponentInfo(rawType);
          return new ArrayFieldType(
              typeId,
              nullable,
              trackingRef,
              buildFieldType(resolver, null, GenericType.build(arrayComponentInfo.f0)),
              arrayComponentInfo.f1);
        }
      }
      if (resolver.isRegisteredById(rawType)) {
        return new RegisteredFieldType(nullable, trackingRef, typeId, -1);
      } else {
        return new ObjectFieldType(typeId, nullable, trackingRef);
      }
    }
  }

  public abstract static class FieldType implements Serializable {
    private static final int KIND_OBJECT = 0;
    private static final int KIND_MAP = 1;
    private static final int KIND_COLLECTION = 2;
    private static final int KIND_ARRAY = 3;
    private static final int KIND_ENUM = 4;
    private static final int KIND_REGISTERED = 5;

    protected final int typeId;
    protected final int userTypeId;
    protected final boolean nullable;
    protected final boolean trackingRef;

    public FieldType(int typeId, int userTypeId, boolean nullable, boolean trackingRef) {
      this.trackingRef = trackingRef;
      this.nullable = nullable;
      this.typeId = typeId;
      this.userTypeId = userTypeId;
    }

    public boolean trackingRef() {
      return trackingRef;
    }

    public boolean nullable() {
      return nullable;
    }

    private int typeKind() {
      if (this instanceof RegisteredFieldType) {
        return KIND_REGISTERED;
      }
      if (this instanceof EnumFieldType) {
        return KIND_ENUM;
      }
      if (this instanceof ArrayFieldType) {
        return KIND_ARRAY;
      }
      if (this instanceof CollectionFieldType) {
        return KIND_COLLECTION;
      }
      if (this instanceof MapFieldType) {
        return KIND_MAP;
      }
      return KIND_OBJECT;
    }

    /**
     * Convert a serializable field type to type token. If field type is a generic type with
     * generics, the generics will be built up recursively. The final leaf object type will be built
     * from class id or class stub.
     */
    public abstract TypeRef<?> toTypeToken(TypeResolver classResolver, TypeRef<?> declared);

    public String getTypeName(TypeResolver resolver, TypeRef<?> typeRef) {
      return typeRef.getType().getTypeName();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FieldType fieldType = (FieldType) o;
      return trackingRef == fieldType.trackingRef && nullable == fieldType.nullable;
    }

    @Override
    public int hashCode() {
      return Objects.hash(nullable, trackingRef);
    }

    /** Write field type info. */
    public void write(MemoryBuffer buffer, boolean writeHeader) {
      // Header format:
      // - bit 0: trackingRef
      // - bit 1: nullable
      // - bits 2+: type kind
      byte header = (byte) ((nullable ? 0b10 : 0) | (trackingRef ? 0b1 : 0));
      byte kindHeader = (byte) ((typeKind() << 2) | (writeHeader ? header : 0));
      if (this instanceof RegisteredFieldType) {
        int typeId = ((RegisteredFieldType) this).getTypeId();
        buffer.writeUint8(kindHeader);
        buffer.writeUint8(typeId);
      } else if (this instanceof EnumFieldType) {
        buffer.writeUint8(kindHeader);
      } else if (this instanceof ArrayFieldType) {
        ArrayFieldType arrayFieldType = (ArrayFieldType) this;
        buffer.writeUint8(kindHeader);
        buffer.writeVarUint32Small7(arrayFieldType.getDimensions());
        (arrayFieldType).getComponentType().write(buffer);
      } else if (this instanceof CollectionFieldType) {
        buffer.writeUint8(kindHeader);
        // TODO remove it when new collection deserialization jit finished.
        ((CollectionFieldType) this).getElementType().write(buffer);
      } else if (this instanceof MapFieldType) {
        buffer.writeUint8(kindHeader);
        // TODO remove it when new map deserialization jit finished.
        MapFieldType mapFieldType = (MapFieldType) this;
        mapFieldType.getKeyType().write(buffer);
        mapFieldType.getValueType().write(buffer);
      } else {
        Preconditions.checkArgument(this instanceof ObjectFieldType);
        buffer.writeUint8(kindHeader);
      }
    }

    public void write(MemoryBuffer buffer) {
      write(buffer, true);
    }

    public static FieldType read(MemoryBuffer buffer, TypeResolver resolver) {
      // Header format:
      // - bit 0: trackingRef
      // - bit 1: nullable
      // - bits 2+: type kind
      int header = buffer.readUint8();
      boolean trackingRef = (header & 0b1) != 0;
      boolean nullable = (header & 0b10) != 0;
      int kind = header >>> 2;
      return read(buffer, resolver, nullable, trackingRef, kind);
    }

    /** Read field type info. */
    public static FieldType read(
        MemoryBuffer buffer,
        TypeResolver resolver,
        boolean nullable,
        boolean trackingRef,
        int kind) {
      if (kind == 0) {
        return new ObjectFieldType(Types.UNKNOWN, nullable, trackingRef);
      } else if (kind == 1) {
        return new MapFieldType(
            -1, nullable, trackingRef, read(buffer, resolver), read(buffer, resolver));
      } else if (kind == 2) {
        return new CollectionFieldType(-1, nullable, trackingRef, read(buffer, resolver));
      } else if (kind == 3) {
        int dims = buffer.readVarUint32Small7();
        return new ArrayFieldType(-1, nullable, trackingRef, read(buffer, resolver), dims);
      } else if (kind == 4) {
        return new EnumFieldType(nullable, -1, -1);
      } else if (kind == 5) {
        int actualTypeId = buffer.readUint8();
        return new RegisteredFieldType(nullable, trackingRef, actualTypeId, -1);
      } else {
        throw new IllegalStateException("Unexpected field type kind: " + kind);
      }
    }

    public final void writeCrossLanguage(MemoryBuffer buffer, boolean writeFlags) {
      if (writeFlags) {
        int typeId = (this.typeId << 2);
        if (nullable) {
          typeId |= 0b10;
        }
        if (trackingRef) {
          typeId |= 0b1;
        }
        buffer.writeVarUint32Small7(typeId);
      } else {
        buffer.writeUint8(this.typeId);
      }
      // Use the original typeId for the switch (not the one with flags)
      switch (this.typeId) {
        case Types.LIST:
        case Types.SET:
          ((CollectionFieldType) this).getElementType().writeCrossLanguage(buffer, true);
          break;
        case Types.MAP:
          MapFieldType mapFieldType = (MapFieldType) this;
          mapFieldType.getKeyType().writeCrossLanguage(buffer, true);
          mapFieldType.getValueType().writeCrossLanguage(buffer, true);
          break;
        default:
          {
          }
      }
    }

    public static FieldType readCrossLanguage(MemoryBuffer buffer, XtypeResolver resolver) {
      int typeId = buffer.readVarUint32Small7();
      boolean trackingRef = (typeId & 0b1) != 0;
      boolean nullable = (typeId & 0b10) != 0;
      typeId = typeId >>> 2;
      return readCrossLanguage(buffer, resolver, typeId, nullable, trackingRef);
    }

    public static FieldType readCrossLanguage(
        MemoryBuffer buffer,
        XtypeResolver resolver,
        int typeId,
        boolean nullable,
        boolean trackingRef) {
      switch (typeId) {
        case Types.LIST:
        case Types.SET:
          return new CollectionFieldType(
              typeId, nullable, trackingRef, readCrossLanguage(buffer, resolver));
        case Types.MAP:
          return new MapFieldType(
              typeId,
              nullable,
              trackingRef,
              readCrossLanguage(buffer, resolver),
              readCrossLanguage(buffer, resolver));
        case Types.ENUM:
          return new EnumFieldType(nullable, typeId, -1);
        case Types.UNION:
          return new UnionFieldType(nullable, trackingRef);
        case Types.UNKNOWN:
          return new ObjectFieldType(typeId, nullable, trackingRef);
        default:
          {
            if (Types.isPrimitiveType(typeId)) {
              // unsigned types share same class with signed numeric types, so unsigned types are
              // not registered.
              return new RegisteredFieldType(nullable, trackingRef, typeId, -1);
            }
            if (!Types.isUserDefinedType((byte) typeId)) {
              TypeInfo typeInfo = resolver.getXtypeInfo(typeId);
              if (typeInfo == null) {
                // Type not registered locally - this can happen in compatible mode
                // when remote sends a type ID that's not registered here.
                // Fall back to ObjectFieldType to handle gracefully.
                LOG.warn("Type {} not registered locally, treating as ObjectFieldType", typeId);
                return new ObjectFieldType(typeId, nullable, trackingRef);
              }
              return new RegisteredFieldType(nullable, trackingRef, typeId, -1);
            } else {
              return new ObjectFieldType(typeId, nullable, trackingRef);
            }
          }
      }
    }
  }

  /** Class for field type which is registered. */
  public static class RegisteredFieldType extends FieldType {
    public RegisteredFieldType(boolean nullable, boolean trackingRef, int typeId, int userTypeId) {
      super(typeId, userTypeId, nullable, trackingRef);
      Preconditions.checkArgument(typeId > 0);
    }

    public int getTypeId() {
      return typeId;
    }

    @Override
    public TypeRef<?> toTypeToken(TypeResolver resolver, TypeRef<?> declared) {
      Class<?> cls;
      int internalTypeId = typeId;
      if (declared != null && internalTypeId == Types.ENUM && declared.getRawType().isEnum()) {
        return TypeRef.of(declared.getRawType(), new TypeExtMeta(typeId, nullable, trackingRef));
      }
      if (Types.isPrimitiveType(internalTypeId)) {
        if (declared != null) {
          TypeInfo declaredInfo = resolver.getTypeInfo(declared.getRawType(), false);
          if (declaredInfo != null && declaredInfo.getTypeId() == typeId) {
            return TypeRef.of(
                declared.getRawType(), new TypeExtMeta(typeId, nullable, trackingRef));
          }
        }
        cls = Types.getClassForTypeId(internalTypeId);
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
            cls = declared.getRawType();
          }
        }
        return TypeRef.of(cls, new TypeExtMeta(typeId, nullable, trackingRef));
      }
      if (Types.isPrimitiveArray(internalTypeId)) {
        if (declared != null) {
          Class<?> declaredRaw = declared.getRawType();
          if (declaredRaw.isArray()) {
            return TypeRef.of(declaredRaw, new TypeExtMeta(typeId, nullable, trackingRef));
          }
          Class<?> listClass = getPrimitiveListClass(internalTypeId);
          if (listClass != null && listClass.isAssignableFrom(declaredRaw)) {
            return TypeRef.of(declaredRaw, new TypeExtMeta(typeId, nullable, trackingRef));
          }
        }
        cls = getPrimitiveArrayClass(internalTypeId);
        if (cls != null) {
          return TypeRef.of(cls, new TypeExtMeta(typeId, nullable, trackingRef));
        }
      }
      if (Types.isUserDefinedType((byte) internalTypeId)) {
        if (declared != null) {
          return TypeRef.of(declared.getRawType(), new TypeExtMeta(typeId, nullable, trackingRef));
        }
        LOG.warn("Class {} not registered, take it as Struct type for deserialization.", typeId);
        boolean isEnum = internalTypeId == Types.ENUM;
        cls = UnknownClass.getUnknowClass(isEnum, 0, resolver.getFory().isShareMeta());
        return TypeRef.of(cls, new TypeExtMeta(typeId, nullable, trackingRef));
      }
      if (resolver instanceof XtypeResolver) {
        TypeInfo xtypeInfo = ((XtypeResolver) resolver).getXtypeInfo(typeId);
        Preconditions.checkNotNull(xtypeInfo);
        cls = xtypeInfo.getCls();
      } else {
        cls = ((ClassResolver) resolver).getRegisteredClassByTypeId(typeId);
      }
      if (cls == null) {
        LOG.warn("Class {} not registered, take it as Struct type for deserialization.", typeId);
        boolean isEnum = internalTypeId == Types.ENUM;
        cls = UnknownClass.getUnknowClass(isEnum, 0, resolver.getFory().isShareMeta());
      }
      return TypeRef.of(cls, new TypeExtMeta(typeId, nullable, trackingRef));
    }

    @Override
    public String getTypeName(TypeResolver resolver, TypeRef<?> typeRef) {
      // Some registered class may not be registered on peer class, we always use
      // registered id to keep consistent order.
      // Note that this is only used for fields sort in native mode.
      // For xlang mode, we always sort fields by type id in
      if (resolver instanceof ClassResolver) {
        ClassResolver classResolver = (ClassResolver) resolver;
        // Peer class may not register this class id, which will introduce inconsistent field order
        if (classResolver.isInternalRegistered(typeId)) {
          return String.valueOf(typeId);
        } else {
          return "Registered";
        }
      }
      return String.valueOf(typeId);
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
      return typeId == that.typeId && userTypeId == that.userTypeId;
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), typeId, userTypeId);
    }

    @Override
    public String toString() {
      return "RegisteredFieldType{"
          + "nullable="
          + nullable()
          + ", trackingRef="
          + trackingRef()
          + ", typeId="
          + typeId
          + ", userTypeId="
          + userTypeId
          + '}';
    }
  }

  private static Class<?> getPrimitiveArrayClass(int typeId) {
    switch (typeId) {
      case Types.BOOL_ARRAY:
        return boolean[].class;
      case Types.INT8_ARRAY:
      case Types.UINT8_ARRAY:
        return byte[].class;
      case Types.INT16_ARRAY:
      case Types.UINT16_ARRAY:
        return short[].class;
      case Types.INT32_ARRAY:
      case Types.UINT32_ARRAY:
        return int[].class;
      case Types.INT64_ARRAY:
      case Types.UINT64_ARRAY:
        return long[].class;
      case Types.FLOAT32_ARRAY:
        return float[].class;
      case Types.FLOAT64_ARRAY:
        return double[].class;
      default:
        return null;
    }
  }

  private static Class<?> getPrimitiveListClass(int typeId) {
    switch (typeId) {
      case Types.BOOL_ARRAY:
        return BoolList.class;
      case Types.INT8_ARRAY:
        return Int8List.class;
      case Types.UINT8_ARRAY:
        return Uint8List.class;
      case Types.INT16_ARRAY:
        return Int16List.class;
      case Types.UINT16_ARRAY:
        return Uint16List.class;
      case Types.INT32_ARRAY:
        return Int32List.class;
      case Types.UINT32_ARRAY:
        return Uint32List.class;
      case Types.INT64_ARRAY:
        return Int64List.class;
      case Types.UINT64_ARRAY:
        return Uint64List.class;
      case Types.FLOAT32_ARRAY:
        return Float32List.class;
      case Types.FLOAT64_ARRAY:
        return Float64List.class;
      default:
        return null;
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
        int typeId, boolean nullable, boolean trackingRef, FieldType elementType) {
      super(typeId, -1, nullable, trackingRef);
      this.elementType = elementType;
    }

    public FieldType getElementType() {
      return elementType;
    }

    @Override
    public TypeRef<?> toTypeToken(TypeResolver resolver, TypeRef<?> declared) {
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
      TypeRef<?> elementType = this.elementType.toTypeToken(resolver, declElementType);
      if (declared == null) {
        return collectionOf(elementType, new TypeExtMeta(typeId, nullable, trackingRef));
      }
      if (!declaredClass.isArray()) {
        if (declElementType.equals(elementType)) {
          return declared;
        }
        return collectionOf(
            declaredClass, elementType, new TypeExtMeta(typeId, nullable, trackingRef));
      }
      // Build array type from element type
      // elementType could be base type (int) or intermediate array (int[])
      // Calculate how many dimensions to add
      int declaredDimensions = getArrayDimensions(declaredClass);
      Class<?> elemRawType = elementType.getRawType();
      int elementDimensions = elemRawType.isArray() ? getArrayDimensions(elemRawType) : 0;
      int dimensionsToAdd = declaredDimensions - elementDimensions;
      TypeRef<?> currentType = elementType;
      for (int i = 0; i < dimensionsToAdd; i++) {
        Class<?> arrayClass = Array.newInstance(currentType.getRawType(), 0).getClass();
        // Apply field metadata (nullable, trackingRef) to outermost array only
        TypeExtMeta meta =
            (i == dimensionsToAdd - 1)
                ? new TypeExtMeta(typeId, nullable, trackingRef)
                : currentType.getTypeExtMeta();
        currentType = TypeRef.of(arrayClass, meta);
      }
      return currentType;
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
        int typeId, boolean nullable, boolean trackingRef, FieldType keyType, FieldType valueType) {
      super(typeId, -1, nullable, trackingRef);
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
            new TypeExtMeta(typeId, nullable, trackingRef));
      }
      return mapOf(
          keyType.toTypeToken(classResolver, keyDecl),
          valueType.toTypeToken(classResolver, valueDecl),
          new TypeExtMeta(typeId, nullable, trackingRef));
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
          + ", nullable="
          + nullable()
          + ", trackingRef="
          + trackingRef()
          + '}';
    }
  }

  public static class EnumFieldType extends FieldType {
    public EnumFieldType(boolean nullable, int typeId, int userTypeId) {
      super(typeId, userTypeId, nullable, false);
    }

    @Override
    public TypeRef<?> toTypeToken(TypeResolver classResolver, TypeRef<?> declared) {
      if (declared != null && declared.getRawType().isEnum()) {
        return declared;
      }
      return TypeRef.of(UnknownClass.UnknownEnum.class);
    }

    @Override
    public String getTypeName(TypeResolver resolver, TypeRef<?> typeRef) {
      return "Enum";
    }

    @Override
    public String toString() {
      return "EnumFieldType{"
          + "typeId="
          + typeId
          + ", userTypeId="
          + userTypeId
          + ", nullable="
          + nullable
          + '}';
    }
  }

  public static class ArrayFieldType extends FieldType {
    private final FieldType componentType;
    private final int dimensions;

    public ArrayFieldType(
        int typeId,
        boolean nullable,
        boolean trackingRef,
        FieldType componentType,
        int dimensions) {
      super(typeId, -1, nullable, trackingRef);
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
      if (UnknownClass.class.isAssignableFrom(componentRawType)) {
        return TypeRef.of(
            UnknownClass.getUnknowClass(componentType instanceof EnumFieldType, dimensions, true),
            new TypeExtMeta(typeId, nullable, trackingRef));
      } else {
        return TypeRef.of(
            Array.newInstance(componentRawType, new int[dimensions]).getClass(),
            new TypeExtMeta(typeId, nullable, trackingRef));
      }
    }

    @Override
    public String getTypeName(TypeResolver resolver, TypeRef<?> typeRef) {
      // For native mode, this return same `Array` type to ensure consistent order even some array
      // type
      // is not exist on current deserialization process.
      // For primitive/registered array, it goes to RegisteredFieldType.
      return "Array";
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
          + ", nullable="
          + nullable
          + ", trackingRef="
          + trackingRef
          + '}';
    }
  }

  /** Class for field type which isn't registered and not collection/map type too. */
  public static class ObjectFieldType extends FieldType {

    public ObjectFieldType(int typeId, boolean nullable, boolean trackingRef) {
      super(typeId, -1, nullable, trackingRef);
    }

    @Override
    public TypeRef<?> toTypeToken(TypeResolver classResolver, TypeRef<?> declared) {
      Class<?> clz = declared == null ? Object.class : declared.getRawType();
      return TypeRef.of(clz, new TypeExtMeta(typeId, nullable, trackingRef));
    }

    @Override
    public String getTypeName(TypeResolver resolver, TypeRef<?> typeRef) {
      // When fields not exist on deserializing struct, we can't know its actual field type,
      // sort based on actual type name will incur inconsistent fields order
      return "Object";
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
          + "typeId="
          + typeId
          + ", nullable="
          + nullable
          + ", trackingRef="
          + trackingRef
          + '}';
    }
  }

  /** Class for Union field type. Union types use declared type. */
  public static class UnionFieldType extends FieldType {

    public UnionFieldType(boolean nullable, boolean trackingRef) {
      super(Types.UNION, -1, nullable, trackingRef);
    }

    @Override
    public TypeRef<?> toTypeToken(TypeResolver classResolver, TypeRef<?> declared) {
      // Union types use the declared field type directly
      if (declared != null) {
        return declared;
      }
      // Fallback to base Union class if no declared type
      return TypeRef.of(
          org.apache.fory.type.union.Union.class, new TypeExtMeta(typeId, nullable, trackingRef));
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
