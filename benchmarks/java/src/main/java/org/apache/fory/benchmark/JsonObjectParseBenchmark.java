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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.JsonObject;
import org.apache.fory.reflect.TypeRef;
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
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@Threads(1)
public class JsonObjectParseBenchmark {
  private static final String LATIN1_JSON =
      "{\"alpha\":1,\"bravo\":2,\"charlie\":3,\"delta\":4,"
          + "\"echo\":5,\"foxtrot\":6,\"golf\":7,\"hotel\":8}";
  private static final String UTF16_JSON =
      "{\"alpha\":\"中文\",\"bravo\":2,\"charlie\":3,\"delta\":4,"
          + "\"echo\":5,\"foxtrot\":6,\"golf\":7,\"hotel\":8}";
  private static final String LENGTH_17_JSON =
      "{\"abcdefghijklmnopq\":1,\"bcdefghijklmnopqr\":2,"
          + "\"cdefghijklmnopqrs\":3,\"defghijklmnopqrst\":4,"
          + "\"efghijklmnopqrstu\":5,\"fghijklmnopqrstuv\":6,"
          + "\"ghijklmnopqrstuvw\":7,\"hijklmnopqrstuvwx\":8}";
  private static final String ESCAPED_NAMES_JSON =
      "{\"al\\u0070ha\":1,\"br\\u0061vo\":2,\"ch\\u0061rlie\":3,\"del\\u0074a\":4,"
          + "\"ec\\u0068o\":5,\"fox\\u0074rot\":6,\"go\\u006cf\":7,\"hot\\u0065l\":8}";
  private static final String UNICODE_NAMES_JSON =
      "{\"甲\":1,\"乙\":2,\"丙\":3,\"丁\":4,\"戊\":5,\"己\":6,\"庚\":7,\"辛\":8}";
  private static final String LOCAL_COLLISION_JSON = "{\"second\":1}";
  private static final String SHARED_FULL_JSON = "{\"second\":1}";
  private static final String SHARED_FULL_MIXED_JSON = "{\"first\":1,\"second\":2}";
  private static final TypeRef<Map<String, Integer>> STRING_INT_MAP =
      new TypeRef<Map<String, Integer>>() {};

  @State(Scope.Thread)
  public static class ParseState {
    private ForyJson cachedJson;
    private ForyJson disabledJson;
    private ForyJson localCollisionJson;
    private ForyJson sharedFullJson;
    private byte[] utf8Json;
    private byte[] unicodeNamesUtf8;

    @Setup
    public void setup() {
      cachedJson = buildJson(ForyJson.DEFAULT_MAX_CACHED_MEMBER_NAMES);
      disabledJson = buildJson(0);
      localCollisionJson = buildJson(3);
      sharedFullJson = buildJson(1);
      utf8Json = LATIN1_JSON.getBytes(StandardCharsets.UTF_8);
      unicodeNamesUtf8 = UNICODE_NAMES_JSON.getBytes(StandardCharsets.UTF_8);
      localCollisionJson.fromJson("{\"first\":0}", JsonObject.class);
      sharedFullJson.fromJson("{\"first\":0}".getBytes(StandardCharsets.UTF_8), JsonObject.class);
    }

    private static ForyJson buildJson(int maxCachedMemberNames) {
      return ForyJson.builder()
          .withConcurrencyLevel(1)
          .withAsyncCompilation(false)
          .withMaxCachedMemberNames(maxCachedMemberNames)
          .build();
    }
  }

  public static class TypedPojo {
    public int alpha;
    public int bravo;
    public int charlie;
    public int delta;
    public int echo;
    public int foxtrot;
    public int golf;
    public int hotel;
  }

  @Benchmark
  public void parseLatin1(ParseState state, Blackhole blackhole) {
    blackhole.consume(state.cachedJson.fromJson(LATIN1_JSON, JsonObject.class));
  }

  @Benchmark
  public void parseUtf16(ParseState state, Blackhole blackhole) {
    blackhole.consume(state.cachedJson.fromJson(UTF16_JSON, JsonObject.class));
  }

  @Benchmark
  public void parseUtf8(ParseState state, Blackhole blackhole) {
    blackhole.consume(state.cachedJson.fromJson(state.utf8Json, JsonObject.class));
  }

  @Benchmark
  public void parseCacheDisabled(ParseState state, Blackhole blackhole) {
    blackhole.consume(state.disabledJson.fromJson(LATIN1_JSON, JsonObject.class));
  }

  @Benchmark
  public void parseCacheDisabledObject(ParseState state, Blackhole blackhole) {
    blackhole.consume(state.disabledJson.fromJson(LATIN1_JSON, Object.class));
  }

  @Benchmark
  public void parseCacheDisabledStringMap(ParseState state, Blackhole blackhole) {
    blackhole.consume(state.disabledJson.fromJson(LATIN1_JSON, STRING_INT_MAP));
  }

  @Benchmark
  public void parseLength17(ParseState state, Blackhole blackhole) {
    blackhole.consume(state.cachedJson.fromJson(LENGTH_17_JSON, JsonObject.class));
  }

  @Benchmark
  public void parseEscapedNames(ParseState state, Blackhole blackhole) {
    blackhole.consume(state.cachedJson.fromJson(ESCAPED_NAMES_JSON, JsonObject.class));
  }

  @Benchmark
  public void parseUnicodeNames(ParseState state, Blackhole blackhole) {
    blackhole.consume(state.cachedJson.fromJson(state.unicodeNamesUtf8, JsonObject.class));
  }

  @Benchmark
  public void parseLocalCollision(ParseState state, Blackhole blackhole) {
    blackhole.consume(state.localCollisionJson.fromJson(LOCAL_COLLISION_JSON, JsonObject.class));
  }

  @Benchmark
  public void parseSharedFull(ParseState state, Blackhole blackhole) {
    blackhole.consume(state.sharedFullJson.fromJson(SHARED_FULL_JSON, JsonObject.class));
  }

  @Benchmark
  public void parseSharedFullMixed(ParseState state, Blackhole blackhole) {
    blackhole.consume(state.sharedFullJson.fromJson(SHARED_FULL_MIXED_JSON, JsonObject.class));
  }

  @Benchmark
  public void parseTypedPojo(ParseState state, Blackhole blackhole) {
    blackhole.consume(state.cachedJson.fromJson(LATIN1_JSON, TypedPojo.class));
  }
}
