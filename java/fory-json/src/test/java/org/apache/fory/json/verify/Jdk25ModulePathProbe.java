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

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/** Exercises the packaged JDK25 writer with Fory core and JSON loaded as named modules. */
public final class Jdk25ModulePathProbe {
  private Jdk25ModulePathProbe() {}

  public static void main(String[] args) throws Exception {
    Class<?> fieldsType = Class.forName("org.apache.fory.json.writer.BigDecimalFields");
    requireHandle(fieldsType, "INT_COMPACT");
    requireHandle(fieldsType, "INT_VAL");
    requireHandle(fieldsType, "SCALE");
    requireFormatterHandles(Class.forName("org.apache.fory.json.writer.JdkFloatFormatter"));
    requireFormatterHandles(Class.forName("org.apache.fory.json.writer.JdkDoubleFormatter"));

    Class<?> foryJsonType = Class.forName("org.apache.fory.json.ForyJson");
    Object builder = foryJsonType.getMethod("builder").invoke(null);
    Object foryJson = builder.getClass().getMethod("build").invoke(builder);
    Method toJson = foryJsonType.getMethod("toJson", Object.class);
    Method toJsonBytes = foryJsonType.getMethod("toJsonBytes", Object.class);
    requireEquals("1.25", toJson.invoke(foryJson, Float.valueOf(1.25f)), "Latin1 float");
    requireEquals("-2.5", toJson.invoke(foryJson, Double.valueOf(-2.5d)), "Latin1 double");
    requireEquals(
        "1.25",
        new String(
            (byte[]) toJsonBytes.invoke(foryJson, Float.valueOf(1.25f)), StandardCharsets.UTF_8),
        "UTF8 float");
    requireEquals(
        "-2.5",
        new String(
            (byte[]) toJsonBytes.invoke(foryJson, Double.valueOf(-2.5d)), StandardCharsets.UTF_8),
        "UTF8 double");
    requireEquals(
        "{\"aPrefix\":\"\u0100\",\"doubleValue\":-2.5,\"floatValue\":1.25}",
        toJson.invoke(foryJson, new FloatingHolder()),
        "UTF16 float/double");

    Object output = toJson.invoke(foryJson, new BigDecimal("123.45"));
    if (!"123.45".equals(output)) {
      throw new AssertionError("Unexpected module-path BigDecimal output: " + output);
    }

    BigDecimalHolder holder = new BigDecimalHolder();
    holder.value = new BigDecimalSubtype("12345678901234567890.125");
    String expected = "{\"value\":12345678901234567890.125}";
    output = toJson.invoke(foryJson, holder);
    if (!expected.equals(output)) {
      throw new AssertionError("Unexpected inflated subtype output: " + output);
    }
    String byteOutput =
        new String((byte[]) toJsonBytes.invoke(foryJson, holder), StandardCharsets.UTF_8);
    if (!expected.equals(byteOutput)) {
      throw new AssertionError("Unexpected inflated subtype UTF8 output: " + byteOutput);
    }
  }

  private static void requireHandle(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    if (field.get(null) == null) {
      throw new AssertionError(name + " is unavailable on the module path");
    }
  }

  private static void requireFormatterHandles(Class<?> type) throws Exception {
    requireMethodHandle(type, "PUT_DECIMAL_LATIN1");
    requireMethodHandle(type, "PUT_DECIMAL_UTF16");
  }

  private static void requireMethodHandle(Class<?> type, String name) throws Exception {
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    if (!(field.get(null) instanceof MethodHandle)) {
      throw new AssertionError(type.getName() + "." + name + " is unavailable");
    }
  }

  private static void requireEquals(String expected, Object actual, String path) {
    if (!expected.equals(actual)) {
      throw new AssertionError("Unexpected " + path + " output: " + actual);
    }
  }

  public static final class FloatingHolder {
    public String aPrefix = "\u0100";
    public double doubleValue = -2.5d;
    public float floatValue = 1.25f;
  }

  public static final class BigDecimalHolder {
    public BigDecimal value;
  }

  private static final class BigDecimalSubtype extends BigDecimal {
    private BigDecimalSubtype(String value) {
      super(value);
    }

    @Override
    public String toString() {
      throw new AssertionError("BigDecimal subtype toString must not be invoked");
    }

    @Override
    public BigInteger unscaledValue() {
      throw new AssertionError("BigDecimal subtype unscaledValue must not be invoked");
    }

    @Override
    public int scale() {
      throw new AssertionError("BigDecimal subtype scale must not be invoked");
    }

    @Override
    public BigDecimal negate() {
      throw new AssertionError("BigDecimal subtype negate must not be invoked");
    }
  }
}
