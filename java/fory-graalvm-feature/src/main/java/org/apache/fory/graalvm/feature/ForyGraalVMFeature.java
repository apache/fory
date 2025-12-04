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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.record.RecordUtils;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

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

  @Override
  public String getDescription() {
    return "Registers Fory serialization classes and proxy interfaces for GraalVM native image";
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

    for (Class<?> proxyInterface : GraalvmSupport.getProxyInterfaces()) {
      if (processedProxyInterfaces.add(proxyInterface)) {
        RuntimeReflection.register(proxyInterface);
        RuntimeReflection.register(proxyInterface.getMethods());
        changed = true;
      }
    }

    if (changed) {
      access.requireAnalysisIteration();
    }
  }

  private void registerClass(Class<?> clazz) {
    RuntimeReflection.register(clazz);
    RuntimeReflection.registerClassLookup(clazz.getName());

    if (RecordUtils.isRecord(clazz)) {
      registerRecordClass(clazz);
    } else if (GraalvmSupport.needReflectionRegisterForCreation(clazz)) {
      registerForReflectiveInstantiation(clazz);
    }
  }

  private void registerRecordClass(Class<?> clazz) {
    RuntimeReflection.registerForReflectiveInstantiation(clazz);
    for (Field field : clazz.getDeclaredFields()) {
      RuntimeReflection.register(field);
      RuntimeReflection.registerFieldLookup(clazz, field.getName());
    }
    for (Method method : clazz.getDeclaredMethods()) {
      RuntimeReflection.register(method);
      RuntimeReflection.registerMethodLookup(clazz, method.getName(), method.getParameterTypes());
    }
    for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
      RuntimeReflection.register(constructor);
      RuntimeReflection.registerConstructorLookup(clazz, constructor.getParameterTypes());
    }
  }

  private void registerForReflectiveInstantiation(Class<?> clazz) {
    RuntimeReflection.registerForReflectiveInstantiation(clazz);
    for (Field field : clazz.getDeclaredFields()) {
      RuntimeReflection.register(field);
    }
  }
}
