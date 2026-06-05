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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

import com.google.common.collect.ImmutableList;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.List;
import org.apache.fory.TestUtils;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.type.Descriptor;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ReflectionUtilsTest {

  @Test
  public void getReturnType() {
    assertEquals(
        ReflectionUtils.getReturnType(ReflectionUtils.class, "getReturnType"), Class.class);
    assertFalse(ReflectionUtils.hasException(List.class, "getClass"));
  }

  class A {
    String f1;
  }

  @Test
  public void testGetClassNameWithoutPackage() {
    assertEquals(
        ReflectionUtils.getClassNameWithoutPackage(A.class),
        ReflectionUtilsTest.class.getSimpleName() + "$" + A.class.getSimpleName());
  }

  @Test
  public void testObjectEqual() {
    BeanA beanA = BeanA.createBeanA(1);
    TestUtils.objectFieldsEquals(beanA, beanA, true);
    TestUtils.objectCommonFieldsEquals(beanA, beanA, true);
  }

  static class GetFieldValuesTestClass {
    Object f1;
    int f2; // test primitive field
  }

  @Test
  public void testGetFieldValues() {
    GetFieldValuesTestClass o = new GetFieldValuesTestClass();
    o.f1 = "str";
    o.f2 = 10;
    List<Object> fieldValues =
        ReflectionUtils.getFieldValues(Descriptor.getFields(GetFieldValuesTestClass.class), o);
    assertEquals(fieldValues, ImmutableList.of("str", 10));
  }

  enum MonomorphicTestEnum1 {
    A,
    B
  }

  enum MonomorphicTestEnum2 {
    A {
      @Override
      int f() {
        return 0;
      }
    },
    B {
      @Override
      int f() {
        return 1;
      }
    };

    abstract int f();
  }

  @Test
  public void testMonomorphic() {
    Assert.assertTrue(ReflectionUtils.isMonomorphic(MonomorphicTestEnum1.class));
    Assert.assertTrue(ReflectionUtils.isMonomorphic(MonomorphicTestEnum2.class));
    Assert.assertTrue(ReflectionUtils.isMonomorphic(MonomorphicTestEnum1[].class));
    Assert.assertTrue(ReflectionUtils.isMonomorphic(MonomorphicTestEnum2[].class));
  }

  static class NoArgConstructor1 implements Serializable {
    int f1;

    public NoArgConstructor1(int f1) {
      this.f1 = f1;
    }
  }

  @Test
  public void testGetNoArgConstructor() {
    Constructor ctr = ReflectionUtils.getNoArgConstructor(NoArgConstructor1.class);
    Assert.assertNull(ctr);
  }
}
