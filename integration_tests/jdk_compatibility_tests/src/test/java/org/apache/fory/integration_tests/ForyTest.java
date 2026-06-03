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

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.fory.Fory;
import org.apache.fory.platform.JdkVersion;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ForyTest {

  @Test
  public void testClasspathBeanGraph() {
    ClasspathBean object =
        new ClasspathBean(
            "graph", Arrays.asList(new ClasspathItem("left", 1), new ClasspathItem("right", 2)));
    Fory fory = Fory.builder().withXlang(false).requireClassRegistration(false).build();
    byte[] data = fory.serialize(object);
    Assert.assertEquals(fory.deserialize(data), object);
  }

  @Test
  public void testClasspathRuntime() throws Exception {
    if (JdkVersion.MAJOR_VERSION >= 9) {
      Object module = Class.class.getMethod("getModule").invoke(Fory.class);
      boolean named = (boolean) module.getClass().getMethod("isNamed").invoke(module);
      Assert.assertFalse(named);
    }
  }

  @Test
  public void testFinalFieldBean() {
    Fory fory = Fory.builder().withXlang(false).requireClassRegistration(false).build();
    FinalFieldBean value = new FinalFieldBean("amy", 42);
    Object deserialized = fory.deserialize(fory.serialize(value));
    Assert.assertEquals(deserialized, value);
  }

  static final class FinalFieldBean implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final int age;

    private FinalFieldBean(String name, int age) {
      this.name = name;
      this.age = age;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof FinalFieldBean)) {
        return false;
      }
      FinalFieldBean other = (FinalFieldBean) obj;
      return age == other.age && name.equals(other.name);
    }

    @Override
    public int hashCode() {
      return name.hashCode() * 31 + age;
    }
  }

  static final class ClasspathBean implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private List<ClasspathItem> items;

    private ClasspathBean(String name, List<ClasspathItem> items) {
      this.name = name;
      this.items = items;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof ClasspathBean)) {
        return false;
      }
      ClasspathBean other = (ClasspathBean) obj;
      return Objects.equals(name, other.name) && Objects.equals(items, other.items);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, items);
    }
  }

  static final class ClasspathItem implements Serializable {
    private static final long serialVersionUID = 1L;

    private String name;
    private int count;

    private ClasspathItem(String name, int count) {
      this.name = name;
      this.count = count;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof ClasspathItem)) {
        return false;
      }
      ClasspathItem other = (ClasspathItem) obj;
      return count == other.count && Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, count);
    }
  }
}
