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

import java.lang.reflect.Field;
import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.reflect.ObjectCreators;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * GraalVM Feature for Apache Fory serialization framework.
 *
 * <p>This feature automatically registers necessary metadata for GraalVM native image compilation
 * to ensure Fory serialization works correctly at runtime. It handles:
 *
 * <ul>
 *   <li>Registering problematic classes for unsafe allocation
 *   <li>Registering field reflection access for serialization
 *   <li>Registering proxy interfaces for dynamic proxy creation
 * </ul>
 */
public class ForyGraalVMFeature implements Feature {

  @Override
  public void beforeAnalysis(BeforeAnalysisAccess access) {
    // Process all registered classes
    Set<Class<?>> registeredClasses = Fory.getRegisteredClasses();
    for (Class<?> clazz : registeredClasses) {
      handleForyClass(clazz);
    }

    // Process all proxy interfaces
    Set<Class<?>> proxyInterfaces = Fory.getProxyInterfaces();
    for (Class<?> proxyInterface : proxyInterfaces) {
      RuntimeReflection.register(proxyInterface);
      RuntimeReflection.register(proxyInterface.getMethods());
    }
  }

  private void handleForyClass(Class<?> clazz) {
    if (ObjectCreators.isProblematicForCreation(clazz)) {
      // Register for unsafe allocation
      RuntimeReflection.registerForReflectiveInstantiation(clazz);

      // Register all fields for reflection access
      for (Field field : clazz.getDeclaredFields()) {
        RuntimeReflection.register(field);
      }
    }

    // Always register the class itself for reflection
    RuntimeReflection.register(clazz);
  }

  @Override
  public String getDescription() {
    return "Fory GraalVM Feature: Registers classes for serialization, proxying, and unsafe allocation.";
  }
}
