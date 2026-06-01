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

package org.apache.fory.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.Fory;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.UnsafeOps;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.type.ScalaTypes;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.unsafe._JDKAccess;

/**
 * Utility class for detecting Scala classes with default values and their default value methods.
 *
 * <p>Scala classes (including case classes) with default parameters generate companion objects with
 * methods like `apply$default$1`, `apply$default$2`, etc. that return the default values.
 */
@Internal
public class DefaultValueUtils {
  private static final Logger LOG = LoggerFactory.getLogger(DefaultValueUtils.class);
  private static final String SCALA_APPLY_DEFAULT_METHOD_PREFIX = "apply$default$";
  private static final String SCALA_CONSTRUCTOR_DEFAULT_METHOD_PREFIX =
      "$lessinit$greater$default$";

  private static final ClassValueCache<Map<Integer, Object>> cachedCtrDefaultValues =
      ClassValueCache.newClassKeySoftCache(32);
  private static final ClassValueCache<DefaultValueField[]> defaultValueFieldsCache =
      ClassValueCache.newClassKeySoftCache(32);
  private static final ClassValueCache<Map<String, Object>> allDefaultValuesCache =
      ClassValueCache.newClassKeySoftCache(32);

  /** Field info for scala/kotlin class fields with default values. */
  public static final class DefaultValueField {
    private final Object defaultValue;
    private final String fieldName;
    private final FieldAccessor fieldAccessor;
    private final int dispatchId;

    private DefaultValueField(
        String fieldName, Object defaultValue, FieldAccessor fieldAccessor, int dispatchId) {
      this.fieldName = fieldName;
      this.defaultValue = defaultValue;
      this.fieldAccessor = fieldAccessor;
      this.dispatchId = dispatchId;
    }

    public Object getDefaultValue() {
      return defaultValue;
    }

    public String getFieldName() {
      return fieldName;
    }

    public FieldAccessor getFieldAccessor() {
      return fieldAccessor;
    }

    public int getDispatchId() {
      return dispatchId;
    }
  }

  public abstract static class DefaultValueSupport {
    public abstract boolean hasDefaultValues(Class<?> cls);

    public abstract Map<String, Object> getAllDefaultValues(Class<?> cls);

    public abstract Object getDefaultValue(Class<?> cls, String fieldName);

    /**
     * Builds Scala default value fields for the given class. Only includes fields that are not
     * present in the serialized data.
     *
     * @param fory the Fory instance
     * @param type the class type
     * @param descriptors list of descriptors that are present in the serialized data
     * @return array of DefaultValueField objects
     */
    public final DefaultValueField[] buildDefaultValueFields(
        Fory fory, Class<?> type, java.util.List<org.apache.fory.type.Descriptor> descriptors) {
      return buildDefaultValueFields(fory.getTypeResolver(), type, descriptors);
    }

    public final DefaultValueField[] buildDefaultValueFields(
        TypeResolver typeResolver,
        Class<?> type,
        java.util.List<org.apache.fory.type.Descriptor> descriptors) {
      DefaultValueField[] defaultFieldsArray = defaultValueFieldsCache.getIfPresent(type);
      if (defaultFieldsArray != null) {
        return defaultFieldsArray;
      }
      try {
        // Extract field names from descriptors
        java.util.Set<String> serializedFieldNames = new java.util.HashSet<>();
        for (org.apache.fory.type.Descriptor descriptor : descriptors) {
          java.lang.reflect.Field field = descriptor.getField();
          if (field != null) {
            serializedFieldNames.add(field.getName());
          }
        }
        java.lang.reflect.Field[] allFields = type.getDeclaredFields();
        List<DefaultValueField> defaultFields = new ArrayList<>();
        Map<String, Object> allDefaults = getAllDefaultValues(type);
        for (java.lang.reflect.Field field : allFields) {
          // Only include fields that are not in the serialized data
          if (!serializedFieldNames.contains(field.getName())) {
            String fieldName = field.getName();
            Object defaultValue = allDefaults.get(fieldName);

            if (defaultValue != null
                && TypeUtils.wrap(field.getType()).isAssignableFrom(defaultValue.getClass())) {
              FieldAccessor fieldAccessor = FieldAccessor.createAccessor(field);
              int dispatchId = Types.getTypeId(typeResolver, field.getType());
              // Convert value to correct type once during initialization
              Object convertedValue = convertToType(defaultValue, dispatchId);
              defaultFields.add(
                  new DefaultValueField(fieldName, convertedValue, fieldAccessor, dispatchId));
            }
          }
        }
        defaultFieldsArray = defaultFields.toArray(new DefaultValueField[0]);
        defaultValueFieldsCache.put(type, defaultFieldsArray);
      } catch (Exception e) {
        LOG.warn(
            "Error {} building Scala default value fields for {}, default values support is disabled when deserializing object of type {}",
            e.getMessage(),
            type.getName(),
            type.getName());
        // Ignore exceptions and return empty array
        defaultValueFieldsCache.put(type, new DefaultValueField[0]);
      }
      return defaultFieldsArray;
    }
  }

  private static final DefaultValueSupport SCALA_DEFAULT_VALUE_SUPPORT =
      new ScalaDefaultValueSupport();

  private static DefaultValueSupport KOTLIN_DEFAULT_VALUE_SUPPORT = null;

  public static synchronized DefaultValueSupport getScalaDefaultValueSupport() {
    return SCALA_DEFAULT_VALUE_SUPPORT;
  }

  public static synchronized DefaultValueSupport getKotlinDefaultValueSupport() {
    return KOTLIN_DEFAULT_VALUE_SUPPORT;
  }

  public static synchronized void setKotlinDefaultValueSupport(
      DefaultValueSupport defaultValueSupport) {
    KOTLIN_DEFAULT_VALUE_SUPPORT = defaultValueSupport;
  }

  public static final class ScalaDefaultValueSupport extends DefaultValueSupport {

    @Override
    public boolean hasDefaultValues(Class<?> cls) {
      return getAllDefaultValues(cls).size() > 0;
    }

    @Override
    public Object getDefaultValue(Class<?> cls, String fieldName) {
      Map<String, Object> allDefaults = getAllDefaultValues(cls);
      return allDefaults.get(fieldName);
    }

    /**
     * Gets all default values for a Scala class. This method caches all default values at the class
     * level for better performance.
     *
     * @param cls the Scala class
     * @return a map from parameter index to default value (null if no default)
     */
    @Override
    public Map<String, Object> getAllDefaultValues(Class<?> cls) {
      Preconditions.checkNotNull(cls, "Class must not be null");
      // Check cache first
      Map<String, Object> allDefaults = allDefaultValuesCache.getIfPresent(cls);
      if (allDefaults != null) {
        return allDefaults;
      }
      allDefaults = new HashMap<>();
      // Get all constructors
      Constructor<?>[] constructors = cls.getDeclaredConstructors();
      // Find the constructor with the most parameters (assuming it's the primary constructor)
      Constructor<?> primaryConstructor = null;
      for (Constructor<?> constructor : constructors) {
        if (primaryConstructor == null
            || constructor.getParameterCount() > primaryConstructor.getParameterCount()) {
          primaryConstructor = constructor;
        }
      }
      Preconditions.checkNotNull(
          primaryConstructor, "Primary constructor not found for class " + cls.getName());
      int paramCount = primaryConstructor.getParameterCount();
      Map<Integer, Object> defaultValues = getDefaultValuesForClass(cls, paramCount);
      for (int i = 0; i < paramCount; i++) {
        String paramName = primaryConstructor.getParameters()[i].getName();
        Object defaultValue = defaultValues.get(i + 1); // +1 because default values are 1-indexed
        if (defaultValue != null) {
          allDefaults.put(paramName, defaultValue);
        }
      }
      allDefaultValuesCache.put(cls, allDefaults);
      return allDefaults;
    }

    /**
     * Finds all default value methods for a Scala class.
     *
     * @param cls the Scala class
     * @return a map from parameter index to method handle
     */
    private Map<Integer, Object> getDefaultValuesForClass(Class<?> cls, int paramCount) {
      if (cachedCtrDefaultValues.getIfPresent(cls) != null) {
        return cachedCtrDefaultValues.getIfPresent(cls);
      }
      Map<Integer, Object> defaultValueMethods;
      if (ScalaTypes.isScalaProductType(cls)) {
        defaultValueMethods = getDefaultValuesForCaseClass(cls, paramCount);
      } else {
        defaultValueMethods = getDefaultValuesForRegularScalaClass(cls, paramCount);
      }
      cachedCtrDefaultValues.put(cls, defaultValueMethods);
      return defaultValueMethods;
    }

    private static Map<Integer, Object> getDefaultValuesForCaseClass(Class<?> cls, int paramCount) {
      Map<Integer, Object> values = new HashMap<>();
      ScalaCompanionObject companionObject = getScalaCompanionObject(cls, true);
      if (companionObject == null) {
        return values;
      }
      MethodHandles.Lookup lookup =
          AndroidSupport.IS_ANDROID ? null : _JDKAccess._trustedLookup(companionObject.cls);

      Method[] companionMethods = companionObject.cls.getDeclaredMethods();
      for (Method method : companionMethods) {
        int paramIndex =
            getScalaDefaultParameterIndex(method, SCALA_APPLY_DEFAULT_METHOD_PREFIX, paramCount);
        if (paramIndex < 0) {
          paramIndex =
              getScalaDefaultParameterIndex(
                  method, SCALA_CONSTRUCTOR_DEFAULT_METHOD_PREFIX, paramCount);
        }
        if (paramIndex > 0) {
          try {
            Object defaultValue =
                invokeDefaultValueMethod(lookup, method, companionObject.instance);
            values.put(paramIndex, defaultValue);
          } catch (Throwable e) {
            LOG.warn(
                "Error: {} finding default value methods for {}, default values support is disabled when deserializing object of type {}",
                e.getMessage(),
                cls.getName(),
                cls.getName());
            return values;
          }
        }
      }
      return values;
    }

    private static Map<Integer, Object> getDefaultValuesForRegularScalaClass(
        Class<?> cls, int paramCount) {
      Map<Integer, Object> values = new HashMap<>();
      ScalaCompanionObject companionObject = getScalaCompanionObject(cls, false);
      if (companionObject == null) {
        return values;
      }
      try {
        MethodHandles.Lookup lookup =
            AndroidSupport.IS_ANDROID ? null : _JDKAccess._trustedLookup(companionObject.cls);
        Method[] companionMethods = companionObject.cls.getDeclaredMethods();
        for (Method method : companionMethods) {
          int paramIndex =
              getScalaDefaultParameterIndex(
                  method, SCALA_CONSTRUCTOR_DEFAULT_METHOD_PREFIX, paramCount);
          if (paramIndex > 0) {
            try {
              Object defaultValue =
                  invokeDefaultValueMethod(lookup, method, companionObject.instance);
              values.put(paramIndex, defaultValue);
            } catch (Throwable e) {
              LOG.warn(
                  "Error {} finding default value for {}, default values support is disabled when deserializing object of type {}",
                  e.getMessage(),
                  cls.getName(),
                  cls.getName());
              return values;
            }
          }
        }
      } catch (Exception e) {
        LOG.warn(
            "Error {} finding default value for {}, default values support is disabled when deserializing object of type {}",
            e.getMessage(),
            cls.getName(),
            cls.getName());
        return values;
      }
      return values;
    }

    private static ScalaCompanionObject getScalaCompanionObject(
        Class<?> cls, boolean warnIfMissing) {
      String companionClassName = cls.getName() + "$";
      try {
        Class<?> companionClass = Class.forName(companionClassName, false, cls.getClassLoader());
        Object companionInstance = companionClass.getField("MODULE$").get(null);
        if (companionInstance != null) {
          return new ScalaCompanionObject(companionClass, companionInstance);
        }
      } catch (Exception e) {
        // For nested case classes, try to find the companion object in the enclosing class.
        Class<?> enclosingClass = cls.getEnclosingClass();
        if (enclosingClass != null) {
          for (java.lang.reflect.Field field : enclosingClass.getDeclaredFields()) {
            if (field.getType().getName().equals(companionClassName)) {
              field.setAccessible(true);
              try {
                Object companionInstance = field.get(null);
                if (companionInstance != null) {
                  return new ScalaCompanionObject(companionInstance.getClass(), companionInstance);
                }
              } catch (Exception e1) {
                LOG.warn(
                    "Error {} accessing companion object for {}, default values support is disabled when deserializing object of type {}",
                    e1.getMessage(),
                    cls.getName(),
                    cls.getName());
                return null;
              }
            }
          }
        }
      }
      if (warnIfMissing) {
        LOG.warn(
            "Companion class not found for {}, default values support is disabled when deserializing object of type {}",
            cls.getName(),
            cls.getName());
      }
      return null;
    }

    private static Object invokeDefaultValueMethod(
        MethodHandles.Lookup lookup, Method method, Object target) throws Throwable {
      if (AndroidSupport.IS_ANDROID) {
        try {
          method.setAccessible(true);
          return method.invoke(target);
        } catch (InvocationTargetException e) {
          throw e.getCause();
        }
      }
      MethodHandle methodHandle = lookup.unreflect(method);
      if (target == null) {
        return methodHandle.invoke();
      }
      return methodHandle.invoke(target);
    }

    private static int getScalaDefaultParameterIndex(
        Method method, String methodPrefix, int maxParamIndex) {
      if (method.getParameterCount() != 0) {
        return -1;
      }
      String methodName = method.getName();
      if (!methodName.startsWith(methodPrefix)) {
        return -1;
      }
      int indexStart = methodPrefix.length();
      if (indexStart == methodName.length()) {
        return -1;
      }
      int paramIndex = 0;
      for (int i = indexStart; i < methodName.length(); i++) {
        char c = methodName.charAt(i);
        if (c < '0' || c > '9') {
          return -1;
        }
        int digit = c - '0';
        if (paramIndex > (Integer.MAX_VALUE - digit) / 10) {
          return -1;
        }
        paramIndex = paramIndex * 10 + digit;
      }
      return paramIndex > 0 && paramIndex <= maxParamIndex ? paramIndex : -1;
    }

    private static final class ScalaCompanionObject {
      private final Class<?> cls;
      private final Object instance;

      private ScalaCompanionObject(Class<?> cls, Object instance) {
        this.cls = cls;
        this.instance = instance;
      }
    }
  }

  /** Convert value to correct type based on dispatchId. */
  private static Object convertToType(Object value, int dispatchId) {
    switch (dispatchId) {
      case Types.BOOL:
        return (Boolean) value;
      case Types.INT8:
        return ((Number) value).byteValue();
      case Types.INT16:
        return ((Number) value).shortValue();
      case Types.INT32:
      case Types.VARINT32:
        return ((Number) value).intValue();
      case Types.INT64:
      case Types.VARINT64:
      case Types.TAGGED_INT64:
        return ((Number) value).longValue();
      case Types.FLOAT32:
        return ((Number) value).floatValue();
      case Types.FLOAT64:
        return ((Number) value).doubleValue();
      default:
        return value;
    }
  }

  /**
   * Sets default values for missing fields in a Scala/Kotlin class.
   *
   * @param obj the object to set default values on
   * @param defaultValueFields the cached default value fields
   */
  public static void setDefaultValues(Object obj, DefaultValueField[] defaultValueFields) {
    for (DefaultValueField defaultField : defaultValueFields) {
      FieldAccessor fieldAccessor = defaultField.getFieldAccessor();
      if (fieldAccessor != null) {
        Object defaultValue = defaultField.getDefaultValue();
        if (AndroidSupport.IS_ANDROID) {
          fieldAccessor.set(obj, defaultValue);
          continue;
        }
        long fieldOffset = fieldAccessor.getFieldOffset();
        switch (defaultField.dispatchId) {
          case Types.BOOL:
            UnsafeOps.putBoolean(obj, fieldOffset, (Boolean) defaultValue);
            break;
          case Types.INT8:
            UnsafeOps.putByte(obj, fieldOffset, (Byte) defaultValue);
            break;
          case Types.INT16:
            UnsafeOps.putShort(obj, fieldOffset, (Short) defaultValue);
            break;
          case Types.INT32:
          case Types.VARINT32:
            UnsafeOps.putInt(obj, fieldOffset, (Integer) defaultValue);
            break;
          case Types.INT64:
          case Types.VARINT64:
          case Types.TAGGED_INT64:
            UnsafeOps.putLong(obj, fieldOffset, (Long) defaultValue);
            break;
          case Types.FLOAT32:
            UnsafeOps.putFloat(obj, fieldOffset, (Float) defaultValue);
            break;
          case Types.FLOAT64:
            UnsafeOps.putDouble(obj, fieldOffset, (Double) defaultValue);
            break;
          default:
            // Object type (including String, char, boxed types not covered above)
            fieldAccessor.putObject(obj, defaultValue);
        }
      }
    }
  }

  public static Object getScalaDefaultValue(Class<?> cls, String fieldName) {
    return getScalaDefaultValueSupport().getDefaultValue(cls, fieldName);
  }

  public static Object getKotlinDefaultValue(Class<?> cls, String fieldName) {
    return getKotlinDefaultValueSupport().getDefaultValue(cls, fieldName);
  }
}
