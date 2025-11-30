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

package org.apache.fory.format.type;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import com.google.common.base.CaseFormat;
import java.lang.reflect.TypeVariable;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.type.Descriptor;
import org.testng.annotations.Test;

public class TypeInferenceTest {
  @Test
  public void inferDataType() {
    List<Descriptor> descriptors = Descriptor.getDescriptors(BeanA.class);
    Schema schema = TypeInference.inferSchema(BeanA.class);
    List<String> fieldNames =
        schema.fields().stream().map(Field::name).collect(Collectors.toList());
    List<String> expectedFieldNames =
        descriptors.stream()
            .map(d -> CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, d.getName()))
            .collect(Collectors.toList());
    assertEquals(fieldNames, expectedFieldNames);
    assertNotNull(schema.getFieldByName("double_list"));
  }

  /**
   * Test that TypeVariable types (like K, V from Map<K, V>) are handled correctly by resolving to
   * their bounds. This can happen with Scala 3 LTS where generic type information may not be fully
   * resolved. See https://github.com/apache/fory/issues/2439
   */
  @Test
  public void testTypeVariableInference() {
    // Get the TypeVariable K from Map<K, V>
    TypeVariable<?>[] typeParams = Map.class.getTypeParameters();
    assertEquals(typeParams.length, 2);
    TypeVariable<?> keyTypeVar = typeParams[0]; // K
    TypeVariable<?> valueTypeVar = typeParams[1]; // V

    // Verify these are indeed TypeVariables
    assertTrue(keyTypeVar instanceof TypeVariable);
    assertTrue(valueTypeVar instanceof TypeVariable);

    // TypeVariable K has bound Object which is not a supported row format type,
    // but the TypeVariable resolution itself should work.
    // The fix ensures TypeVariable is resolved to its bound before failing.
    TypeRef<?> keyTypeRef = TypeRef.of(keyTypeVar);
    try {
      TypeInference.inferDataType(keyTypeRef);
    } catch (UnsupportedOperationException e) {
      // Expected: Object is not a supported type, but the error should mention Object,
      // not the TypeVariable name "K"
      assertTrue(
          e.getMessage().contains("java.lang.Object"),
          "TypeVariable should be resolved to Object, not remain as K");
    }
  }

  /** Test inferring schema for a class with Map field containing Long keys and values. */
  @Test
  public void testMapFieldTypeInference() {
    Schema schema = TypeInference.inferSchema(ClassWithMapField.class);
    assertNotNull(schema);
    Field mapField = schema.getFieldByName("long_map");
    assertNotNull(mapField);
    assertTrue(mapField.type() instanceof DataTypes.MapType);
    DataTypes.MapType mapType = (DataTypes.MapType) mapField.type();
    assertEquals(mapType.keyType(), DataTypes.int64());
    assertEquals(mapType.itemType(), DataTypes.int64());
  }

  private static class ClassWithMapField {
    public Map<Long, Long> longMap;
  }
}
