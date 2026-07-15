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

package org.apache.fory.json.meta;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.platform.AndroidSupport;
import org.testng.Assert;
import org.testng.annotations.Test;

public class JsonInvocationTest {
  @Test
  public void testAndroidExactInvocations() throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(
            Arrays.asList(
                System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                "-cp",
                forkClassPath(),
                AndroidExactInvocationProbe.class.getName()));
    builder.environment().put("FORY_ANDROID_ENABLED", "0");
    Process process = builder.redirectErrorStream(true).start();
    String output = readFully(process.getInputStream());
    Assert.assertEquals(process.waitFor(), 0, output);
  }

  private static String readFully(InputStream inputStream) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int read;
    while ((read = inputStream.read(buffer)) != -1) {
      outputStream.write(buffer, 0, read);
    }
    return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
  }

  private static String forkClassPath() {
    String classPath = System.getProperty("surefire.real.class.path");
    return classPath == null || classPath.isEmpty()
        ? System.getProperty("java.class.path")
        : classPath;
  }

  public static final class AndroidExactInvocationProbe {
    public static void main(String[] args) throws Throwable {
      System.setProperty("java.vm.name", "Dalvik");
      System.setProperty("java.runtime.name", "Android Runtime");
      check(AndroidSupport.IS_ANDROID, "AndroidSupport should detect the real runtime first");

      MethodValues values = new MethodValues();
      assertProperty(values, "Boolean", true, false);
      assertProperty(values, "Byte", (byte) 1, (byte) 2);
      assertProperty(values, "Char", 'a', 'z');
      assertProperty(values, "Short", (short) 3, (short) 4);
      assertProperty(values, "Int", 5, 6);
      assertProperty(values, "Long", 7L, 8L);
      assertProperty(values, "Float", 1.25f, 2.5f);
      assertProperty(values, "Double", 3.5d, 4.5d);
      assertProperty(values, "Object", "before", "after");

      JsonCreatorInvoker creator =
          JsonCreatorInvoker.forExecutable(
              CreatorValue.class,
              CreatorValue.class.getDeclaredConstructor(int.class, String.class));
      CreatorValue created = (CreatorValue) creator.create(new Object[] {10, "creator"});
      check(created.id == 10 && "creator".equals(created.name), "creator invocation");
      JsonCreatorInvoker factory =
          JsonCreatorInvoker.forExecutable(
              CreatorValue.class,
              CreatorValue.class.getDeclaredMethod("create", int.class, String.class));
      CreatorValue factoryValue = (CreatorValue) factory.create(new Object[] {11, "factory"});
      check(factoryValue.id == 11 && "factory".equals(factoryValue.name), "factory invocation");

      assertCreatorFailure(
          JsonCreatorInvoker.forExecutable(
              FailingCreator.class, FailingCreator.class.getDeclaredConstructor(int.class)),
          "constructor failure");
      assertCreatorFailure(
          JsonCreatorInvoker.forExecutable(
              FailingCreator.class, FailingCreator.class.getDeclaredMethod("create", int.class)),
          "factory failure");

      Method anyMethod = AnyTarget.class.getDeclaredMethod("put", String.class, int.class);
      JsonAnySetterInvoker anySetter = JsonAnySetterInvoker.forMethod(anyMethod);
      AnyTarget anyTarget = new AnyTarget();
      anySetter.set(anyTarget, "key", 12);
      check("key".equals(anyTarget.name) && anyTarget.value == 12, "Any setter invocation");
      anySetter
          .exactHandle()
          .invokeExact((Object) anyTarget, "exact", (Object) Integer.valueOf(13));
      check("exact".equals(anyTarget.name) && anyTarget.value == 13, "exact Any setter handle");
      try {
        anySetter.set(anyTarget, "null", null);
        throw new AssertionError("Primitive Any setter should reject null");
      } catch (ForyJsonException expected) {
        check(expected.getCause() instanceof NullPointerException, "primitive null cause");
      }

      JsonAnySetterInvoker failingSetter =
          JsonAnySetterInvoker.forMethod(
              AnyTarget.class.getDeclaredMethod("fail", String.class, Object.class));
      try {
        failingSetter.set(anyTarget, "error", new Object());
        throw new AssertionError("Any setter Error should propagate");
      } catch (AssertionError expected) {
        check("any error".equals(expected.getMessage()), "Any setter Error message");
      }
    }

    private static void assertProperty(
        MethodValues values, String suffix, Object expected, Object replacement) throws Exception {
      Method getter = MethodValues.class.getDeclaredMethod("get" + suffix);
      Method setter = MethodValues.class.getDeclaredMethod("set" + suffix, getter.getReturnType());
      JsonFieldAccessor readAccessor = JsonFieldAccessor.forGetter(getter);
      JsonFieldAccessor writeAccessor = JsonFieldAccessor.forSetter(setter);
      checkEquals(readTyped(readAccessor, values, getter.getReturnType()), expected, suffix);
      writeTyped(writeAccessor, values, replacement, getter.getReturnType());
      checkEquals(readTyped(readAccessor, values, getter.getReturnType()), replacement, suffix);
    }

    private static void assertCreatorFailure(JsonCreatorInvoker invoker, String message) {
      try {
        invoker.create(new Object[] {1});
        throw new AssertionError("Creator should fail");
      } catch (ForyJsonException expected) {
        check(expected.getCause() instanceof IllegalStateException, message + " cause type");
        check(message.equals(expected.getCause().getMessage()), message + " cause message");
      }
    }

    private static Object readTyped(JsonFieldAccessor accessor, Object target, Class<?> type) {
      if (type == boolean.class) {
        return accessor.getBoolean(target);
      } else if (type == byte.class) {
        return accessor.getByte(target);
      } else if (type == char.class) {
        return accessor.getChar(target);
      } else if (type == short.class) {
        return accessor.getShort(target);
      } else if (type == int.class) {
        return accessor.getInt(target);
      } else if (type == long.class) {
        return accessor.getLong(target);
      } else if (type == float.class) {
        return accessor.getFloat(target);
      } else if (type == double.class) {
        return accessor.getDouble(target);
      }
      return accessor.getObject(target);
    }

    private static void writeTyped(
        JsonFieldAccessor accessor, Object target, Object value, Class<?> type) {
      if (type == boolean.class) {
        accessor.putBoolean(target, (Boolean) value);
      } else if (type == byte.class) {
        accessor.putByte(target, (Byte) value);
      } else if (type == char.class) {
        accessor.putChar(target, (Character) value);
      } else if (type == short.class) {
        accessor.putShort(target, (Short) value);
      } else if (type == int.class) {
        accessor.putInt(target, (Integer) value);
      } else if (type == long.class) {
        accessor.putLong(target, (Long) value);
      } else if (type == float.class) {
        accessor.putFloat(target, (Float) value);
      } else if (type == double.class) {
        accessor.putDouble(target, (Double) value);
      } else {
        accessor.putObject(target, value);
      }
    }

    private static void check(boolean value, String message) {
      if (!value) {
        throw new AssertionError(message);
      }
    }

    private static void checkEquals(Object actual, Object expected, String message) {
      if (!expected.equals(actual)) {
        throw new AssertionError(message + ": expected " + expected + ", actual " + actual);
      }
    }
  }

  private static final class MethodValues {
    private boolean booleanValue = true;
    private byte byteValue = 1;
    private char charValue = 'a';
    private short shortValue = 3;
    private int intValue = 5;
    private long longValue = 7;
    private float floatValue = 1.25f;
    private double doubleValue = 3.5d;
    private Object objectValue = "before";

    public boolean getBoolean() {
      return booleanValue;
    }

    public void setBoolean(boolean value) {
      booleanValue = value;
    }

    public byte getByte() {
      return byteValue;
    }

    public void setByte(byte value) {
      byteValue = value;
    }

    public char getChar() {
      return charValue;
    }

    public void setChar(char value) {
      charValue = value;
    }

    public short getShort() {
      return shortValue;
    }

    public void setShort(short value) {
      shortValue = value;
    }

    public int getInt() {
      return intValue;
    }

    public void setInt(int value) {
      intValue = value;
    }

    public long getLong() {
      return longValue;
    }

    public void setLong(long value) {
      longValue = value;
    }

    public float getFloat() {
      return floatValue;
    }

    public void setFloat(float value) {
      floatValue = value;
    }

    public double getDouble() {
      return doubleValue;
    }

    public void setDouble(double value) {
      doubleValue = value;
    }

    public Object getObject() {
      return objectValue;
    }

    public void setObject(Object value) {
      objectValue = value;
    }
  }

  private static final class CreatorValue {
    private final int id;
    private final String name;

    public CreatorValue(int id, String name) {
      this.id = id;
      this.name = name;
    }

    public static CreatorValue create(int id, String name) {
      return new CreatorValue(id, name);
    }
  }

  private static final class FailingCreator {
    public FailingCreator(int ignored) {
      throw new IllegalStateException("constructor failure");
    }

    public static FailingCreator create(int ignored) {
      throw new IllegalStateException("factory failure");
    }
  }

  private static final class AnyTarget {
    private String name;
    private int value;

    public void put(String name, int value) {
      this.name = name;
      this.value = value;
    }

    public void fail(String name, Object value) {
      throw new AssertionError("any error");
    }
  }
}
