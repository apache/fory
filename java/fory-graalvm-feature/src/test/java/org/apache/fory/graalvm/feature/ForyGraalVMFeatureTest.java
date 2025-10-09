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

import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.reflect.ObjectCreators;
import org.junit.Before;
import org.junit.Test;

public class ForyGraalVMFeatureTest {

  private ForyGraalVMFeature feature;

  public static class TestClass {
    private String field1;
    private int field2;
  }

  public static class ProblematicClass {
    private String data;

    private ProblematicClass() {}
  }

  @Before
  public void setUp() {
    feature = new ForyGraalVMFeature();
  }

  @Test
  public void testGetDescription() {
    String description = feature.getDescription();
    assertEquals(
        "Fory GraalVM Feature: Registers classes for serialization, proxying, and unsafe allocation.",
        description);
  }

  @Test
  public void testObjectCreatorsIntegration() {
    // Test that ObjectCreators.isProblematicForCreation works correctly
    boolean isProblematic = ObjectCreators.isProblematicForCreation(ProblematicClass.class);
    assertTrue("ProblematicClass should be detected as problematic", isProblematic);

    boolean isNotProblematic = ObjectCreators.isProblematicForCreation(TestClass.class);
    assertFalse("TestClass should not be detected as problematic", isNotProblematic);
  }

  @Test
  public void testForyStaticMethods() {
    // Test that Fory static methods are accessible
    Set<Class<?>> registeredClasses = Fory.getRegisteredClasses();
    assertNotNull("Registered classes should not be null", registeredClasses);

    Set<Class<?>> proxyInterfaces = Fory.getProxyInterfaces();
    assertNotNull("Proxy interfaces should not be null", proxyInterfaces);
  }

  @Test
  public void testFeatureInstantiation() {
    assertNotNull("Feature should be instantiated", feature);
    assertNotNull("Feature description should not be null", feature.getDescription());
  }
}
