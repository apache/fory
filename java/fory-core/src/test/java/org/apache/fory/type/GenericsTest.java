/* Copyright (c) 2008-2023, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. */

package org.apache.fory.type;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.apache.fory.ForyTestBase;
import org.apache.fory.TestUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

@SuppressWarnings("rawtypes")
public class GenericsTest extends ForyTestBase {

  public static class Test1<FLOAT, STRING, NULL> {
    public ArrayList<String> fromField1;
    public ArrayList<FLOAT> fromSubClass;
    public ArrayList<STRING> fromSubSubClass;
    public HashMap<FLOAT, STRING> multipleFromSubClasses;
    public HashMap<FLOAT, NULL> multipleWithUnknown;
    public FLOAT[] arrayFromSubClass;
    public STRING[] arrayFromSubSubClass;
  }

  public static class Test2<STRING, NULL, ARRAYLIST> extends Test1<Float, STRING, NULL> {
    public STRING known;
    public STRING[] array1;
    public STRING[][] array2;
    public ArrayList<NULL> unknown1;
    public NULL[] arrayUnknown1;
    public ArrayList<String> fromField2;
    public ArrayList<STRING>[] arrayWithTypeVar;
    public ArrayList<ArrayList<String>> fromFieldNested;
    public ARRAYLIST parameterizedTypeFromSubClass;
    public ArrayList<ARRAYLIST>[] parameterizedArrayFromSubClass;
  }

  public static class Test3<DOUBLE extends Number & Comparable, NULL, LONG>
      extends Test2<String, NULL, ArrayList<LONG>> {
    public ArrayList<String> fromField3;
    public ArrayList raw;
    public NULL unknown2;
    public NULL[] arrayUnknown2;
    public ArrayList<?> unboundWildcard;
    public ArrayList<? extends Number> upperBound;
    public ArrayList<? super Integer> lowerBound;
    public ArrayList<DOUBLE> multipleUpperBounds;
  }

  public static class Test4<NULL> extends Test3<Double, NULL, Long> {}

  public static class Test5 {
    public List<String> f1;
  }

  @Test
  public void testGenericType() throws Exception {
    // Test1
    Assert.assertEquals(
        GenericType.build(Test4.class, Test1.class.getField("fromField1").getGenericType())
            .getTypeParameter0()
            .getCls(),
        String.class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test1.class.getField("fromSubClass").getGenericType())
            .getTypeParameter0()
            .getCls(),
        Float.class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test1.class.getField("fromSubSubClass").getGenericType())
            .getTypeParameter0()
            .getCls(),
        String.class);
    Assert.assertEquals(
        GenericType.build(
                Test4.class, Test1.class.getField("multipleFromSubClasses").getGenericType())
            .getTypeParameter0()
            .getCls(),
        Float.class);
    Assert.assertEquals(
        GenericType.build(
                Test4.class, Test1.class.getField("multipleFromSubClasses").getGenericType())
            .getTypeParameter1()
            .getCls(),
        String.class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test1.class.getField("multipleWithUnknown").getGenericType())
            .getTypeParameter0()
            .getCls(),
        Float.class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test1.class.getField("multipleWithUnknown").getGenericType())
            .getTypeParameter1()
            .getCls(),
        Object.class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test1.class.getField("arrayFromSubClass").getGenericType())
            .getCls(),
        Float[].class);
    Assert.assertEquals(
        GenericType.build(
                Test4.class, Test1.class.getField("arrayFromSubSubClass").getGenericType())
            .getCls(),
        String[].class);

    // Test2
    Assert.assertEquals(
        GenericType.build(Test4.class, Test2.class.getField("known").getGenericType()).getCls(),
        String.class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test2.class.getField("array1").getGenericType()).getCls(),
        String[].class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test2.class.getField("array2").getGenericType()).getCls(),
        String[][].class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test2.class.getField("unknown1").getGenericType())
            .getTypeParameter0()
            .getCls(),
        Object.class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test2.class.getField("arrayUnknown1").getGenericType())
            .getCls(),
        Object[].class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test2.class.getField("fromField2").getGenericType())
            .getTypeParameter0()
            .getCls(),
        String.class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test2.class.getField("arrayWithTypeVar").getGenericType())
            .getTypeParameter0()
            .getTypeParameter0()
            .getCls(),
        String.class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test2.class.getField("fromFieldNested").getGenericType())
            .getTypeParameter0()
            .getTypeParameter0()
            .getCls(),
        String.class);
    Assert.assertEquals(
        GenericType.build(
                Test4.class, Test2.class.getField("parameterizedTypeFromSubClass").getGenericType())
            .getCls(),
        ArrayList.class);
    Assert.assertEquals(
        GenericType.build(
                Test4.class,
                Test2.class.getField("parameterizedArrayFromSubClass").getGenericType())
            .getTypeParameter0()
            .getCls(),
        ArrayList.class);

    // Test3
    Assert.assertEquals(
        GenericType.build(Test4.class, Test3.class.getField("fromField3").getGenericType())
            .getTypeParameter0()
            .getCls(),
        String.class);
    Assert.assertNull(
        GenericType.build(Test4.class, Test3.class.getField("raw").getGenericType())
            .getTypeParameter0());
    Assert.assertEquals(
        GenericType.build(Test4.class, Test3.class.getField("unknown2").getGenericType()).getCls(),
        Object.class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test3.class.getField("arrayUnknown2").getGenericType())
            .getCls(),
        Object[].class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test3.class.getField("unboundWildcard").getGenericType())
            .getTypeParameter0()
            .getCls(),
        Object.class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test3.class.getField("upperBound").getGenericType())
            .getTypeParameter0()
            .getCls(),
        Number.class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test3.class.getField("lowerBound").getGenericType())
            .getTypeParameter0()
            .getCls(),
        Object.class);
    Assert.assertEquals(
        GenericType.build(Test4.class, Test3.class.getField("multipleUpperBounds").getGenericType())
            .getTypeParameter0()
            .getCls(),
        Double.class);

    // Test5
    Assert.assertEquals(
        GenericType.build(Test5.class, Test5.class.getField("f1").getGenericType())
            .getTypeParameter0()
            .getCls(),
        String.class);
  }

  @Test
  public void testGenerics() throws NoSuchFieldException {
    Generics generics = new Generics();
    int depth = 0;
    {
      GenericType genericType =
          GenericType.build(Test4.class, Test2.class.getField("fromFieldNested").getGenericType());
      // push generics in outer serialization.
      generics.pushGenericType(genericType, depth);
      // increase serialization depth.
      depth += 1;
      // get generics in inner serialization.
      GenericType genericType1 = generics.nextGenericType(depth);
      Assert.assertSame(genericType1, genericType);
      depth -= 1;
      generics.popGenericType(depth);
    }
    {
      for (String fieldName : new String[] {"fromField2", "arrayWithTypeVar", "fromFieldNested"}) {
        GenericType genericType =
            GenericType.build(Test4.class, Test2.class.getField(fieldName).getGenericType());
        generics.pushGenericType(genericType, depth);
        depth += 1;
      }
      for (String fieldName :
          ImmutableList.of("fromField2", "arrayWithTypeVar", "fromFieldNested").reverse()) {
        GenericType genericType =
            GenericType.build(Test4.class, Test2.class.getField(fieldName).getGenericType());
        GenericType genericType1 = generics.nextGenericType(depth);
        Assert.assertEquals(genericType1.typeRef, genericType.typeRef);
        depth -= 1;
        generics.popGenericType(depth);
      }
    }
    Assert.assertEquals(TestUtils.getFieldValue(generics, "genericTypesSize"), Integer.valueOf(0));
    GenericType[] genericTypes = TestUtils.getFieldValue(generics, "genericTypes");
    Assert.assertNull(genericTypes[0]);
    Assert.assertNull(genericTypes[1]);
  }
}
