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
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.ClassInfoHolder;
import org.apache.fory.resolver.RefMode;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.converter.FieldConverter;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.DispatchId;
import org.apache.fory.type.GenericType;
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
        descriptors.add(new Descriptor(field, TypeRef.of(field.getGenericType()), null, null));
      }
    }
    DescriptorGrouper descriptorGrouper =
        fory.getClassResolver().createDescriptorGrouper(descriptors, false);
    return buildFieldInfos(fory, descriptorGrouper);
  }

  public static FieldGroups buildFieldInfos(Fory fory, DescriptorGrouper grouper) {
    // When a type is both Collection/Map and final, add it to collection/map fields to keep
    // consistent with jit.
    Collection<Descriptor> primitives = grouper.getPrimitiveDescriptors();
    Collection<Descriptor> boxed = grouper.getBoxedDescriptors();
    Collection<Descriptor> buildIn = grouper.getBuildInDescriptors();
    SerializationFieldInfo[] allBuildIn =
        new SerializationFieldInfo[primitives.size() + boxed.size() + buildIn.size()];
    int cnt = 0;
    for (Descriptor d : primitives) {
      allBuildIn[cnt++] = new SerializationFieldInfo(fory, d);
    }
    for (Descriptor d : boxed) {
      allBuildIn[cnt++] = new SerializationFieldInfo(fory, d);
    }
    for (Descriptor d : buildIn) {
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
    public final TypeRef<?> typeRef;
    public final int dispatchId;
    public final ClassInfo classInfo;
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
    public final ClassInfoHolder classInfoHolder;
    public final boolean isArray;
    public final ClassInfo containerClassInfo;

    SerializationFieldInfo(Fory fory, Descriptor d) {
      this.descriptor = d;
      this.typeRef = d.getTypeRef();
      this.dispatchId = DispatchId.getDispatchId(fory, d);
      TypeResolver resolver = fory.getTypeResolver();
      // invoke `copy` to avoid ObjectSerializer construct clear serializer by `clearSerializer`.
      if (resolver.isMonomorphic(descriptor)) {
        classInfo = SerializationUtils.getClassInfo(fory, typeRef.getRawType());
        if (!fory.isShareMeta()
            && !fory.isCompatible()
            && classInfo.getSerializer() instanceof ReplaceResolveSerializer) {
          // overwrite replace resolve serializer for final field
          classInfo.setSerializer(new FinalFieldReplaceResolveSerializer(fory, classInfo.getCls()));
        }
      } else {
        classInfo = null;
      }
      useDeclaredTypeInfo = classInfo != null && resolver.isMonomorphic(descriptor);
      if (classInfo != null) {
        serializer = classInfo.getSerializer();
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
      classInfoHolder = resolver.nilClassInfoHolder();
      isArray = cls.isArray();
      if (!fory.isCrossLanguage()) {
        containerClassInfo = null;
      } else {
        if (resolver.isMap(cls) || resolver.isCollection(cls) || resolver.isSet(cls)) {
          containerClassInfo = resolver.getClassInfo(cls);
        } else {
          containerClassInfo = null;
        }
      }
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
