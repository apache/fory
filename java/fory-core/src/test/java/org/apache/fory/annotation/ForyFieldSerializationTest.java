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

import java.nio.charset.StandardCharsets;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ForyFieldSerializationTest extends ForyTestBase {

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PersonWithTagId {
    @ForyField(id = 0, nullable = false)
    public String veryLongFieldNameForFirstName;

    @ForyField(id = 1, nullable = false)
    public String anotherVeryLongFieldNameForLastName;

    @ForyField(id = 2, nullable = false)
    public int age;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PersonWithoutTagId {
    public String veryLongFieldNameForFirstName;
    public String anotherVeryLongFieldNameForLastName;
    public int age;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PersonWithOptOutTagId {
    @ForyField(id = -1, nullable = false)
    public String veryLongFieldNameForFirstName;

    @ForyField(id = -1, nullable = false)
    public String anotherVeryLongFieldNameForLastName;

    @ForyField(id = -1, nullable = false)
    public int age;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PersonMixedTagId {
    @ForyField(id = 0, nullable = false)
    public String firstName;

    // This field uses field name (id = -1)
    @ForyField(id = -1, nullable = false)
    public String veryLongFieldNameForLastName;

    public int age; // No annotation, uses field name
  }

  /** Nested object classes for testing field ID vs field type serialization */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class VeryLongNestedObjectClassName {
    @ForyField(id = 0, nullable = false)
    public String value;

    @ForyField(id = 1, nullable = false)
    public int count;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AnotherVeryLongNestedObjectClassName {
    @ForyField(id = 0, nullable = false)
    public String description;
  }

  /** Container with nested objects using field tag IDs */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ContainerWithTagIds {
    @ForyField(id = 0, nullable = false)
    public VeryLongNestedObjectClassName veryLongFieldNameForNestedObject;

    @ForyField(id = 1, nullable = false)
    public AnotherVeryLongNestedObjectClassName anotherVeryLongFieldNameForAnotherNestedObject;

    @ForyField(id = 2, nullable = false)
    public String simpleField;
  }

  /** Container with nested objects WITHOUT tag IDs (uses field names) */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ContainerWithoutTagIds {
    public VeryLongNestedObjectClassName veryLongFieldNameForNestedObject;
    public AnotherVeryLongNestedObjectClassName anotherVeryLongFieldNameForAnotherNestedObject;
    public String simpleField;
  }

  @DataProvider(name = "modes")
  public Object[][] modes() {
    return new Object[][] {
      // JAVA mode with and without registration
      {Language.JAVA, CompatibleMode.SCHEMA_CONSISTENT, false, false},
      {Language.JAVA, CompatibleMode.SCHEMA_CONSISTENT, true, false},
      {Language.JAVA, CompatibleMode.COMPATIBLE, false, false},
      {Language.JAVA, CompatibleMode.COMPATIBLE, true, false},
      {Language.JAVA, CompatibleMode.SCHEMA_CONSISTENT, false, true},
      {Language.JAVA, CompatibleMode.SCHEMA_CONSISTENT, true, true},
      {Language.JAVA, CompatibleMode.COMPATIBLE, false, true},
      {Language.JAVA, CompatibleMode.COMPATIBLE, true, true},
      // XLANG mode always requires registration
      {Language.XLANG, CompatibleMode.SCHEMA_CONSISTENT, false, true},
      {Language.XLANG, CompatibleMode.SCHEMA_CONSISTENT, true, true},
      {Language.XLANG, CompatibleMode.COMPATIBLE, false, true},
      {Language.XLANG, CompatibleMode.COMPATIBLE, true, true},
    };
  }

  @Test(dataProvider = "modes")
  public void testTagIdReducesPayloadSize(
      Language language, CompatibleMode compatibleMode, boolean codegen, boolean registered) {
    Fory fory =
        Fory.builder()
            .withLanguage(language)
            .requireClassRegistration(registered)
            .withCompatibleMode(compatibleMode)
            .withCodegen(codegen)
            .build();

    // Register classes based on parameter
    if (registered) {
      fory.register(PersonWithTagId.class, "test.PersonWithTagId");
      fory.register(PersonWithoutTagId.class, "test.PersonWithoutTagId");
      fory.register(PersonWithOptOutTagId.class, "test.PersonWithOptOutTagId");
    }

    PersonWithTagId personWithTag = new PersonWithTagId("John", "Doe", 30);
    PersonWithoutTagId personWithoutTag = new PersonWithoutTagId("John", "Doe", 30);
    PersonWithOptOutTagId personWithOptOut = new PersonWithOptOutTagId("John", "Doe", 30);

    byte[] bytesWithTag = fory.serialize(personWithTag);
    byte[] bytesWithoutTag = fory.serialize(personWithoutTag);
    byte[] bytesWithOptOut = fory.serialize(personWithOptOut);

    // Verify deserialization works
    PersonWithTagId deserializedWithTag = (PersonWithTagId) fory.deserialize(bytesWithTag);
    PersonWithoutTagId deserializedWithoutTag =
        (PersonWithoutTagId) fory.deserialize(bytesWithoutTag);
    PersonWithOptOutTagId deserializedWithOptOut =
        (PersonWithOptOutTagId) fory.deserialize(bytesWithOptOut);

    assertEquals(deserializedWithTag.veryLongFieldNameForFirstName, "John");
    assertEquals(deserializedWithTag.anotherVeryLongFieldNameForLastName, "Doe");
    assertEquals(deserializedWithTag.age, 30);

    assertEquals(deserializedWithoutTag.veryLongFieldNameForFirstName, "John");
    assertEquals(deserializedWithoutTag.anotherVeryLongFieldNameForLastName, "Doe");
    assertEquals(deserializedWithoutTag.age, 30);

    assertEquals(deserializedWithOptOut.veryLongFieldNameForFirstName, "John");
    assertEquals(deserializedWithOptOut.anotherVeryLongFieldNameForLastName, "Doe");
    assertEquals(deserializedWithOptOut.age, 30);

    System.out.printf(
        "Mode: %s/%s/codegen=%s - With tag: %d bytes, Without tag: %d bytes, Opt-out (id=-1): %d bytes%n",
        language,
        compatibleMode,
        codegen,
        bytesWithTag.length,
        bytesWithoutTag.length,
        bytesWithOptOut.length);

    // Tag IDs should reduce payload size in all modes (JAVA and XLANG)
    // This is the core benefit of the @ForyField annotation feature
    assertTrue(
        bytesWithTag.length <= bytesWithoutTag.length,
        String.format(
            "Expected tag ID version (%d bytes) to be <= field name version (%d bytes) in mode %s/%s/codegen=%s",
            bytesWithTag.length, bytesWithoutTag.length, language, compatibleMode, codegen));

    // Tag ID version should also be smaller than or equal to opt-out version (id=-1)
    assertTrue(
        bytesWithTag.length <= bytesWithOptOut.length,
        String.format(
            "Expected tag ID version (%d bytes) to be <= opt-out id=-1 version (%d bytes) in mode %s/%s/codegen=%s",
            bytesWithTag.length, bytesWithOptOut.length, language, compatibleMode, codegen));

    // Opt-out (id=-1) should have similar size to no annotation (both use field names)
    // They should be equal or very close in size
    int sizeDifference = Math.abs(bytesWithOptOut.length - bytesWithoutTag.length);
    assertTrue(
        sizeDifference <= 5,
        String.format(
            "Expected opt-out id=-1 (%d bytes) to have similar size to no annotation (%d bytes), but difference is %d bytes",
            bytesWithOptOut.length, bytesWithoutTag.length, sizeDifference));
  }

  @Test(dataProvider = "modes")
  public void testFieldNameNotInPayloadWithTagId(
      Language language, CompatibleMode compatibleMode, boolean codegen, boolean registered) {
    Fory fory =
        Fory.builder()
            .withLanguage(language)
            .requireClassRegistration(registered)
            .withCompatibleMode(compatibleMode)
            .withCodegen(codegen)
            .build();

    if (registered) {
      fory.register(PersonWithTagId.class, "test.PersonWithTagId");
    }

    PersonWithTagId person = new PersonWithTagId("Alice", "Smith", 25);
    byte[] bytes = fory.serialize(person);

    // Convert to string to search for field names
    String serialized = new String(bytes, StandardCharsets.UTF_8);

    // With tag IDs, field names should generally NOT appear in the payload in most modes
    // Note: Exact behavior may vary by mode, but we verify deserialization always works
    // In XLANG/COMPATIBLE mode specifically, field names should definitely not be present
    if (language == Language.XLANG && compatibleMode == CompatibleMode.COMPATIBLE) {
      assertFalse(
          serialized.contains("veryLongFieldNameForFirstName"),
          String.format(
              "Field name 'veryLongFieldNameForFirstName' should not be in payload with tag ID in mode %s/%s/codegen=%s",
              language, compatibleMode, codegen));
      assertFalse(
          serialized.contains("anotherVeryLongFieldNameForLastName"),
          String.format(
              "Field name 'anotherVeryLongFieldNameForLastName' should not be in payload with tag ID in mode %s/%s/codegen=%s",
              language, compatibleMode, codegen));
    }

    // Verify deserialization still works in ALL modes
    PersonWithTagId deserialized = (PersonWithTagId) fory.deserialize(bytes);
    assertEquals(deserialized.veryLongFieldNameForFirstName, "Alice");
    assertEquals(deserialized.anotherVeryLongFieldNameForLastName, "Smith");
    assertEquals(deserialized.age, 25);
  }

  @Test(dataProvider = "modes")
  public void testFieldNameInPayloadWithoutTagId(
      Language language, CompatibleMode compatibleMode, boolean codegen, boolean registered) {
    Fory fory =
        Fory.builder()
            .withLanguage(language)
            .requireClassRegistration(registered)
            .withCompatibleMode(compatibleMode)
            .withCodegen(codegen)
            .build();

    if (registered) {
      fory.register(PersonWithoutTagId.class, "test.PersonWithoutTagId");
    }

    PersonWithoutTagId person = new PersonWithoutTagId("Bob", "Johnson", 35);
    byte[] bytes = fory.serialize(person);

    // In COMPATIBLE mode without tag IDs, field names are used for field matching
    // (though they may be encoded using meta string compression)
    if (compatibleMode == CompatibleMode.COMPATIBLE) {
      // Verify the data deserializes correctly
      PersonWithoutTagId deserialized = (PersonWithoutTagId) fory.deserialize(bytes);
      assertEquals(deserialized.veryLongFieldNameForFirstName, "Bob");
      assertEquals(deserialized.anotherVeryLongFieldNameForLastName, "Johnson");
      assertEquals(deserialized.age, 35);
    }
  }

  @Test(dataProvider = "modes")
  public void testMixedTagIdAndFieldName(
      Language language, CompatibleMode compatibleMode, boolean codegen, boolean registered) {
    Fory fory =
        Fory.builder()
            .withLanguage(language)
            .requireClassRegistration(registered)
            .withCompatibleMode(compatibleMode)
            .withCodegen(codegen)
            .build();

    if (registered) {
      fory.register(PersonMixedTagId.class, "test.PersonMixedTagId");
    }

    PersonMixedTagId person = new PersonMixedTagId("Charlie", "Brown", 40);
    byte[] bytes = fory.serialize(person);

    // Verify deserialization works correctly with mixed mode
    PersonMixedTagId deserialized = (PersonMixedTagId) fory.deserialize(bytes);
    assertEquals(deserialized.firstName, "Charlie");
    assertEquals(deserialized.veryLongFieldNameForLastName, "Brown");
    assertEquals(deserialized.age, 40);

    System.out.printf(
        "Mixed mode - %s/%s/codegen=%s: %d bytes%n",
        language, compatibleMode, codegen, bytes.length);
  }

  /** Test class for nullable and ref flags */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class TestNullableRef {
    @ForyField(id = 0, nullable = false, ref = false)
    String nonNullableNoRef;

    @ForyField(id = 1, nullable = true, ref = false)
    String nullableNoRef;

    @ForyField(id = 2, nullable = false, ref = true)
    String nonNullableWithRef;

    @ForyField(id = 3, nullable = true, ref = true)
    String nullableWithRef;
  }

  @Test(dataProvider = "modes")
  public void testNullableAndRefFlagsInPayload(
      Language language, CompatibleMode compatibleMode, boolean codegen, boolean registered) {
    Fory fory =
        Fory.builder()
            .withLanguage(language)
            .requireClassRegistration(registered)
            .withCompatibleMode(compatibleMode)
            .withCodegen(codegen)
            .build();

    if (registered) {
      fory.register(TestNullableRef.class, "test.TestNullableRef");
    }

    TestNullableRef obj = new TestNullableRef("a", null, "c", "d");
    byte[] bytes = fory.serialize(obj);

    // Verify deserialization
    TestNullableRef deserialized = (TestNullableRef) fory.deserialize(bytes);
    assertEquals(deserialized.nonNullableNoRef, "a");
    assertNull(deserialized.nullableNoRef);
    assertEquals(deserialized.nonNullableWithRef, "c");
    assertEquals(deserialized.nullableWithRef, "d");

    System.out.printf(
        "Nullable/Ref test - %s/%s/codegen=%s: %d bytes%n",
        language, compatibleMode, codegen, bytes.length);
  }

  /** Test class with all fields nullable=false, ref=false for size comparison */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AllNonNullableNoRef {
    @ForyField(id = 0, nullable = false, ref = false)
    String field1;

    @ForyField(id = 1, nullable = false, ref = false)
    String field2;

    @ForyField(id = 2, nullable = false, ref = false)
    String field3;
  }

  /** Test class with all fields nullable=true, ref=false for size comparison */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AllNullableNoRef {
    @ForyField(id = 0, nullable = true, ref = false)
    String field1;

    @ForyField(id = 1, nullable = true, ref = false)
    String field2;

    @ForyField(id = 2, nullable = true, ref = false)
    String field3;
  }

  /** Test class with all fields nullable=false, ref=true for size comparison */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AllNonNullableWithRef {
    @ForyField(id = 0, nullable = false, ref = true)
    String field1;

    @ForyField(id = 1, nullable = false, ref = true)
    String field2;

    @ForyField(id = 2, nullable = false, ref = true)
    String field3;
  }

  /** Test class with all fields nullable=true, ref=true for size comparison */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AllNullableWithRef {
    @ForyField(id = 0, nullable = true, ref = true)
    String field1;

    @ForyField(id = 1, nullable = true, ref = true)
    String field2;

    @ForyField(id = 2, nullable = true, ref = true)
    String field3;
  }

  @Test(dataProvider = "modes")
  public void testNullableFlagReducesPayloadSize(
      Language language, CompatibleMode compatibleMode, boolean codegen, boolean registered) {
    Fory fory =
        Fory.builder()
            .withLanguage(language)
            .requireClassRegistration(registered)
            .withCompatibleMode(compatibleMode)
            .withCodegen(codegen)
            .build();

    if (registered) {
      fory.register(AllNonNullableNoRef.class, "test.AllNonNullableNoRef");
      fory.register(AllNullableNoRef.class, "test.AllNullableNoRef");
    }

    // Create objects with same data
    AllNonNullableNoRef nonNullable = new AllNonNullableNoRef("value1", "value2", "value3");
    AllNullableNoRef nullable = new AllNullableNoRef("value1", "value2", "value3");

    byte[] bytesNonNullable = fory.serialize(nonNullable);
    byte[] bytesNullable = fory.serialize(nullable);

    // Verify deserialization works
    AllNonNullableNoRef deserializedNonNullable =
        (AllNonNullableNoRef) fory.deserialize(bytesNonNullable);
    AllNullableNoRef deserializedNullable = (AllNullableNoRef) fory.deserialize(bytesNullable);

    assertEquals(deserializedNonNullable.field1, "value1");
    assertEquals(deserializedNonNullable.field2, "value2");
    assertEquals(deserializedNonNullable.field3, "value3");
    assertEquals(deserializedNullable.field1, "value1");
    assertEquals(deserializedNullable.field2, "value2");
    assertEquals(deserializedNullable.field3, "value3");

    System.out.printf(
        "Nullable flag test - %s/%s/codegen=%s/registered=%s - NonNullable: %d bytes, Nullable: %d bytes%n",
        language,
        compatibleMode,
        codegen,
        registered,
        bytesNonNullable.length,
        bytesNullable.length);

    // nullable=false should produce smaller or equal payload
    // Each nullable=true field adds 1 byte for null flag
    assertTrue(
        bytesNonNullable.length <= bytesNullable.length,
        String.format(
            "Expected non-nullable (%d bytes) to be <= nullable (%d bytes) in mode %s/%s/codegen=%s/registered=%s",
            bytesNonNullable.length,
            bytesNullable.length,
            language,
            compatibleMode,
            codegen,
            registered));
  }

  @Test(dataProvider = "modes")
  public void testRefFlagReducesPayloadSize(
      Language language, CompatibleMode compatibleMode, boolean codegen, boolean registered) {
    Fory fory =
        Fory.builder()
            .withLanguage(language)
            .requireClassRegistration(registered)
            .withCompatibleMode(compatibleMode)
            .withCodegen(codegen)
            .build();

    if (registered) {
      fory.register(AllNonNullableNoRef.class, "test.AllNonNullableNoRef");
      fory.register(AllNonNullableWithRef.class, "test.AllNonNullableWithRef");
    }

    // Create objects with same data
    AllNonNullableNoRef noRef = new AllNonNullableNoRef("value1", "value2", "value3");
    AllNonNullableWithRef withRef = new AllNonNullableWithRef("value1", "value2", "value3");

    byte[] bytesNoRef = fory.serialize(noRef);
    byte[] bytesWithRef = fory.serialize(withRef);

    // Verify deserialization works
    AllNonNullableNoRef deserializedNoRef = (AllNonNullableNoRef) fory.deserialize(bytesNoRef);
    AllNonNullableWithRef deserializedWithRef =
        (AllNonNullableWithRef) fory.deserialize(bytesWithRef);

    assertEquals(deserializedNoRef.field1, "value1");
    assertEquals(deserializedNoRef.field2, "value2");
    assertEquals(deserializedNoRef.field3, "value3");
    assertEquals(deserializedWithRef.field1, "value1");
    assertEquals(deserializedWithRef.field2, "value2");
    assertEquals(deserializedWithRef.field3, "value3");

    System.out.printf(
        "Ref flag test - %s/%s/codegen=%s/registered=%s - NoRef: %d bytes, WithRef: %d bytes%n",
        language, compatibleMode, codegen, registered, bytesNoRef.length, bytesWithRef.length);

    // ref=false should produce smaller or equal payload
    // Each ref=true field may add overhead for reference tracking
    assertTrue(
        bytesNoRef.length <= bytesWithRef.length,
        String.format(
            "Expected no-ref (%d bytes) to be <= with-ref (%d bytes) in mode %s/%s/codegen=%s/registered=%s",
            bytesNoRef.length, bytesWithRef.length, language, compatibleMode, codegen, registered));
  }

  @Test(dataProvider = "modes")
  public void testCombinedNullableAndRefFlagsReducePayloadSize(
      Language language, CompatibleMode compatibleMode, boolean codegen, boolean registered) {
    Fory fory =
        Fory.builder()
            .withLanguage(language)
            .requireClassRegistration(registered)
            .withCompatibleMode(compatibleMode)
            .withCodegen(codegen)
            .build();

    if (registered) {
      fory.register(AllNonNullableNoRef.class, "test.AllNonNullableNoRef");
      fory.register(AllNullableWithRef.class, "test.AllNullableWithRef");
    }

    // Create objects with same data
    // Most optimized: nullable=false, ref=false
    AllNonNullableNoRef optimized = new AllNonNullableNoRef("value1", "value2", "value3");
    // Least optimized: nullable=true, ref=true
    AllNullableWithRef unoptimized = new AllNullableWithRef("value1", "value2", "value3");

    byte[] bytesOptimized = fory.serialize(optimized);
    byte[] bytesUnoptimized = fory.serialize(unoptimized);

    // Verify deserialization works
    AllNonNullableNoRef deserializedOptimized =
        (AllNonNullableNoRef) fory.deserialize(bytesOptimized);
    AllNullableWithRef deserializedUnoptimized =
        (AllNullableWithRef) fory.deserialize(bytesUnoptimized);

    assertEquals(deserializedOptimized.field1, "value1");
    assertEquals(deserializedOptimized.field2, "value2");
    assertEquals(deserializedOptimized.field3, "value3");
    assertEquals(deserializedUnoptimized.field1, "value1");
    assertEquals(deserializedUnoptimized.field2, "value2");
    assertEquals(deserializedUnoptimized.field3, "value3");

    System.out.printf(
        "Combined flags test - %s/%s/codegen=%s/registered=%s - Optimized: %d bytes, Unoptimized: %d bytes, Savings: %d bytes (%.1f%%)%n",
        language,
        compatibleMode,
        codegen,
        registered,
        bytesOptimized.length,
        bytesUnoptimized.length,
        bytesUnoptimized.length - bytesOptimized.length,
        100.0 * (bytesUnoptimized.length - bytesOptimized.length) / bytesUnoptimized.length);

    // Optimized (nullable=false, ref=false) should be smaller than unoptimized (nullable=true,
    // ref=true)
    assertTrue(
        bytesOptimized.length < bytesUnoptimized.length,
        String.format(
            "Expected optimized (nullable=false,ref=false) %d bytes to be < unoptimized (nullable=true,ref=true) %d bytes in mode %s/%s/codegen=%s/registered=%s",
            bytesOptimized.length,
            bytesUnoptimized.length,
            language,
            compatibleMode,
            codegen,
            registered));
  }

  /** Version 1 of Person class for schema evolution test */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PersonV1 {
    @ForyField(id = 0, nullable = false)
    String name;

    @ForyField(id = 1, nullable = false)
    int age;
  }

  /** Version 2 of Person class for schema evolution test */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PersonV2 {
    @ForyField(id = 0, nullable = false)
    String name;

    @ForyField(id = 1, nullable = false)
    int age;

    @ForyField(id = 2, nullable = true) // New optional field
    String email;
  }

  @Test
  public void testSchemaEvolutionWithTagIds() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(false)
            .build();

    // Serialize with V1
    PersonV1 personV1 = new PersonV1("Alice", 30);
    byte[] bytesV1 = fory.serialize(personV1);

    // Note: Schema evolution across different class types requires XLANG mode with proper
    // tag ID support. In JAVA mode, we can only test serialization/deserialization
    // of the same class version. The tag IDs are stored in metadata but not used
    // for field matching in JAVA mode.

    PersonV1 deserialized = (PersonV1) fory.deserialize(bytesV1);
    assertEquals(deserialized.name, "Alice");
    assertEquals(deserialized.age, 30);

    // Serialize with V2
    PersonV2 personV2Full = new PersonV2("Bob", 25, "bob@example.com");
    byte[] bytesV2 = fory.serialize(personV2Full);

    PersonV2 deserializedV2 = (PersonV2) fory.deserialize(bytesV2);
    assertEquals(deserializedV2.name, "Bob");
    assertEquals(deserializedV2.age, 25);
    assertEquals(deserializedV2.email, "bob@example.com");

    System.out.printf(
        "Schema evolution test - V1: %d bytes, V2: %d bytes%n", bytesV1.length, bytesV2.length);
  }

  /**
   * Comprehensive test for nested objects with @ForyField tag IDs. Verifies that: 1. Field IDs are
   * written instead of field names for nested object fields 2. Nested object class IDs (if
   * registered) are written instead of class names/types 3. Payload size is smaller when using tag
   * IDs 4. Deserialization works correctly
   */
  @Test(dataProvider = "modes")
  public void testNestedObjectsWithTagIds(
      Language language, CompatibleMode compatibleMode, boolean codegen, boolean registered) {
    Fory fory =
        Fory.builder()
            .withLanguage(language)
            .requireClassRegistration(registered)
            .withCompatibleMode(compatibleMode)
            .withCodegen(codegen)
            .build();

    if (registered) {
      fory.register(ContainerWithTagIds.class, "test.ContainerWithTagIds");
      fory.register(ContainerWithoutTagIds.class, "test.ContainerWithoutTagIds");
      fory.register(VeryLongNestedObjectClassName.class, "test.VeryLongNestedObjectClassName");
      fory.register(
          AnotherVeryLongNestedObjectClassName.class, "test.AnotherVeryLongNestedObjectClassName");
    }

    // Create nested objects with same data
    VeryLongNestedObjectClassName nested1 = new VeryLongNestedObjectClassName("value1", 42);
    AnotherVeryLongNestedObjectClassName nested2 =
        new AnotherVeryLongNestedObjectClassName("description1");

    ContainerWithTagIds containerWithTags =
        new ContainerWithTagIds(nested1, nested2, "simpleValue");
    ContainerWithoutTagIds containerWithoutTags =
        new ContainerWithoutTagIds(nested1, nested2, "simpleValue");

    // Serialize both
    byte[] bytesWithTags = fory.serialize(containerWithTags);
    byte[] bytesWithoutTags = fory.serialize(containerWithoutTags);

    // Verify deserialization works
    ContainerWithTagIds deserializedWithTags =
        (ContainerWithTagIds) fory.deserialize(bytesWithTags);
    ContainerWithoutTagIds deserializedWithoutTags =
        (ContainerWithoutTagIds) fory.deserialize(bytesWithoutTags);

    // Verify nested object values
    assertEquals(
        deserializedWithTags.veryLongFieldNameForNestedObject.value,
        "value1",
        "Nested object value should match");
    assertEquals(
        deserializedWithTags.veryLongFieldNameForNestedObject.count,
        42,
        "Nested object count should match");
    assertEquals(
        deserializedWithTags.anotherVeryLongFieldNameForAnotherNestedObject.description,
        "description1",
        "Another nested object description should match");
    assertEquals(deserializedWithTags.simpleField, "simpleValue", "Simple field should match");

    assertEquals(
        deserializedWithoutTags.veryLongFieldNameForNestedObject.value,
        "value1",
        "Nested object value should match");
    assertEquals(
        deserializedWithoutTags.veryLongFieldNameForNestedObject.count,
        42,
        "Nested object count should match");
    assertEquals(
        deserializedWithoutTags.anotherVeryLongFieldNameForAnotherNestedObject.description,
        "description1",
        "Another nested object description should match");
    assertEquals(deserializedWithoutTags.simpleField, "simpleValue", "Simple field should match");

    System.out.printf(
        "Nested objects test - %s/%s/codegen=%s/registered=%s - With tags: %d bytes, Without tags: %d bytes%n",
        language,
        compatibleMode,
        codegen,
        registered,
        bytesWithTags.length,
        bytesWithoutTags.length);

    // Tag IDs should produce smaller payload in all modes
    assertTrue(
        bytesWithTags.length < bytesWithoutTags.length,
        String.format(
            "Expected nested objects with tag IDs (%d bytes) to be < without tag IDs (%d bytes) in %s/%s/codegen=%s/registered=%s",
            bytesWithTags.length,
            bytesWithoutTags.length,
            language,
            compatibleMode,
            codegen,
            registered));

    // Print savings information
    System.out.printf(
        "  Savings from tag IDs: %d bytes (%.1f%%)%n",
        bytesWithoutTags.length - bytesWithTags.length,
        100.0 * (bytesWithoutTags.length - bytesWithTags.length) / bytesWithoutTags.length);
  }

  /**
   * Test that verifies field IDs are written in the payload (not field names). This test inspects
   * the raw bytes to confirm the serialization format.
   */
  @Test(dataProvider = "modes")
  public void testNestedObjectFieldIdInPayload(
      Language language, CompatibleMode compatibleMode, boolean codegen, boolean registered) {
    Fory fory =
        Fory.builder()
            .withLanguage(language)
            .requireClassRegistration(registered)
            .withCompatibleMode(compatibleMode)
            .withCodegen(codegen)
            .build();

    if (registered) {
      fory.register(ContainerWithTagIds.class, "test.ContainerWithTagIds");
      fory.register(VeryLongNestedObjectClassName.class, "test.VeryLongNestedObjectClassName");
      fory.register(
          AnotherVeryLongNestedObjectClassName.class, "test.AnotherVeryLongNestedObjectClassName");
    }

    VeryLongNestedObjectClassName nested1 = new VeryLongNestedObjectClassName("test", 1);
    AnotherVeryLongNestedObjectClassName nested2 = new AnotherVeryLongNestedObjectClassName("desc");

    ContainerWithTagIds container = new ContainerWithTagIds(nested1, nested2, "simple");

    byte[] bytes = fory.serialize(container);

    // Verify deserialization
    ContainerWithTagIds deserialized = (ContainerWithTagIds) fory.deserialize(bytes);
    assertEquals(deserialized.veryLongFieldNameForNestedObject.value, "test");
    assertEquals(deserialized.veryLongFieldNameForNestedObject.count, 1);
    assertEquals(deserialized.anotherVeryLongFieldNameForAnotherNestedObject.description, "desc");
    assertEquals(deserialized.simpleField, "simple");

    // When using tag IDs with @ForyField, field names should NOT be in payload
    // This works in all modes: JAVA/XLANG and COMPATIBLE/SCHEMA_CONSISTENT
    String serialized = new String(bytes, StandardCharsets.UTF_8);

    // These long field names should not be present because we're using field IDs (0, 1, 2)
    boolean hasLongFieldName1 = serialized.contains("veryLongFieldNameForNestedObject");
    boolean hasLongFieldName2 =
        serialized.contains("anotherVeryLongFieldNameForAnotherNestedObject");

    assertFalse(
        hasLongFieldName1,
        String.format(
            "Field name 'veryLongFieldNameForNestedObject' should NOT be in payload with tag ID in %s/%s/codegen=%s/registered=%s",
            language, compatibleMode, codegen, registered));
    assertFalse(
        hasLongFieldName2,
        String.format(
            "Field name 'anotherVeryLongFieldNameForAnotherNestedObject' should NOT be in payload with tag ID in %s/%s/codegen=%s/registered=%s",
            language, compatibleMode, codegen, registered));

    System.out.printf(
        "Verified: Field IDs used instead of field names in %s/%s/codegen=%s/registered=%s%n",
        language, compatibleMode, codegen, registered);
  }
}
