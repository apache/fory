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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.apache.fory.annotation.ForyEnumId;
import org.apache.fory.collection.LongMap;
import org.apache.fory.config.Config;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.util.Preconditions;

@SuppressWarnings("rawtypes")
public class EnumSerializer extends ImmutableSerializer<Enum> implements Shareable {
  private static final int MAX_ENUM_ID_ARRAY_SIZE = 2048;

  private final Config config;
  private final Enum[] enumConstants;
  private final Map<String, Enum> stringToEnum;
  private final int[] tagByOrdinal;
  private final Enum[] enumConstantByTagArray;
  private final LongMap<Enum> enumConstantByTagMap;

  public EnumSerializer(Config config, Class<Enum> cls) {
    super(config, cls, false);
    this.config = config;
    Class<Enum> enumClass = resolveEnumClass(cls);
    enumConstants = enumClass.getEnumConstants();
    if (config.serializeEnumByName()) {
      stringToEnum = new HashMap<>();
      for (Enum enumConstant : enumConstants) {
        stringToEnum.put(enumConstant.name(), enumConstant);
      }
    } else {
      stringToEnum = null;
    }
    EnumTagCodec tagCodec = EnumTagCodec.build(enumClass, enumConstants);
    tagByOrdinal = tagCodec.tagByOrdinal;
    enumConstantByTagArray = tagCodec.enumConstantByTagArray;
    enumConstantByTagMap = tagCodec.enumConstantByTagMap;
  }

  @Override
  public void write(WriteContext writeContext, Enum value) {
    if (!config.isXlang() && config.serializeEnumByName()) {
      writeContext.writeString(value.name());
    } else {
      writeContext.getBuffer().writeVarUInt32Small7(tagByOrdinal[value.ordinal()]);
    }
  }

  @Override
  public Enum read(ReadContext readContext) {
    if (!config.isXlang() && config.serializeEnumByName()) {
      String name = readContext.readString();
      Enum e = stringToEnum.get(name);
      if (e != null) {
        return e;
      }
      return handleUnknownEnumValue(name);
    } else {
      int tag = readContext.getBuffer().readVarUInt32Small7();
      Enum value = null;
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
  }

  private Enum handleUnknownEnumValue(int tag) {
    switch (config.getUnknownEnumValueStrategy()) {
      case RETURN_NULL:
        return null;
      case RETURN_FIRST_VARIANT:
        return enumConstants[0];
      case RETURN_LAST_VARIANT:
        return enumConstants[enumConstants.length - 1];
      default:
        throw new IllegalArgumentException(
            String.format("Enum tag %s not in %s", tag, Arrays.toString(enumConstants)));
    }
  }

  private Enum handleUnknownEnumValue(String value) {
    switch (config.getUnknownEnumValueStrategy()) {
      case RETURN_NULL:
        return null;
      case RETURN_FIRST_VARIANT:
        return enumConstants[0];
      case RETURN_LAST_VARIANT:
        return enumConstants[enumConstants.length - 1];
      default:
        throw new IllegalArgumentException(
            String.format("Enum string %s not in %s", value, Arrays.toString(enumConstants)));
    }
  }

  private static Class<Enum> resolveEnumClass(Class<Enum> cls) {
    if (cls.isEnum()) {
      return cls;
    }
    Preconditions.checkArgument(Enum.class.isAssignableFrom(cls) && cls != Enum.class);
    @SuppressWarnings("unchecked")
    Class<Enum> enclosingClass = (Class<Enum>) cls.getEnclosingClass();
    Preconditions.checkNotNull(enclosingClass);
    Preconditions.checkArgument(enclosingClass.isEnum());
    return enclosingClass;
  }

  private interface EnumIdAccessor {
    int getId(Enum value);
  }

  private static final class EnumTagCodec {
    private final int[] tagByOrdinal;
    private final Enum[] enumConstantByTagArray;
    private final LongMap<Enum> enumConstantByTagMap;

    private EnumTagCodec(
        int[] tagByOrdinal, Enum[] enumConstantByTagArray, LongMap<Enum> enumConstantByTagMap) {
      this.tagByOrdinal = tagByOrdinal;
      this.enumConstantByTagArray = enumConstantByTagArray;
      this.enumConstantByTagMap = enumConstantByTagMap;
    }

    private static EnumTagCodec build(Class<Enum> enumClass, Enum[] enumConstants) {
      EnumIdAccessor accessor = resolveEnumIdAccessor(enumClass);
      if (accessor == null) {
        int[] tagByOrdinal = new int[enumConstants.length];
        for (int i = 0; i < enumConstants.length; i++) {
          tagByOrdinal[i] = i;
        }
        return new EnumTagCodec(tagByOrdinal, enumConstants, null);
      }
      return buildExplicitCodec(enumClass, enumConstants, accessor);
    }

    private static EnumTagCodec buildExplicitCodec(
        Class<Enum> enumClass, Enum[] enumConstants, EnumIdAccessor accessor) {
      int[] tagByOrdinal = new int[enumConstants.length];
      LongMap<Enum> enumConstantByTag = new LongMap<>(enumConstants.length);
      int maxTag = 0;
      for (Enum enumConstant : enumConstants) {
        int tag = accessor.getId(enumConstant);
        Enum previous = enumConstantByTag.put(tag, enumConstant);
        if (previous != null) {
          throw new IllegalArgumentException(
              String.format(
                  "Enum %s reuses Fory enum id %s for %s and %s",
                  enumClass.getName(), tag, previous.name(), enumConstant.name()));
        }
        tagByOrdinal[enumConstant.ordinal()] = tag;
        if (tag > maxTag) {
          maxTag = tag;
        }
      }
      if (maxTag < MAX_ENUM_ID_ARRAY_SIZE) {
        Enum[] enumConstantByTagArray = new Enum[maxTag + 1];
        enumConstantByTag.forEach((tag, value) -> enumConstantByTagArray[tag.intValue()] = value);
        return new EnumTagCodec(tagByOrdinal, enumConstantByTagArray, null);
      }
      return new EnumTagCodec(tagByOrdinal, null, enumConstantByTag);
    }

    private static EnumIdAccessor resolveEnumIdAccessor(Class<Enum> enumClass) {
      Map<String, Integer> constantIds = new HashMap<>();
      Field idField = null;
      Method idMethod = null;
      int enumConstantFieldCount = 0;
      for (Field field : enumClass.getDeclaredFields()) {
        ForyEnumId annotation = field.getAnnotation(ForyEnumId.class);
        if (field.isEnumConstant()) {
          enumConstantFieldCount++;
          if (annotation != null) {
            Preconditions.checkArgument(
                annotation.value() >= 0,
                "Enum %s constant %s annotated with @ForyEnumId must declare a non-negative value",
                enumClass.getName(),
                field.getName());
            constantIds.put(field.getName(), annotation.value());
          }
        } else if (annotation != null) {
          Preconditions.checkArgument(
              annotation.value() == -1,
              "Enum %s field %s annotated with @ForyEnumId must not declare an explicit value",
              enumClass.getName(),
              field.getName());
          Preconditions.checkArgument(
              idField == null,
              "Enum %s has multiple fields annotated with @ForyEnumId",
              enumClass.getName());
          idField = field;
        }
      }
      for (Method method : enumClass.getDeclaredMethods()) {
        ForyEnumId annotation = method.getAnnotation(ForyEnumId.class);
        if (annotation != null) {
          Preconditions.checkArgument(
              annotation.value() == -1,
              "Enum %s method %s annotated with @ForyEnumId must not declare an explicit value",
              enumClass.getName(),
              method.getName());
          Preconditions.checkArgument(
              idMethod == null,
              "Enum %s has multiple methods annotated with @ForyEnumId",
              enumClass.getName());
          idMethod = method;
        }
      }

      if (!constantIds.isEmpty()) {
        Preconditions.checkArgument(
            constantIds.size() == enumConstantFieldCount,
            "Enum %s must annotate every enum constant with @ForyEnumId when any enum constant uses it",
            enumClass.getName());
        Preconditions.checkArgument(
            idField == null && idMethod == null,
            "Enum %s must use exactly one @ForyEnumId strategy",
            enumClass.getName());
        return value -> constantIds.get(value.name());
      }

      if (idField == null && idMethod == null) {
        return null;
      }
      Preconditions.checkArgument(
          idField == null || idMethod == null,
          "Enum %s must use exactly one @ForyEnumId strategy",
          enumClass.getName());
      if (idField != null) {
        Field field = idField;
        Preconditions.checkArgument(
            !Modifier.isStatic(field.getModifiers()),
            "Enum %s field %s annotated with @ForyEnumId must not be static",
            enumClass.getName(),
            field.getName());
        field.setAccessible(true);
        return value -> readFieldId(enumClass, field, value);
      }
      Method method = idMethod;
      Preconditions.checkArgument(
          !Modifier.isStatic(method.getModifiers()),
          "Enum %s method %s annotated with @ForyEnumId must not be static",
          enumClass.getName(),
          method.getName());
      Preconditions.checkArgument(
          Modifier.isPublic(method.getModifiers()),
          "Enum %s method %s annotated with @ForyEnumId must be public",
          enumClass.getName(),
          method.getName());
      Preconditions.checkArgument(
          method.getParameterCount() == 0,
          "Enum %s method %s annotated with @ForyEnumId must not take arguments",
          enumClass.getName(),
          method.getName());
      method.setAccessible(true);
      return value -> invokeMethodId(enumClass, method, value);
    }

    private static int readFieldId(Class<Enum> enumClass, Field field, Enum value) {
      try {
        return extractTag(enumClass, field.getName(), field.get(value));
      } catch (IllegalAccessException e) {
        throw new IllegalArgumentException(
            String.format(
                "Failed to read @ForyEnumId field %s on enum %s",
                field.getName(), enumClass.getName()),
            e);
      }
    }

    private static int invokeMethodId(Class<Enum> enumClass, Method method, Enum value) {
      try {
        return extractTag(enumClass, method.getName(), method.invoke(value));
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new IllegalArgumentException(
            String.format(
                "Failed to invoke @ForyEnumId method %s on enum %s",
                method.getName(), enumClass.getName()),
            e);
      }
    }

    private static int extractTag(Class<Enum> enumClass, String source, Object tagValue) {
      Preconditions.checkArgument(
          tagValue instanceof Number,
          "Enum %s @ForyEnumId source %s must return a numeric value",
          enumClass.getName(),
          source);
      long tag = ((Number) tagValue).longValue();
      Preconditions.checkArgument(
          tag >= 0 && tag <= Integer.MAX_VALUE,
          "Enum %s @ForyEnumId source %s returned out-of-range id %s",
          enumClass.getName(),
          source,
          tag);
      return (int) tag;
    }
  }
}
