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

package org.apache.fory.resolver;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.MetaWriteContext;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.BeanB;
import org.apache.fory.test.bean.Foo;
import org.testng.Assert;
import org.testng.annotations.Test;

public class MetaShareContextTest extends ForyTestBase {
  @Test
  public void testShareClassName() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .build();
    for (Object o : new Object[] {Foo.create(), BeanB.createBeanB(2), BeanA.createBeanA(2)}) {
      checkMetaShared(fory, o);
    }
  }

  @Test(dataProvider = "enableCodegen")
  public void testShareTypeDefCompatible(boolean enableCodegen) {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withMetaShare(true)
            .withScopedMetaShare(false)
            .withCompatible(true)
            .withCodegen(enableCodegen)
            .requireClassRegistration(false)
            .build();
    for (Object o : new Object[] {Foo.create(), BeanB.createBeanB(2), BeanA.createBeanA(2)}) {
      checkMetaShared(fory, o);
    }
  }

  private void checkMetaShared(Fory fory, Object o) {
    MetaWriteContext metaWriteContext = new MetaWriteContext();
    MetaReadContext metaReadContext = new MetaReadContext();
    setMetaContexts(fory, metaWriteContext, metaReadContext);
    byte[] bytes = fory.serialize(o);
    setMetaContexts(fory, metaWriteContext, metaReadContext);
    Assert.assertEquals(fory.deserialize(bytes), o);
    setMetaContexts(fory, metaWriteContext, metaReadContext);
    byte[] bytes1 = fory.serialize(o);
    Assert.assertTrue(bytes1.length < bytes.length);
    setMetaContexts(fory, metaWriteContext, metaReadContext);
    Assert.assertEquals(fory.deserialize(bytes1), o);
    setMetaContexts(fory, new MetaWriteContext(), new MetaReadContext());
    Assert.assertEquals(fory.serialize(o), bytes);
    assertThrowsCause(AssertionError.class, () -> fory.serialize(o));
  }

  // final InnerPojo will be taken as non-final for writing class def.
  @Data
  @AllArgsConstructor
  public static final class InnerPojo {
    public Integer integer;
  }

  @Data
  @AllArgsConstructor
  public static class OuterPojo {
    public List<InnerPojo> list;
  }

  @Test(dataProvider = "enableCodegen")
  public void testFinalTypeWriteMeta(boolean enableCodegen) {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(true)
            .withMetaShare(true)
            .withScopedMetaShare(false)
            .withCompatible(true)
            .withCodegen(enableCodegen)
            .requireClassRegistration(false)
            .build();
    OuterPojo outerPojo =
        new OuterPojo(new ArrayList<>(ImmutableList.of(new InnerPojo(1), new InnerPojo(2))));
    checkMetaShared(fory, outerPojo);
  }
}
