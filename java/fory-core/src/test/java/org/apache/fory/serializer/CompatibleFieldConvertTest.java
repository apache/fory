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

package org.apache.fory.serializer;

import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Field;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.serializer.converter.FieldConverter;
import org.apache.fory.serializer.converter.FieldConverters;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CompatibleFieldConvertTest extends ForyTestBase {
  public static final class CompatibleFieldConvert1 {
    public boolean ftrue;
    public Boolean ffalse;
    public byte f3;
    public Byte f4;
    public short f5;
    public Short f6;
    public int f7;
    public Integer f8;
    public long f9;
    public Long f10;
    public float f11;
    public Float f12;
    public double f13;
    public Double f14;

    public String toString() {
      return "" + ftrue + ffalse + f3 + f4 + f5 + f6 + f7 + f8 + f9 + f10 + f11 + f12 + f13 + f14;
    }
  }

  public static final class CompatibleFieldConvert2 {
    public Boolean ftrue;
    public boolean ffalse;
    public Byte f3;
    public byte f4;
    public Short f5;
    public short f6;
    public Integer f7;
    public int f8;
    public Long f9;
    public long f10;
    public Float f11;
    public float f12;
    public Double f13;
    public double f14;

    public String toString() {
      return "" + ftrue + ffalse + f3 + f4 + f5 + f6 + f7 + f8 + f9 + f10 + f11 + f12 + f13 + f14;
    }
  }

  public static final class CompatibleFieldConvert3 {
    public String ftrue;
    public String ffalse;
    public String f3;
    public String f4;
    public String f5;
    public String f6;
    public String f7;
    public String f8;
    public String f9;
    public String f10;
    public String f11;
    public String f12;
    public String f13;
    public String f14;

    public String toString() {
      return ftrue + ffalse + f3 + f4 + f5 + f6 + f7 + f8 + f9 + f10 + f11 + f12 + f13 + f14;
    }
  }

  @Test(dataProvider = "xlang")
  public void testCompatibleFieldConvert(boolean xlang) throws Exception {
    byte[] bytes;
    Object o1;
    ImmutableSet<String> floatFields = ImmutableSet.of("f11", "f12", "f13", "f14");
    {
      Class<?> cls = CompatibleFieldConvert1.class;
      o1 = cls.newInstance();
      for (Field field : ReflectionUtils.getSortedFields(cls, false)) {
        String name = field.getName();
        field.setAccessible(true);
        FieldConverter<?> converter = FieldConverters.getConverter(String.class, field);
        Assert.assertNotNull(converter);
        Object converted = converter.convert(name.substring(1));
        field.set(o1, converted);
      }
      Fory fory = builder().withXlang(xlang).withCompatible(true).build();
      fory.register(cls);
      bytes = fory.serialize(o1);
    }
    {
      Class<?> cls = CompatibleFieldConvert2.class;
      Assert.assertNotEquals(o1.getClass(), cls);
      Fory fory = builder().withXlang(xlang).withCompatible(true).build();
      fory.register(cls);
      Object o = fory.deserialize(bytes);
      Assert.assertEquals(o.getClass(), cls);
      List<Field> fields = ReflectionUtils.getSortedFields(cls, false);
      for (Field field : fields) {
        field.setAccessible(true);
        Object fieldValue = field.get(o);
        if (fieldValue instanceof Float || fieldValue instanceof Double) {
          Assert.assertEquals(fieldValue.toString(), field.getName().substring(1) + ".0");
        } else {
          Assert.assertEquals(
              fieldValue.toString(), field.getName().substring(1), field.getName() + " not equal");
        }
      }
      Assert.assertEquals(o.toString(), o1.toString());
    }
    {
      Fory fory = builder().withXlang(xlang).withCompatible(true).build();
      Class<?> cls = CompatibleFieldConvert3.class;
      Assert.assertNotEquals(o1.getClass(), cls);
      fory.register(cls);
      Object o = fory.deserialize(bytes);
      Assert.assertEquals(o.getClass(), cls);
      List<Field> fields = ReflectionUtils.getSortedFields(cls, false);
      for (Field field : fields) {
        field.setAccessible(true);
        Object fieldValue = field.get(o);
        if (floatFields.contains(field.getName())) {
          Assert.assertEquals(fieldValue.toString(), field.getName().substring(1) + ".0");
        } else {
          Assert.assertEquals(fieldValue.toString(), field.getName().substring(1));
        }
      }
      Assert.assertEquals(o.toString(), o1.toString());
    }
  }
}
