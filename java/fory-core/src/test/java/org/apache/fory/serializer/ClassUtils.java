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

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.CollectionFields;
import org.apache.fory.test.bean.MapFields;
import org.apache.fory.test.bean.Struct;
import org.apache.fory.util.ClassLoaderUtils;
import org.testng.Assert;

public class ClassUtils {
  public static Class<?> createCompatibleClass1() {
    String pkg = BeanA.class.getPackage().getName();
    String code =
        ""
            + "package "
            + pkg
            + ";\n"
            + "import java.util.*;\n"
            + "import java.math.*;\n"
            + "public class BeanA {\n"
            + "  private Float f4;\n"
            + "  private double f5;\n"
            + "  private BeanB beanB;\n"
            + "  private BeanB beanB_added;\n"
            + "  private int[] intArray;\n"
            + "  private int[] intArray_added;\n"
            + "  private byte[] bytes;\n"
            + "  private transient BeanB f13;\n"
            + "  public BigDecimal f16;\n"
            + "  public String f17;\n"
            + "  public String longStringNameField_added;\n"
            + "  private List<Double> doubleList;\n"
            + "  private Iterable<BeanB> beanBIterable;\n"
            + "  private List<BeanB> beanBList;\n"
            + "  private List<BeanB> beanBList_added;\n"
            + "  private Map<String, BeanB> stringBeanBMap;\n"
            + "  private Map<String, String> stringStringMap_added;\n"
            + "  private int[][] int2DArray;\n"
            + "  private int[][] int2DArray_added;\n"
            + "}";
    return loadClass(BeanA.class, code, ClassUtils.class + "createCompatibleClass1");
  }

  public static Class<?> createCompatibleClass2() {
    String pkg = CollectionFields.class.getPackage().getName();
    String code =
        ""
            + "package "
            + pkg
            + ";\n"
            + "import java.util.*;\n"
            + "public class CollectionFields {\n"
            + "  public Collection<Integer> collection2;\n"
            + "  public List<Integer> collection3;\n"
            + "  public Collection<String> randomAccessList2;\n"
            + "  public List<String> randomAccessList3;\n"
            + "  public Collection list;\n"
            + "  public Collection<String> list2;\n"
            + "  public List<String> list3;\n"
            + "  public Collection<String> set2;\n"
            + "  public Set<String> set3;\n"
            + "  public Collection<String> sortedSet2;\n"
            + "  public SortedSet<String> sortedSet3;\n"
            + "  public Map map;\n"
            + "  public Map<String, String> map2;\n"
            + "  public SortedMap<Integer, Integer> sortedMap3;"
            + "}";
    return loadClass(CollectionFields.class, code, ClassUtils.class + "createCompatibleClass2");
  }

  public static Class<?> createCompatibleClass3() {
    String pkg = MapFields.class.getPackage().getName();
    String code =
        ""
            + "package "
            + pkg
            + ";\n"
            + "import java.util.*;\n"
            + "import java.util.concurrent.*;\n"
            + "public class MapFields {\n"
            + " public Map map;\n"
            + "  public Map<String, Integer> map2;\n"
            + "  public Map<String, Integer> map3;\n"
            + "  public Map linkedHashMap;\n"
            + "  public LinkedHashMap<String, Integer> linkedHashMap3;\n"
            + "  public LinkedHashMap<String, Integer> linkedHashMap4;\n"
            + "  public SortedMap sortedMap;\n"
            + "  public SortedMap<String, Integer> sortedMap2;\n"
            + "  public Map concurrentHashMap;\n"
            + "  public ConcurrentHashMap<String, Integer> concurrentHashMap2;\n"
            + "  public ConcurrentSkipListMap skipListMap2;\n"
            + "  public ConcurrentSkipListMap<String, Integer> skipListMap3;\n"
            + "  public ConcurrentSkipListMap<String, Integer> skipListMap4;\n"
            + "  public EnumMap enumMap2;\n"
            + "  public Map emptyMap;\n"
            + "  public Map singletonMap;\n"
            + "  public Map<String, Integer> singletonMap2;\n"
            + "}";
    return loadClass(MapFields.class, code, ClassUtils.class + "createCompatibleClass3");
  }

  static Class<?> loadClass(Class<?> cls, String code, Object cacheKey) {
    return Struct.loadClass(cacheKey, () -> compileClass(cls, code));
  }

  private static Class<?> compileClass(Class<?> cls, String code) {
    String pkg = ReflectionUtils.getPackage(cls);
    Path path = Paths.get(pkg.replace(".", "/") + "/" + cls.getSimpleName() + ".java");
    try {
      Files.deleteIfExists(path);
      path.getParent().toFile().mkdirs();
      Files.write(path, code.getBytes());
      // Use JavaCompiler because janino doesn't support generics.
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      int result =
          compiler.run(
              null,
              new ByteArrayOutputStream(), // ignore output
              System.err,
              "-classpath",
              System.getProperty("java.class.path"),
              path.toString());
      if (result != 0) {
        throw new RuntimeException(String.format("Couldn't compile code:\n %s.", code));
      }
      Class<?> clz =
          new ClassLoaderUtils.ChildFirstURLClassLoader(
                  new URL[] {Paths.get(".").toUri().toURL()}, Struct.class.getClassLoader())
              .loadClass(cls.getName());
      Files.deleteIfExists(path);
      Files.deleteIfExists(Paths.get(pkg.replace(".", "/") + "/" + cls.getSimpleName() + ".class"));
      Assert.assertNotEquals(clz, cls);
      return clz;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  static Class<?> loadClass(String pkg, String className, String code) {
    Path path = Paths.get(pkg.replace(".", "/") + "/" + className + ".java");
    try {
      Files.deleteIfExists(path);
      path.getParent().toFile().mkdirs();
      Files.write(path, code.getBytes());
      // Use JavaCompiler because janino doesn't support generics.
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      int result =
          compiler.run(
              null,
              new ByteArrayOutputStream(), // ignore output
              System.err,
              "-classpath",
              System.getProperty("java.class.path"),
              path.toString());
      if (result != 0) {
        throw new RuntimeException(String.format("Couldn't compile code:\n %s.", code));
      }
      Class<?> clz =
          new ClassLoaderUtils.ChildFirstURLClassLoader(
                  new URL[] {Paths.get(".").toUri().toURL()}, Struct.class.getClassLoader())
              .loadClass(pkg + "." + className);
      Files.deleteIfExists(path);
      Files.deleteIfExists(Paths.get(pkg.replace(".", "/") + "/" + className + ".class"));
      return clz;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
