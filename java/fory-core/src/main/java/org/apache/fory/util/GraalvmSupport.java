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

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.util.record.RecordUtils;

/** A helper for Graalvm native image support. */
public class GraalvmSupport {
  // https://github.com/oracle/graal/blob/master/sdk/src/org.graalvm.nativeimage/src/org/graalvm/nativeimage/ImageInfo.java
  public static final boolean IN_GRAALVM_NATIVE_IMAGE;
  // We can't cache code key or isBuildTime as static constant, since this class will be initialized
  // at build time,
  // and will be still build time value when it's graalvm runtime actually.
  private static final String GRAAL_IMAGE_CODE_KEY = "org.graalvm.nativeimage.imagecode";
  private static final String GRAAL_IMAGE_BUILDTIME = "buildtime";
  private static final String GRAAL_IMAGE_RUNTIME = "runtime";

  static {
    String imageCode = System.getProperty(GRAAL_IMAGE_CODE_KEY);
    IN_GRAALVM_NATIVE_IMAGE = imageCode != null;
  }

  /** Returns true if current process is running in graalvm native image build stage. */
  public static boolean isGraalBuildtime() {
    return IN_GRAALVM_NATIVE_IMAGE
        && GRAAL_IMAGE_BUILDTIME.equals(System.getProperty(GRAAL_IMAGE_CODE_KEY));
  }

  /** Returns true if current process is running in graalvm native image runtime stage. */
  public static boolean isGraalRuntime() {
    return IN_GRAALVM_NATIVE_IMAGE
        && GRAAL_IMAGE_RUNTIME.equals(System.getProperty(GRAAL_IMAGE_CODE_KEY));
  }

  /**
   * Returns all classes registered for GraalVM native image compilation across all configurations.
   */
  public static Set<Class<?>> getRegisteredClasses() {
    return TypeResolver.getAllRegisteredClasses();
  }

  /** Returns all proxy interfaces registered for GraalVM native image compilation. */
  public static Set<Class<?>> getProxyInterfaces() {
    return TypeResolver.getAllProxyInterfaces();
  }

  /** Clears all GraalVM native image registrations. Primarily for testing purposes. */
  public static void clearRegistrations() {
    TypeResolver.clearGraalvmRegistrations();
  }

  public static class GraalvmSerializerHolder extends Serializer {
    private final Class serializerClass;
    private Serializer serializer;

    public GraalvmSerializerHolder(Fory fory, Class<?> type, Class<?> serializerClass) {
      super(fory, type);
      this.serializerClass = Objects.requireNonNull(serializerClass);
    }

    public Class<? extends Serializer> getSerializerClass() {
      return serializerClass;
    }

    @Override
    public void write(MemoryBuffer buffer, Object value) {
      // for debug only, graalvm native image won't go to here
      getSerializer().write(buffer, value);
    }

    @Override
    public Object read(MemoryBuffer buffer) {
      // for debug only, graalvm native image won't go to here
      return getSerializer().read(buffer);
    }

    private Serializer getSerializer() {
      if (serializer == null) {
        try {
          Constructor ctr = serializerClass.getDeclaredConstructor(Fory.class, Class.class);
          ctr.setAccessible(true);
          serializer = (Serializer) ctr.newInstance(fory, type);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      return serializer;
    }
  }

  public static ForyException throwNoArgCtrException(Class<?> type) {
    throw new ForyException("Please provide a no-arg constructor for " + type);
  }

  public static boolean isRecordConstructorPublicAccessible(Class<?> type) {
    if (!RecordUtils.isRecord(type)) {
      return false;
    }

    try {
      Constructor<?>[] constructors = type.getDeclaredConstructors();
      for (Constructor<?> constructor : constructors) {
        if (Modifier.isPublic(constructor.getModifiers())) {
          Class<?>[] paramTypes = constructor.getParameterTypes();
          boolean allParamsPublic = true;
          for (Class<?> paramType : paramTypes) {
            if (!Modifier.isPublic(paramType.getModifiers())) {
              allParamsPublic = false;
              break;
            }
          }
          if (allParamsPublic) {
            return true;
          }
        }
      }
    } catch (Exception e) {
      return false;
    }
    return false;
  }
}
