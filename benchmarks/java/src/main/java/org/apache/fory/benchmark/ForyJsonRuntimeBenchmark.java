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

import com.alibaba.fastjson2.JSON;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.fory.benchmark.data.MediaContent;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.annotation.JsonAnyGetter;
import org.apache.fory.json.annotation.JsonAnySetter;
import org.apache.fory.json.annotation.JsonIgnore;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/** Generated, interpreted, and cold-construction regression coverage for Fory JSON. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 7, time = 1)
@Fork(1)
@Threads(1)
public class ForyJsonRuntimeBenchmark {
  @State(Scope.Thread)
  public static class RuntimeState {
    @Param({"false", "true"})
    public boolean codegen;

    ForyJson foryJson;
    MediaContent value;
    String json;
    byte[] jsonBytes;

    @Setup
    public void setup() {
      value = JSON.parseObject(readResource(), MediaContent.class);
      foryJson = ForyJson.builder().withCodegen(codegen).withAsyncCompilation(false).build();
      json = foryJson.toJson(value);
      jsonBytes = foryJson.toJsonBytes(value);
      foryJson.fromJson(json, MediaContent.class);
      foryJson.fromJson(jsonBytes, MediaContent.class);
    }
  }

  @State(Scope.Thread)
  public static class ColdState {
    @Param({"false", "true"})
    public boolean codegen;

    MediaContent value;

    @Setup
    public void setup() {
      value = JSON.parseObject(readResource(), MediaContent.class);
    }
  }

  @State(Scope.Thread)
  public static class AnyState {
    @Param({"false", "true"})
    public boolean codegen;

    ForyJson foryJson;
    AnyModel value;
    String json;

    @Setup
    public void setup() {
      foryJson = ForyJson.builder().withCodegen(codegen).withAsyncCompilation(false).build();
      value = new AnyModel();
      value.fixed = "fixed";
      value.put("first", 1);
      value.put("second", "two");
      json = foryJson.toJson(value);
      foryJson.fromJson(json, AnyModel.class);
    }
  }

  public static class AnyModel {
    public String fixed;
    @JsonIgnore private final Map<String, Object> storage = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, Object> values() {
      return storage;
    }

    @JsonAnySetter
    public void put(String name, Object value) {
      storage.put(name, value);
    }
  }

  @Benchmark
  public String writeString(RuntimeState state) {
    return state.foryJson.toJson(state.value);
  }

  @Benchmark
  public byte[] writeBytes(RuntimeState state) {
    return state.foryJson.toJsonBytes(state.value);
  }

  @Benchmark
  public MediaContent readString(RuntimeState state) {
    return state.foryJson.fromJson(state.json, MediaContent.class);
  }

  @Benchmark
  public MediaContent readBytes(RuntimeState state) {
    return state.foryJson.fromJson(state.jsonBytes, MediaContent.class);
  }

  @Benchmark
  public String writeAny(AnyState state) {
    return state.foryJson.toJson(state.value);
  }

  @Benchmark
  public AnyModel readAny(AnyState state) {
    return state.foryJson.fromJson(state.json, AnyModel.class);
  }

  @Benchmark
  public String constructAndWrite(ColdState state) {
    ForyJson foryJson =
        ForyJson.builder().withCodegen(state.codegen).withAsyncCompilation(false).build();
    return foryJson.toJson(state.value);
  }

  private static String readResource() {
    InputStream stream =
        ForyJsonRuntimeBenchmark.class.getClassLoader().getResourceAsStream("data/eishay.json");
    if (stream == null) {
      throw new IllegalStateException("Missing data/eishay.json");
    }
    try (InputStream closeable = stream;
        InputStreamReader reader = new InputStreamReader(closeable, StandardCharsets.UTF_8)) {
      char[] buffer = new char[1024];
      StringBuilder builder = new StringBuilder();
      int read;
      while ((read = reader.read(buffer)) != -1) {
        builder.append(buffer, 0, read);
      }
      return builder.toString();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to read data/eishay.json", e);
    }
  }
}
