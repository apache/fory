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

package org.apache.fory.collection.selflist;

import java.lang.reflect.Field;
import java.util.ArrayList;
import org.apache.fory.Fory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Int64Encoding;
import org.apache.fory.config.Language;
import org.apache.fory.meta.FieldTypes;
import org.apache.fory.meta.FieldTypes.CollectionFieldType;
import org.apache.fory.resolver.TypeResolver;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class SelfSingletonListTest {
  public static class SelfList extends ArrayList<SelfList> {}

  public static class Holder {
    private SelfList values;

    public Holder() {}

    public Holder(SelfList values) {
      this.values = values;
    }

    public SelfList getValues() {
      return values;
    }

    public void setValues(SelfList values) {
      this.values = values;
    }
  }

  @DataProvider
  public static Object[][] codegen() {
    return new Object[][] {{true}, {false}};
  }

  @Test
  public void testSelfCollectionFieldType() throws NoSuchFieldException {
    Fory fory = createFory(true);
    TypeResolver resolver = fory.getTypeResolver();
    Field field = Holder.class.getDeclaredField("values");
    FieldTypes.FieldType fieldType = FieldTypes.buildFieldType(resolver, field);
    Assert.assertTrue(fieldType instanceof CollectionFieldType, fieldType.toString());

    FieldTypes.FieldType elementType = ((CollectionFieldType) fieldType).getElementType();
    Assert.assertFalse(elementType instanceof CollectionFieldType, elementType.toString());
    Assert.assertEquals(elementType.getTypeId(), resolver.getTypeInfo(Object.class).getTypeId());
  }

  @Test(dataProvider = "codegen")
  public void testSelfCollectionRoundTrip(boolean codegen) {
    SelfList values = new SelfList();
    values.add(values);
    Holder holder = new Holder(values);

    byte[] bytes = createFory(codegen).serialize(holder);
    Holder cloned = (Holder) createFory(codegen).deserialize(bytes);

    Assert.assertNotNull(cloned.getValues());
    Assert.assertEquals(cloned.getValues().size(), 1);
    Assert.assertSame(cloned.getValues().get(0), cloned.getValues());
  }

  private static Fory createFory(boolean codegen) {
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withAsyncCompilation(false)
            .withIntCompressed(true)
            .withCodegen(codegen)
            .withLongCompressed(Int64Encoding.VARINT)
            .withIntArrayCompressed(true)
            .withLongArrayCompressed(true);
    return builder.build();
  }
}
