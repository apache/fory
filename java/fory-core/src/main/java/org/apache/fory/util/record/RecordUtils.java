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

package org.apache.fory.util.record;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.exception.ForyException;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.internal._JDKAccess;

/** Utils for java.lang.Record. */
@SuppressWarnings({"rawtypes"})
public class RecordUtils {
  private static final Method IS_RECORD;
  private static final Method GET_RECORD_COMPONENTS;
  private static final Method GET_DECLARING_RECORD;
  private static final Method GET_NAME;
  private static final Method GET_TYPE;
  private static final Method GET_GENERIC_TYPE;
  private static final Method GET_ANNOTATED_TYPE;
  private static final Method GET_ACCESSOR;

  static {
    Method isRecord;
    Method getRecordComponents;
    Method getDeclaringRecord;
    Method getName;
    Method getType;
    Method getGenericType;
    Method getAnnotatedType;
    Method getAccessor;

    try {
      // use reflection to support compilation for jdk before 16
      isRecord = Class.class.getDeclaredMethod("isRecord");
      getRecordComponents = Class.class.getMethod("getRecordComponents");
      Class<?> componentClass = Class.forName("java.lang.reflect.RecordComponent");
      getName = componentClass.getMethod("getName");
      getType = componentClass.getMethod("getType");
      getDeclaringRecord = componentClass.getMethod("getDeclaringRecord");
      getGenericType = componentClass.getMethod("getGenericType");
      getAnnotatedType = componentClass.getMethod("getAnnotatedType");
      getAccessor = componentClass.getMethod("getAccessor");
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      isRecord = null;
      getRecordComponents = null;
      getDeclaringRecord = null;
      getName = null;
      getType = null;
      getGenericType = null;
      getAnnotatedType = null;
      getAccessor = null;
    }
    // all public methods, no need to setAccessible.
    IS_RECORD = isRecord;
    GET_RECORD_COMPONENTS = getRecordComponents;
    GET_DECLARING_RECORD = getDeclaringRecord;
    GET_NAME = getName;
    GET_TYPE = getType;
    GET_GENERIC_TYPE = getGenericType;
    GET_ANNOTATED_TYPE = getAnnotatedType;
    GET_ACCESSOR = getAccessor;
  }

  private static final ClassValueCache<Boolean> isRecordCache =
      ClassValueCache.newClassKeyCache(32);

  private static final ClassValueCache<RecordComponent[]> recordComponentsCache =
      ClassValueCache.newClassKeyCache(32);

  private static final ClassValueCache<Tuple2<Constructor, MethodHandle>> ctrCache =
      ClassValueCache.newClassKeyCache(32);

  /**
   * Returns {@code true} if and only if this class is a record class.
   *
   * <p>The direct superclass of a record class is {@code java.lang.Record}. A record class is
   * {@linkplain Modifier#FINAL final}. A record class has (possibly zero) record components; {@link
   * #getRecordComponents} returns a non-null but possibly empty value for a record.
   *
   * <p>Note that class java.lang.Record is not a record class and thus invoking this method on
   * class {@code Record} returns {@code false}.
   *
   * @return true if and only if this class is a record class, otherwise false
   * @since 16
   */
  public static boolean isRecord(Class<?> cls) {
    if (IS_RECORD == null) {
      return false;
    }
    return isRecordCache.get(cls, () -> isRecordUncached(cls));
  }

  /**
   * Returns an array of {@code RecordComponent} objects representing all the record components of
   * this record class, or {@code null} if this class is not a record class.
   *
   * <p>The components are returned in the same order that they are declared in the record header.
   * The array is empty if this record class has no components. If the class is not a record class,
   * that is {@link #isRecord} returns {@code false}, then this method returns {@code null}.
   * Conversely, if {@link #isRecord} returns {@code true}, then this method returns a non-null
   * value.
   */
  public static RecordComponent[] getRecordComponents(Class<?> cls) {
    if (GET_RECORD_COMPONENTS == null) {
      return null;
    }
    return recordComponentsCache.get(cls, () -> getRecordComponentsUncached(cls));
  }

  /** Returns the record canonical constructor. */
  public static Tuple2<Constructor, MethodHandle> getRecordConstructor(Class<?> cls) {
    return ctrCache.get(cls, () -> getRecordConstructorUncached(cls));
  }

  private static boolean isRecordUncached(Class<?> type) {
    try {
      return (boolean) IS_RECORD.invoke(type);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private static RecordComponent[] getRecordComponentsUncached(Class<?> type) {
    try {
      MethodHandles.Lookup lookup =
          AndroidSupport.IS_ANDROID ? null : _JDKAccess._trustedLookup(type);
      Object[] components = (Object[]) GET_RECORD_COMPONENTS.invoke(type);
      RecordComponent[] recordComponents = new RecordComponent[components.length];
      for (int i = 0; i < components.length; i++) {
        Object component = components[i];
        Method accessor = (Method) GET_ACCESSOR.invoke(component);
        Class<?> fieldType = (Class<?>) GET_TYPE.invoke(component);
        Object getter = null;
        if (AndroidSupport.IS_ANDROID) {
          try {
            accessor.setAccessible(true);
          } catch (RuntimeException e) {
            throw new ForyException("Failed to make record accessor accessible: " + accessor, e);
          }
        } else {
          MethodHandle handle = lookup.unreflect(accessor);
          getter = _JDKAccess.makeGetterFunction(lookup, handle, fieldType);
        }
        recordComponents[i] =
            new RecordComponent(
                (Class<?>) GET_DECLARING_RECORD.invoke(component),
                (String) GET_NAME.invoke(component),
                fieldType,
                (Type) GET_GENERIC_TYPE.invoke(component),
                GET_ANNOTATED_TYPE.invoke(component),
                accessor,
                getter);
      }
      return recordComponents;
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private static Tuple2<Constructor, MethodHandle> getRecordConstructorUncached(Class<?> type) {
    RecordComponent[] components = RecordUtils.getRecordComponents(type);
    if (components == null) {
      return null;
    }
    Class<?>[] paramTypes =
        Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
    Constructor constructor;
    try {
      constructor = type.getDeclaredConstructor(paramTypes);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
    if (AndroidSupport.IS_ANDROID) {
      try {
        constructor.setAccessible(true);
      } catch (RuntimeException e) {
        throw new ForyException("Failed to make record constructor accessible: " + constructor, e);
      }
      return Tuple2.of(constructor, null);
    }
    MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(type);
    if (lookup != null) {
      try {
        MethodHandle handle =
            lookup.findConstructor(type, MethodType.methodType(void.class, paramTypes));
        return Tuple2.of(constructor, handle);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        return Tuple2.of(constructor, null);
      }
    } else {
      return Tuple2.of(constructor, null);
    }
  }

  // Invoked by jit
  public static MethodHandle getRecordCtrHandle(Class<?> cls) {
    return getRecordConstructor(cls).f1;
  }

  public static Object[] buildRecordComponentDefaultValues(Class<?> cls) {
    RecordComponent[] components = RecordUtils.getRecordComponents(cls);
    assert components != null;
    Object[] defaultValues = new Object[components.length];
    for (int i = 0; i < components.length; i++) {
      Class<?> type = components[i].getType();
      Object defaultValue = null;
      if (type == boolean.class) {
        defaultValue = false;
      } else if (type == byte.class) {
        defaultValue = (byte) 0;
      } else if (type == short.class) {
        defaultValue = (short) 0;
      } else if (type == char.class) {
        defaultValue = (char) 0;
      } else if (type == int.class) {
        defaultValue = 0;
      } else if (type == long.class) {
        defaultValue = (long) 0;
      } else if (type == double.class) {
        defaultValue = (double) 0;
      } else if (type == float.class) {
        defaultValue = (float) 0;
      }
      defaultValues[i] = defaultValue;
    }
    return defaultValues;
  }

  /** Build mapping from record component to read field. */
  public static int[] buildRecordComponentMapping(Class<?> cls, List<String> fields) {
    Map<String, Integer> fieldOrderIndex = new HashMap<>(fields.size());
    int counter = 0;
    for (String fieldName : fields) {
      fieldOrderIndex.put(fieldName, counter++);
    }
    RecordComponent[] components = getRecordComponents(cls);
    if (components == null) {
      return null;
    }
    int[] mapping = new int[components.length];
    for (int i = 0; i < mapping.length; i++) {
      RecordComponent component = components[i];
      Integer index = fieldOrderIndex.get(component.getName());
      if (index == null) {
        // field missing in current process.
        mapping[i] = -1;
      } else {
        mapping[i] = index;
      }
    }
    return mapping;
  }

  /** Build reversed mapping from read field to record component. */
  public static Map<String, Integer> buildFieldToComponentMapping(Class<?> cls) {
    RecordComponent[] components = getRecordComponents(cls);
    assert components != null;
    Map<String, Integer> recordComponentsIndex = new HashMap<>(components.length);
    int counter = 0;
    for (RecordComponent component : components) {
      recordComponentsIndex.put(component.getName(), counter++);
    }
    return recordComponentsIndex;
  }

  public static Object[] remapping(RecordInfo recordInfo, Object[] fields) {
    int[] recordComponentsIndex = recordInfo.getRecordComponentsIndex();
    Object[] recordComponents = recordInfo.getRecordComponents();
    Object[] recordComponentsDefaultValues = recordInfo.getRecordComponentsDefaultValues();
    for (int i = 0; i < recordComponentsIndex.length; i++) {
      int index = recordComponentsIndex[i];
      if (index != -1) {
        recordComponents[i] = fields[index];
      } else {
        // field missing in peer process.
        recordComponents[i] = recordComponentsDefaultValues[i];
      }
    }
    return recordComponents;
  }
}
