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

package org.apache.fory.collection;

import java.util.ArrayList;
import org.apache.fory.Fory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Int64Encoding;
import org.apache.fory.config.Language;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class MultiParamCollectionTest {

  /**
   * A custom list with two generic parameters: {@code A} — metadata type (NOT the element type of
   * the Collection) {@code E} — the actual element type (inherited from {@code ArrayList<E>})
   *
   * <p>{@code getTypeParameter0()} on {@code MyList<String, Integer>} returns {@code
   * GenericType(String)} (parameter {@code A}), but the Collection element type is {@code Integer}
   * (parameter {@code E}, inherited via {@code ArrayList<E>}).
   */
  public static class MyList<A, E> extends ArrayList<E> {
    private A metadata;

    public MyList() {}

    public MyList(A metadata) {
      this.metadata = metadata;
    }

    public A getMetadata() {
      return metadata;
    }

    public void setMetadata(A metadata) {
      this.metadata = metadata;
    }
  }

  public static class Container {
    private String name;
    private MyList<String, Integer> numbers;

    public Container() {}

    public Container(String name, MyList<String, Integer> numbers) {
      this.name = name;
      this.numbers = numbers;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public MyList<String, Integer> getNumbers() {
      return numbers;
    }

    public void setNumbers(MyList<String, Integer> numbers) {
      this.numbers = numbers;
    }
  }

  // ==================== tests ====================

  @DataProvider
  public static Object[][] codegen() {
    return new Object[][] {{true}, {false}};
  }

  @Test(dataProvider = "codegen")
  public void testMultiParamCollectionRoundTrip(boolean codegen) {
    MyList<String, Integer> list = new MyList<>("my-metadata");
    list.add(1);
    list.add(2);
    list.add(3);

    Container container = new Container("test-container", list);
    byte[] bytes = createFory(codegen).serialize(container);
    Container cloned = (Container) createFory(codegen).deserialize(bytes);

    Assert.assertEquals(cloned.getName(), "test-container");
    Assert.assertNotNull(cloned.getNumbers());
    Assert.assertEquals(cloned.getNumbers().getMetadata(), "my-metadata");
    Assert.assertEquals(cloned.getNumbers().size(), 3);
    Assert.assertEquals(cloned.getNumbers().get(0), Integer.valueOf(1));
    Assert.assertEquals(cloned.getNumbers().get(1), Integer.valueOf(2));
    Assert.assertEquals(cloned.getNumbers().get(2), Integer.valueOf(3));
  }

  // ==================== helpers ====================

  private Fory createFory(boolean codegen) {
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
