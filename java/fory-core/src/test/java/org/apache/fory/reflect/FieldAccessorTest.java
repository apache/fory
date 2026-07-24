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

package org.apache.fory.reflect;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import lombok.AllArgsConstructor;
import org.apache.fory.TestUtils;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.reflect.InstanceFieldAccessors.GeneratedAccessor;
import org.testng.Assert;
import org.testng.annotations.Test;

public class FieldAccessorTest {
  @AllArgsConstructor
  private static final class TestStruct {
    private int f1;
    private boolean f2;
    private String f3;
  }

  @Test
  public void testGeneratedAccessor() throws Exception {
    TestStruct struct = new TestStruct(10, true, "str");
    GeneratedAccessor f1 = new GeneratedAccessor(TestStruct.class.getDeclaredField("f1"));
    Assert.assertEquals(f1.get(struct), 10);
    f1.set(struct, 20);
    Assert.assertEquals(f1.get(struct), 20);
    Assert.assertEquals(f1.getInt(struct), 20);
    f1.putInt(struct, 30);
    Assert.assertEquals(f1.getInt(struct), 30);
    GeneratedAccessor f2 = new GeneratedAccessor(TestStruct.class.getDeclaredField("f2"));
    Assert.assertEquals(f2.get(struct), true);
    f2.set(struct, false);
    Assert.assertEquals(f2.get(struct), false);
    Assert.assertFalse(f2.getBoolean(struct));
    f2.putBoolean(struct, true);
    Assert.assertTrue(f2.getBoolean(struct));
    GeneratedAccessor f3 = new GeneratedAccessor(TestStruct.class.getDeclaredField("f3"));
    Assert.assertEquals(f3.get(struct), "str");
    f3.set(struct, "a");
    Assert.assertEquals(f3.get(struct), "a");
    Assert.assertEquals(f3.getObject(struct), "a");
    f3.putObject(struct, "b");
    Assert.assertEquals(f3.getObject(struct), "b");
  }

  @Test
  public void testHiddenAccessor() throws Exception {
    HiddenFields fields = new HiddenFields();
    FieldAccessor intAccessor =
        FieldAccessor.createAccessor(HiddenFields.class.getDeclaredField("i"));
    Assert.assertEquals(intAccessor.getInt(fields), 1);
    Assert.assertEquals(intAccessor.getObject(fields), 1);
    intAccessor.putInt(fields, 2);
    Assert.assertEquals(intAccessor.getInt(fields), 2);
    intAccessor.putObject(fields, 3);
    Assert.assertEquals(intAccessor.getInt(fields), 3);
    Assert.assertEquals(intAccessor.getObject(fields), 3);

    FieldAccessor objectAccessor =
        FieldAccessor.createAccessor(HiddenFields.class.getDeclaredField("text"));
    Assert.assertEquals(objectAccessor.getObject(fields), "a");
    objectAccessor.putObject(fields, "b");
    Assert.assertEquals(objectAccessor.getObject(fields), "b");

    FieldAccessor finalAccessor =
        FieldAccessor.createAccessor(HiddenFields.class.getDeclaredField("finalValue"));
    Assert.assertEquals(finalAccessor.getLong(fields), 3L);
  }

  @Test
  public void testFinalFieldWrites() throws Exception {
    FinalFields fields = new FinalFields(1, "a");
    FieldAccessor intAccessor =
        FieldAccessor.createAccessor(FinalFields.class.getDeclaredField("intValue"));
    intAccessor.putInt(fields, 2);
    Assert.assertEquals(intAccessor.getInt(fields), 2);

    FieldAccessor objectAccessor =
        FieldAccessor.createAccessor(FinalFields.class.getDeclaredField("objectValue"));
    objectAccessor.putObject(fields, "b");
    Assert.assertEquals(objectAccessor.getObject(fields), "b");
  }

  @Test
  public void testAndroidReflectionFieldAccessorPaths() throws Exception {
    Process process =
        new ProcessBuilder(TestUtils.javaCommand(AndroidReflectionFieldAccessorProbe.class))
            .redirectErrorStream(true)
            .start();
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

  public static final class AndroidReflectionFieldAccessorProbe {
    public static void main(String[] args) throws Exception {
      System.setProperty("java.vm.name", "Dalvik");
      System.setProperty("java.runtime.name", "Android Runtime");
      check(AndroidSupport.IS_ANDROID, "AndroidSupport should detect Dalvik runtime");

      AndroidFields fields = new AndroidFields();
      assertAccessor("boolValue", fields, true, false);
      assertAccessor("byteValue", fields, (byte) 1, (byte) 2);
      assertAccessor("charValue", fields, 'a', 'z');
      assertAccessor("shortValue", fields, (short) 3, (short) 4);
      assertAccessor("intValue", fields, 5, 6);
      assertAccessor("longValue", fields, 7L, 8L);
      assertAccessor("floatValue", fields, 1.25f, 2.5f);
      assertAccessor("doubleValue", fields, 3.5d, 4.5d);
      assertAccessor("objectValue", fields, "before", "after");
    }

    private static void assertAccessor(
        String fieldName, AndroidFields fields, Object expected, Object replacement)
        throws Exception {
      Field field = AndroidFields.class.getDeclaredField(fieldName);
      FieldAccessor accessor = FieldAccessor.createAccessor(field);
      check(
          accessor.getClass().getEnclosingClass() == InstanceFieldAccessors.class,
          "Expected instance accessor owner for " + field);
      checkEquals(accessor.get(fields), expected, "initial " + fieldName);
      accessor.set(fields, replacement);
      checkEquals(accessor.get(fields), replacement, "updated " + fieldName);
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

  private static final class AndroidFields {
    private boolean boolValue = true;
    private byte byteValue = 1;
    private char charValue = 'a';
    private short shortValue = 3;
    private int intValue = 5;
    private long longValue = 7;
    private float floatValue = 1.25f;
    private double doubleValue = 3.5d;
    private Object objectValue = "before";
  }

  private static final class HiddenFields {
    private int i = 1;
    private String text = "a";
    private final long finalValue = 3;
  }

  private static final class FinalFields {
    private final int intValue;
    private final Object objectValue;

    private FinalFields(int value, Object object) {
      intValue = value;
      objectValue = object;
    }
  }
}
