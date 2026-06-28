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

import com.google.common.collect.Lists;
import java.util.List;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ForyAnnotationTest extends ForyTestBase {

  @Data
  public static class BeanM {
    @ForyField(id = 0)
    public Long f1;

    @ForyField(id = 1)
    private Long f2;

    String s = "str";
    Short shortValue = Short.valueOf((short) 2);
    Byte byteValue = Byte.valueOf((byte) 3);
    Long longValue = Long.valueOf(4L);
    Boolean booleanValue = Boolean.TRUE;
    Float floatValue = Float.valueOf(5.0f);
    Double doubleValue = Double.valueOf(6.0);
    Character character = Character.valueOf('c');

    int i = 10;

    int i2;

    long l1;

    double d1;

    char c1;

    boolean b1;

    byte byte1;

    @ForyField(id = 2)
    int i3 = 10;

    @ForyField(id = 3)
    List<Integer> integerList = Lists.newArrayList(1);

    @ForyField(id = 4)
    String s1 = "str";

    @ForyField(id = 5)
    Short shortValue1 = Short.valueOf((short) 2);

    @ForyField(id = 6)
    Byte byteValue1 = Byte.valueOf((byte) 3);

    @ForyField(id = 7)
    Long longValue1 = Long.valueOf(4L);

    @ForyField(id = 8)
    Boolean booleanValue1 = Boolean.TRUE;

    @ForyField(id = 9)
    Float floatValue1 = Float.valueOf(5.0f);

    @ForyField(id = 10)
    Double doubleValue1 = Double.valueOf(6.0);

    @ForyField(id = 11)
    Character character1 = Character.valueOf('c');

    @Nullable
    @ForyField(id = 12)
    List<Integer> integerList1 = Lists.newArrayList(1);

    @Nullable
    @ForyField(id = 13)
    String s2 = "str";

    @Nullable
    @ForyField(id = 14)
    Short shortValue2 = Short.valueOf((short) 2);

    @Nullable
    @ForyField(id = 15)
    Byte byteValue2 = Byte.valueOf((byte) 3);

    @Nullable
    @ForyField(id = 16)
    Long longValue2 = Long.valueOf(4L);

    @Nullable
    @ForyField(id = 17)
    Boolean booleanValue2 = Boolean.TRUE;

    @Nullable
    @ForyField(id = 18)
    Float floatValue2 = Float.valueOf(5.0f);

    @Nullable
    @ForyField(id = 19)
    Double doubleValue2 = Double.valueOf(6.0);

    @Nullable
    @ForyField(id = 20)
    Character character2 = Character.valueOf('c');

    public BeanM() {
      this.f1 = 1L;
      this.f2 = 1L;
    }
  }

  @Data
  public static class BeanN {
    public long f1;
    private long f2;
  }

  @Data
  public static class BeanM1 {

    @ForyField(id = 0)
    private BeanN beanN = new BeanN();
  }

  @Test(dataProvider = "basicMultiConfigFory")
  public void testForyFieldAnnotation(boolean trackingRef, boolean codeGen, boolean compatible) {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(trackingRef)
            .requireClassRegistration(false)
            .withCodegen(codeGen)
            .withCompatible(compatible)
            .build();
    BeanM o = new BeanM();
    byte[] bytes = fory.serialize(o);
    final Object deserialize = fory.deserialize(bytes);
    Assert.assertEquals(o, deserialize);
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testForyFieldAnnotationException(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false)
            .withCodegen(false)
            .withCompatible(false)
            .build();
    BeanM1 o1 = new BeanM1();
    assertEquals(serDe(fory, o1), o1);
  }
}
