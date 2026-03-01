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

import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.Fory;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.collection.Tuple3;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.util.ClassLoaderUtils;
import org.apache.fory.util.GraalvmSupport;
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
  public static <T> Class<? extends Serializer<T>> loadOrGenObjectCodecClass(
      Class<T> cls, Fory fory) {
    Preconditions.checkNotNull(fory);
    return loadSerializer(
        "loadOrGenObjectCodecClass",
        cls,
        fory,
        () -> loadOrGenCodecClass(cls, fory, new ObjectCodecBuilder(cls, fory)));
  }

  public static <T> Class<? extends Serializer<T>> loadOrGenMetaSharedCodecClass(
      Fory fory, Class<T> cls, TypeDef typeDef) {
    Preconditions.checkNotNull(fory);
    return loadSerializer(
        "loadOrGenMetaSharedCodecClass",
        cls,
        fory,
        () ->
            loadOrGenCodecClass(
                cls, fory, new MetaSharedCodecBuilder(TypeRef.of(cls), fory, typeDef)));
  }

  /**
   * Load or generate a JIT serializer class for single-layer meta-shared serialization.
   *
   * @param cls the target class
   * @param fory the Fory instance
   * @param layerTypeDef the TypeDef for this layer only
   * @param layerMarkerClass the marker class for this layer
   * @return the generated serializer class
   */
  public static <T> Class<? extends Serializer<T>> loadOrGenMetaSharedLayerCodecClass(
      Class<T> cls, Fory fory, TypeDef layerTypeDef, Class<?> layerMarkerClass) {
    Preconditions.checkNotNull(fory);
    return loadSerializer(
        "loadOrGenMetaSharedLayerCodecClass",
        cls,
        fory,
        () ->
            loadOrGenCodecClass(
                cls,
                fory,
                new MetaSharedLayerCodecBuilder(
                    TypeRef.of(cls), fory, layerTypeDef, layerMarkerClass)));
  }

  @SuppressWarnings("unchecked")
  static <T> Class<? extends Serializer<T>> loadOrGenCodecClass(
      Class<T> beanClass, Fory fory, BaseObjectCodecBuilder codecBuilder) {
    // use genCodeFunc to avoid gen code repeatedly
    CompileUnit compileUnit =
        new CompileUnit(
            CodeGenerator.getPackage(beanClass),
            codecBuilder.codecClassName(beanClass),
            codecBuilder::genCode);
    CodeGenerator codeGenerator;
    ClassLoader beanClassClassLoader =
        beanClass.getClassLoader() == null
            ? Thread.currentThread().getContextClassLoader()
            : beanClass.getClassLoader();
    if (beanClassClassLoader == null) {
      beanClassClassLoader = fory.getClass().getClassLoader();
    }
    ClassResolver classResolver = fory.getClassResolver();
    codeGenerator = getCodeGenerator(fory, beanClassClassLoader, classResolver);
    ClassLoader classLoader =
        codeGenerator.compile(
            Collections.singletonList(compileUnit), compileState -> compileState.lock.lock());
    String className = codecBuilder.codecQualifiedClassName(beanClass);
    try {
      return (Class<? extends Serializer<T>>) classLoader.loadClass(className);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("Impossible because we just compiled class", e);
    }
  }

  private static CodeGenerator getCodeGenerator(
      Fory fory, ClassLoader beanClassClassLoader, ClassResolver classResolver) {
    CodeGenerator codeGenerator;
    try {
      // generated code imported fory classes.
      if (beanClassClassLoader.loadClass(Fory.class.getName()) != Fory.class) {
        throw new ClassNotFoundException();
      }
      codeGenerator = classResolver.getCodeGenerator(beanClassClassLoader);
      if (codeGenerator == null) {
        codeGenerator = CodeGenerator.getSharedCodeGenerator(beanClassClassLoader);
        // Hold strong reference of {@link CodeGenerator}, so the referent of `DelayedRef`
        // won't be null.
        classResolver.setCodeGenerator(beanClassClassLoader, codeGenerator);
      }
    } catch (ClassNotFoundException e) {
      codeGenerator =
          classResolver.getCodeGenerator(beanClassClassLoader, fory.getClass().getClassLoader());
      ClassLoader[] loaders = {beanClassClassLoader, fory.getClass().getClassLoader()};
      if (codeGenerator == null) {
        codeGenerator =
            CodeGenerator.getSharedCodeGenerator(
                ClassLoaderUtils.ForyJarClassLoader.getInstance(), beanClassClassLoader);
        // Hold strong reference of {@link CodeGenerator}, so the referent of `DelayedRef`
        // won't be null.
        classResolver.setCodeGenerator(loaders, codeGenerator);
      }
    }
    return codeGenerator;
  }

  private static <T> Class<? extends Serializer<T>> loadSerializer(
      String name, Class<?> cls, Fory fory, Callable<Class<? extends Serializer<T>>> func) {
    int configHash = fory.getConfigHash();
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
