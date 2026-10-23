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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.format.annotation.ForyRowVersion;
import org.apache.fory.format.encoder.ArrayEncoder;
import org.apache.fory.format.encoder.Encoders;
import org.apache.fory.format.encoder.MapEncoder;
import org.apache.fory.format.encoder.RowEncoder;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.reflect.TypeRef;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;

/**
 * Row-codec schema-evolution throughput and allocation. Pair with the JMH gc profiler ({@code -prof
 * gc}) to read {@code gc.alloc.rate.norm} (bytes per op). Two comparisons matter: {@code
 * currentDecode} vs {@code olderDecode} shows that decoding an older payload through a projection
 * codec allocates no more than decoding the current schema, because each projection holds its
 * historical schema's row layout (no per-decode rebuild); and the {@code *NoEvolution} benchmarks
 * vs their evolution-on counterparts show the steady-state cost of enabling {@code
 * withSchemaEvolution()} when reading and writing current-version data. The {@code array*} and
 * {@code map*} benchmarks repeat both comparisons for a top-level {@code List<PersonV2>} and {@code
 * Map<String, PersonV2>}, whose evolution-on payloads carry an 8-byte hash prefix.
 */
public class SchemaEvolutionSuite {
  private static final Logger LOG = LoggerFactory.getLogger(SchemaEvolutionSuite.class);

  public static class PersonV1 {
    String name;
    int age;
  }

  public static class PersonV2 {
    String name;
    int age;

    @ForyRowVersion(since = 2)
    String email;
  }

  // Evolution-enabled codecs for the current (V2) schema; the V1 codec only produces a payload
  // whose hash routes the V2 reader onto its projection path. Both standard and compact formats
  // are measured: compact is where a per-projection cached row layout matters, so olderDecode vs
  // currentDecode there is the parity check.
  private static final RowEncoder<PersonV1> v1Codec =
      Encoders.buildBeanCodec(PersonV1.class).withSchemaEvolution().build().get();
  private static final RowEncoder<PersonV2> v2Codec =
      Encoders.buildBeanCodec(PersonV2.class).withSchemaEvolution().build().get();
  private static final RowEncoder<PersonV1> v1CompactCodec =
      Encoders.buildBeanCodec(PersonV1.class).compactEncoding().withSchemaEvolution().build().get();
  private static final RowEncoder<PersonV2> v2CompactCodec =
      Encoders.buildBeanCodec(PersonV2.class).compactEncoding().withSchemaEvolution().build().get();

  // Evolution-disabled codecs for the same current (V2) schema. Comparing the *NoEvolution
  // benchmarks against their evolution-on counterparts isolates the steady-state cost of the
  // withSchemaEvolution() flag on the common path (reading and writing current-version data).
  // Rows carry the 8-byte hash slot and decode compares it in both modes, so on current-version
  // data the flag adds no work; only a hash mismatch takes the projection lookup.
  private static final RowEncoder<PersonV2> v2PlainCodec =
      Encoders.buildBeanCodec(PersonV2.class).build().get();
  private static final RowEncoder<PersonV2> v2PlainCompactCodec =
      Encoders.buildBeanCodec(PersonV2.class).compactEncoding().build().get();

  private static final PersonV2 person = newPerson();
  private static final byte[] currentBytes = v2Codec.encode(person);
  private static final byte[] olderBytes = v1Codec.encode(newPersonV1());
  private static final byte[] currentCompactBytes = v2CompactCodec.encode(person);
  private static final byte[] olderCompactBytes = v1CompactCodec.encode(newPersonV1());
  private static final byte[] plainBytes = v2PlainCodec.encode(person);
  private static final byte[] plainCompactBytes = v2PlainCompactCodec.encode(person);

  private static final TypeRef<List<PersonV1>> V1_LIST = new TypeRef<List<PersonV1>>() {};
  private static final TypeRef<List<PersonV2>> V2_LIST = new TypeRef<List<PersonV2>>() {};
  private static final TypeRef<Map<String, PersonV1>> V1_MAP =
      new TypeRef<Map<String, PersonV1>>() {};
  private static final TypeRef<Map<String, PersonV2>> V2_MAP =
      new TypeRef<Map<String, PersonV2>>() {};

  private static final ArrayEncoder<List<PersonV2>> v2ArrayCodec =
      Encoders.buildArrayCodec(V2_LIST).withSchemaEvolution().build().get();
  private static final ArrayEncoder<List<PersonV2>> v2PlainArrayCodec =
      Encoders.buildArrayCodec(V2_LIST).build().get();
  private static final MapEncoder<Map<String, PersonV2>> v2MapCodec =
      Encoders.buildMapCodec(V2_MAP).withSchemaEvolution().build().get();
  private static final MapEncoder<Map<String, PersonV2>> v2PlainMapCodec =
      Encoders.buildMapCodec(V2_MAP).build().get();

  private static final List<PersonV2> people = Arrays.asList(person, person, person, person);
  private static final Map<String, PersonV2> personMap = newPersonMap(person);
  private static final byte[] currentArrayBytes = v2ArrayCodec.encode(people);
  private static final byte[] olderArrayBytes =
      Encoders.buildArrayCodec(V1_LIST)
          .withSchemaEvolution()
          .build()
          .get()
          .encode(Arrays.asList(newPersonV1(), newPersonV1(), newPersonV1(), newPersonV1()));
  private static final byte[] plainArrayBytes = v2PlainArrayCodec.encode(people);
  private static final byte[] currentMapBytes = v2MapCodec.encode(personMap);
  private static final byte[] olderMapBytes =
      Encoders.buildMapCodec(V1_MAP)
          .withSchemaEvolution()
          .build()
          .get()
          .encode(newPersonMap(newPersonV1()));
  private static final byte[] plainMapBytes = v2PlainMapCodec.encode(personMap);

  private static <V> Map<String, V> newPersonMap(V value) {
    Map<String, V> map = new LinkedHashMap<>();
    for (int i = 0; i < 4; i++) {
      map.put("person" + i, value);
    }
    return map;
  }

  private static PersonV2 newPerson() {
    PersonV2 p = new PersonV2();
    p.name = "Ada Lovelace";
    p.age = 36;
    p.email = "ada@example.com";
    return p;
  }

  private static PersonV1 newPersonV1() {
    PersonV1 p = new PersonV1();
    p.name = "Ada Lovelace";
    p.age = 36;
    return p;
  }

  @Benchmark
  public Object encode() {
    return v2Codec.encode(person);
  }

  @Benchmark
  public Object currentDecode() {
    return v2Codec.decode(currentBytes);
  }

  @Benchmark
  public Object olderDecode() {
    return v2Codec.decode(olderBytes);
  }

  @Benchmark
  public Object compactEncode() {
    return v2CompactCodec.encode(person);
  }

  @Benchmark
  public Object compactCurrentDecode() {
    return v2CompactCodec.decode(currentCompactBytes);
  }

  @Benchmark
  public Object compactOlderDecode() {
    return v2CompactCodec.decode(olderCompactBytes);
  }

  // Evolution-off baselines for the current path. Pair each with its evolution-on counterpart
  // (encode/currentDecode and the compact variants) to read the flag's overhead.
  @Benchmark
  public Object encodeNoEvolution() {
    return v2PlainCodec.encode(person);
  }

  @Benchmark
  public Object currentDecodeNoEvolution() {
    return v2PlainCodec.decode(plainBytes);
  }

  @Benchmark
  public Object compactEncodeNoEvolution() {
    return v2PlainCompactCodec.encode(person);
  }

  @Benchmark
  public Object compactCurrentDecodeNoEvolution() {
    return v2PlainCompactCodec.decode(plainCompactBytes);
  }

  @Benchmark
  public Object arrayEncode() {
    return v2ArrayCodec.encode(people);
  }

  @Benchmark
  public Object arrayCurrentDecode() {
    return v2ArrayCodec.decode(currentArrayBytes);
  }

  @Benchmark
  public Object arrayOlderDecode() {
    return v2ArrayCodec.decode(olderArrayBytes);
  }

  @Benchmark
  public Object arrayEncodeNoEvolution() {
    return v2PlainArrayCodec.encode(people);
  }

  @Benchmark
  public Object arrayCurrentDecodeNoEvolution() {
    return v2PlainArrayCodec.decode(plainArrayBytes);
  }

  @Benchmark
  public Object mapEncode() {
    return v2MapCodec.encode(personMap);
  }

  @Benchmark
  public Object mapCurrentDecode() {
    return v2MapCodec.decode(currentMapBytes);
  }

  @Benchmark
  public Object mapOlderDecode() {
    return v2MapCodec.decode(olderMapBytes);
  }

  @Benchmark
  public Object mapEncodeNoEvolution() {
    return v2PlainMapCodec.encode(personMap);
  }

  @Benchmark
  public Object mapCurrentDecodeNoEvolution() {
    return v2PlainMapCodec.decode(plainMapBytes);
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      String commandLine =
          "org.apache.fory.*SchemaEvolutionSuite.* -f 3 -wi 3 -i 3 -t 1 -w 2s -r 2s -prof gc -rf csv";
      args = commandLine.split(" ");
    }
    LOG.info("command line: {}", Arrays.toString(args));
    Main.main(args);
  }
}
