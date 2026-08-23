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

package org.apache.fory.serializer.scala;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.IdentityHashMap;
import org.apache.fory.annotation.ForyEnumId;
import org.apache.fory.collection.IdentityObjectIntMap;
import org.apache.fory.collection.LongMap;
import org.apache.fory.config.Config;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.serializer.ImmutableSerializer;
import org.apache.fory.serializer.Shareable;
import org.apache.fory.type.ScalaTypes;
import org.apache.fory.util.Preconditions;

/** Serializer for Scala 3 enums with parameterless cases. */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class ScalaEnumSerializer extends ImmutableSerializer<Object> implements Shareable {
  private static final int MAX_ENUM_ID_ARRAY_SIZE = 2048;

  private final Config config;
  private final Object[] enumConstants;
  private final IdentityObjectIntMap<Object> tagByValue;
  private final Object[] enumConstantByTagArray;
  private final LongMap<Object> enumConstantByTagMap;

  public ScalaEnumSerializer(org.apache.fory.resolver.TypeResolver resolver, Class<?> cls) {
    super(resolver.getConfig(), resolveSerializerClass(cls), false);
    config = resolver.getConfig();
    Class<?> enumClass = ScalaTypes.resolveScalaEnumClass(cls);
    enumConstants = loadValues(enumClass);
    IdentityHashMap<Object, Integer> explicitTags = loadExplicitTags(enumClass);
    tagByValue = new IdentityObjectIntMap<>(enumConstants.length, 0.5f);
    LongMap<Object> constantsByTag = new LongMap<>(enumConstants.length);
    int maxTag = 0;
    for (int i = 0; i < enumConstants.length; i++) {
      Object enumConstant = enumConstants[i];
      int tag = explicitTags == null ? i : explicitTags.getOrDefault(enumConstant, -1);
      Preconditions.checkArgument(
          tag >= 0,
          "Scala enum %s must annotate every case with @ForyEnumId when any case uses it",
          enumClass.getName());
      tagByValue.put(enumConstant, tag);
      Object previous = constantsByTag.put(tag, enumConstant);
      Preconditions.checkArgument(
          previous == null,
          "Scala enum %s reuses Fory enum id %s for %s and %s",
          enumClass.getName(),
          tag,
          previous,
          enumConstant);
      if (tag > maxTag) {
        maxTag = tag;
      }
    }
    if (maxTag < MAX_ENUM_ID_ARRAY_SIZE) {
      enumConstantByTagArray = new Object[maxTag + 1];
      constantsByTag.forEach((tag, value) -> enumConstantByTagArray[tag.intValue()] = value);
      enumConstantByTagMap = null;
    } else {
      enumConstantByTagArray = null;
      enumConstantByTagMap = constantsByTag;
    }
  }

  @Override
  public void write(WriteContext writeContext, Object value) {
    int tag = tagByValue.get(value, -1);
    Preconditions.checkArgument(tag >= 0, "Scala enum value %s is not a registered case", value);
    writeContext.getBuffer().writeVarUInt32Small7(tag);
  }

  @Override
  public Object read(ReadContext readContext) {
    int tag = readContext.getBuffer().readVarUInt32Small7();
    Object value = null;
    if (enumConstantByTagArray != null && tag < enumConstantByTagArray.length) {
      value = enumConstantByTagArray[tag];
    } else if (enumConstantByTagMap != null) {
      value = enumConstantByTagMap.get(tag);
    }
    if (value != null) {
      return value;
    }
    return handleUnknownEnumValue(tag);
  }

  private Object handleUnknownEnumValue(int tag) {
    switch (config.getUnknownEnumValueStrategy()) {
      case RETURN_NULL:
        return null;
      case RETURN_FIRST_VARIANT:
        return enumConstants[0];
      case RETURN_LAST_VARIANT:
        return enumConstants[enumConstants.length - 1];
      default:
        throw new IllegalArgumentException(
            String.format("Scala enum tag %s not in %s", tag, Arrays.toString(enumConstants)));
    }
  }

  static Object[] loadValues(Class<?> cls) {
    Class<?> enumClass = ScalaTypes.resolveScalaEnumClass(cls);
    Preconditions.checkArgument(
        enumClass != null, "Scala enum %s must implement scala.reflect.Enum", cls);
    try {
      Method values = enumClass.getMethod("values");
      Object result = values.invoke(null);
      Preconditions.checkArgument(
          result instanceof Object[], "Scala enum %s values() did not return an array", enumClass);
      return (Object[]) result;
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      throw new IllegalArgumentException(
          "Failed to load Scala enum values for " + enumClass.getName(), e);
    }
  }

  static boolean canSerialize(Class<?> cls) {
    Class<?> enumClass = ScalaTypes.resolveScalaEnumClass(cls);
    if (enumClass == null) {
      return false;
    }
    try {
      enumClass.getMethod("values");
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  private static Class<Object> resolveSerializerClass(Class<?> cls) {
    Class<?> enumClass = ScalaTypes.resolveScalaEnumClass(cls);
    Preconditions.checkArgument(
        enumClass != null, "Scala enum %s must implement scala.reflect.Enum", cls);
    Preconditions.checkArgument(
        canSerialize(enumClass),
        "Scala enum %s must define values() to use ScalaEnumSerializer",
        enumClass);
    return (Class<Object>) enumClass;
  }

  private static IdentityHashMap<Object, Integer> loadExplicitTags(Class<?> enumClass) {
    Class<?> companion = loadCompanionClass(enumClass);
    IdentityHashMap<Object, Integer> tagsByValue = new IdentityHashMap<>();
    for (Field field : companion.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers())
          || !enumClass.isAssignableFrom(field.getType())) {
        continue;
      }
      ForyEnumId annotation = field.getAnnotation(ForyEnumId.class);
      if (annotation == null) {
        continue;
      }
      Preconditions.checkArgument(
          annotation.value() >= 0,
          "Scala enum %s case %s annotated with @ForyEnumId must declare a non-negative value",
          enumClass.getName(),
          field.getName());
      field.setAccessible(true);
      Object enumConstant = readCaseField(enumClass, field);
      Integer previous = tagsByValue.put(enumConstant, annotation.value());
      Preconditions.checkArgument(
          previous == null,
          "Scala enum %s case %s has multiple @ForyEnumId declarations",
          enumClass.getName(),
          field.getName());
    }
    return tagsByValue.isEmpty() ? null : tagsByValue;
  }

  private static Class<?> loadCompanionClass(Class<?> enumClass) {
    try {
      return Class.forName(enumClass.getName() + "$", false, enumClass.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException(
          "Failed to load Scala enum companion for " + enumClass.getName(), e);
    }
  }

  private static Object readCaseField(Class<?> enumClass, Field field) {
    try {
      return field.get(null);
    } catch (IllegalAccessException e) {
      throw new IllegalArgumentException(
          String.format(
              "Failed to read @ForyEnumId case field %s on Scala enum %s",
              field.getName(), enumClass.getName()),
          e);
    }
  }
}
