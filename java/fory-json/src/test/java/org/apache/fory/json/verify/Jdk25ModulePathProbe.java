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

package org.apache.fory.json.verify;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;

/** Exercises the packaged JDK25 writer with Fory core and JSON loaded as named modules. */
public final class Jdk25ModulePathProbe {
  private Jdk25ModulePathProbe() {}

  public static void main(String[] args) throws Exception {
    Class<?> fieldsType = Class.forName("org.apache.fory.json.writer.BigDecimalFields");
    requireHandle(fieldsType, "INT_COMPACT");
    requireHandle(fieldsType, "INT_VAL");
    requireHandle(fieldsType, "SCALE");

    Class<?> foryJsonType = Class.forName("org.apache.fory.json.ForyJson");
    Object builder = foryJsonType.getMethod("builder").invoke(null);
    Object foryJson = builder.getClass().getMethod("build").invoke(builder);
    Method toJson = foryJsonType.getMethod("toJson", Object.class);
    Object output = toJson.invoke(foryJson, new BigDecimal("123.45"));
    if (!"123.45".equals(output)) {
      throw new AssertionError("Unexpected module-path BigDecimal output: " + output);
    }
  }

  private static void requireHandle(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    if (field.get(null) == null) {
      throw new AssertionError(name + " is unavailable on the module path");
    }
  }
}
