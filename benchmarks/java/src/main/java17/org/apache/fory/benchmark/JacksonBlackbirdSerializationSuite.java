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

import java.util.concurrent.TimeUnit;
import org.apache.fory.benchmark.JsonSerializationSuite.JsonState;
import org.apache.fory.benchmark.data.MediaContent;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.module.blackbird.BlackbirdModule;

/** Jackson 3 Blackbird benchmarks, isolated here because Jackson 3 requires Java 17 bytecode. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@Threads(1)
public class JacksonBlackbirdSerializationSuite {
  @State(Scope.Thread)
  public static class BlackbirdState extends JsonState {
    JsonMapper blackbirdMapper;

    @Setup
    @Override
    public void setup() {
      super.setup();
      blackbirdMapper = JsonMapper.builder().addModule(new BlackbirdModule()).build();
      if (!mediaContent.equals(blackbirdMapper.readValue(jsonBytes, MediaContent.class))
          || !mediaContent.equals(blackbirdMapper.readValue(jsonString, MediaContent.class))) {
        throw new IllegalStateException("Jackson Blackbird produced different MediaContent");
      }
    }
  }

  @Benchmark
  public byte[] blackbirdToJsonBytes(BlackbirdState state) {
    return state.blackbirdMapper.writeValueAsBytes(state.mediaContent);
  }

  @Benchmark
  public String blackbirdToJsonString(BlackbirdState state) {
    return state.blackbirdMapper.writeValueAsString(state.mediaContent);
  }

  @Benchmark
  public MediaContent blackbirdFromJsonBytes(BlackbirdState state) {
    return state.blackbirdMapper.readValue(state.jsonBytes, MediaContent.class);
  }

  @Benchmark
  public MediaContent blackbirdFromJsonString(BlackbirdState state) {
    return state.blackbirdMapper.readValue(state.jsonString, MediaContent.class);
  }
}
