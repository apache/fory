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

package org.apache.fory.annotation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.type.Descriptor;
import org.testng.annotations.Test;

public class ForyFieldTest extends ForyTestBase {

  /** Example 1: Simple Value Object */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Point {
    @ForyField(id = 0)
    public double x;

    @ForyField(id = 1)
    public double y;
  }

  @Test
  public void testSimpleValueObject() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    Point point = new Point(3.14, 2.71);
    byte[] bytes = fory.serialize(point);
    Point deserialized = (Point) fory.deserialize(bytes);
    assertEquals(deserialized.x, 3.14);
    assertEquals(deserialized.y, 2.71);
  }

  /** Example 2: Entity with Optional Fields */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class User {
    @ForyField(id = 0)
    public long userId;

    @ForyField(id = 1)
    public String username;

    @Nullable
    @ForyField(id = 2)
    public String email; // Can be null during account creation

    @Nullable
    @ForyField(id = 3)
    public String phoneNumber; // Optional contact method

    @ForyField(id = 4)
    public long createdAt;
  }

  @Test
  public void testEntityWithOptionalFields() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    User user = new User(123L, "john_doe", "john@example.com", null, System.currentTimeMillis());
    byte[] bytes = fory.serialize(user);
    User deserialized = (User) fory.deserialize(bytes);
    assertEquals(deserialized.userId, 123L);
    assertEquals(deserialized.username, "john_doe");
    assertEquals(deserialized.email, "john@example.com");
    assertNull(deserialized.phoneNumber);
  }

  @Test
  public void testEntityWithAllNullOptionalFields() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    User user = new User(456L, "jane_doe", null, null, System.currentTimeMillis());
    byte[] bytes = fory.serialize(user);
    User deserialized = (User) fory.deserialize(bytes);
    assertEquals(deserialized.userId, 456L);
    assertEquals(deserialized.username, "jane_doe");
    assertNull(deserialized.email);
    assertNull(deserialized.phoneNumber);
  }

  /** Example 3: Shared Object References */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Customer {
    @ForyField(id = 0)
    public long customerId;

    @ForyField(id = 1)
    public String name;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Order {
    @ForyField(id = 0)
    public long orderId;

    @ForyField(id = 1)
    @Ref
    public Customer customer; // Same Customer might appear in many orders

    @Nullable
    @ForyField(id = 2)
    public String notes; // Unique per order
  }

  @Test
  public void testSharedObjectReferences() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    Customer customer = new Customer(1L, "Alice");
    Order order1 = new Order(100L, customer, "First order");
    Order order2 = new Order(101L, customer, null);

    // Serialize orders that share the same customer
    List<Order> orders = new ArrayList<>();
    orders.add(order1);
    orders.add(order2);

    byte[] bytes = fory.serialize(orders);
    @SuppressWarnings("unchecked")
    List<Order> deserialized = (List<Order>) fory.deserialize(bytes);

    assertEquals(deserialized.size(), 2);
    assertEquals(deserialized.get(0).orderId, 100L);
    assertEquals(deserialized.get(1).orderId, 101L);
    assertEquals(deserialized.get(0).customer.customerId, 1L);
    assertEquals(deserialized.get(1).customer.customerId, 1L);
    // Both orders should reference the same customer object due to @Ref
    // (though this is more about serialization efficiency than behavior)
  }

  /** Test nullable override */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class DefaultNullableTest {
    @ForyField(id = 0)
    public String field1;

    @Nullable
    @ForyField(id = 1)
    public String field2;
  }

  @Test
  public void testNullableDefaults() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    DefaultNullableTest obj = new DefaultNullableTest("value1", null);
    byte[] bytes = fory.serialize(obj);
    DefaultNullableTest deserialized = (DefaultNullableTest) fory.deserialize(bytes);
    assertEquals(deserialized.field1, "value1");
    assertNull(deserialized.field2);
  }

  /** Test field-wrapper reference tracking defaults */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class DefaultRefTest {
    @ForyField(id = 0) // no @Ref, so field-wrapper ref tracking is disabled
    public String field1;

    @ForyField(id = 1)
    @Ref
    public String field2;
  }

  @Test
  public void testRefDefaults() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    DefaultRefTest obj = new DefaultRefTest("value1", "value2");
    byte[] bytes = fory.serialize(obj);
    DefaultRefTest deserialized = (DefaultRefTest) fory.deserialize(bytes);
    assertEquals(deserialized.field1, "value1");
    assertEquals(deserialized.field2, "value2");
  }

  /** Test mixed annotations and non-annotations */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class MixedFieldsTest {
    @ForyField(id = 0)
    public String annotatedField;

    public String regularField; // No annotation

    @Nullable
    @ForyField(id = 1)
    public String anotherAnnotatedField;
  }

  @Test
  public void testMixedAnnotatedAndRegularFields() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    MixedFieldsTest obj = new MixedFieldsTest("annotated", "regular", null);
    byte[] bytes = fory.serialize(obj);
    MixedFieldsTest deserialized = (MixedFieldsTest) fory.deserialize(bytes);
    assertEquals(deserialized.annotatedField, "annotated");
    assertEquals(deserialized.regularField, "regular");
    assertNull(deserialized.anotherAnnotatedField);
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class RefOwnerDefaults {
    @ForyField(id = 0)
    public Customer foryFieldOnly;

    @Ref(enable = false)
    public Customer refDisabled;
  }

  @Test
  public void testRefAnnotationOwnsNativeRefOverride() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    List<Descriptor> descriptors =
        fory.getTypeResolver().getFieldDescriptors(RefOwnerDefaults.class, true);

    assertTrue(descriptorNamed(descriptors, "foryFieldOnly").isTrackingRef());
    assertFalse(descriptorNamed(descriptors, "refDisabled").isTrackingRef());
  }

  private static Descriptor descriptorNamed(List<Descriptor> descriptors, String name) {
    for (Descriptor descriptor : descriptors) {
      if (descriptor.getName().equals(name)) {
        return descriptor;
      }
    }
    throw new AssertionError("Descriptor not found: " + name);
  }

  /** Test with primitive types */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PrimitiveFieldsTest {
    @ForyField(id = 0) // primitives are never null
    public int intField;

    @ForyField(id = 1)
    public long longField;

    @ForyField(id = 2)
    public boolean booleanField;

    @ForyField(id = 3)
    public Integer boxedInt;
  }

  @Test
  public void testPrimitiveFields() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    PrimitiveFieldsTest obj = new PrimitiveFieldsTest(42, 123456789L, true, 99);
    byte[] bytes = fory.serialize(obj);
    PrimitiveFieldsTest deserialized = (PrimitiveFieldsTest) fory.deserialize(bytes);
    assertEquals(deserialized.intField, 42);
    assertEquals(deserialized.longField, 123456789L);
    assertTrue(deserialized.booleanField);
    assertEquals(deserialized.boxedInt, Integer.valueOf(99));
  }
}
