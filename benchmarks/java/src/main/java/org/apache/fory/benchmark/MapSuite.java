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

package org.apache.fory.benchmark;

import io.timeandspace.smoothie.SwissTable;
import java.io.IOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.stream.IntStream;
import org.apache.fory.collection.IdentityMap;
import org.apache.fory.collection.IdentityObjectIntMap;
import org.apache.fory.collection.ObjectMap;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

public class MapSuite {
  public static void main(String[] args) throws IOException {
    if (args.length == 0) {
      String commandLine = "org.apache.fory.*MapSuite.* -f 1 -wi 3 -i 3 -t 1 -w 2s -r 2s -rf csv";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    Main.main(args);
  }

  @State(Scope.Thread)
  public static class MapState {
    @Param() public MapType mapType;
    IdentityHashMap<Object, Integer> jdkIdentityHashMap = new IdentityHashMap<>();
    IdentityMap<Object, Integer> identityMap = new IdentityMap<>(51, 0.5f);
    Identity2IdMap identity2IdMap = new Identity2IdMap(51);
    IdentityObjectIntMap<Object> identityObjectIntMap = new IdentityObjectIntMap<>(51, 0.5f);
    HashMap<Object, Integer> hashMap = new HashMap<>(51, 0.5f);
    ObjectMap<Object, Integer> objectMap = new ObjectMap<>(51, 0.5f);
    SwissTable<Object, Integer> swissTable = new SwissTable<>();
    Object[] objects = IntStream.range(0, 500).mapToObj(x -> new Object()).toArray();
  }

  public enum MapType {
    JDK_IDENTITY_HASH_MAP,
    FORY_IDENTITY_MAP,
    FST_IDENTITY_ID_MAP,
    FORY_IDENTITY_OBJECT_INT_MAP,
    JDK_HASH_MAP,
    FORY_OBJECT_MAP,
    SWISS_TABLE
  }

  // Benchmark                           (mapType)   Mode  Cnt       Score        Error  Units
  // MapSuite.mapPut         JDK_IDENTITY_HASH_MAP  thrpt    3   75986.955 ±  72223.455  ops/s
  // MapSuite.mapPut             FORY_IDENTITY_MAP  thrpt    3   92591.956 ±  52688.050  ops/s
  // MapSuite.mapPut           FST_IDENTITY_ID_MAP  thrpt    3  160133.546 ±  33177.290  ops/s
  // MapSuite.mapPut  FORY_IDENTITY_OBJECT_INT_MAP  thrpt    3  198303.860 ±  25850.037  ops/s
  // MapSuite.mapPut                  JDK_HASH_MAP  thrpt    3   74419.121 ±  22626.560  ops/s
  // MapSuite.mapPut               FORY_OBJECT_MAP  thrpt    3   79909.686 ± 172890.529  ops/s
  // MapSuite.mapPut                   SWISS_TABLE  thrpt    3   79599.055 ±   8423.791  ops/s
  @Benchmark
  public Object mapPut(MapSuite.MapState state, Blackhole blackhole) {
    Object o = mapPut(state, state.objects, blackhole);
    blackhole.consume(o);
    return o;
  }

  // @Benchmark
  public Object mapPutSame(MapSuite.MapState state, Blackhole blackhole) {
    Object[] objects = new Object[500];
    Object[] arr = IntStream.range(0, objects.length / 2).mapToObj(x -> new Object()).toArray();
    System.arraycopy(arr, 0, objects, 0, arr.length);
    System.arraycopy(arr, 0, objects, arr.length, arr.length);
    Object o = mapPut(state, objects, blackhole);
    blackhole.consume(o);
    return o;
  }

  private Object mapPut(MapSuite.MapState state, Object[] objects, Blackhole blackhole) {
    switch (state.mapType) {
      case JDK_IDENTITY_HASH_MAP:
        state.jdkIdentityHashMap.clear();
        for (int i = 0; i < objects.length; i++) {
          if (state.jdkIdentityHashMap.containsKey(objects[i])) {
            blackhole.consume(i);
          } else {
            state.jdkIdentityHashMap.put(objects[i], i);
            blackhole.consume(i);
          }
        }
        return state.jdkIdentityHashMap;
      case FORY_IDENTITY_MAP:
        state.identityMap.clear();
        for (int i = 0; i < 500; i++) {
          if (state.identityMap.get(objects[i]) == null) {
            blackhole.consume(state.identityMap.put(objects[i], i));
          }
        }
        return state.identityMap;
      case FST_IDENTITY_ID_MAP:
        state.identity2IdMap.clear();
        for (int i = 0; i < 500; i++) {
          blackhole.consume(state.identity2IdMap.putOrGet(objects[i], i));
        }
        return state.identity2IdMap;
      case FORY_IDENTITY_OBJECT_INT_MAP:
        state.identityObjectIntMap.clear();
        for (int i = 0; i < 500; i++) {
          blackhole.consume(state.identityObjectIntMap.putOrGet(objects[i], i));
        }
        return state.identityObjectIntMap;
      case JDK_HASH_MAP:
        state.hashMap.clear();
        for (int i = 0; i < 500; i++) {
          if (state.hashMap.get(objects[i]) == null) {
            blackhole.consume(state.hashMap.put(objects[i], i));
          }
        }
        return state.hashMap;
      case FORY_OBJECT_MAP:
        state.objectMap.clear();
        for (int i = 0; i < 500; i++) {
          if (state.objectMap.get(objects[i]) == null) {
            blackhole.consume(state.objectMap.put(objects[i], i));
          }
        }
        return state.objectMap;
      case SWISS_TABLE:
        state.swissTable = new SwissTable<>(500);
        for (int i = 0; i < 500; i++) {
          if (state.swissTable.get(objects[i]) == null) {
            blackhole.consume(state.swissTable.put(objects[i], i));
          }
        }
        return state.swissTable;
    }
    return null;
  }

  @State(Scope.Thread)
  public static class StringState {}
}
