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
import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.fory.benchmark.data.MediaContent;
import org.apache.fory.json.ForyJson;
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

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@Threads(1)
public class JsonSerializationSuite {
  @State(Scope.Thread)
  public static class JsonState {
    ForyJson foryJson;
    JSONReader.Context fastjson2ReadContext;
    JSONWriter.Context fastjson2WriteContext;
    ObjectMapper mapper;
    Gson gson;
    MediaContent mediaContent;
    byte[] jsonBytes;
    String jsonString;

    @Setup
    public void setup() {
      foryJson = ForyJson.builder().build();
      fastjson2ReadContext = JSONFactory.createReadContext();
      fastjson2WriteContext = new JSONWriter.Context();
      mapper = new ObjectMapper();
      gson = new Gson();
      jsonString = readResource();
      jsonBytes = jsonString.getBytes(StandardCharsets.UTF_8);
      mediaContent = JSON.parseObject(jsonString, MediaContent.class);
      byte[] foryBytes = foryJson.toJsonBytes(mediaContent);
      byte[] fastjsonBytes =
          JSON.toJSONBytes(mediaContent, StandardCharsets.UTF_8, fastjson2WriteContext);
      if (!JSON.parseObject(foryBytes).equals(JSON.parseObject(fastjsonBytes))) {
        throw new IllegalStateException("Fory JSON and fastjson2 produce different JSON objects");
      }
      String foryString = foryJson.toJson(mediaContent);
      String fastjsonString = JSON.toJSONString(mediaContent, fastjson2WriteContext);
      if (!JSON.parseObject(foryString).equals(JSON.parseObject(fastjsonString))) {
        throw new IllegalStateException("Fory JSON and fastjson2 produce different JSON strings");
      }
      try {
        verifyDecoded("Fory JSON bytes", foryJson.fromJson(jsonBytes, MediaContent.class));
        verifyDecoded(
            "Fastjson2 bytes",
            JSON.parseObject(jsonBytes, MediaContent.class, fastjson2ReadContext));
        verifyDecoded("Jackson bytes", mapper.readValue(jsonBytes, MediaContent.class));
        verifyDecoded(
            "Gson bytes",
            gson.fromJson(new String(jsonBytes, StandardCharsets.UTF_8), MediaContent.class));
        verifyDecoded("Fory JSON string", foryJson.fromJson(jsonString, MediaContent.class));
        verifyDecoded(
            "Fastjson2 string",
            JSON.parseObject(jsonString, MediaContent.class, fastjson2ReadContext));
        verifyDecoded("Jackson string", mapper.readValue(jsonString, MediaContent.class));
        verifyDecoded("Gson string", gson.fromJson(jsonString, MediaContent.class));
      } catch (IOException e) {
        throw new IllegalStateException("Unable to verify JSON deserialization", e);
      }
    }

    private void verifyDecoded(String serializer, MediaContent decoded) {
      if (!mediaContent.equals(decoded)) {
        throw new IllegalStateException(serializer + " produced different MediaContent");
      }
    }

    private static String readResource() {
      InputStream input =
          JsonSerializationSuite.class.getClassLoader().getResourceAsStream("data/eishay.json");
      if (input == null) {
        throw new IllegalStateException("Missing data/eishay.json");
      }
      try (InputStream closeable = input;
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

  @Benchmark
  public byte[] foryToJsonBytes(JsonState state) {
    return state.foryJson.toJsonBytes(state.mediaContent);
  }

  @Benchmark
  public byte[] fastjson2ToJsonBytes(JsonState state) {
    return JSON.toJSONBytes(
        state.mediaContent, StandardCharsets.UTF_8, state.fastjson2WriteContext);
  }

  @Benchmark
  public byte[] jacksonToJsonBytes(JsonState state) throws IOException {
    return state.mapper.writeValueAsBytes(state.mediaContent);
  }

  @Benchmark
  public byte[] gsonToJsonBytes(JsonState state) {
    return state.gson.toJson(state.mediaContent).getBytes(StandardCharsets.UTF_8);
  }

  @Benchmark
  public String foryToJsonString(JsonState state) {
    return state.foryJson.toJson(state.mediaContent);
  }

  @Benchmark
  public String fastjson2ToJsonString(JsonState state) {
    return JSON.toJSONString(state.mediaContent, state.fastjson2WriteContext);
  }

  @Benchmark
  public String jacksonToJsonString(JsonState state) throws IOException {
    return state.mapper.writeValueAsString(state.mediaContent);
  }

  @Benchmark
  public String gsonToJsonString(JsonState state) {
    return state.gson.toJson(state.mediaContent);
  }

  @Benchmark
  public MediaContent foryFromJsonBytes(JsonState state) {
    return state.foryJson.fromJson(state.jsonBytes, MediaContent.class);
  }

  @Benchmark
  public MediaContent fastjson2FromJsonBytes(JsonState state) {
    return JSON.parseObject(state.jsonBytes, MediaContent.class, state.fastjson2ReadContext);
  }

  @Benchmark
  public MediaContent jacksonFromJsonBytes(JsonState state) throws IOException {
    return state.mapper.readValue(state.jsonBytes, MediaContent.class);
  }

  @Benchmark
  public MediaContent gsonFromJsonBytes(JsonState state) {
    return state.gson.fromJson(
        new String(state.jsonBytes, StandardCharsets.UTF_8), MediaContent.class);
  }

  @Benchmark
  public MediaContent foryFromJsonString(JsonState state) {
    return state.foryJson.fromJson(state.jsonString, MediaContent.class);
  }

  @Benchmark
  public MediaContent fastjson2FromJsonString(JsonState state) {
    return JSON.parseObject(state.jsonString, MediaContent.class, state.fastjson2ReadContext);
  }

  @Benchmark
  public MediaContent jacksonFromJsonString(JsonState state) throws IOException {
    return state.mapper.readValue(state.jsonString, MediaContent.class);
  }

  @Benchmark
  public MediaContent gsonFromJsonString(JsonState state) {
    return state.gson.fromJson(state.jsonString, MediaContent.class);
  }
}
