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

package org.apache.fory.util;

import org.apache.fory.reflect.ObjectCreators;
import org.apache.fory.util.record.RecordUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

public class GraalvmSupportRecordTest {

  public static class RegularClass {
    public int intField;
    public String stringField;

    public RegularClass() {}

    public RegularClass(int intField, String stringField) {
      this.intField = intField;
      this.stringField = stringField;
    }
  }

  private static class PrivateClass {
    @SuppressWarnings("unused")
    private final int value;

    @SuppressWarnings("unused")
    public PrivateClass(int value) {
      this.value = value;
    }
  }

  @Test
  public void testIsRecordConstructorPublicAccessible_WithNonRecord() {
    boolean result = GraalvmSupport.isRecordConstructorPublicAccessible(RegularClass.class);
    Assert.assertFalse(result, "Non-Record class should return false");
  }

  @Test
  public void testIsRecordConstructorPublicAccessible_WithObject() {
    boolean result = GraalvmSupport.isRecordConstructorPublicAccessible(Object.class);
    Assert.assertFalse(result, "Object class should return false");
  }

  @Test
  public void testIsRecordConstructorPublicAccessible_WithString() {
    boolean result = GraalvmSupport.isRecordConstructorPublicAccessible(String.class);
    Assert.assertFalse(result, "String class should return false");
  }

  @Test
  public void testObjectCreators_IsProblematicForCreation_WithRegularClass() {
    boolean result = ObjectCreators.isProblematicForCreation(RegularClass.class);
    Assert.assertFalse(
        result, "RegularClass with no-arg constructor should not be problematic for creation");
  }

  @Test
  public void testBackwardCompatibility_OnOlderJDK() {
    boolean result = GraalvmSupport.isRecordConstructorPublicAccessible(RegularClass.class);
    Assert.assertFalse(result, "Should return false for non-Record classes on any JDK version");

    try {
      GraalvmSupport.isRecordConstructorPublicAccessible(Object.class);
      GraalvmSupport.isRecordConstructorPublicAccessible(String.class);
      GraalvmSupport.isRecordConstructorPublicAccessible(Integer.class);
      Assert.assertTrue(true, "Method should not throw exceptions on older JDK versions");
    } catch (Exception e) {
      Assert.fail("Method should not throw exceptions on older JDK versions", e);
    }
  }

  @Test
  public void testObjectCreators_BackwardCompatibility() {
    Assert.assertFalse(ObjectCreators.isProblematicForCreation(RegularClass.class));
    Assert.assertFalse(ObjectCreators.isProblematicForCreation(Object.class));
    Assert.assertFalse(ObjectCreators.isProblematicForCreation(String.class));

    Assert.assertTrue(ObjectCreators.isProblematicForCreation(PrivateClass.class));

    Assert.assertFalse(ObjectCreators.isProblematicForCreation(Runnable.class));
  }

  @Test
  public void testRecordUtilsIntegration() {
    Assert.assertFalse(RecordUtils.isRecord(RegularClass.class));
    Assert.assertFalse(RecordUtils.isRecord(Object.class));
    Assert.assertFalse(RecordUtils.isRecord(String.class));

    Assert.assertFalse(GraalvmSupport.isRecordConstructorPublicAccessible(RegularClass.class));
    Assert.assertFalse(GraalvmSupport.isRecordConstructorPublicAccessible(Object.class));
    Assert.assertFalse(GraalvmSupport.isRecordConstructorPublicAccessible(String.class));
  }
}
