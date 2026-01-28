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

package org.apache.fory;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.fory.collection.Tuple3;
import org.apache.fory.memory.Platform;
import org.apache.fory.meta.ClassDef;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.type.Descriptor;
import org.apache.fory.util.unsafe._JDKAccess;
import org.testng.SkipException;

/** Test utils. */
public class TestUtils {
  @SuppressWarnings("unchecked")
  public static <T> T getFieldValue(Object obj, String fieldName) {
    return (T)
        FieldAccessor.createAccessor(ReflectionUtils.getField(obj.getClass(), fieldName)).get(obj);
  }

  public static <K, V> ImmutableMap<K, V> mapOf(K k1, V v1) {
    return ImmutableBiMap.of(k1, v1);
  }

  /**
   * Trigger OOM for SoftGC by allocate more memory.
   *
   * @param predicate whether stop Trigger OOM.
   */
  public static void triggerOOMForSoftGC(Supplier<Boolean> predicate) {
    if (_JDKAccess.IS_OPEN_J9) {
      throw new SkipException("OpenJ9 unsupported");
    }
    System.gc();
    while (predicate.get()) {
      triggerOOM();
      System.gc();
      System.out.printf("Wait gc.");
      try {
        Thread.sleep(50);
      } catch (InterruptedException e1) {
        throw new RuntimeException(e1);
      }
    }
  }

  private static void triggerOOM() {
    while (true) {
      // Force an OOM
      try {
        final ArrayList<Object[]> allocations = new ArrayList<>();
        int size;
        while ((size =
                Math.min(Math.abs((int) Runtime.getRuntime().freeMemory()), Integer.MAX_VALUE))
            > 0) allocations.add(new Object[size]);
      } catch (OutOfMemoryError e) {
        System.out.println("Met OOM.");
        break;
      }
    }
  }

  public static byte[] jdkSerialize(Object data) {
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    jdkSerialize(bas, data);
    return bas.toByteArray();
  }

  public static void jdkSerialize(ByteArrayOutputStream bas, Object data) {
    bas.reset();
    try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(bas)) {
      objectOutputStream.writeObject(data);
      objectOutputStream.flush();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static <T> T unsafeCopy(T obj) {
    @SuppressWarnings("unchecked")
    T newInstance = (T) Platform.newInstance(obj.getClass());
    for (Field field : ReflectionUtils.getFields(obj.getClass(), true)) {
      if (!Modifier.isStatic(field.getModifiers())) {
        // Don't cache accessors by `obj.getClass()` using WeakHashMap, the `field` will reference
        // `class`, which cause circular reference.
        FieldAccessor accessor = FieldAccessor.createAccessor(field);
        accessor.set(newInstance, accessor.get(obj));
      }
    }
    return newInstance;
  }

  public static void unsafeCopy(Object from, Object to) {
    Tuple3<Set<String>, Map<String, Field>, Map<String, Field>> commonFieldsInfo =
        getCommonFields(from.getClass(), to.getClass());
    Map<String, Field> fieldMap1 = commonFieldsInfo.f1;
    Map<String, Field> fieldMap2 = commonFieldsInfo.f2;
    for (String commonField : commonFieldsInfo.f0) {
      Field field1 = fieldMap1.get(commonField);
      Field field2 = fieldMap2.get(commonField);
      FieldAccessor accessor1 = FieldAccessor.createAccessor(field1);
      FieldAccessor accessor2 = FieldAccessor.createAccessor(field2);
      accessor2.set(to, accessor1.get(from));
    }
  }

  public static boolean objectFieldsEquals(Object actual, Object expected) {
    return objectFieldsEquals(actual, expected, false);
  }

  public static boolean objectFieldsEquals(
      Object actual, Object expected, boolean throwExceptionWhenUnEqual) {
    List<Field> actualFields = ReflectionUtils.getFields(actual.getClass(), true);
    List<Field> expectedFields = ReflectionUtils.getFields(expected.getClass(), true);
    if (actualFields.size() != expectedFields.size()) {
      if (throwExceptionWhenUnEqual) {
        throw new AssertionError(
            String.format(
                "Field count mismatch: expected %s to have %d fields, got %s with %d fields",
                expected.getClass().getName(),
                expectedFields.size(),
                actual.getClass().getName(),
                actualFields.size()));
      }
      return false;
    }
    Tuple3<Set<String>, Map<String, Field>, Map<String, Field>> commonFieldsInfo =
        getCommonFields(actual.getClass(), expected.getClass());
    if (commonFieldsInfo.f1.size() != actualFields.size()) {
      if (throwExceptionWhenUnEqual) {
        throw new AssertionError(
            String.format(
                "Common fields count mismatch: expected %d, got %d",
                actualFields.size(), commonFieldsInfo.f1.size()));
      }
      return false;
    }
    if (commonFieldsInfo.f1.size() != commonFieldsInfo.f2.size()) {
      if (throwExceptionWhenUnEqual) {
        throw new AssertionError(
            String.format(
                "Field map size mismatch: actual has %d, expected has %d",
                commonFieldsInfo.f1.size(), commonFieldsInfo.f2.size()));
      }
      return false;
    }
    return objectCommonFieldsEquals(commonFieldsInfo, actual, expected, throwExceptionWhenUnEqual);
  }

  public static boolean objectFieldsEquals(
      Set<String> fields, Object actual, Object expected, boolean throwExceptionWhenUnEqual) {
    Tuple3<Set<String>, Map<String, Field>, Map<String, Field>> commonFieldsInfo =
        getCommonFields(actual.getClass(), expected.getClass());
    Map<String, Field> map1 =
        commonFieldsInfo.f1.entrySet().stream()
            .filter(e -> fields.contains(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    Map<String, Field> map2 =
        commonFieldsInfo.f2.entrySet().stream()
            .filter(e -> fields.contains(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    return objectCommonFieldsEquals(
        Tuple3.of(fields, map1, map2), actual, expected, throwExceptionWhenUnEqual);
  }

  public static boolean objectCommonFieldsEquals(Object actual, Object expected) {
    return objectCommonFieldsEquals(actual, expected, true);
  }

  public static boolean objectCommonFieldsEquals(
      Object actual, Object expected, boolean throwExceptionWhenUnEqual) {
    Tuple3<Set<String>, Map<String, Field>, Map<String, Field>> commonFieldsInfo =
        getCommonFields(actual.getClass(), expected.getClass());
    return objectCommonFieldsEquals(commonFieldsInfo, actual, expected, throwExceptionWhenUnEqual);
  }

  private static boolean objectCommonFieldsEquals(
      Tuple3<Set<String>, Map<String, Field>, Map<String, Field>> commonFieldsInfo,
      Object actual,
      Object expected,
      boolean throwExceptionWhenUnEqual) {

    for (String commonField : commonFieldsInfo.f0) {
      Field field1 = Objects.requireNonNull(commonFieldsInfo.f1.get(commonField));
      Field field2 = Objects.requireNonNull(commonFieldsInfo.f2.get(commonField));
      FieldAccessor accessor1 = FieldAccessor.createAccessor(field1);
      FieldAccessor accessor2 = FieldAccessor.createAccessor(field2);
      Object actualValue = accessor1.get(actual);
      Object expectedValue = accessor2.get(expected);
      if (actualValue == null) {
        if (expectedValue != null) {
          if (throwExceptionWhenUnEqual) {
            throw new AssertionError(
                String.format(
                    "Field '%s' mismatch: expected '%s' (type: %s), got null",
                    commonField, expectedValue, expectedValue.getClass().getName()));
          }
          return false;
        }
      } else {
        if (field1.getType().isArray()) {
          boolean arrayEquals = checkArrayEquals(field1.getType(), actualValue, expectedValue);
          if (!arrayEquals) {
            if (throwExceptionWhenUnEqual) {
              throw new AssertionError(
                  String.format(
                      "Field '%s' array mismatch: expected '%s' (type: %s), got '%s' (type: %s)",
                      commonField,
                      arrayToString(expectedValue),
                      expectedValue == null ? "null" : expectedValue.getClass().getName(),
                      arrayToString(actualValue),
                      actualValue.getClass().getName()));
            }
            return false;
          }
        } else {
          if (!actualValue.equals(expectedValue)) {
            if (throwExceptionWhenUnEqual) {
              throw new AssertionError(
                  String.format(
                      "Field '%s' mismatch: expected '%s' (type: %s), got '%s' (type: %s)",
                      commonField,
                      expectedValue,
                      expectedValue == null ? "null" : expectedValue.getClass().getName(),
                      actualValue,
                      actualValue.getClass().getName()));
            }
            return false;
          }
        }
      }
    }
    return true;
  }

  private static boolean checkArrayEquals(Class<?> arrayType, Object f1, Object f2) {
    if (arrayType == boolean[].class) {
      return Arrays.equals((boolean[]) f1, (boolean[]) f2);
    } else if (arrayType == byte[].class) {
      return Arrays.equals((byte[]) f1, (byte[]) f2);
    } else if (arrayType == short[].class) {
      return Arrays.equals((short[]) f1, (short[]) f2);
    } else if (arrayType == char[].class) {
      return Arrays.equals((char[]) f1, (char[]) f2);
    } else if (arrayType == int[].class) {
      return Arrays.equals((int[]) f1, (int[]) f2);
    } else if (arrayType == long[].class) {
      return Arrays.equals((long[]) f1, (long[]) f2);
    } else if (arrayType == float[].class) {
      return Arrays.equals((float[]) f1, (float[]) f2);
    } else if (arrayType == double[].class) {
      return Arrays.equals((double[]) f1, (double[]) f2);
    } else {
      return Arrays.deepEquals((Object[]) f1, (Object[]) f2);
    }
  }

  private static String arrayToString(Object arr) {
    if (arr == null) {
      return "null";
    }
    Class<?> arrayType = arr.getClass();
    if (arrayType == boolean[].class) {
      return Arrays.toString((boolean[]) arr);
    } else if (arrayType == byte[].class) {
      return Arrays.toString((byte[]) arr);
    } else if (arrayType == short[].class) {
      return Arrays.toString((short[]) arr);
    } else if (arrayType == char[].class) {
      return Arrays.toString((char[]) arr);
    } else if (arrayType == int[].class) {
      return Arrays.toString((int[]) arr);
    } else if (arrayType == long[].class) {
      return Arrays.toString((long[]) arr);
    } else if (arrayType == float[].class) {
      return Arrays.toString((float[]) arr);
    } else if (arrayType == double[].class) {
      return Arrays.toString((double[]) arr);
    } else {
      return Arrays.deepToString((Object[]) arr);
    }
  }

  public static Tuple3<Set<String>, Map<String, Field>, Map<String, Field>> getCommonFields(
      Class<?> cls1, Class<?> cls2) {
    List<Field> fields1 = ReflectionUtils.getFields(cls1, true);
    List<Field> fields2 = ReflectionUtils.getFields(cls2, true);
    return getCommonFields(fields1, fields2);
  }

  public static Tuple3<Set<String>, Map<String, Field>, Map<String, Field>> getCommonFields(
      List<Field> fields1, List<Field> fields2) {
    Map<String, Field> fieldMap1 =
        fields1.stream()
            .collect(
                Collectors.toMap(
                    // don't use `getGenericType` since janino doesn't support generics.
                    f -> f.getDeclaringClass().getSimpleName() + f.getName(),
                    f -> f));
    Map<String, Field> fieldMap2 =
        fields2.stream()
            .collect(
                Collectors.toMap(f -> f.getDeclaringClass().getSimpleName() + f.getName(), f -> f));
    Set<String> commonFields = new HashSet<>(fieldMap1.keySet());
    commonFields.retainAll(fieldMap2.keySet());
    return Tuple3.of(commonFields, fieldMap1, fieldMap2);
  }

  /**
   * Convert an object to a Map using Fory's field descriptors. The map uses qualified field names
   * (className.fieldName) as keys to match NonexistentClass format.
   *
   * @param fory the Fory instance
   * @param obj the object to convert
   * @return a map of qualified field names to field values
   */
  public static Map<String, Object> objectToMap(Fory fory, Object obj) {
    Class<?> cls = obj.getClass();
    ClassDef classDef = fory.getClassResolver().getTypeDef(cls, true);
    List<Descriptor> descriptors = classDef.getDescriptors(fory.getTypeResolver(), cls);
    Map<String, Object> result = new LinkedHashMap<>();
    for (Descriptor descriptor : descriptors) {
      Field field = descriptor.getField();
      if (field != null) {
        FieldAccessor accessor = FieldAccessor.createAccessor(field);
        Object value = accessor.get(obj);
        // Use qualified field name format: className.fieldName
        String qualifiedName = descriptor.getDeclaringClass() + "." + descriptor.getName();
        result.put(qualifiedName, value);
      }
    }
    return result;
  }
}
