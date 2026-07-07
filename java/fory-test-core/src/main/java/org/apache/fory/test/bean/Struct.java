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

package org.apache.fory.test.bean;

import java.io.Serializable;
import java.lang.ref.SoftReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.apache.commons.lang3.StringUtils;

/**
 * Util class to create a struct with specified type fields dynamically. Compared to class compiled
 * by janino, the created class by this util can have generic type fields.
 */
public final class Struct implements Serializable {

  /** Return object string repr. */
  public static String toString(Object o) {
    StringBuilder builder = new StringBuilder(o.getClass().getSimpleName() + "(");
    try {
      for (int i = 0; i < o.getClass().getDeclaredFields().length; i++) {
        Field field = o.getClass().getDeclaredFields()[i];
        field.setAccessible(true);
        builder.append(field.getName());
        builder.append("=");
        if (field.get(o) != null) {
          builder.append(field.get(o).toString());
        } else {
          builder.append("null");
        }
        if (i != o.getClass().getDeclaredFields().length) {
          builder.append(", ");
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    builder.append(")");
    return builder.toString();
  }

  /** Return whether tow objects equals. */
  public static boolean equalsWith(Object o1, Object o2) {
    if (o1 == o2) {
      return true;
    }
    if (o1.getClass() != o2.getClass()) {
      return false;
    }

    try {
      for (Field field : o1.getClass().getDeclaredFields()) {
        field.setAccessible(true);
        Object v1 = field.get(o1);
        Object v2 = field.get(o2);
        if (v1 == null) {
          if (v2 != null) {
            return false;
          } else {
            continue;
          }
        }
        if (v1.getClass().isArray()) {
          if (v1.getClass().getComponentType().isPrimitive()) {
            if (Array.getLength(v1) != Array.getLength(v2)) {
              return false;
            } else {
              for (int i = 0; i < Array.getLength(v1); i++) {
                if (!Array.get(v1, i).equals(Array.get(v2, i))) {
                  return false;
                }
              }
            }
          } else {
            if (!Arrays.deepEquals(new Object[] {v1}, new Object[] {v2})) {
              return false;
            }
          }
        } else {
          if (!v1.equals(v2)) {
            return false;
          }
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return true;
  }

  /** Create Object. */
  public static Object create(String classname, int repeat) {
    try {
      return createPOJO(Struct.createStructClass(classname, repeat));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Create Object. */
  public static Object createPOJO(Class<?> clz) {
    Random random = new Random(17);
    try {
      Object o = clz.newInstance();
      for (Field field : o.getClass().getDeclaredFields()) {
        field.setAccessible(true);
        if (field.getType() == boolean.class || field.getType() == Boolean.class) {
          field.set(o, random.nextBoolean());
        } else if (field.getType() == byte.class || field.getType() == Byte.class) {
          field.set(o, (byte) random.nextInt());
        } else if (field.getType() == short.class || field.getType() == Short.class) {
          field.set(o, (short) random.nextInt());
        } else if (field.getType() == int.class || field.getType() == Integer.class) {
          field.set(o, random.nextInt());
        } else if (field.getType() == long.class || field.getType() == Long.class) {
          field.set(o, random.nextLong());
        } else if (field.getType() == float.class || field.getType() == Float.class) {
          field.set(o, random.nextFloat());
        } else if (field.getType() == double.class || field.getType() == Double.class) {
          field.set(o, random.nextDouble());
        } else if (field.getType() == int[].class) {
          field.set(o, random.ints().limit(100).toArray());
        } else if (field.getType() == String.class) {
          field.set(o, "abc");
        } else if (field.getType() == Double[].class) {
          field.set(o, random.doubles().limit(100).boxed().toArray(Double[]::new));
        } else if (field.getName().startsWith("list_str")) {
          List<String> list = new ArrayList<>();
          for (int i = 0; i < 10; i++) {
            list.add("str");
          }
          field.set(o, list);
        } else if (field.getName().startsWith("list_int")) {
          field.set(o, random.ints().limit(100).boxed().collect(Collectors.toList()));
        } else if (field.getName().startsWith("map_ss")) {
          Map<String, String> map = new HashMap<>();
          for (int i = 0; i < 10; i++) {
            map.put("k" + i, "v" + i);
          }
          field.set(o, map);
        } else if (field.getName().startsWith("map_ld")) {
          Map<Long, Double> map = new HashMap<>();
          for (int i = 0; i < 10; i++) {
            map.put((long) i, (double) i);
          }
          field.set(o, map);
        }
      }
      return o;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static final ConcurrentHashMap<Object, Object> cacheLock = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<Object, SoftReference<Class<?>>> classCache =
      new ConcurrentHashMap<>();

  public static Class<?> loadClass(Object key, Supplier<Class<?>> func) {
    return loadClass(key, true, func);
  }

  public static Class<?> loadClass(Object key, boolean cache, Supplier<Class<?>> func) {
    if (!cache) {
      return func.get();
    }
    Object lock = cacheLock.computeIfAbsent(key, k -> new Object());
    synchronized (lock) {
      SoftReference<Class<?>> ref = classCache.get(key);
      if (ref != null) {
        Class<?> cls = ref.get();
        if (cls != null) {
          return cls;
        }
      }
      Class<?> cls = func.get();
      classCache.put(key, new SoftReference<>(cls));
      return cls;
    }
  }

  /** Create class. */
  public static Class<?> createNumberStructClass(String classname, int repeat) {
    return createNumberStructClass(classname, repeat, true);
  }

  public static Class<?> createNumberStructClass(String classname, int repeat, boolean cache) {
    if (StringUtils.isBlank(classname)) {
      throw new IllegalArgumentException("Class name is empty");
    }
    String key = "createNumberStructClass" + classname + repeat;
    return loadClass(
        key,
        cache,
        () -> {
          StringBuilder classCode =
              new StringBuilder(
                  String.format(
                      ""
                          + "import java.util.*;\n"
                          + "public final class %s implements java.io.Serializable {\n"
                          + "  public String toString() {\n"
                          + "   return "
                          + Struct.class.getName()
                          + ".toString(this);\n"
                          + "  }\n"
                          + "  public boolean equals(Object o) {\n"
                          + "   return "
                          + Struct.class.getName()
                          + ".equalsWith(this, o);\n"
                          + "  }\n",
                      classname));

          String fields =
              ""
                  + "  public boolean f%s;\n"
                  + "  public byte f%s;\n"
                  + "  public short f%s;\n"
                  + "  public int f%s;\n"
                  + "  public int f%s;\n"
                  + "  public long f%s;\n"
                  + "  public long f%s;\n"
                  + "  public float f%s;\n"
                  + "  public double f%s;\n"
                  + "  public Integer f%s;\n";
          int numFields = 10;
          for (int i = 0; i < repeat; i++) {
            classCode.append(
                String.format(
                    fields,
                    IntStream.range(i * numFields, i * numFields + numFields).boxed().toArray()));
          }
          classCode.append("}");
          return compile(classname, classCode.toString());
        });
  }

  /** Create Class. */
  public static Class<?> createStructClass(String classname, int repeat) {
    return createStructClass(classname, repeat, true);
  }

  public static Class<?> createStructClass(String classname, int repeat, boolean cache) {
    if (StringUtils.isBlank(classname)) {
      throw new IllegalArgumentException("Class name is empty");
    }
    String key = "createStructClass" + classname + repeat;
    return loadClass(
        key,
        cache,
        () -> {
          StringBuilder classCode =
              new StringBuilder(
                  String.format(
                      ""
                          + "import java.util.*;\n"
                          + "public final class %s implements java.io.Serializable {\n"
                          + "  public String toString() {\n"
                          + "   return "
                          + Struct.class.getName()
                          + ".toString(this);\n"
                          + "  }\n"
                          + "  public boolean equals(Object o) {\n"
                          + "   return "
                          + Struct.class.getName()
                          + ".equalsWith(this, o);\n"
                          + "  }\n",
                      classname));

          String fields =
              ""
                  + "  public byte f%s;\n"
                  + "  public int f%s;\n"
                  + "  public long f%s;\n"
                  + "  public Integer f%s;\n"
                  + "  public String f%s;\n"
                  + "  public int[] f%s;\n"
                  + "  public Double[] f%s;\n"
                  + "  public List<Integer> list_int%s;\n"
                  + "  public List<String> list_str%s;\n"
                  + "  public Map<String, String> map_ss%s;\n"
                  + "  public Map<Long, Double> map_ld%s;\n"
                  + "  public Object obj%s;\n";
          int numFields = 13;
          for (int i = 0; i < repeat; i++) {
            classCode.append(
                String.format(
                    fields,
                    IntStream.range(i * numFields, i * numFields + numFields).boxed().toArray()));
          }
          classCode.append("}");
          return compile(classname, classCode.toString());
        });
  }

  /** Create class. */
  public static Class<?> createStructClass(String classname, String classCode, Object cache) {
    if (cache == null) {
      return compile(classname, classCode);
    }
    return loadClass(cache, () -> compile(classname, classCode));
  }

  /** Create class. */
  private static Class<?> compile(String classname, String classCode) {
    Path path = Paths.get(classname + ".java");
    try {
      Files.deleteIfExists(path);
      Files.write(path, classCode.toString().getBytes());
      // Use JavaCompiler because janino doesn't support generics.
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      int result =
          compiler.run(
              null,
              System.out, // ignore output
              System.err, // ignore output
              "-classpath",
              System.getProperty("java.class.path"),
              path.toString());
      if (result != 0) {
        throw new RuntimeException(String.format("Couldn't compile code:\n %s.", classCode));
      }
      Class<?> clz =
          new URLClassLoader(
                  new URL[] {Paths.get(".").toUri().toURL()}, Struct.class.getClassLoader())
              .loadClass(classname);
      Files.delete(path);
      Files.delete(Paths.get(classname + ".class"));
      return clz;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
