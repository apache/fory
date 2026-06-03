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

package org.apache.fory.builder;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.Fory;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.collection.Tuple3;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.util.ClassLoaderUtils;
import org.apache.fory.util.Preconditions;

/** Codec util to create and load jit serializer class. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class CodecUtils {
  // Cache key includes configHash to distinguish between xlang and non-xlang modes
  private static final ConcurrentHashMap<Tuple3<String, Class<?>, Integer>, Class>
      graalvmSerializers = new ConcurrentHashMap<>();

  // TODO(chaokunyang) how to uninstall org.apache.fory.codegen/builder classes for graalvm build
  // time
  //  maybe use a temporal URLClassLoader
  public static <T> Class<? extends Serializer> loadOrGenObjectCodecClass(Class<T> cls, Fory fory) {
    Preconditions.checkNotNull(fory);
    return loadSerializer(
        "loadOrGenObjectCodecClass",
        cls,
        fory,
        () -> loadOrGenCodecClass(cls, fory, new ObjectCodecBuilder(cls, fory)));
  }

  public static <T> Class<? extends Serializer> loadOrGenCompatibleCodecClass(
      Fory fory, Class<T> cls, TypeDef typeDef) {
    Preconditions.checkNotNull(fory);
    return loadSerializer(
        "loadOrGenCompatibleCodecClass",
        cls,
        fory,
        () ->
            loadOrGenCodecClass(
                cls, fory, new CompatibleCodecBuilder(TypeRef.of(cls), fory, typeDef)));
  }

  public static <T> Class<? extends Serializer> loadOrGenCompatibleCodecClass(
      TypeResolver typeResolver, Class<T> cls, TypeDef typeDef) {
    return typeResolver
        .getJITContext()
        .asyncVisitFory(f -> loadOrGenCompatibleCodecClass(f, cls, typeDef));
  }

  public static <T> Class<? extends Serializer> loadOrGenStaticCompatibleCodecClass(
      Fory fory, Class<T> cls, TypeDef typeDef) {
    Preconditions.checkNotNull(fory);
    return loadSerializer(
        "loadOrGenStaticCompatibleCodecClass",
        cls,
        fory,
        () ->
            loadOrGenCodecClass(
                cls, fory, new StaticCompatibleCodecBuilder(TypeRef.of(cls), fory, typeDef)));
  }

  public static <T> Class<? extends Serializer> loadOrGenStaticCompatibleCodecClass(
      TypeResolver typeResolver, Class<T> cls, TypeDef typeDef) {
    return typeResolver
        .getJITContext()
        .asyncVisitFory(f -> loadOrGenStaticCompatibleCodecClass(f, cls, typeDef));
  }

  /**
   * Load or generate a JIT serializer class for single-layer compatible serialization.
   *
   * @param cls the target class
   * @param fory the Fory instance
   * @param layerTypeDef the TypeDef for this layer only
   * @param layerMarkerClass the marker class for this layer
   * @return the generated serializer class
   */
  public static <T> Class<? extends Serializer> loadOrGenCompatibleLayerCodecClass(
      Class<T> cls, Fory fory, TypeDef layerTypeDef, Class<?> layerMarkerClass) {
    Preconditions.checkNotNull(fory);
    return loadSerializer(
        "loadOrGenCompatibleLayerCodecClass",
        cls,
        fory,
        () ->
            loadOrGenCodecClass(
                cls,
                fory,
                new CompatibleLayerCodecBuilder(
                    TypeRef.of(cls), fory, layerTypeDef, layerMarkerClass)));
  }

  @SuppressWarnings("unchecked")
  static <T> Class<? extends Serializer> loadOrGenCodecClass(
      Class<T> beanClass, Fory fory, BaseObjectCodecBuilder codecBuilder) {
    CodeGenerator codeGenerator;
    ClassLoader beanClassClassLoader =
        beanClass.getClassLoader() == null
            ? Thread.currentThread().getContextClassLoader()
            : beanClass.getClassLoader();
    if (beanClassClassLoader == null) {
      beanClassClassLoader = fory.getClass().getClassLoader();
    }
    TypeResolver typeResolver = fory.getTypeResolver();
    codeGenerator = getCodeGenerator(beanClassClassLoader, typeResolver);
    Class<?> neighborClass = codecNeighbor(beanClass, beanClassClassLoader);
    codecBuilder.setSamePackageAccess(neighborClass != null);
    // use genCodeFunc to avoid gen code repeatedly
    CompileUnit compileUnit =
        new CompileUnit(
            CodeGenerator.getPackage(beanClass),
            codecBuilder.codecClassName(beanClass),
            codecBuilder::genCode,
            neighborClass);
    return (Class<? extends Serializer>)
        codeGenerator.compileAndLoad(compileUnit, compileState -> compileState.lock.lock());
  }

  private static Class<?> codecNeighbor(Class<?> beanClass, ClassLoader beanClassClassLoader) {
    // Hidden generated serializers are only a JDK25+ path. JDK8-24 keeps the existing unsafe-backed
    // field/object access strategy, so broadening this to Java15+ adds complexity without removing
    // unsafe from those runtimes.
    if (AndroidSupport.IS_ANDROID
        || JdkVersion.MAJOR_VERSION < 25
        || beanClass.getClassLoader() == null) {
      return null;
    }
    if (!CodeGenerator.getPackage(beanClass).equals(ReflectionUtils.getPackage(beanClass))) {
      return null;
    }
    try {
      // A generated serializer defined in the bean loader must resolve Fory runtime classes there.
      if (beanClassClassLoader.loadClass(Fory.class.getName()) == Fory.class) {
        return beanClass;
      }
    } catch (ClassNotFoundException e) {
      // The composed-loader path remains the owner when the bean loader cannot see Fory directly.
    }
    return null;
  }

  private static CodeGenerator getCodeGenerator(
      ClassLoader beanClassClassLoader, TypeResolver typeResolver) {
    CodeGenerator codeGenerator;
    try {
      // generated code imported fory classes.
      if (beanClassClassLoader.loadClass(Fory.class.getName()) != Fory.class) {
        throw new ClassNotFoundException();
      }
      codeGenerator =
          typeResolver.getOrCreateCodeGenerator(
              new ClassLoader[] {beanClassClassLoader},
              loaders -> CodeGenerator.getSharedCodeGenerator(loaders[0]));
    } catch (ClassNotFoundException e) {
      ClassLoader[] loaders = {beanClassClassLoader, Fory.class.getClassLoader()};
      codeGenerator =
          typeResolver.getOrCreateCodeGenerator(
              loaders,
              unused ->
                  CodeGenerator.getSharedCodeGenerator(
                      ClassLoaderUtils.ForyJarClassLoader.getInstance(), beanClassClassLoader));
    }
    return codeGenerator;
  }

  private static <T> Class<? extends Serializer> loadSerializer(
      String name, Class<?> cls, Fory fory, Callable<Class<? extends Serializer>> func) {
    int configHash = fory.getConfig().getConfigHash();
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      Tuple3<String, Class<?>, Integer> key = Tuple3.of(name, cls, configHash);
      Class serializerClass = graalvmSerializers.get(key);
      if (serializerClass != null) {
        return serializerClass;
      }
    }
    try {
      Class serializerClass = func.call();
      if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
        graalvmSerializers.putIfAbsent(Tuple3.of(name, cls, configHash), serializerClass);
      }
      return serializerClass;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
