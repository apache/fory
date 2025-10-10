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
import java.util.concurrent.ConcurrentHashMap;
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

  private final Set<Class<?>> processedClasses = ConcurrentHashMap.newKeySet();
  private final Set<Class<?>> processedProxyInterfaces = ConcurrentHashMap.newKeySet();

  @Override
  public void duringAnalysis(DuringAnalysisAccess access) {
    boolean changed = false;

    for (Class<?> clazz : Fory.getRegisteredClasses()) {
      if (processedClasses.add(clazz)) {
        handleForyClass(clazz);
        changed = true;
      }
    }

    for (Class<?> proxyInterface : Fory.getProxyInterfaces()) {
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

  public String getDescription() {
    return "Fory GraalVM Feature: Registers classes for serialization, proxying, and unsafe allocation.";
  }

  private void handleForyClass(Class<?> clazz) {
    if (ObjectCreators.isProblematicForCreation(clazz)) {
      try {
        RuntimeReflection.registerForReflectiveInstantiation(clazz);
        for (Field field : clazz.getDeclaredFields()) {
          RuntimeReflection.register(field);
        }
      } catch (Exception e) {
        throw new RuntimeException(
            String.format(
                "Failed to register class '%s' for GraalVM Native Image. "
                    + "This class lacks an accessible no-arg constructor. "
                    + "Please ensure fory-graalvm-feature is included in your native-image build.",
                clazz.getName()),
            e);
      }
    }

    RuntimeReflection.register(clazz);
  }
}
