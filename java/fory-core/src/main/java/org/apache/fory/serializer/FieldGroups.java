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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.RefMode;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeInfoHolder;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.collection.CollectionSerializer;
import org.apache.fory.serializer.converter.FieldConverter;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.DispatchId;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.TypeAnnotationUtils;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.StringUtils;

public class FieldGroups {
  public final SerializationFieldInfo[] buildInFields;
  public final SerializationFieldInfo[] userTypeFields;
  public final SerializationFieldInfo[] containerFields;
  public final SerializationFieldInfo[] allFields;

  public FieldGroups(
      SerializationFieldInfo[] buildInFields,
      SerializationFieldInfo[] containerFields,
      SerializationFieldInfo[] userTypeFields,
      SerializationFieldInfo[] allFields) {
    this.buildInFields = buildInFields;
    this.userTypeFields = userTypeFields;
    this.containerFields = containerFields;
    this.allFields = allFields;
  }

  public static FieldGroups buildFieldsInfo(TypeResolver typeResolver, List<Field> fields) {
    List<Descriptor> descriptors = new ArrayList<>();
    for (Field field : fields) {
      if (!Modifier.isTransient(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
        TypeRef<?> typeRef = TypeUtils.getFieldTypeRef(field);
        descriptors.add(new Descriptor(field, typeRef, null, null));
      }
    }
    DescriptorGrouper descriptorGrouper =
        buildDescriptorGrouper(typeResolver, descriptors, false, null);
    return buildFieldInfos(typeResolver, descriptorGrouper);
  }

  static DescriptorGrouper buildDescriptorGrouper(
      TypeResolver typeResolver,
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator) {
    return typeResolver.groupDescriptors(descriptors, descriptorsGroupedOrdered, descriptorUpdator);
  }

  public static FieldGroups buildFieldInfos(TypeResolver typeResolver, DescriptorGrouper grouper) {
    // When a type is both Collection/Map and final, add it to collection/map fields to keep
    // consistent with jit.
    List<Descriptor> primitives = new ArrayList<>(grouper.getPrimitiveDescriptors());
    List<Descriptor> boxed = new ArrayList<>(grouper.getBoxedDescriptors());
    Collection<Descriptor> buildIn = grouper.getBuildInDescriptors();
    List<Descriptor> regularBuildIn = new ArrayList<>(buildIn.size());
    for (Descriptor d : buildIn) {
      int dispatchId = DispatchId.getDispatchId(typeResolver, d);
      if (dispatchId == DispatchId.FLOAT16 || dispatchId == DispatchId.BFLOAT16) {
        if (d.isNullable()) {
          boxed.add(d);
        } else {
          primitives.add(d);
        }
      } else {
        regularBuildIn.add(d);
      }
    }
    Map<Descriptor, SerializationFieldInfo> fieldInfos = new IdentityHashMap<>();
    SerializationFieldInfo[] allBuildIn =
        new SerializationFieldInfo[primitives.size() + boxed.size() + regularBuildIn.size()];
    int cnt = 0;
    for (Descriptor d : primitives) {
      allBuildIn[cnt++] = buildFieldInfo(typeResolver, d, FieldCodecCategory.BUILD_IN, fieldInfos);
    }
    for (Descriptor d : boxed) {
      allBuildIn[cnt++] = buildFieldInfo(typeResolver, d, FieldCodecCategory.BUILD_IN, fieldInfos);
    }
    for (Descriptor d : regularBuildIn) {
      allBuildIn[cnt++] = buildFieldInfo(typeResolver, d, FieldCodecCategory.BUILD_IN, fieldInfos);
    }
    cnt = 0;
    SerializationFieldInfo[] otherFields =
        new SerializationFieldInfo[grouper.getOtherDescriptors().size()];
    for (Descriptor descriptor : grouper.getOtherDescriptors()) {
      SerializationFieldInfo genericTypeField =
          buildFieldInfo(typeResolver, descriptor, FieldCodecCategory.OTHER, fieldInfos);
      otherFields[cnt++] = genericTypeField;
    }
    cnt = 0;
    Collection<Descriptor> collections = grouper.getCollectionDescriptors();
    Collection<Descriptor> maps = grouper.getMapDescriptors();
    SerializationFieldInfo[] containerFields =
        new SerializationFieldInfo[collections.size() + maps.size()];
    for (Descriptor d : collections) {
      containerFields[cnt++] =
          buildFieldInfo(typeResolver, d, FieldCodecCategory.CONTAINER, fieldInfos);
    }
    for (Descriptor d : maps) {
      containerFields[cnt++] =
          buildFieldInfo(typeResolver, d, FieldCodecCategory.CONTAINER, fieldInfos);
    }
    SerializationFieldInfo[] allFields = new SerializationFieldInfo[grouper.getNumDescriptors()];
    cnt = 0;
    for (Descriptor descriptor : grouper.getSortedDescriptors()) {
      SerializationFieldInfo fieldInfo = fieldInfos.get(descriptor);
      if (fieldInfo == null) {
        throw new IllegalStateException("Missing serialization field info for " + descriptor);
      }
      allFields[cnt++] = fieldInfo;
    }
    return new FieldGroups(allBuildIn, containerFields, otherFields, allFields);
  }

  private static SerializationFieldInfo buildFieldInfo(
      TypeResolver typeResolver,
      Descriptor descriptor,
      FieldCodecCategory codecCategory,
      Map<Descriptor, SerializationFieldInfo> fieldInfos) {
    SerializationFieldInfo fieldInfo =
        new SerializationFieldInfo(typeResolver, descriptor, codecCategory);
    fieldInfos.put(descriptor, fieldInfo);
    return fieldInfo;
  }

  public enum FieldCodecCategory {
    BUILD_IN,
    CONTAINER,
    OTHER
  }

  public static final class SerializationFieldInfo {
    public final Descriptor descriptor;
    public final Class<?> type;
    public final TypeRef<?> typeRef;
    public final int dispatchId;
    public final String qualifiedFieldName;
    public final FieldAccessor fieldAccessor;
    public final FieldConverter<?> fieldConverter;
    public final RefMode refMode;
    public final boolean nullable;
    public final boolean trackingRef;
    public final boolean isPrimitiveField;
    public final boolean isArray;
    // Use declared type for serialization/deserialization
    public final boolean useDeclaredTypeInfo;

    public final TypeInfo typeInfo;
    public final Serializer serializer;
    public final GenericType genericType;
    public final TypeInfoHolder classInfoHolder;
    public final TypeInfo containerTypeInfo;
    public final Serializer<?> containerSerializerOverride;
    public final FieldCodecCategory codecCategory;

    public SerializationFieldInfo(TypeResolver resolver, Descriptor d) {
      this(resolver, d, FieldCodecCategory.OTHER);
    }

    SerializationFieldInfo(TypeResolver resolver, Descriptor d, FieldCodecCategory codecCategory) {
      this.descriptor = d;
      this.codecCategory = codecCategory;
      this.type = descriptor.getRawType();
      this.typeRef = d.getTypeRef();
      this.dispatchId = DispatchId.getDispatchId(resolver, d);
      boolean primitiveListArray =
          TypeUtils.isPrimitiveListClass(typeRef.getRawType())
              && TypeAnnotationUtils.isArrayType(d);
      boolean primitiveListCollection =
          TypeUtils.isPrimitiveListClass(typeRef.getRawType())
              && resolver.isCollectionDescriptor(d);
      // invoke `copy` to avoid ObjectSerializer construct clear serializer by `clearSerializer`.
      if (resolver.isMonomorphic(descriptor)) {
        typeInfo = resolver.getTypeInfo(typeRef.getRawType());
        if (!resolver.isShareMeta()
            && !resolver.isCompatible()
            && typeInfo.getSerializer() instanceof ReplaceResolveSerializer) {
          // overwrite replace resolve serializer for final field
          typeInfo.setSerializer(
              new FinalFieldReplaceResolveSerializer(resolver, typeInfo.getType()));
        }
      } else {
        typeInfo = null;
      }
      useDeclaredTypeInfo =
          typeInfo != null && resolver.isMonomorphic(descriptor) && !primitiveListCollection;
      if (typeInfo != null) {
        serializer = typeInfo.getSerializer();
      } else {
        serializer = null;
      }

      this.qualifiedFieldName = d.getDeclaringClass() + "." + d.getName();
      if (d.getField() != null) {
        this.fieldAccessor = FieldAccessor.createAccessor(d.getField());
      } else {
        this.fieldAccessor = null;
      }
      // Use local field type to determine if field is primitive.
      // This determines how to write the value to the object (unboxed putInt vs putObject).
      isPrimitiveField = typeRef.getRawType().isPrimitive();
      fieldConverter = d.getFieldConverter();
      // TypeExtMeta is xlang field-wrapper metadata. Native local descriptors keep native
      // nullable-by-default field semantics on the descriptor, while remote TypeDef descriptors
      // already carry their schema nullability there. Primitive-list carrier TypeExtMeta is also
      // element wire metadata, not field-wrapper metadata.
      TypeExtMeta extMeta = typeRef.getTypeExtMeta();
      if (resolver.isCrossLanguage()
          && extMeta != null
          && !primitiveListArray
          && !primitiveListCollection) {
        nullable = extMeta.nullable();
        // Descriptor owns field-wrapper reference tracking through @Ref or generated metadata; a
        // top-level TypeExtMeta can also carry only nullability.
        trackingRef = d.isTrackingRef();
      } else {
        nullable = d.isNullable();
        trackingRef = d.isTrackingRef();
      }
      refMode = RefMode.of(trackingRef, nullable);

      GenericType t;
      if (primitiveListCollection) {
        TypeRef<?> elementTypeRef = TypeAnnotationUtils.getPrimitiveListElementTypeRef(d);
        t = new GenericType(typeRef, true, resolver.buildGenericType(elementTypeRef));
      } else {
        t = resolver.buildGenericType(typeRef);
      }
      Class<?> cls = t.getCls();
      genericType = t;
      Field field = descriptor.getField();
      if (field != null) {
        TypeUtils.applyFieldRefTrackingOverride(t, field, resolver.trackingRef());
      }
      if (needsClassInfoHolder(resolver, cls)) {
        classInfoHolder = resolver.nilTypeInfoHolder();
      } else {
        classInfoHolder = null;
      }
      isArray = cls.isArray();
      if (primitiveListArray) {
        containerSerializerOverride =
            org.apache.fory.serializer.collection.PrimitiveListSerializers.createArraySerializer(
                resolver, type);
      } else if (primitiveListCollection) {
        containerSerializerOverride = new CollectionSerializer(resolver, (Class) type);
      } else if (TypeAnnotationUtils.isBoxedListArrayType(descriptor)) {
        containerSerializerOverride =
            new org.apache.fory.serializer.collection.PrimitiveListSerializers
                .BoxedArrayAsListSerializer(
                resolver,
                TypeAnnotationUtils.getBoxedListArrayTypeId(descriptor),
                qualifiedFieldName);
      } else {
        containerSerializerOverride = null;
      }
      if (!resolver.isCrossLanguage()) {
        containerTypeInfo = null;
      } else {
        if (!primitiveListCollection
            && (resolver.isMap(cls) || resolver.isCollection(cls) || resolver.isSet(cls))) {
          containerTypeInfo = resolver.getTypeInfo(cls);
        } else {
          containerTypeInfo = null;
        }
      }
    }

    private boolean needsClassInfoHolder(TypeResolver resolver, Class<?> cls) {
      return !useDeclaredTypeInfo
          || resolver.isCollection(cls)
          || resolver.isMap(cls)
          || resolver.isSet(cls);
    }

    public String getName() {
      if (fieldAccessor != null) {
        return fieldAccessor.getField().getName();
      }
      return qualifiedFieldName;
    }

    @Override
    public String toString() {
      String[] rsplit = StringUtils.rsplit(qualifiedFieldName, ".", 1);
      return "InternalFieldInfo{"
          + "fieldName='"
          + rsplit[1]
          + ", typeRef="
          + typeRef
          + ", classId="
          + dispatchId
          + ", fieldAccessor="
          + fieldAccessor
          + ", nullable="
          + nullable
          + '}';
    }
  }
}
