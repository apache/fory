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
import org.apache.fory.memory.Platform;
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
      Map<Integer, Object> defaultValues = getDefaultValuesForClass(cls);
      int paramCount = primaryConstructor.getParameterCount();
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
    private Map<Integer, Object> getDefaultValuesForClass(Class<?> cls) {
      if (cachedCtrDefaultValues.getIfPresent(cls) != null) {
        return cachedCtrDefaultValues.getIfPresent(cls);
      }
      Map<Integer, Object> defaultValueMethods;
      if (ScalaTypes.isScalaProductType(cls)) {
        defaultValueMethods = getDefaultValuesForCaseClass(cls);
      } else {
        defaultValueMethods = getDefaultValuesForRegularScalaClass(cls);
      }
      cachedCtrDefaultValues.put(cls, defaultValueMethods);
      return defaultValueMethods;
    }

    private static Map<Integer, Object> getDefaultValuesForCaseClass(Class<?> cls) {
      Map<Integer, Object> values = new HashMap<>();
      String companionClassName = cls.getName() + "$";
      Class<?> companionClass = null;
      Object companionInstance = null;
      try {
        companionClass = Class.forName(companionClassName, false, cls.getClassLoader());
        companionInstance = companionClass.getField("MODULE$").get(null);
      } catch (Exception e) {
        // For nested case classes, try to find the companion object in the enclosing class
        Class<?> enclosingClass = cls.getEnclosingClass();
        if (enclosingClass != null) {
          // Look for a companion object field in the enclosing class
          for (java.lang.reflect.Field field : enclosingClass.getDeclaredFields()) {
            if (field.getType().getName().equals(companionClassName)) {
              field.setAccessible(true);
              try {
                companionInstance = field.get(null);
              } catch (Exception e1) {
                LOG.warn(
                    "Error {} accessing companion object for {}, default values support is disabled when deserializing object of type {}",
                    e1.getMessage(),
                    cls.getName(),
                    cls.getName());
                return values;
              }
              if (companionInstance != null) {
                companionClass = companionInstance.getClass();
                break;
              }
            }
          }
        }
      }
      if (companionClass == null) {
        LOG.warn(
            "Companion class not found for {}, default values support is disabled when deserializing object of type {}",
            cls.getName(),
            cls.getName());
        return values;
      }
      MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(companionClass);

      // Look for methods named `apply$default$1`, `apply$default$2`, etc.
      Method[] companionMethods = companionClass.getDeclaredMethods();
      for (Method method : companionMethods) {
        String methodName = method.getName();
        if (methodName.contains("$default$")) {
          try {
            // Extract the parameter index from the method name
            String indexStr =
                methodName.substring(methodName.lastIndexOf("$default$") + "$default$".length());
            int paramIndex = Integer.parseInt(indexStr);
            // Create method handle for the default value method
            MethodHandle methodHandle = lookup.unreflect(method);
            Object defaultValue = methodHandle.invoke(companionInstance);
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

    private static Map<Integer, Object> getDefaultValuesForRegularScalaClass(Class<?> cls) {
      Map<Integer, Object> values = new HashMap<>();
      try {
        MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(cls);
        Method[] classMethods = cls.getDeclaredMethods();
        for (Method method : classMethods) {
          String methodName = method.getName();
          if (methodName.contains("$default$")) {
            try {
              // Extract the parameter index from the method name
              String indexStr =
                  methodName.substring(methodName.lastIndexOf("$default$") + "$default$".length());
              int paramIndex = Integer.parseInt(indexStr);
              // Create method handle for the default value method
              MethodHandle methodHandle = lookup.unreflect(method);
              // For regular Scala classes, we need to create an instance to call instance methods
              // Since these are default value methods, we can try to call them as static methods
              Object defaultValue = methodHandle.invoke();
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
        long fieldOffset = fieldAccessor.getFieldOffset();
        switch (defaultField.dispatchId) {
          case Types.BOOL:
            Platform.putBoolean(obj, fieldOffset, (Boolean) defaultValue);
            break;
          case Types.INT8:
            Platform.putByte(obj, fieldOffset, (Byte) defaultValue);
            break;
          case Types.INT16:
            Platform.putShort(obj, fieldOffset, (Short) defaultValue);
            break;
          case Types.INT32:
          case Types.VARINT32:
            Platform.putInt(obj, fieldOffset, (Integer) defaultValue);
            break;
          case Types.INT64:
          case Types.VARINT64:
          case Types.TAGGED_INT64:
            Platform.putLong(obj, fieldOffset, (Long) defaultValue);
            break;
          case Types.FLOAT32:
            Platform.putFloat(obj, fieldOffset, (Float) defaultValue);
            break;
          case Types.FLOAT64:
            Platform.putDouble(obj, fieldOffset, (Double) defaultValue);
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
