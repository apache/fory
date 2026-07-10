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
  private static final MethodHandle PUT_DECIMAL_LATIN1 = loadPutDecimal("LATIN1");
  private static final MethodHandle PUT_DECIMAL_UTF16 = loadPutDecimal("UTF16");

  private JdkFloatFormatter() {}

  static int write(byte[] buffer, int position, float value) {
    MethodHandle putDecimal = PUT_DECIMAL_LATIN1;
    if (putDecimal == null) {
      return -1;
    }
    try {
      return (int) putDecimal.invokeExact(buffer, position, value);
    } catch (ThreadDeath e) {
      throw e;
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot write JSON float " + value, e);
    }
  }

  static int writeUtf16(byte[] buffer, int position, float value) {
    MethodHandle putDecimal = PUT_DECIMAL_UTF16;
    if (putDecimal == null) {
      return -1;
    }
    try {
      return (int) putDecimal.invokeExact(buffer, position >>> 1, value) << 1;
    } catch (ThreadDeath e) {
      throw e;
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot write JSON float " + value, e);
    }
  }

  static boolean isAvailable() {
    return PUT_DECIMAL_LATIN1 != null;
  }

  static void appendTo(float value, StringBuilder builder) {
    try {
      builder.setLength(0);
      // Supported OpenJDK and Android libcore implementations append through their decimal
      // formatter, so the portable path reuses this builder instead of materializing a String.
      builder.append(value);
      if (builder.length() == 0) {
        throw new ForyJsonException("JDK float formatter produced no output");
      }
    } catch (ThreadDeath e) {
      throw e;
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot write JSON float " + value, e);
    }
  }

  private static MethodHandle loadPutDecimal(String coder) {
    if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return null;
    }
    try {
      Class<?> formatterClass = Class.forName("jdk.internal.math.FloatToDecimal");
      Lookup lookup = _JDKAccess._trustedLookup(formatterClass);
      Object formatter = lookup.findStaticGetter(formatterClass, coder, formatterClass).invoke();
      MethodHandle putDecimal =
          lookup.findVirtual(
              formatterClass,
              "putDecimal",
              MethodType.methodType(int.class, byte[].class, int.class, float.class));
      return putDecimal
          .bindTo(formatter)
          .asType(MethodType.methodType(int.class, byte[].class, int.class, float.class));
    } catch (ThreadDeath e) {
      throw e;
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      return null;
    }
  }
}
