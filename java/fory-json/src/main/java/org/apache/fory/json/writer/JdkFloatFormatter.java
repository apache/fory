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

package org.apache.fory.json.writer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.internal._JDKAccess;

/** Exact JDK float formatter access for JSON number output without materializing a String. */
final class JdkFloatFormatter {
  static final int MAX_CHARS = 16;
  private static final MethodHandle PUT_DECIMAL = loadPutDecimal();

  private JdkFloatFormatter() {}

  static int write(byte[] buffer, int position, float value) {
    MethodHandle putDecimal = PUT_DECIMAL;
    if (putDecimal == null) {
      return -1;
    }
    try {
      return (int) putDecimal.invokeExact(buffer, position, value);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot write JSON float " + value, e);
    }
  }

  static void appendTo(float value, StringBuilder builder) {
    try {
      builder.setLength(0);
      // HotSpot JDK 8 delegates this to FloatingDecimal.appendTo and current HotSpot delegates it
      // to FloatToDecimal.putDecimal, so the portable fallback writes into the reused builder
      // instead of materializing Float.toString(value).
      builder.append(value);
      if (builder.length() == 0) {
        throw new ForyJsonException("JDK float formatter produced no output");
      }
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot write JSON float " + value, e);
    }
  }

  private static MethodHandle loadPutDecimal() {
    if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return null;
    }
    try {
      Class<?> formatterClass = Class.forName("jdk.internal.math.FloatToDecimal");
      Lookup lookup = _JDKAccess._trustedLookup(formatterClass);
      Object latin1 = lookup.findStaticGetter(formatterClass, "LATIN1", formatterClass).invoke();
      MethodHandle putDecimal =
          lookup.findVirtual(
              formatterClass,
              "putDecimal",
              MethodType.methodType(int.class, byte[].class, int.class, float.class));
      return putDecimal
          .bindTo(latin1)
          .asType(MethodType.methodType(int.class, byte[].class, int.class, float.class));
    } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException e) {
      return null;
    } catch (IllegalAccessException e) {
      return null;
    } catch (Throwable e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
