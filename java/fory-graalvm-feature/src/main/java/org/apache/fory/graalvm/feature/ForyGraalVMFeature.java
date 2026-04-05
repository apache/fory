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

package org.apache.fory.graalvm.feature;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.record.RecordUtils;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.graalvm.nativeimage.hosted.RuntimeProxyCreation;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.hosted.RuntimeSerialization;

/**
 * GraalVM native image feature for Apache Fory serialization framework.
 *
 * <p>This feature automatically registers reflection metadata during native image build to ensure
 * Fory serialization works correctly at runtime. It handles:
 *
 * <ul>
 *   <li>Classes requiring reflective instantiation (private constructors, Records, etc.)
 *   <li>Record class accessor methods and canonical constructors
 *   <li>Proxy interfaces for dynamic proxy serialization
 * </ul>
 *
 * <p>Usage: Add to native-image build via META-INF/native-image/.../native-image.properties:
 *
 * <pre>Args = --features=org.apache.fory.graalvm.feature.ForyGraalVMFeature</pre>
 */
public class ForyGraalVMFeature implements Feature {

  private final Set<Class<?>> processedClasses = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedProxyInterfaces = ConcurrentHashMap.newKeySet();
  private final Set<List<Class<?>>> processedProxyInterfaceLists = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedSerializerClasses = ConcurrentHashMap.newKeySet();

  @Override
  public String getDescription() {
    return "Registers Fory serialization classes and proxy interfaces for GraalVM native image";
  }

  @Override
  public void beforeAnalysis(BeforeAnalysisAccess access) {
    for (Class<?> serializerClass : GraalvmSupport.getRegisteredSerializerClasses()) {
      RuntimeClassInitialization.initializeAtBuildTime(serializerClass);
    }
  }

  @Override
  public void duringAnalysis(DuringAnalysisAccess access) {
    boolean changed = false;

    for (Class<?> clazz : GraalvmSupport.getRegisteredClasses()) {
      if (processedClasses.add(clazz)) {
        registerClass(clazz);
        changed = true;
      }
    }

    for (Class<?> serializerClass : GraalvmSupport.getRegisteredSerializerClasses()) {
      if (processedSerializerClasses.add(serializerClass)) {
        registerSerializerClass(serializerClass);
        changed = true;
      }
    }

    for (List<Class<?>> proxyInterfaceList : GraalvmSupport.getProxyInterfaceLists()) {
      if (processedProxyInterfaceLists.add(proxyInterfaceList)) {
        RuntimeProxyCreation.register(proxyInterfaceList.toArray(new Class<?>[0]));
        for (Class<?> proxyInterface : proxyInterfaceList) {
          if (processedProxyInterfaces.add(proxyInterface)) {
            RuntimeReflection.register(proxyInterface);
            RuntimeReflection.register(proxyInterface.getMethods());
          }
        }
        changed = true;
      }
    }

    if (changed) {
      access.requireAnalysisIteration();
    }
  }

  private void registerClass(Class<?> clazz) {
    RuntimeReflection.register(clazz);
    registerHierarchyMembers(clazz);
    if (Serializable.class.isAssignableFrom(clazz)) {
      registerSerializableHierarchy(clazz);
    }

    if (RecordUtils.isRecord(clazz)) {
      RuntimeReflection.registerAllRecordComponents(clazz);
    } else if (GraalvmSupport.needReflectionRegisterForCreation(clazz)) {
      RuntimeReflection.registerForReflectiveInstantiation(clazz);
    }
  }

  private void registerHierarchyMembers(Class<?> clazz) {
    for (Class<?> current = clazz;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      RuntimeReflection.register(current);
      registerFields(current);
      registerMethods(current);
      registerConstructors(current);
    }
  }

  private void registerSerializerClass(Class<?> clazz) {
    registerConstructors(clazz);
  }

  private void registerSerializableHierarchy(Class<?> clazz) {
    for (Class<?> current = clazz;
        current != null && current != Object.class && Serializable.class.isAssignableFrom(current);
        current = current.getSuperclass()) {
      RuntimeSerialization.registerIncludingAssociatedClasses(current);
    }
  }

  private void registerFields(Class<?> clazz) {
    for (Field field : clazz.getDeclaredFields()) {
      RuntimeReflection.register(field);
    }
  }

  private void registerMethods(Class<?> clazz) {
    for (Method method : clazz.getDeclaredMethods()) {
      RuntimeReflection.register(method);
    }
  }

  private void registerConstructors(Class<?> clazz) {
    for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
      RuntimeReflection.register(constructor);
    }
  }
}
