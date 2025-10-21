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

import static org.junit.Assert.*;

import org.apache.fory.Fory;
import org.apache.fory.util.GraalvmSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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

  @Before
  public void setUp() {
    GraalvmSupport.clearRegistrations();
    feature = new ForyGraalVMFeature();
  }

  @After
  public void tearDown() {
    GraalvmSupport.clearRegistrations();
  }

  @Test
  public void testGetDescription() {
    String description = feature.getDescription();
    assertEquals(
        "Fory GraalVM Feature: Registers classes for serialization, proxying, and unsafe allocation.",
        description);
  }

  @Test
  public void testObjectCreatorsProblematicDetection() {
    assertTrue(
        "Class without no-arg constructor should be problematic",
        GraalvmSupport.needReflectionRegisterForCreation(
            PrivateParameterizedConstructorClass.class));

    assertFalse(
        "Public no-arg constructor should not be problematic",
        GraalvmSupport.needReflectionRegisterForCreation(PublicNoArgConstructorClass.class));

    assertFalse(
        "Protected no-arg constructor should not be problematic",
        GraalvmSupport.needReflectionRegisterForCreation(ProtectedNoArgConstructorClass.class));

    assertFalse(
        "Enums should not be considered problematic",
        GraalvmSupport.needReflectionRegisterForCreation(SampleEnum.class));
  }

  @Test
  public void testForyStaticMethods() {
    // Test that Fory static methods are accessible
    assertNotNull("Registered classes should not be null", GraalvmSupport.getRegisteredClasses());

    assertNotNull("Proxy interfaces should not be null", GraalvmSupport.getProxyInterfaces());
  }

  @Test
  public void testFeatureInstantiation() {
    assertNotNull("Feature should be instantiated", feature);
    assertNotNull("Feature description should not be null", feature.getDescription());
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

    assertFalse(GraalvmSupport.getRegisteredClasses().isEmpty());
    assertFalse(GraalvmSupport.getProxyInterfaces().isEmpty());

    GraalvmSupport.clearRegistrations();

    assertTrue(GraalvmSupport.getRegisteredClasses().isEmpty());
    assertTrue(GraalvmSupport.getProxyInterfaces().isEmpty());
  }
}
