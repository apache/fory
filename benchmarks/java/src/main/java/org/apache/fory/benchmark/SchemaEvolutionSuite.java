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
import org.apache.fory.format.annotation.ForyVersion;
import org.apache.fory.format.encoder.Encoders;
import org.apache.fory.format.encoder.RowEncoder;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;

/**
 * Row-codec schema-evolution throughput and allocation. Pair with the JMH gc profiler ({@code -prof
 * gc}) to read {@code gc.alloc.rate.norm} (bytes per op). Two comparisons matter: {@code
 * currentDecode} vs {@code olderDecode} shows that decoding an older payload through a projection
 * codec allocates no more than decoding the current schema, because each projection holds its
 * historical schema's row layout (no per-decode rebuild); and the {@code *NoEvolution} benchmarks
 * vs their evolution-on counterparts show the steady-state cost of enabling {@code
 * withSchemaEvolution()} when reading and writing current-version data.
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

    @ForyVersion(since = 2)
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
  // withSchemaEvolution() flag on the common path (reading and writing current-version data): the
  // 8-byte hash slot the evolution wire format adds, plus the hash compare on decode.
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
