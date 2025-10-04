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

import java.util.concurrent.ArrayBlockingQueue;
import org.apache.fory.reflect.ObjectCreators.DeclaredNoArgCtrObjectCreator;
import org.apache.fory.reflect.ObjectCreators.ParentNoArgCtrObjectCreator;
import org.apache.fory.reflect.ObjectCreators.UnsafeObjectCreator;
import org.testng.Assert;
import org.testng.annotations.Test;

@SuppressWarnings("rawtypes")
public class ObjectCreatorsTest {

  static class NoCtrTestClass {
    int f1;

    public NoCtrTestClass(int f1) {
      this.f1 = f1;
    }
  }

  static class PrivateConstructorClass {
    private int value;

    private PrivateConstructorClass() {
      this.value = 42;
    }

    public int getValue() {
      return value;
    }
  }

  static class PublicConstructorClass {
    public int value;

    public PublicConstructorClass() {
      this.value = 100;
    }
  }

  @Test
  public void testObjectCreator() {
    ParentNoArgCtrObjectCreator<ArrayBlockingQueue> creator =
        new ParentNoArgCtrObjectCreator<>(ArrayBlockingQueue.class);
    Assert.assertEquals(creator.newInstance().getClass(), ArrayBlockingQueue.class);
    Assert.assertEquals(
        new ParentNoArgCtrObjectCreator<>(NoCtrTestClass.class).newInstance().getClass(),
        NoCtrTestClass.class);
  }

  @Test
  public void testCreateObjectCreatorWithPublicConstructor() {
    ObjectCreator<PublicConstructorClass> creator =
        ObjectCreators.getObjectCreator(PublicConstructorClass.class);
    Assert.assertNotNull(creator);

    PublicConstructorClass instance = creator.newInstance();
    Assert.assertNotNull(instance);
    Assert.assertEquals(instance.getClass(), PublicConstructorClass.class);
    Assert.assertEquals(instance.value, 100);
  }

  @Test
  public void testCreateObjectCreatorWithPrivateConstructor() {
    ObjectCreator<PrivateConstructorClass> creator =
        ObjectCreators.getObjectCreator(PrivateConstructorClass.class);
    Assert.assertNotNull(creator);

    PrivateConstructorClass instance = creator.newInstance();
    Assert.assertNotNull(instance);
    Assert.assertEquals(instance.getClass(), PrivateConstructorClass.class);
    Assert.assertEquals(instance.getValue(), 42);
  }

  @Test
  public void testDeclaredNoArgCtrObjectCreatorErrorHandling() {
    try {
      DeclaredNoArgCtrObjectCreator<PrivateConstructorClass> creator =
          new DeclaredNoArgCtrObjectCreator<>(PrivateConstructorClass.class);
      PrivateConstructorClass instance = creator.newInstance();
      Assert.assertNotNull(instance);
      Assert.assertEquals(instance.getClass(), PrivateConstructorClass.class);
    } catch (Exception e) {
      String message = e.getMessage();
      Assert.assertTrue(
          message.contains("GraalVM")
              || message.contains("reflection")
              || message.contains("Native Image"),
          "Error message should contain GraalVM guidance: " + message);
    }
  }

  @Test
  public void testUnsafeObjectCreatorFallback() {
    UnsafeObjectCreator<PrivateConstructorClass> creator =
        new UnsafeObjectCreator<>(PrivateConstructorClass.class);
    Assert.assertNotNull(creator);

    PrivateConstructorClass instance = creator.newInstance();
    Assert.assertNotNull(instance);
    Assert.assertEquals(instance.getClass(), PrivateConstructorClass.class);
  }
}
