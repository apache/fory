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

package org.apache.fory.integration_tests;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.apache.fory.Fory;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.memory.Platform;
import org.testng.Assert;
import org.testng.annotations.Test;

public class JDKCompatibilityTest {

  ForyBuilder builder() {
    return Fory.builder().withXlang(false).requireClassRegistration(false);
  }

  Object createObject() {
    // test non latin1 string
    return Arrays.asList("Hello", "Hello，你好");
  }

  @Test
  public void testAndPrepareData() throws IOException {
    {
      Fory fory = builder().build();
      Object object = createObject();
      Assert.assertEquals(createObject(), object);
      byte[] serialized = fory.serialize(object);
      Assert.assertEquals(fory.deserialize(serialized), object);
      write("object_schema_consistent" + Platform.JAVA_VERSION, serialized);
    }
    {
      Fory fory = builder().withCompatible(true).build();
      Object object = createObject();
      byte[] serialized = fory.serialize(object);
      Assert.assertEquals(fory.deserialize(serialized), object);
      write("object_schema_compatible" + Platform.JAVA_VERSION, serialized);
    }
    // 11Test the case for the user registration class
    {
      Fory fory = builder().build();
      fory.register(CustomObject.class);
      CustomObject customObject = createCustomObject();
      Assert.assertEquals(createCustomObject(), customObject);
      byte[] serialized = fory.serialize(customObject);
      Assert.assertEquals(fory.deserialize(serialized), customObject);
      write("custom_object_schema_consistent" + Platform.JAVA_VERSION, serialized);
    }
    {
      Fory fory = builder().withCompatible(true).build();
      fory.register(CustomObject.class);
      CustomObject customObject = createCustomObject();
      byte[] serialized = fory.serialize(customObject);
      Assert.assertEquals(fory.deserialize(serialized), customObject);
      write("custom_object_schema_compatible" + Platform.JAVA_VERSION, serialized);
    }
  }

  @Test
  public void testSchemaConsist() throws IOException {
    Object object = createObject();
    Fory fory = builder().build();
    fory.register(CustomObject.class);
    File dir = new File(".");
    File[] files = dir.listFiles((d, name) -> name.startsWith("object_schema_consistent"));
    assert files != null;
    check(object, fory, files);
    CustomObject customObject = createCustomObject();
    File[] files1 = dir.listFiles((d, name) -> name.startsWith("custom_object_schema_consistent"));
    assert files1 != null;
    check(customObject, fory, files1);
  }

  @Test
  public void testSchemaCompatible() throws IOException {
    Object object = createObject();
    Fory fory = builder().withCompatible(true).build();
    fory.register(CustomObject.class);
    File dir = new File(".");
    File[] files = dir.listFiles((d, name) -> name.startsWith("object_schema_compatible"));
    assert files != null;
    check(object, fory, files);
    CustomObject customObject = createCustomObject();
    File[] files1 = dir.listFiles((d, name) -> name.startsWith("custom_object_schema_compatible"));
    assert files1 != null;
    check(customObject, fory, files1);
  }

  private static void check(Object object, Fory fory, File[] files) throws IOException {
    for (File file : files) {
      byte[] bytes = Files.readAllBytes(file.toPath());
      Assert.assertEquals(fory.serialize(object).length, bytes.length);
      try {
        Object o = fory.deserialize(bytes);
        Assert.assertEquals(o, object);
      } catch (Throwable e) {
        throw new RuntimeException(
            "Check failed for " + file + " under JDK " + Platform.JAVA_VERSION, e);
      }
    }
  }

  private void write(String path, byte[] data) {
    try {
      Path p = Paths.get(path);
      Files.deleteIfExists(p);
      Files.write(p, data);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  static class CustomObject {
    private String str;

    public String getStr() {
      return str;
    }

    public void setStr(String str) {
      this.str = str;
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      CustomObject entity = (CustomObject) o;
      return str.equals(entity.str);
    }

    @Override
    public int hashCode() {
      return str.hashCode();
    }
  }

  CustomObject createCustomObject() {
    CustomObject customObject = new CustomObject();
    customObject.setStr("hello");
    return customObject;
  }
}
