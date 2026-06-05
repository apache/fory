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

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.internal.DefineClass;

/** ClassLoader utility for defining class and loading class by strategies. */
public class ClassLoaderUtils {
  private static final Logger LOG = LoggerFactory.getLogger(ClassLoaderUtils.class);

  // Derived from
  // https://github.com/apache/spark/blob/921fb289f003317d89120faa6937e4abd359195c/core/src/main/java/org/apache/spark/util/ParentClassLoader.java.
  /** A class loader which makes some protected methods in ClassLoader accessible. */
  public static class ParentClassLoader extends ClassLoader {
    static {
      ClassLoader.registerAsParallelCapable();
    }

    public ParentClassLoader(ClassLoader parent) {
      super(parent);
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
      return super.findClass(name);
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      return super.loadClass(name, resolve);
    }
  }

  // Derived from
  // https://github.com/apache/spark/blob/921fb289f003317d89120faa6937e4abd359195c/core/src/main/java/org/apache/spark/util/ChildFirstURLClassLoader.java.
  /**
   * A mutable class loader that gives preference to its own URLs over the parent class loader when
   * loading classes and resources.
   */
  public static class ChildFirstURLClassLoader extends URLClassLoader {

    static {
      registerAsParallelCapable();
    }

    private ParentClassLoader parent;

    public ChildFirstURLClassLoader(URL[] urls, ClassLoader parent) {
      super(urls, null);
      this.parent = new ParentClassLoader(parent);
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      try {
        return super.loadClass(name, resolve);
      } catch (ClassNotFoundException cnf) {
        return parent.loadClass(name, resolve);
      }
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
      ArrayList<URL> urls = Collections.list(super.getResources(name));
      urls.addAll(Collections.list(parent.getResources(name)));
      return Collections.enumeration(urls);
    }

    @Override
    public URL getResource(String name) {
      URL url = super.getResource(name);
      if (url != null) {
        return url;
      } else {
        return parent.getResource(name);
      }
    }

    @Override
    public void addURL(URL url) {
      super.addURL(url);
    }
  }

  /**
   * A class loader that gives preference to its contained classloaders over the parent class loader
   * when loading classes and resources.
   */
  public static class ComposedClassLoader extends URLClassLoader {

    static {
      ClassLoader.registerAsParallelCapable();
    }

    private final List<ParentClassLoader> parentClassloaders;

    public ComposedClassLoader(ClassLoader[] classLoaders) {
      super(new URL[] {}, null);
      this.parentClassloaders =
          Arrays.stream(classLoaders).map(ParentClassLoader::new).collect(Collectors.toList());
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      for (ParentClassLoader classLoader : parentClassloaders) {
        try {
          return classLoader.loadClass(name, resolve);
          // CHECKSTYLE.OFF:EmptyCatchBlock
        } catch (ClassNotFoundException ignored) {
        }
        // CHECKSTYLE.ON:EmptyCatchBlock
      }
      return super.loadClass(name, resolve);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
      ArrayList<URL> urls = new ArrayList<>();
      for (ParentClassLoader classLoader : parentClassloaders) {
        urls.addAll(Collections.list(classLoader.getResources(name)));
      }
      urls.addAll(Collections.list(super.getResources(name)));
      return Collections.enumeration(urls);
    }

    @Override
    public URL getResource(String name) {
      for (ParentClassLoader classLoader : parentClassloaders) {
        URL url = classLoader.getResource(name);
        if (url != null) {
          return url;
        }
      }
      return super.getResource(name);
    }
  }

  /** A classloader to load Fory jar classes only. */
  public static class ForyJarClassLoader extends URLClassLoader {
    static {
      ClassLoader.registerAsParallelCapable();
    }

    private static final ParentClassLoader LOADER =
        new ParentClassLoader(Fory.class.getClassLoader());
    private static final ForyJarClassLoader FORY_JAR_LOADER = new ForyJarClassLoader();

    private ForyJarClassLoader() {
      super(new URL[0]);
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.startsWith(Fory.class.getPackage().getName()) && !name.contains("test")) {
        return LOADER.loadClass(name, resolve);
      }
      return null;
    }

    public static ForyJarClassLoader getInstance() {
      return FORY_JAR_LOADER;
    }
  }

  /**
   * A parallel loadable {@link ClassLoader} which make defineClass public for using in JDK17+.
   * {@link MethodHandle} can also be used for access `defineClass`.
   */
  public static class ByteArrayClassLoader extends ClassLoader {

    static {
      // support parallel load multiple classes.
      ClassLoader.registerAsParallelCapable();
    }

    private final boolean childFirst;
    private final ParentClassLoader parent;
    // className-or-classFileName -> data
    private final Map<String, byte[]> classes;

    public ByteArrayClassLoader(Map<String, byte[]> classes) {
      this(classes, ByteArrayClassLoader.class.getClassLoader(), false);
    }

    public ByteArrayClassLoader(Map<String, byte[]> classes, ClassLoader parent) {
      this(classes, parent, false);
    }

    public ByteArrayClassLoader(
        Map<String, byte[]> classes, ClassLoader parent, boolean childFirst) {
      super(checkRuntimeBytecodeLoadingSupported(childFirst ? null : parent));
      this.childFirst = childFirst;
      this.parent = new ParentClassLoader(parent);
      this.classes = new ConcurrentHashMap<>(classes);
    }

    private static ClassLoader checkRuntimeBytecodeLoadingSupported(ClassLoader parent) {
      if (AndroidSupport.IS_ANDROID) {
        throw new UnsupportedOperationException(
            "Runtime bytecode loading is unsupported on Android.");
      }
      return parent;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      byte[] bytecodes = this.classes.get(name);
      if (bytecodes == null) {
        bytecodes = this.classes.get(name.replace('.', '/') + ".class");
        if (bytecodes == null) {
          throw new ClassNotFoundException(name);
        }
      }
      return super.defineClass(
          name, bytecodes, 0, bytecodes.length, this.getClass().getProtectionDomain());
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (childFirst) {
        try {
          return super.loadClass(name, resolve);
        } catch (ClassNotFoundException cnf) {
          return Objects.requireNonNull(parent).loadClass(name, resolve);
        }
      } else {
        return super.loadClass(name, resolve);
      }
    }

    public Class<?> defineClassPublic(String name, byte[] b) {
      return defineClassPublic(name, b, getClass().getProtectionDomain());
    }

    public Class<?> defineClassPublic(String name, byte[] b, ProtectionDomain protectionDomain)
        throws ClassFormatError {
      if (AndroidSupport.IS_ANDROID) {
        throw new UnsupportedOperationException(
            "Runtime bytecode loading is unsupported on Android.");
      }
      return super.defineClass(name, b, 0, b.length, protectionDomain);
    }
  }

  public static Class<?> tryDefineClassesInClassLoader(
      String className, Class<?> neighbor, ClassLoader classLoader, byte[] bytecode) {
    ProtectionDomain domain =
        neighbor != null
            ? neighbor.getProtectionDomain()
            : classLoader.getClass().getProtectionDomain();
    return tryDefineClassesInClassLoader(className, neighbor, classLoader, domain, bytecode);
  }

  public static Class<?> tryDefineClassesInClassLoader(
      String className,
      Class<?> neighbor,
      ClassLoader classLoader,
      ProtectionDomain domain,
      byte[] bytecode) {
    try {
      if (classLoader instanceof ByteArrayClassLoader) {
        return ((ByteArrayClassLoader) classLoader).defineClassPublic(className, bytecode, domain);
      }
      return DefineClass.defineClass(className, neighbor, classLoader, domain, bytecode);
    } catch (Exception | LinkageError e) {
      return null;
    }
  }
}
