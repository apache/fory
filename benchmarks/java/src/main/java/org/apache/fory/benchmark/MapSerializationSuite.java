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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.fory.Fory;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@BenchmarkMode(Mode.Throughput)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class MapSerializationSuite {
  public static void main(String[] args) throws IOException {
    if (args.length == 0) {
      String commandLine =
          "org.apache.fory.*MapSerializationSuite.* -f 1 -wi 3 -i 3 -t 1 -w 2s -r 2s -rf csv";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    Main.main(args);
  }

  public static class StringKVMapStruct {
    Map<String, String> map;
  }

  public static class IntKVMapStruct {
    Map<Integer, Integer> map;
  }

  @State(Scope.Thread)
  public static class MapState {
    @Param({"50"})
    public int mapSize;

    @Param({"false", "true"})
    public boolean struct;

    @Param({"int", "string"})
    public String datatype;

    private Object object;
    private byte[] bytes;
    private Fory fory;

    @Setup(Level.Trial)
    public void setup() {
      fory = Fory.builder().build();
      fory.register(StringKVMapStruct.class);
      fory.register(IntKVMapStruct.class);
      Map<String, String> stringMap = new HashMap<>(mapSize);
      Map<Integer, Integer> intMap = new HashMap<>(mapSize);
      for (int i = 0; i < mapSize; i++) {
        stringMap.put("k" + i, "v" + i);
        intMap.put(i, i * 2);
      }
      StringKVMapStruct stringKVMapStruct = new StringKVMapStruct();
      stringKVMapStruct.map = stringMap;
      IntKVMapStruct intKVMapStruct = new IntKVMapStruct();
      intKVMapStruct.map = intMap;
      byte[] stringMapBytes = fory.serialize(stringMap);
      byte[] intMapBytes = fory.serialize(intMap);
      byte[] stringKVStructBytes = fory.serialize(stringKVMapStruct);
      byte[] intKVStructBytes = fory.serialize(intKVMapStruct);
      switch (datatype) {
        case "int":
          object = struct ? intKVMapStruct : intMap;
          bytes = struct ? intKVStructBytes : intMapBytes;
          break;
        case "string":
          object = struct ? stringKVMapStruct : stringMap;
          bytes = struct ? stringKVStructBytes : stringMapBytes;
          break;
        default:
          throw new UnsupportedOperationException();
      }
    }
  }

  @Benchmark
  public Object serialize(MapState state) {
    return state.fory.serialize(state.object);
  }

  @Benchmark
  public Object deserialize(MapState state) {
    return state.fory.deserialize(state.bytes);
  }
}
