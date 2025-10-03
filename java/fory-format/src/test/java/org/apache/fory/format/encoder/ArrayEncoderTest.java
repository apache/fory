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

package org.apache.fory.format.encoder;

import static org.apache.fory.format.encoder.CodecBuilderTest.testStreamingEncode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.reflect.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ArrayEncoderTest {

  @Test
  public void testListEncoder() {
    List<RowEncoderTest.Bar> bars = new ArrayList<>();
    for (int k = 0; k < 5; k++) {
      RowEncoderTest.Bar bar = new RowEncoderTest.Bar();
      bar.f1 = k;
      bar.f2 = "i" + k;
      bars.add(bar);
    }

    ArrayEncoder<List<RowEncoderTest.Bar>> encoder =
        Encoders.arrayEncoder(new TypeRef<List<RowEncoderTest.Bar>>() {});
    BinaryArray array = encoder.toArray(bars);
    List<RowEncoderTest.Bar> newBars = encoder.fromArray(array);

    Assert.assertEquals(bars, newBars);

    byte[] bs = encoder.encode(bars);
    List<RowEncoderTest.Bar> bbars = encoder.decode(bs);

    Assert.assertEquals(bs.length, 216);
    Assert.assertEquals(bars, bbars);

    testStreamingEncode(encoder, bars);
  }

  @Test
  public void testNestListEncoder() {
    List<List<List<RowEncoderTest.Bar>>> bars = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      List<List<RowEncoderTest.Bar>> bars1 = new ArrayList<>();
      for (int j = 0; j < i; j++) {
        List<RowEncoderTest.Bar> bars2 = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
          RowEncoderTest.Bar bar = new RowEncoderTest.Bar();
          bar.f1 = k;
          bar.f2 = "s" + k;
          bars2.add(bar);
        }
        bars1.add(bars2);
      }
      bars.add(bars1);
    }

    ArrayEncoder<List<List<List<RowEncoderTest.Bar>>>> encoder =
        Encoders.arrayEncoder(new TypeRef<List<List<List<RowEncoderTest.Bar>>>>() {});
    BinaryArray array = encoder.toArray(bars);
    List<List<List<RowEncoderTest.Bar>>> newBars = encoder.fromArray(array);

    Assert.assertEquals(bars, newBars);

    byte[] bs = encoder.encode(bars);
    List<List<List<RowEncoderTest.Bar>>> bbars = encoder.decode(bs);

    Assert.assertEquals(bs.length, 1568);
    Assert.assertEquals(bars, bbars);

    testStreamingEncode(encoder, bars);
  }

  @Test
  public void testNestArrayWithMapEncoder() {
    List<List<Map<RowEncoderTest.Foo, List<RowEncoderTest.Bar>>>> lmap = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      List<Map<RowEncoderTest.Foo, List<RowEncoderTest.Bar>>> list = new ArrayList<>();
      for (int j = 0; j < 3; j++) {
        Map<RowEncoderTest.Foo, List<RowEncoderTest.Bar>> map = new HashMap<>();
        RowEncoderTest.Bar bar = new RowEncoderTest.Bar();
        bar.f1 = j;
        bar.f2 = "x" + j;
        map.put(new RowEncoderTest.Foo(), Arrays.asList(bar));
        list.add(map);
      }
      lmap.add(list);
    }

    ArrayEncoder<List<List<Map<RowEncoderTest.Foo, List<RowEncoderTest.Bar>>>>> encoder =
        Encoders.arrayEncoder(
            new TypeRef<List<List<Map<RowEncoderTest.Foo, List<RowEncoderTest.Bar>>>>>() {});
    BinaryArray array = encoder.toArray(lmap);
    List<List<Map<RowEncoderTest.Foo, List<RowEncoderTest.Bar>>>> newLmap =
        encoder.fromArray(array);

    Assert.assertEquals(lmap, newLmap);

    byte[] bs = encoder.encode(lmap);
    List<List<Map<RowEncoderTest.Foo, List<RowEncoderTest.Bar>>>> blmap = encoder.decode(bs);

    Assert.assertEquals(bs.length, 10816);
    Assert.assertEquals(lmap, blmap);

    testStreamingEncode(encoder, lmap);
  }
}
