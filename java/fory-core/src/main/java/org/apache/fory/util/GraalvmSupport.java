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

import java.util.Objects;
import org.apache.fory.Fory;
import org.apache.fory.serializer.Serializer;

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

  public static class GraalvmSerializerHolder extends Serializer {
    private final Class serializerClass;

    public GraalvmSerializerHolder(Fory fory, Class<?> type, Class<?> serializerClass) {
      super(fory, type);
      this.serializerClass = Objects.requireNonNull(serializerClass);
    }

    public Class<? extends Serializer> getSerializerClass() {
      return serializerClass;
    }
  }
}
