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

package org.apache.fory.graalvm.feature;

import static org.testng.Assert.*;

import org.apache.fory.Fory;
import org.apache.fory.util.GraalvmSupport;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ForyGraalVMFeatureTest {

  private ForyGraalVMFeature feature;

  public static class PublicNoArgConstructorClass {
    private String field1;
    private int field2;
  }

  public static class ProtectedNoArgConstructorClass {
    protected ProtectedNoArgConstructorClass() {}
  }

  public static class PrivateParameterizedConstructorClass {
    private String data;

    private PrivateParameterizedConstructorClass(String data) {
      this.data = data;
    }
  }

  public interface SampleProxyInterface {
    void execute();
  }

  public static class NonInterfaceProxy {}

  public enum SampleEnum {
    VALUE
  }

  @BeforeMethod
  public void setUp() {
    GraalvmSupport.clearRegistrations();
    feature = new ForyGraalVMFeature();
  }

  @AfterMethod
  public void tearDown() {
    GraalvmSupport.clearRegistrations();
  }

  @Test
  public void testGetDescription() {
    String description = feature.getDescription();
    assertEquals(
        "Fory GraalVM Feature: Registers classes for serialization and proxy support.",
        description);
  }

  @Test
  public void testObjectCreatorsDetection() {
    assertTrue(
        GraalvmSupport.needReflectionRegisterForCreation(
            PrivateParameterizedConstructorClass.class),
        "Class without no-arg constructor requires reflective instantiation registration");

    assertFalse(
        GraalvmSupport.needReflectionRegisterForCreation(PublicNoArgConstructorClass.class),
        "Public no-arg constructor does not require reflective instantiation registration");

    assertFalse(
        GraalvmSupport.needReflectionRegisterForCreation(ProtectedNoArgConstructorClass.class),
        "Protected no-arg constructor does not require reflective instantiation registration");

    assertFalse(
        GraalvmSupport.needReflectionRegisterForCreation(SampleEnum.class),
        "Enums do not require reflective instantiation registration");
  }

  @Test
  public void testForyStaticMethods() {
    // Test that Fory static methods are accessible
    assertNotNull(GraalvmSupport.getRegisteredClasses(), "Registered classes should not be null");

    assertNotNull(GraalvmSupport.getProxyInterfaces(), "Proxy interfaces should not be null");
  }

  @Test
  public void testFeatureInstantiation() {
    assertNotNull(feature, "Feature should be instantiated");
    assertNotNull(feature.getDescription(), "Feature description should not be null");
  }

  @Test
  public void testAddProxyInterfaceRejectsNull() {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      try {
        GraalvmSupport.registerProxySupport(null);
        fail("Null proxy interface should throw NullPointerException");
      } catch (NullPointerException expected) {
        // expected
      }
    } else {
      GraalvmSupport.registerProxySupport(null);
    }
  }

  @Test
  public void testAddProxyInterfaceRejectsNonInterface() {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      try {
        GraalvmSupport.registerProxySupport(NonInterfaceProxy.class);
        fail("Non-interface proxy type should throw IllegalArgumentException");
      } catch (IllegalArgumentException expected) {
        // expected
      }
    } else {
      GraalvmSupport.registerProxySupport(NonInterfaceProxy.class);
    }
  }

  @Test
  public void testClearRegistrationsResetsState() {
    Fory builderInstance = Fory.builder().build();
    GraalvmSupport.clearRegistrations();
    builderInstance.register(PublicNoArgConstructorClass.class);
    GraalvmSupport.registerProxySupport(SampleProxyInterface.class);

    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      assertFalse(GraalvmSupport.getRegisteredClasses().isEmpty());
      assertFalse(GraalvmSupport.getProxyInterfaces().isEmpty());

      GraalvmSupport.clearRegistrations();

      assertTrue(GraalvmSupport.getRegisteredClasses().isEmpty());
      assertTrue(GraalvmSupport.getProxyInterfaces().isEmpty());
    } else {
      assertTrue(GraalvmSupport.getRegisteredClasses().isEmpty());
      assertTrue(GraalvmSupport.getProxyInterfaces().isEmpty());

      GraalvmSupport.clearRegistrations();

      assertTrue(GraalvmSupport.getRegisteredClasses().isEmpty());
      assertTrue(GraalvmSupport.getProxyInterfaces().isEmpty());
    }
  }
}

