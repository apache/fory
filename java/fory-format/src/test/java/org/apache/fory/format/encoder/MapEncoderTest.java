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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.format.row.binary.BinaryMap;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.test.bean.Foo;
import org.apache.fory.test.bean.SimpleFoo;
import org.testng.Assert;
import org.testng.annotations.Test;

public class MapEncoderTest {

  @Test
  public void testMapEncoder() {
    Map<String, RowEncoderTest.Bar> bars = new HashMap<>();
    for (int k = 0; k < 5; k++) {
      RowEncoderTest.Bar bar = new RowEncoderTest.Bar();
      bar.f1 = k;
      bar.f2 = "i" + k;
      bars.put(bar.f2, bar);
    }

    MapEncoder<Map<String, RowEncoderTest.Bar>> encoder =
        Encoders.mapEncoder(bars.getClass(), String.class, RowEncoderTest.Bar.class);
    BinaryMap array = encoder.toMap(bars);
    Map<String, RowEncoderTest.Bar> newBars = encoder.fromMap(array);
    Assert.assertEquals(bars, newBars);

    byte[] bytes = encoder.encode(bars);
    Map<String, RowEncoderTest.Bar> decodeMap = encoder.decode(bytes);
    Assert.assertEquals(decodeMap.size(), 5);
  }

  @Test
  public void testNestListEncoder() {
    Map<String, List<List<RowEncoderTest.Bar>>> bars = new HashMap<>();
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
      bars.put("" + i, bars1);
    }

    MapEncoder<Map<String, List<List<RowEncoderTest.Bar>>>> encoder =
        Encoders.mapEncoder(new TypeRef<Map<String, List<List<RowEncoderTest.Bar>>>>() {});
    BinaryMap array = encoder.toMap(bars);
    Map<String, List<List<RowEncoderTest.Bar>>> newBars = encoder.fromMap(array);

    Assert.assertEquals(bars, newBars);

    byte[] bytes = encoder.encode(bars);
    Map<String, List<List<RowEncoderTest.Bar>>> decodeMap = encoder.decode(bytes);
    Assert.assertEquals(decodeMap.size(), 5);
  }

  @Test
  public void testSimpleNestArrayWithMapEncoder1() {
    Map<String, List<Integer>> map = new HashMap<>();
    map.put("k1", ImmutableList.of(1, 2));

    MapEncoder<Map<String, List<Integer>>> encoder =
        Encoders.mapEncoder(new TypeRef<Map<String, List<Integer>>>() {});

    testStreamingEncode(encoder, map);
  }

  @Test
  public void testSimpleNestArrayWithMapEncoder2() {
    Map<String, List<List<Integer>>> map = new HashMap<>();
    map.put("k1", ImmutableList.of(ImmutableList.of(1, 2), ImmutableList.of(1, 2)));

    MapEncoder<Map<String, List<List<Integer>>>> encoder =
        Encoders.mapEncoder(new TypeRef<Map<String, List<List<Integer>>>>() {});

    testStreamingEncode(encoder, map);
  }

  @Test
  public void testSimpleStructWithMapEncoder2() {
    Map<String, Foo> map = new HashMap<>();
    map.put("k1", Foo.create());

    MapEncoder<Map<String, Foo>> encoder = Encoders.mapEncoder(new TypeRef<Map<String, Foo>>() {});

    testStreamingEncode(encoder, map);
  }

  @Test
  public void testSimpleNestStructWithMapEncoder() {
    Map<String, List<Foo>> map = new HashMap<>();
    map.put("k1", ImmutableList.of(Foo.create()));

    MapEncoder<Map<String, List<Foo>>> encoder =
        Encoders.mapEncoder(new TypeRef<Map<String, List<Foo>>>() {});

    testStreamingEncode(encoder, map);
  }

  @Test
  public void testKVStructMap() {
    Map<SimpleFoo, SimpleFoo> map = ImmutableMap.of(SimpleFoo.create(), SimpleFoo.create());
    var encoder = Encoders.mapEncoder(new TypeRef<Map<SimpleFoo, SimpleFoo>>() {});
    testStreamingEncode(encoder, map);
    var encoder1 = Encoders.mapEncoder(new TypeRef<Map<Foo, Foo>>() {});
    testStreamingEncode(encoder1, ImmutableMap.of(Foo.create(), Foo.create()));
  }

  @Test
  public void testSimpleNestKVStructMapArray() {
    ArrayEncoder<List<Map<SimpleFoo, SimpleFoo>>> encoder =
        Encoders.arrayEncoder(new TypeRef<List<Map<SimpleFoo, SimpleFoo>>>() {});

    testStreamingEncode(
        encoder, ImmutableList.of(ImmutableMap.of(SimpleFoo.create(), SimpleFoo.create())));
  }

  @Test
  public void testSimpleNestKVStruct() {
    Map<String, List<Map<Foo, Foo>>> map = new HashMap<>();
    map.put("k1", ImmutableList.of(ImmutableMap.of(Foo.create(), Foo.create())));

    MapEncoder<Map<String, List<Map<Foo, Foo>>>> encoder =
        Encoders.mapEncoder(new TypeRef<Map<String, List<Map<Foo, Foo>>>>() {});

    testStreamingEncode(encoder, map);
  }

  @Test
  public void testNestArrayWithMapEncoder() {
    Map<String, List<Map<RowEncoderTest.Foo, List<RowEncoderTest.Bar>>>> lmap = new HashMap<>();
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
      lmap.put("" + i, list);
    }

    MapEncoder<Map<String, List<Map<RowEncoderTest.Foo, List<RowEncoderTest.Bar>>>>> encoder =
        Encoders.mapEncoder(
            new TypeRef<Map<String, List<Map<RowEncoderTest.Foo, List<RowEncoderTest.Bar>>>>>() {});
    BinaryMap array = encoder.toMap(lmap);
    Map<String, List<Map<RowEncoderTest.Foo, List<RowEncoderTest.Bar>>>> newLmap =
        encoder.fromMap(array);

    Assert.assertEquals(lmap, newLmap);

    byte[] bytes = encoder.encode(lmap);
    Map<String, List<Map<RowEncoderTest.Foo, List<RowEncoderTest.Bar>>>> decodeMap =
        encoder.decode(bytes);
    Assert.assertEquals(decodeMap.size(), 10);

    testStreamingEncode(encoder, lmap);
  }

  @Test
  public <K, V> void testDynamicTypeDeclaration() {
    Encoders.mapEncoder(
            new TypeRef<HashMap<Integer, Bean>>() {},
            TypeRef.of(Integer.class),
            TypeRef.of(Bean.class),
            null)
        .encode(new HashMap<>());
  }

  public static class Bean {
    int f1;
  }
}
