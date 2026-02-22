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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.RefMode;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeInfoHolder;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.converter.FieldConverter;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.DispatchId;
import org.apache.fory.type.GenericType;
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
      SerializationFieldInfo[] userTypeFields) {
    this.buildInFields = buildInFields;
    this.userTypeFields = userTypeFields;
    this.containerFields = containerFields;
    int len = buildInFields.length + userTypeFields.length + containerFields.length;
    SerializationFieldInfo[] fields = new SerializationFieldInfo[len];
    System.arraycopy(buildInFields, 0, fields, 0, buildInFields.length);
    System.arraycopy(containerFields, 0, fields, buildInFields.length, containerFields.length);
    System.arraycopy(
        userTypeFields,
        0,
        fields,
        buildInFields.length + containerFields.length,
        userTypeFields.length);
    allFields = fields;
  }

  public static FieldGroups buildFieldsInfo(Fory fory, List<Field> fields) {
    List<Descriptor> descriptors = new ArrayList<>();
    for (Field field : fields) {
      if (!Modifier.isTransient(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
        descriptors.add(new Descriptor(field, TypeRef.of(field.getAnnotatedType()), null, null));
      }
    }
    DescriptorGrouper descriptorGrouper =
        fory.getClassResolver().createDescriptorGrouper(descriptors, false);
    return buildFieldInfos(fory, descriptorGrouper);
  }

  public static FieldGroups buildFieldInfos(Fory fory, DescriptorGrouper grouper) {
    // When a type is both Collection/Map and final, add it to collection/map fields to keep
    // consistent with jit.
    List<Descriptor> primitives = new ArrayList<>(grouper.getPrimitiveDescriptors());
    List<Descriptor> boxed = new ArrayList<>(grouper.getBoxedDescriptors());
    Collection<Descriptor> buildIn = grouper.getBuildInDescriptors();
    List<Descriptor> regularBuildIn = new ArrayList<>(buildIn.size());
    for (Descriptor d : buildIn) {
      if (DispatchId.getDispatchId(fory, d) == DispatchId.FLOAT16) {
        if (d.isNullable()) {
          boxed.add(d);
        } else {
          primitives.add(d);
        }
      } else {
        regularBuildIn.add(d);
      }
    }
    SerializationFieldInfo[] allBuildIn =
        new SerializationFieldInfo[primitives.size() + boxed.size() + regularBuildIn.size()];
    int cnt = 0;
    for (Descriptor d : primitives) {
      allBuildIn[cnt++] = new SerializationFieldInfo(fory, d);
    }
    for (Descriptor d : boxed) {
      allBuildIn[cnt++] = new SerializationFieldInfo(fory, d);
    }
    for (Descriptor d : regularBuildIn) {
      allBuildIn[cnt++] = new SerializationFieldInfo(fory, d);
    }
    cnt = 0;
    SerializationFieldInfo[] otherFields =
        new SerializationFieldInfo[grouper.getOtherDescriptors().size()];
    for (Descriptor descriptor : grouper.getOtherDescriptors()) {
      SerializationFieldInfo genericTypeField = new SerializationFieldInfo(fory, descriptor);
      otherFields[cnt++] = genericTypeField;
    }
    cnt = 0;
    Collection<Descriptor> collections = grouper.getCollectionDescriptors();
    Collection<Descriptor> maps = grouper.getMapDescriptors();
    SerializationFieldInfo[] containerFields =
        new SerializationFieldInfo[collections.size() + maps.size()];
    for (Descriptor d : collections) {
      containerFields[cnt++] = new SerializationFieldInfo(fory, d);
    }
    for (Descriptor d : maps) {
      containerFields[cnt++] = new SerializationFieldInfo(fory, d);
    }
    return new FieldGroups(allBuildIn, containerFields, otherFields);
  }

  public static final class SerializationFieldInfo {
    public final Descriptor descriptor;
    public final Class<?> type;
    public final TypeRef<?> typeRef;
    public final int dispatchId;
    public final TypeInfo typeInfo;
    public final Serializer serializer;
    public final String qualifiedFieldName;
    public final FieldAccessor fieldAccessor;
    public final FieldConverter<?> fieldConverter;
    public final RefMode refMode;
    public final boolean nullable;
    public final boolean trackingRef;
    public final boolean isPrimitiveField;
    // Use declared type for serialization/deserialization
    public final boolean useDeclaredTypeInfo;

    public final GenericType genericType;
    public final TypeInfoHolder classInfoHolder;
    public final boolean isArray;
    public final TypeInfo containerTypeInfo;

    SerializationFieldInfo(Fory fory, Descriptor d) {
      this.descriptor = d;
      this.type = descriptor.getRawType();
      this.typeRef = d.getTypeRef();
      this.dispatchId = DispatchId.getDispatchId(fory, d);
      TypeResolver resolver = fory.getTypeResolver();
      // invoke `copy` to avoid ObjectSerializer construct clear serializer by `clearSerializer`.
      if (resolver.isMonomorphic(descriptor)) {
        typeInfo = SerializationUtils.getTypeInfo(fory, typeRef.getRawType());
        if (!fory.isShareMeta()
            && !fory.isCompatible()
            && typeInfo.getSerializer() instanceof ReplaceResolveSerializer) {
          // overwrite replace resolve serializer for final field
          typeInfo.setSerializer(new FinalFieldReplaceResolveSerializer(fory, typeInfo.getCls()));
        }
      } else {
        typeInfo = null;
      }
      useDeclaredTypeInfo = typeInfo != null && resolver.isMonomorphic(descriptor);
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
      // This determines how to write the value to the object (Platform.putInt vs putObject).
      isPrimitiveField = typeRef.getRawType().isPrimitive();
      fieldConverter = d.getFieldConverter();
      // For xlang compatibility, check TypeExtMeta first (from remote peer's type meta)
      // This ensures we read data correctly when remote's nullable differs from local
      TypeExtMeta extMeta = typeRef.getTypeExtMeta();
      if (extMeta != null) {
        nullable = extMeta.nullable();
        trackingRef = extMeta.trackingRef();
      } else {
        nullable = d.isNullable();
        trackingRef = d.isTrackingRef();
      }
      refMode = RefMode.of(trackingRef, nullable);

      GenericType t = resolver.buildGenericType(typeRef);
      Class<?> cls = t.getCls();
      if (t.getTypeParametersCount() > 0) {
        boolean skip =
            Arrays.stream(t.getTypeParameters()).allMatch(p -> p.getCls() == Object.class);
        if (skip) {
          t = new GenericType(t.getTypeRef(), t.isMonomorphic());
        }
      }
      genericType = t;
      Field field = descriptor.getField();
      if (field != null) {
        TypeUtils.applyRefTrackingOverride(t, field.getAnnotatedType(), fory.trackingRef());
      }
      classInfoHolder = resolver.nilTypeInfoHolder();
      isArray = cls.isArray();
      if (!fory.isCrossLanguage()) {
        containerTypeInfo = null;
      } else {
        if (resolver.isMap(cls) || resolver.isCollection(cls) || resolver.isSet(cls)) {
          containerTypeInfo = resolver.getTypeInfo(cls);
        } else {
          containerTypeInfo = null;
        }
      }
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
