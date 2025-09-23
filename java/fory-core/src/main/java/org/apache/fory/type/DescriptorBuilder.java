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

package org.apache.fory.type;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.converter.FieldConverter;

public class DescriptorBuilder {

  TypeRef<?> typeRef;
  Class<?> type;
  String typeName;
  String name;
  int modifier;
  String declaringClass;
  Field field;
  Method readMethod;
  Method writeMethod;
  ForyField foryField;
  boolean nullable;
  boolean trackingRef;
  FieldConverter fieldConverter;

  public DescriptorBuilder(Descriptor descriptor) {
    this.typeRef = descriptor.getTypeRef();
    this.type = descriptor.getType();
    this.typeName = descriptor.getTypeName();
    this.name = descriptor.getName();
    this.modifier = descriptor.getModifier();
    this.declaringClass = descriptor.getDeclaringClass();
    this.field = descriptor.getField();
    this.readMethod = descriptor.getReadMethod();
    this.writeMethod = descriptor.getWriteMethod();
    this.foryField = descriptor.getForyField();
    this.nullable = descriptor.isNullable();
    this.trackingRef = descriptor.isTrackingRef();
    this.fieldConverter = descriptor.getFieldConverter();
  }

  public DescriptorBuilder typeRef(TypeRef<?> typeRef) {
    this.typeRef = typeRef;
    return this;
  }

  public DescriptorBuilder type(Class<?> type) {
    this.type = type;
    return this;
  }

  public DescriptorBuilder typeName(String typeName) {
    this.typeName = typeName;
    return this;
  }

  public DescriptorBuilder name(String name) {
    this.name = name;
    return this;
  }

  public DescriptorBuilder modifier(int modifier) {
    this.modifier = modifier;
    return this;
  }

  public DescriptorBuilder declaringClass(String declaringClass) {
    this.declaringClass = declaringClass;
    return this;
  }

  public DescriptorBuilder field(Field field) {
    this.field = field;
    return this;
  }

  public DescriptorBuilder readMethod(Method readMethod) {
    this.readMethod = readMethod;
    return this;
  }

  public DescriptorBuilder writeMethod(Method writeMethod) {
    this.writeMethod = writeMethod;
    return this;
  }

  public DescriptorBuilder nullable(boolean nullable) {
    this.nullable = nullable;
    return this;
  }

  public DescriptorBuilder trackingRef(boolean trackingRef) {
    this.trackingRef = trackingRef;
    return this;
  }

  public DescriptorBuilder foryField(ForyField foryField) {
    this.foryField = foryField;
    return this;
  }

  public Descriptor build() {
    return new Descriptor(this);
  }
}
