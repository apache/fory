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

package org.apache.fory.benchmark.data;

import java.io.Serializable;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.Objects;
import java.util.Random;
import org.apache.fory.util.ClassLoaderUtils;
import org.apache.fory.util.Preconditions;
import org.codehaus.janino.SimpleCompiler;

public class Struct implements Serializable {

  public static String toString(Object o) {
    StringBuilder builder = new StringBuilder(o.getClass().getSimpleName() + "(");
    try {
      for (int i = 0; i < o.getClass().getDeclaredFields().length; i++) {
        Field field = o.getClass().getDeclaredFields()[i];
        field.setAccessible(true);
        builder.append(field.getName());
        builder.append("=");
        builder.append(field.get(o).toString());
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
        if (field.get(o1) == null) {
          if (field.get(o2) != null) {
            return false;
          } else {
            continue;
          }
        }
        if (!field.get(o1).equals(field.get(o2))) {
          return false;
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return true;
  }

  public static int calcHashCode(Object o) {
    Object[] fields = new Object[o.getClass().getDeclaredFields().length];
    try {
      for (int i = 0; i < o.getClass().getDeclaredFields().length; i++) {
        Field field = o.getClass().getDeclaredFields()[i];
        field.setAccessible(true);
        fields[i] = field.get(o);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return Objects.hash(fields);
  }

  public static void main(String[] args) {
    System.out.println(create(false));
    System.out.println(create(true));
    Class<?> structClass1 = createStructClass(100, false);
    Class<?> structClass2 = createStructClass(100, true);
    Preconditions.checkArgument(createPOJO(structClass1).equals(createPOJO(structClass1)));
    Preconditions.checkArgument(createPOJO(structClass2).equals(createPOJO(structClass2)));
  }

  public static Object create(boolean boxed) {
    try {
      return createPOJO(Struct.createStructClass(100, boxed));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static Object createPOJO(Class<?> clz) {
    try {
      Object o = clz.newInstance();
      Random random = new Random(17);
      for (Field field : o.getClass().getDeclaredFields()) {
        field.setAccessible(true);
        if (field.getType() == int.class || field.getType() == Integer.class) {
          field.set(o, random.nextInt());
        } else if (field.getType() == long.class || field.getType() == Long.class) {
          field.set(o, random.nextLong());

        } else if (field.getType() == float.class || field.getType() == Float.class) {
          field.set(o, random.nextFloat());

        } else if (field.getType() == double.class || field.getType() == Double.class) {
          field.set(o, random.nextDouble());
        }
      }
      return o;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static Class<?> createStructClass(int numFields, boolean boxed) {
    // String classname =
    //     String.format(
    //         "Struct%s", LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd_HHmmss")));
    String classname = "Struct";
    StringBuilder classCode =
        new StringBuilder(
            String.format(
                ""
                    + "package demo.fory.pkg1;\n"
                    + "public final class %s implements java.io.Serializable {\n"
                    + "  public String toString() {\n"
                    + "   return org.apache.fory.benchmark.data.Struct.toString(this);\n"
                    + "  }\n"
                    + "  public boolean equals(Object o) {\n"
                    + "   return org.apache.fory.benchmark.data.Struct.equalsWith(this, o);\n"
                    + "  }\n"
                    + "  public int hashCode() {\n"
                    + "   return org.apache.fory.benchmark.data.Struct.calcHashCode(this);\n"
                    + "  }\n",
                classname));

    String fields =
        ""
            + "  public int f%s;\n"
            + "  public long f%s;\n"
            + "  public float f%s;\n"
            + "  public double f%s;\n";
    if (boxed) {
      fields =
          ""
              + "  public Integer f%s;\n"
              + "  public Long f%s;\n"
              + "  public Float f%s;\n"
              + "  public Double f%s;\n";
    }
    for (int i = 0; i < numFields / 4 + 1; i++) {
      classCode.append(String.format(fields, i * 4, i * 4 + 1, i * 4 + 2, i * 4 + 3));
    }
    classCode.append("}");
    SimpleCompiler compiler = new SimpleCompiler();
    compiler.setParentClassLoader(
        new ClassLoaderUtils.ChildFirstURLClassLoader(new URL[0], Struct.class.getClassLoader()));
    try {
      compiler.cook(new StringReader(classCode.toString()));
      ClassLoader classLoader = compiler.getClassLoader();
      Thread.currentThread().setContextClassLoader(classLoader);
      return classLoader.loadClass("demo.fory.pkg1." + classname);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
