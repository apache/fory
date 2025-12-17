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

import java.lang.reflect.Field;
import java.util.List;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.Language;
import org.apache.fory.meta.ClassDef;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ForyFieldTagIdTest extends ForyTestBase {

  @Data
  public static class TestClass {
    @ForyField(id = 0, nullable = false)
    public String fieldWithTag0;

    @ForyField(id = 5, nullable = false)
    public String fieldWithTag5;

    @ForyField(id = -1, nullable = false)
    public String fieldOptingOutOfTag;

    public String fieldWithoutAnnotation;
  }

  @Test(dataProvider = "languageAndCodegen")
  public void testFieldInfoCreationWithTagIds(
      Language language, boolean codegen, boolean registered) {
    Fory fory =
        Fory.builder()
            .withLanguage(language)
            .requireClassRegistration(registered)
            .withCodegen(codegen)
            .build();

    if (language == Language.XLANG) {
      fory.register(TestClass.class, "test.TestClass");
    }

    ClassDef classDef = ClassDef.buildClassDef(fory, TestClass.class);
    List<ClassDef.FieldInfo> fieldsInfo = classDef.getFieldsInfo();

    // Should have 4 fields
    assertEquals(fieldsInfo.size(), 4);

    // Find each field by name and verify tag behavior
    ClassDef.FieldInfo field0 = findFieldByName(fieldsInfo, "fieldWithTag0");
    ClassDef.FieldInfo field5 = findFieldByName(fieldsInfo, "fieldWithTag5");
    ClassDef.FieldInfo fieldOptOut = findFieldByName(fieldsInfo, "fieldOptingOutOfTag");
    ClassDef.FieldInfo fieldNoAnnotation = findFieldByName(fieldsInfo, "fieldWithoutAnnotation");

    // Verify field with id=0 has tag
    assertTrue(field0.hasFieldId(), "Field with id=0 should have tag in " + language + " mode");
    assertEquals(
        field0.getFieldId(),
        (short) 0,
        "Field with id=0 should have tag value 0 in " + language + " mode");

    // Verify field with id=5 has tag
    assertTrue(field5.hasFieldId(), "Field with id=5 should have tag in " + language + " mode");
    assertEquals(
        field5.getFieldId(),
        (short) 5,
        "Field with id=5 should have tag value 5 in " + language + " mode");

    // Verify field with id=-1 does NOT have tag (opts out)
    assertFalse(
        fieldOptOut.hasFieldId(),
        "Field with id=-1 should NOT have tag (opt-out) in " + language + " mode");
    assertEquals(
        fieldOptOut.getFieldName(),
        "fieldOptingOutOfTag",
        "Field with id=-1 should use field name in " + language + " mode");

    // Verify field without annotation does NOT have tag
    assertFalse(
        fieldNoAnnotation.hasFieldId(),
        "Field without annotation should NOT have tag (use field name) in " + language + " mode");
    assertEquals(
        fieldNoAnnotation.getFieldName(),
        "fieldWithoutAnnotation",
        "Field without annotation should use field name in " + language + " mode");
  }

  @DataProvider(name = "languageAndCodegen")
  public Object[][] languageAndCodegen() {
    return new Object[][] {
      {Language.JAVA, false, false},
      {Language.JAVA, false, true},
      {Language.JAVA, true, false},
      {Language.JAVA, true, true},
      {Language.XLANG, false, true},
      {Language.XLANG, true, true},
    };
  }

  @Test
  public void testTagIdAnnotationValues() throws Exception {
    // Directly test that annotation reading works correctly
    Field field0 = TestClass.class.getDeclaredField("fieldWithTag0");
    Field field5 = TestClass.class.getDeclaredField("fieldWithTag5");
    Field fieldOptOut = TestClass.class.getDeclaredField("fieldOptingOutOfTag");
    Field fieldNoAnnotation = TestClass.class.getDeclaredField("fieldWithoutAnnotation");

    ForyField annotation0 = field0.getAnnotation(ForyField.class);
    ForyField annotation5 = field5.getAnnotation(ForyField.class);
    ForyField annotationOptOut = fieldOptOut.getAnnotation(ForyField.class);
    ForyField annotationNoAnnotation = fieldNoAnnotation.getAnnotation(ForyField.class);

    // Verify annotation values
    assertEquals(annotation0.id(), 0, "Field 0 should have id=0");
    assertEquals(annotation5.id(), 5, "Field 5 should have id=5");
    assertEquals(annotationOptOut.id(), -1, "Opt-out field should have id=-1");
    assertNull(
        annotationNoAnnotation, "Field without annotation should have no ForyField annotation");
  }

  @Test
  public void testIdMinusOneOptOutBehavior() {
    // Test that id=-1 explicitly opts out of tag ID usage
    Fory fory = Fory.builder().withLanguage(Language.XLANG).requireClassRegistration(false).build();
    fory.register(TestClass.class, "test.TestClass");

    TestClass obj = new TestClass();
    obj.setFieldWithTag0("value0");
    obj.setFieldWithTag5("value5");
    obj.setFieldOptingOutOfTag("optOutValue");
    obj.setFieldWithoutAnnotation("noAnnotationValue");

    // Serialize and deserialize
    byte[] bytes = fory.serialize(obj);
    TestClass deserialized = (TestClass) fory.deserialize(bytes);

    // All fields should deserialize correctly
    assertEquals(deserialized.getFieldWithTag0(), "value0");
    assertEquals(deserialized.getFieldWithTag5(), "value5");
    assertEquals(deserialized.getFieldOptingOutOfTag(), "optOutValue");
    assertEquals(deserialized.getFieldWithoutAnnotation(), "noAnnotationValue");
  }

  /** Helper method to find a FieldInfo by field name */
  private ClassDef.FieldInfo findFieldByName(List<ClassDef.FieldInfo> fieldsInfo, String name) {
    for (ClassDef.FieldInfo fieldInfo : fieldsInfo) {
      if (fieldInfo.getFieldName().equals(name)) {
        return fieldInfo;
      }
    }
    throw new AssertionError("Field not found: " + name);
  }
}
