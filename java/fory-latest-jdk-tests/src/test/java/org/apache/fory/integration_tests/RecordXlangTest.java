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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.DeflaterMetaCompressor;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests for Java Records with cross-language serialization nullable field handling.
 *
 * <p>This test verifies that Java Records work correctly with Fory's xlang serialization, testing
 * nullable field handling in both SCHEMA_CONSISTENT and COMPATIBLE modes.
 *
 * <p>For each mode, we test:
 *
 * <ul>
 *   <li>Record as Java data type with annotations on constructor parameters
 *   <li>POJO as xlang type representation (what other languages would use)
 *   <li>Serialization from Record, deserialization to POJO and vice versa
 * </ul>
 */
public class RecordXlangTest {

  @DataProvider(name = "enableCodegen")
  public static Object[][] enableCodegen() {
    return new Object[][] {{true}, {false}};
  }

  // ==================== SCHEMA_CONSISTENT Mode Records and POJOs ====================

  /**
   * Record for SCHEMA_CONSISTENT mode testing.
   *
   * <p>Fields:
   *
   * <ul>
   *   <li>Base non-nullable fields: primitives and reference types
   *   <li>Nullable fields: boxed types and reference types with @ForyField(nullable=true)
   * </ul>
   */
  public record NullableRecordSchemaConsistent(
      // Base non-nullable primitive fields
      byte byteField,
      short shortField,
      int intField,
      long longField,
      float floatField,
      double doubleField,
      boolean boolField,
      // Base non-nullable reference fields
      String stringField,
      List<String> listField,
      Set<String> setField,
      Map<String, String> mapField,
      // Nullable fields - boxed types
      @ForyField(nullable = true) Integer nullableInt,
      @ForyField(nullable = true) Long nullableLong,
      @ForyField(nullable = true) Float nullableFloat,
      // Nullable fields - reference types
      @ForyField(nullable = true) Double nullableDouble,
      @ForyField(nullable = true) Boolean nullableBool,
      @ForyField(nullable = true) String nullableString,
      @ForyField(nullable = true) List<String> nullableList,
      @ForyField(nullable = true) Set<String> nullableSet,
      @ForyField(nullable = true) Map<String, String> nullableMap) {}

  /**
   * POJO for SCHEMA_CONSISTENT mode xlang type. Same structure as the Record - both Java and other
   * languages use the same field nullability.
   */
  @Data
  public static class NullablePojoSchemaConsistentXlang {
    // Base non-nullable primitive fields
    byte byteField;
    short shortField;
    int intField;
    long longField;
    float floatField;
    double doubleField;
    boolean boolField;

    // Base non-nullable reference fields
    String stringField;
    List<String> listField;
    Set<String> setField;
    Map<String, String> mapField;

    // Nullable fields - boxed types
    @ForyField(nullable = true)
    Integer nullableInt;

    @ForyField(nullable = true)
    Long nullableLong;

    @ForyField(nullable = true)
    Float nullableFloat;

    // Nullable fields - reference types
    @ForyField(nullable = true)
    Double nullableDouble;

    @ForyField(nullable = true)
    Boolean nullableBool;

    @ForyField(nullable = true)
    String nullableString;

    @ForyField(nullable = true)
    List<String> nullableList;

    @ForyField(nullable = true)
    Set<String> nullableSet;

    @ForyField(nullable = true)
    Map<String, String> nullableMap;
  }

  // ==================== COMPATIBLE Mode Records and POJOs ====================

  /**
   * Record for COMPATIBLE mode testing. Matches XlangTestBase.NullableComprehensiveCompatible.
   *
   * <p>Fields are organized as:
   *
   * <ul>
   *   <li>Group 1 (non-nullable in Java): primitives, boxed types, and reference types
   *   <li>Group 2 (nullable in Java): boxed types and reference types
   *       with @ForyField(nullable=true)
   * </ul>
   *
   * <p>In Go, Group 1 fields are nullable (*int8, *int16, etc.) and Group 2 fields are non-nullable
   * (int32, int64, etc.) - this tests schema evolution with inverted nullability.
   */
  public record NullableRecordCompatible(
      // Group 1: Non-nullable in Java (nullable in Go with pointer types)
      // Primitive fields
      byte byteField,
      short shortField,
      int intField,
      long longField,
      float floatField,
      double doubleField,
      boolean boolField,
      // Boxed fields (non-nullable in Java)
      Integer boxedInt,
      Long boxedLong,
      Float boxedFloat,
      Double boxedDouble,
      Boolean boxedBool,
      // Reference fields (non-nullable in Java)
      String stringField,
      List<String> listField,
      Set<String> setField,
      Map<String, String> mapField,
      // Group 2: Nullable in Java (non-nullable in Go with value types)
      // Boxed types with @ForyField(nullable=true)
      @ForyField(nullable = true) Integer nullableInt1,
      @ForyField(nullable = true) Long nullableLong1,
      @ForyField(nullable = true) Float nullableFloat1,
      @ForyField(nullable = true) Double nullableDouble1,
      @ForyField(nullable = true) Boolean nullableBool1,
      // Reference types with @ForyField(nullable=true)
      @ForyField(nullable = true) String nullableString2,
      @ForyField(nullable = true) List<String> nullableList2,
      @ForyField(nullable = true) Set<String> nullableSet2,
      @ForyField(nullable = true) Map<String, String> nullableMap2) {}

  /**
   * POJO for COMPATIBLE mode xlang type with INVERTED nullability.
   *
   * <p>This matches Go's NullableComprehensiveCompatible struct where:
   *
   * <ul>
   *   <li>Group 1 fields are nullable (pointer types in Go: *int8, *int16, etc.)
   *   <li>Group 2 fields are non-nullable (value types in Go: int32, int64, etc.)
   * </ul>
   */
  @Data
  public static class NullablePojoCompatibleXlang {
    // Group 1: NULLABLE in xlang (non-nullable in Java Record)
    // These match Go's pointer types (*int8, *int16, etc.)
    @ForyField(nullable = true)
    Byte byteField;

    @ForyField(nullable = true)
    Short shortField;

    @ForyField(nullable = true)
    Integer intField;

    @ForyField(nullable = true)
    Long longField;

    @ForyField(nullable = true)
    Float floatField;

    @ForyField(nullable = true)
    Double doubleField;

    @ForyField(nullable = true)
    Boolean boolField;

    @ForyField(nullable = true)
    Integer boxedInt;

    @ForyField(nullable = true)
    Long boxedLong;

    @ForyField(nullable = true)
    Float boxedFloat;

    @ForyField(nullable = true)
    Double boxedDouble;

    @ForyField(nullable = true)
    Boolean boxedBool;

    @ForyField(nullable = true)
    String stringField;

    @ForyField(nullable = true)
    List<String> listField;

    @ForyField(nullable = true)
    Set<String> setField;

    @ForyField(nullable = true)
    Map<String, String> mapField;

    // Group 2: NON-NULLABLE in xlang (nullable in Java Record)
    // These match Go's value types (int32, int64, etc.)
    int nullableInt1;
    long nullableLong1;
    float nullableFloat1;
    double nullableDouble1;
    boolean nullableBool1;
    String nullableString2;
    List<String> nullableList2;
    Set<String> nullableSet2;
    Map<String, String> nullableMap2;
  }

  // ==================== SCHEMA_CONSISTENT Mode Tests ====================

  @Test(dataProvider = "enableCodegen")
  public void testRecordNullableFieldSchemaConsistentNotNull(boolean enableCodegen) {
    Fory fory =
        Fory.builder().withXlang(true).withCompatible(false).withCodegen(enableCodegen).build();
    fory.register(NullableRecordSchemaConsistent.class, 0);

    Fory foryXlang =
        Fory.builder().withXlang(true).withCompatible(false).withCodegen(enableCodegen).build();
    foryXlang.register(NullablePojoSchemaConsistentXlang.class, 0);

    // Create record with all nullable fields having values
    NullableRecordSchemaConsistent record =
        new NullableRecordSchemaConsistent(
            // Base non-nullable primitive fields
            (byte) 1,
            (short) 2,
            42,
            123456789L,
            1.5f,
            2.5,
            true,
            // Base non-nullable reference fields
            "hello",
            Arrays.asList("a", "b", "c"),
            new HashSet<>(Arrays.asList("x", "y")),
            createMap("key1", "value1", "key2", "value2"),
            // Nullable fields - all have values
            100,
            200L,
            1.5f,
            2.5,
            false,
            "nullable_value",
            Arrays.asList("p", "q"),
            new HashSet<>(Arrays.asList("m", "n")),
            createMap("nk1", "nv1"));

    // Serialize record
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(512);
    fory.serialize(buffer, record);

    // Deserialize as POJO (simulating xlang)
    buffer.readerIndex(0);
    NullablePojoSchemaConsistentXlang xlangObj =
        (NullablePojoSchemaConsistentXlang) foryXlang.deserialize(buffer);

    // Verify all fields
    Assert.assertEquals(xlangObj.byteField, record.byteField());
    Assert.assertEquals(xlangObj.shortField, record.shortField());
    Assert.assertEquals(xlangObj.intField, record.intField());
    Assert.assertEquals(xlangObj.longField, record.longField());
    Assert.assertEquals(xlangObj.floatField, record.floatField(), 0.001f);
    Assert.assertEquals(xlangObj.doubleField, record.doubleField(), 0.001);
    Assert.assertEquals(xlangObj.boolField, record.boolField());
    Assert.assertEquals(xlangObj.stringField, record.stringField());
    Assert.assertEquals(xlangObj.listField, record.listField());
    Assert.assertEquals(xlangObj.setField, record.setField());
    Assert.assertEquals(xlangObj.mapField, record.mapField());
    Assert.assertEquals(xlangObj.nullableInt, record.nullableInt());
    Assert.assertEquals(xlangObj.nullableLong, record.nullableLong());
    Assert.assertEquals(xlangObj.nullableFloat, record.nullableFloat());
    Assert.assertEquals(xlangObj.nullableDouble, record.nullableDouble());
    Assert.assertEquals(xlangObj.nullableBool, record.nullableBool());
    Assert.assertEquals(xlangObj.nullableString, record.nullableString());
    Assert.assertEquals(xlangObj.nullableList, record.nullableList());
    Assert.assertEquals(xlangObj.nullableSet, record.nullableSet());
    Assert.assertEquals(xlangObj.nullableMap, record.nullableMap());

    // Serialize POJO back and deserialize as Record
    MemoryBuffer buffer2 = MemoryBuffer.newHeapBuffer(512);
    foryXlang.serialize(buffer2, xlangObj);
    buffer2.readerIndex(0);
    NullableRecordSchemaConsistent result =
        (NullableRecordSchemaConsistent) fory.deserialize(buffer2);
    Assert.assertEquals(result, record);
  }

  @Test(dataProvider = "enableCodegen")
  public void testRecordNullableFieldSchemaConsistentNull(boolean enableCodegen) {
    Fory fory =
        Fory.builder().withXlang(true).withCompatible(false).withCodegen(enableCodegen).build();
    fory.register(NullableRecordSchemaConsistent.class, 0);

    Fory foryXlang =
        Fory.builder().withXlang(true).withCompatible(false).withCodegen(enableCodegen).build();
    foryXlang.register(NullablePojoSchemaConsistentXlang.class, 0);

    // Create record with all nullable fields as null
    NullableRecordSchemaConsistent record =
        new NullableRecordSchemaConsistent(
            // Base non-nullable primitive fields
            (byte) 1,
            (short) 2,
            42,
            123456789L,
            1.5f,
            2.5,
            true,
            // Base non-nullable reference fields
            "hello",
            Arrays.asList("a", "b", "c"),
            new HashSet<>(Arrays.asList("x", "y")),
            createMap("key1", "value1", "key2", "value2"),
            // Nullable fields - all null
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    // Serialize record
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(512);
    fory.serialize(buffer, record);

    // Deserialize as POJO (simulating xlang)
    buffer.readerIndex(0);
    NullablePojoSchemaConsistentXlang xlangObj =
        (NullablePojoSchemaConsistentXlang) foryXlang.deserialize(buffer);

    // Verify base fields
    Assert.assertEquals(xlangObj.byteField, record.byteField());
    Assert.assertEquals(xlangObj.shortField, record.shortField());
    Assert.assertEquals(xlangObj.intField, record.intField());
    Assert.assertEquals(xlangObj.longField, record.longField());
    Assert.assertEquals(xlangObj.floatField, record.floatField(), 0.001f);
    Assert.assertEquals(xlangObj.doubleField, record.doubleField(), 0.001);
    Assert.assertEquals(xlangObj.boolField, record.boolField());
    Assert.assertEquals(xlangObj.stringField, record.stringField());
    Assert.assertEquals(xlangObj.listField, record.listField());
    Assert.assertEquals(xlangObj.setField, record.setField());
    Assert.assertEquals(xlangObj.mapField, record.mapField());

    // Verify nullable fields are null
    Assert.assertNull(xlangObj.nullableInt);
    Assert.assertNull(xlangObj.nullableLong);
    Assert.assertNull(xlangObj.nullableFloat);
    Assert.assertNull(xlangObj.nullableDouble);
    Assert.assertNull(xlangObj.nullableBool);
    Assert.assertNull(xlangObj.nullableString);
    Assert.assertNull(xlangObj.nullableList);
    Assert.assertNull(xlangObj.nullableSet);
    Assert.assertNull(xlangObj.nullableMap);

    // Serialize POJO back and deserialize as Record
    MemoryBuffer buffer2 = MemoryBuffer.newHeapBuffer(512);
    foryXlang.serialize(buffer2, xlangObj);
    buffer2.readerIndex(0);
    NullableRecordSchemaConsistent result =
        (NullableRecordSchemaConsistent) fory.deserialize(buffer2);
    Assert.assertEquals(result, record);
  }

  // ==================== COMPATIBLE Mode Tests ====================

  @Test(dataProvider = "enableCodegen")
  public void testRecordNullableFieldCompatibleNotNull(boolean enableCodegen) {
    Fory fory =
        Fory.builder()
            .withXlang(true)
            .withCompatible(true)
            .withCodegen(enableCodegen)
            .withMetaCompressor(new DeflaterMetaCompressor())
            .build();
    fory.register(NullableRecordCompatible.class, 1);

    Fory foryXlang =
        Fory.builder()
            .withXlang(true)
            .withCompatible(true)
            .withCodegen(enableCodegen)
            .withMetaCompressor(new DeflaterMetaCompressor())
            .build();
    foryXlang.register(NullablePojoCompatibleXlang.class, 1);

    // Create record with all fields having values
    NullableRecordCompatible record =
        new NullableRecordCompatible(
            // Group 1: Non-nullable in Java (nullable in xlang)
            (byte) 1,
            (short) 2,
            42,
            123456789L,
            1.5f,
            2.5,
            true,
            10,
            20L,
            1.1f,
            2.2,
            true,
            "hello",
            Arrays.asList("a", "b", "c"),
            new HashSet<>(Arrays.asList("x", "y")),
            createMap("key1", "value1", "key2", "value2"),
            // Group 2: Nullable in Java (non-nullable in xlang)
            100,
            200L,
            1.5f,
            2.5,
            false,
            "nullable_value",
            Arrays.asList("p", "q"),
            new HashSet<>(Arrays.asList("m", "n")),
            createMap("nk1", "nv1"));

    // Serialize record
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(1024);
    fory.serialize(buffer, record);

    // Deserialize as POJO with inverted nullability (simulating xlang)
    buffer.readerIndex(0);
    NullablePojoCompatibleXlang xlangObj =
        (NullablePojoCompatibleXlang) foryXlang.deserialize(buffer);

    // Verify Group 1 fields (non-nullable in Record -> nullable in xlang POJO)
    Assert.assertEquals((byte) xlangObj.byteField, record.byteField());
    Assert.assertEquals((short) xlangObj.shortField, record.shortField());
    Assert.assertEquals((int) xlangObj.intField, record.intField());
    Assert.assertEquals((long) xlangObj.longField, record.longField());
    Assert.assertEquals(xlangObj.floatField, record.floatField(), 0.001f);
    Assert.assertEquals(xlangObj.doubleField, record.doubleField(), 0.001);
    Assert.assertEquals(xlangObj.boolField, record.boolField());
    Assert.assertEquals(xlangObj.boxedInt, record.boxedInt());
    Assert.assertEquals(xlangObj.boxedLong, record.boxedLong());
    Assert.assertEquals(xlangObj.boxedFloat, record.boxedFloat());
    Assert.assertEquals(xlangObj.boxedDouble, record.boxedDouble());
    Assert.assertEquals(xlangObj.boxedBool, record.boxedBool());
    Assert.assertEquals(xlangObj.stringField, record.stringField());
    Assert.assertEquals(xlangObj.listField, record.listField());
    Assert.assertEquals(xlangObj.setField, record.setField());
    Assert.assertEquals(xlangObj.mapField, record.mapField());

    // Verify Group 2 fields (nullable in Record -> non-nullable in xlang POJO)
    Assert.assertEquals(xlangObj.nullableInt1, (int) record.nullableInt1());
    Assert.assertEquals(xlangObj.nullableLong1, (long) record.nullableLong1());
    Assert.assertEquals(xlangObj.nullableFloat1, record.nullableFloat1(), 0.001f);
    Assert.assertEquals(xlangObj.nullableDouble1, record.nullableDouble1(), 0.001);
    Assert.assertEquals(xlangObj.nullableBool1, record.nullableBool1());
    Assert.assertEquals(xlangObj.nullableString2, record.nullableString2());
    Assert.assertEquals(xlangObj.nullableList2, record.nullableList2());
    Assert.assertEquals(xlangObj.nullableSet2, record.nullableSet2());
    Assert.assertEquals(xlangObj.nullableMap2, record.nullableMap2());

    // Serialize POJO back and deserialize as Record
    MemoryBuffer buffer2 = MemoryBuffer.newHeapBuffer(1024);
    foryXlang.serialize(buffer2, xlangObj);
    buffer2.readerIndex(0);
    NullableRecordCompatible result = (NullableRecordCompatible) fory.deserialize(buffer2);
    Assert.assertEquals(result, record);
  }

  @Test(dataProvider = "enableCodegen")
  public void testRecordNullableFieldCompatibleNull(boolean enableCodegen) {
    Fory fory =
        Fory.builder()
            .withXlang(true)
            .withCompatible(true)
            .withCodegen(enableCodegen)
            .withMetaCompressor(new DeflaterMetaCompressor())
            .build();
    fory.register(NullableRecordCompatible.class, 1);

    Fory foryXlang =
        Fory.builder()
            .withXlang(true)
            .withCompatible(true)
            .withCodegen(enableCodegen)
            .withMetaCompressor(new DeflaterMetaCompressor())
            .build();
    foryXlang.register(NullablePojoCompatibleXlang.class, 1);

    // Create record with Group 1 having values, Group 2 all null
    NullableRecordCompatible record =
        new NullableRecordCompatible(
            // Group 1: Non-nullable in Java (nullable in xlang) - must have values
            (byte) 1,
            (short) 2,
            42,
            123456789L,
            1.5f,
            2.5,
            true,
            10,
            20L,
            1.1f,
            2.2,
            true,
            "hello",
            Arrays.asList("a", "b", "c"),
            new HashSet<>(Arrays.asList("x", "y")),
            createMap("key1", "value1", "key2", "value2"),
            // Group 2: Nullable in Java (non-nullable in xlang) - all null
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    // Serialize record
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(1024);
    fory.serialize(buffer, record);

    // Deserialize as POJO with inverted nullability (simulating xlang)
    buffer.readerIndex(0);
    NullablePojoCompatibleXlang xlangObj =
        (NullablePojoCompatibleXlang) foryXlang.deserialize(buffer);

    // Verify Group 1 fields
    Assert.assertEquals((byte) xlangObj.byteField, record.byteField());
    Assert.assertEquals((short) xlangObj.shortField, record.shortField());
    Assert.assertEquals((int) xlangObj.intField, record.intField());
    Assert.assertEquals((long) xlangObj.longField, record.longField());
    Assert.assertEquals(xlangObj.floatField, record.floatField(), 0.001f);
    Assert.assertEquals(xlangObj.doubleField, record.doubleField(), 0.001);
    Assert.assertEquals(xlangObj.boolField, record.boolField());
    Assert.assertEquals(xlangObj.boxedInt, record.boxedInt());
    Assert.assertEquals(xlangObj.boxedLong, record.boxedLong());
    Assert.assertEquals(xlangObj.boxedFloat, record.boxedFloat());
    Assert.assertEquals(xlangObj.boxedDouble, record.boxedDouble());
    Assert.assertEquals(xlangObj.boxedBool, record.boxedBool());
    Assert.assertEquals(xlangObj.stringField, record.stringField());
    Assert.assertEquals(xlangObj.listField, record.listField());
    Assert.assertEquals(xlangObj.setField, record.setField());
    Assert.assertEquals(xlangObj.mapField, record.mapField());

    // Verify Group 2 fields - xlang POJO has non-nullable fields, so nulls become defaults
    // Primitive types get default values
    Assert.assertEquals(xlangObj.nullableInt1, 0);
    Assert.assertEquals(xlangObj.nullableLong1, 0L);
    Assert.assertEquals(xlangObj.nullableFloat1, 0.0f, 0.001f);
    Assert.assertEquals(xlangObj.nullableDouble1, 0.0, 0.001);
    Assert.assertEquals(xlangObj.nullableBool1, false);
    // Reference types become null initially
    Assert.assertNull(xlangObj.nullableString2);
    Assert.assertNull(xlangObj.nullableList2);
    Assert.assertNull(xlangObj.nullableSet2);
    Assert.assertNull(xlangObj.nullableMap2);

    // Fill null reference fields with default values to simulate Go/Rust behavior
    // In Go/Rust, non-nullable reference fields get empty values, not null
    xlangObj.nullableString2 = "";
    xlangObj.nullableList2 = new ArrayList<>();
    xlangObj.nullableSet2 = new HashSet<>();
    xlangObj.nullableMap2 = new HashMap<>();

    // Serialize POJO back and deserialize as Record
    MemoryBuffer buffer2 = MemoryBuffer.newHeapBuffer(1024);
    foryXlang.serialize(buffer2, xlangObj);
    buffer2.readerIndex(0);
    NullableRecordCompatible result = (NullableRecordCompatible) fory.deserialize(buffer2);

    // Build expected: Group 2 fields will have default values instead of null
    NullableRecordCompatible expected =
        new NullableRecordCompatible(
            // Group 1: unchanged
            record.byteField(),
            record.shortField(),
            record.intField(),
            record.longField(),
            record.floatField(),
            record.doubleField(),
            record.boolField(),
            record.boxedInt(),
            record.boxedLong(),
            record.boxedFloat(),
            record.boxedDouble(),
            record.boxedBool(),
            record.stringField(),
            record.listField(),
            record.setField(),
            record.mapField(),
            // Group 2: xlang's non-nullable fields send default values
            0, // nullableInt1
            0L, // nullableLong1
            0.0f, // nullableFloat1
            0.0, // nullableDouble1
            false, // nullableBool1
            "", // nullableString2 - empty string
            new ArrayList<>(), // nullableList2 - empty list
            new HashSet<>(), // nullableSet2 - empty set
            new HashMap<>() // nullableMap2 - empty map
            );

    Assert.assertEquals(result, expected);
  }

  // ==================== Reference Tracking Tests ====================

  /** Record for inner struct in reference tracking tests. */
  public record RefInnerRecord(int id, String name) {}

  /**
   * Record for outer struct in reference tracking tests. Contains two fields that can point to the
   * same RefInnerRecord instance.
   */
  public record RefOuterRecord(
      @ForyField(ref = true, nullable = true, dynamic = ForyField.Dynamic.FALSE)
          RefInnerRecord inner1,
      @ForyField(ref = true, nullable = true, dynamic = ForyField.Dynamic.FALSE)
          RefInnerRecord inner2) {}

  /**
   * Test reference tracking with Record in SCHEMA_CONSISTENT mode. Creates an outer struct with two
   * fields pointing to the same inner struct instance. Verifies that reference identity is
   * preserved after serialization/deserialization.
   */
  @Test(dataProvider = "enableCodegen")
  public void testRecordRefSchemaConsistent(boolean enableCodegen) {
    Fory fory =
        Fory.builder()
            .withXlang(true)
            .withCompatible(false)
            .withRefTracking(true)
            .withCodegen(enableCodegen)
            .build();
    fory.register(RefInnerRecord.class, 501);
    fory.register(RefOuterRecord.class, 502);

    // Create inner record
    RefInnerRecord inner = new RefInnerRecord(42, "shared_inner");

    // Create outer record with both fields pointing to the same inner record
    RefOuterRecord outer = new RefOuterRecord(inner, inner);

    // Serialize and deserialize
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(256);
    fory.serialize(buffer, outer);
    buffer.readerIndex(0);
    RefOuterRecord result = (RefOuterRecord) fory.deserialize(buffer);

    // Verify reference identity is preserved
    Assert.assertSame(result.inner1(), result.inner2(), "inner1 and inner2 should be same object");
    Assert.assertEquals(result.inner1().id(), 42);
    Assert.assertEquals(result.inner1().name(), "shared_inner");
  }

  /**
   * Test reference tracking with Record in COMPATIBLE mode. Creates an outer struct with two fields
   * pointing to the same inner struct instance. Verifies that reference identity is preserved after
   * serialization/deserialization with schema evolution support.
   */
  @Test(dataProvider = "enableCodegen")
  public void testRecordRefCompatible(boolean enableCodegen) {
    Fory fory =
        Fory.builder()
            .withXlang(true)
            .withCompatible(true)
            .withRefTracking(true)
            .withCodegen(enableCodegen)
            .withMetaCompressor(new DeflaterMetaCompressor())
            .build();
    fory.register(RefInnerRecord.class, 503);
    fory.register(RefOuterRecord.class, 504);

    // Create inner record
    RefInnerRecord inner = new RefInnerRecord(99, "compatible_shared");

    // Create outer record with both fields pointing to the same inner record
    RefOuterRecord outer = new RefOuterRecord(inner, inner);

    // Serialize and deserialize
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(512);
    fory.serialize(buffer, outer);
    buffer.readerIndex(0);
    RefOuterRecord result = (RefOuterRecord) fory.deserialize(buffer);

    // Verify reference identity is preserved
    Assert.assertSame(result.inner1(), result.inner2(), "inner1 and inner2 should be same object");
    Assert.assertEquals(result.inner1().id(), 99);
    Assert.assertEquals(result.inner1().name(), "compatible_shared");
  }

  // ==================== Helper Methods ====================

  private static Map<String, String> createMap(String... keyValues) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    return map;
  }
}
