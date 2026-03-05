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

package org.apache.fory.serializer.compatible;

import java.util.Arrays;
import org.apache.fory.Fory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.serializer.compatible.classes.ClassCompleteField;
import org.apache.fory.serializer.compatible.classes.ClassMissingField;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Test COMPATIBILITY mode that supports - same field type and name can be deserialized to other
 * also test generic class
 */
public class DifferentPOJOCompatibleSerializerWithRegistrationTest extends Assert {

  Fory getFory(Class<?>... classes) {
    Fory instance =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withMetaShare(true)
            .withScopedMetaShare(true)
            .requireClassRegistration(false)
            .withAsyncCompilation(true)
            .serializeEnumByName(true)
            .withName(getClass() + Arrays.toString(classes))
            .build();
    if (classes != null) {
      for (Class<?> clazz : classes) {
        instance.register(clazz);
      }
    }
    return instance;
  }

  @Test
  void testTargetHasLessFieldComparedToSourceClass() throws InterruptedException {

    ClassCompleteField<String> subclass = new ClassCompleteField<>("subclass", "subclass2");
    ClassCompleteField<ClassCompleteField<String>> classCompleteField =
        new ClassCompleteField<>(subclass, subclass);
    byte[] serialized = getFory(ClassCompleteField.class).serialize(classCompleteField);
    ClassMissingField<ClassMissingField<String>> classMissingField =
        getFory(ClassMissingField.class).deserialize(serialized, ClassMissingField.class);

    assertEq(classCompleteField, classMissingField);
  }

  @Test
  void testTargetHasMoreFieldComparedToSourceClass() throws InterruptedException {

    ClassMissingField<String> subclass = new ClassMissingField<>("subclass");
    ClassMissingField classMissingField = new ClassMissingField(subclass);
    byte[] serialized = getFory(ClassMissingField.class).serialize(classMissingField);

    ClassCompleteField classCompleteField =
        getFory(ClassCompleteField.class).deserialize(serialized, ClassCompleteField.class);

    assertEq(classCompleteField, classMissingField);
  }

  void assertEq(ClassCompleteField classCompleteField, ClassMissingField classMissingField) {
    assertEqSubClass(
        (ClassCompleteField) classCompleteField.getPrivateFieldSubClass(),
        (ClassMissingField) classMissingField.getPrivateFieldSubClass());
    assertEquals(classCompleteField.getPrivateMap(), classMissingField.getPrivateMap());
    assertEquals(classCompleteField.getPrivateList(), classMissingField.getPrivateList());
    assertEquals(classCompleteField.getPrivateString(), classMissingField.getPrivateString());
    assertEquals(classCompleteField.getPrivateInt(), classMissingField.getPrivateInt());
  }

  void assertEqSubClass(
      ClassCompleteField classCompleteField, ClassMissingField classMissingField) {
    assertEquals(
        classCompleteField.getPrivateFieldSubClass(), classMissingField.getPrivateFieldSubClass());
    assertEquals(classCompleteField.getPrivateMap(), classMissingField.getPrivateMap());
    assertEquals(classCompleteField.getPrivateList(), classMissingField.getPrivateList());
    assertEquals(classCompleteField.getPrivateString(), classMissingField.getPrivateString());
    assertEquals(classCompleteField.getPrivateInt(), classMissingField.getPrivateInt());
  }
}
