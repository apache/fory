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

package org.apache.fory.benchmark.json

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okio.Buffer
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup

@State(Scope.Thread)
open class BenchmarkState {
  lateinit var codecs: BenchmarkCodecs
  lateinit var expected: MediaContent
  lateinit var fixtureString: String
  lateinit var fixtureBytes: ByteArray

  @Setup
  fun setup() {
    codecs = BenchmarkCodecs()
    expected = MediaContentFixture.expected()
    fixtureBytes = MediaContentFixture.bytes()
    fixtureString = MediaContentFixture.text(fixtureBytes)
    verifyFixtureReads()
    verifyEncodedTreesAndRoundTrips()
    warmForyPaths()
  }

  private fun verifyFixtureReads() {
    check(codecs.foryFromString(fixtureString) == expected)
    check(codecs.foryFromBytes(fixtureBytes) == expected)
    check(codecs.kotlinxFromString(fixtureString) == expected)
    check(codecs.kotlinxFromBytes(fixtureBytes) == expected)
    check(codecs.moshiFromString(fixtureString) == expected)
    check(codecs.moshiFromBytes(fixtureBytes) == expected)
    check(codecs.jacksonFromString(fixtureString) == expected)
    check(codecs.jacksonFromBytes(fixtureBytes) == expected)
  }

  private fun verifyEncodedTreesAndRoundTrips() {
    val foryString = codecs.foryToString(expected)
    val foryBytes = codecs.foryToBytes(expected)
    val kotlinxString = codecs.kotlinxToString(expected)
    val kotlinxBytes = codecs.kotlinxToBytes(expected)
    val moshiString = codecs.moshiToString(expected)
    val moshiBytes = codecs.moshiToBytes(expected)
    val jacksonString = codecs.jacksonToString(expected)
    val jacksonBytes = codecs.jacksonToBytes(expected)

    val expectedTree = codecs.tree(foryString)
    for (tree in
      listOf(
        codecs.tree(foryBytes),
        codecs.tree(kotlinxString),
        codecs.tree(kotlinxBytes),
        codecs.tree(moshiString),
        codecs.tree(moshiBytes),
        codecs.tree(jacksonString),
        codecs.tree(jacksonBytes)
      )) {
      check(tree == expectedTree) { "JSON libraries emitted structurally different output" }
    }

    check(codecs.foryFromString(foryString) == expected)
    check(codecs.foryFromBytes(foryBytes) == expected)
    check(codecs.kotlinxFromString(kotlinxString) == expected)
    check(codecs.kotlinxFromBytes(kotlinxBytes) == expected)
    check(codecs.moshiFromString(moshiString) == expected)
    check(codecs.moshiFromBytes(moshiBytes) == expected)
    check(codecs.jacksonFromString(jacksonString) == expected)
    check(codecs.jacksonFromBytes(jacksonBytes) == expected)
  }

  private fun warmForyPaths() {
    repeat(32) {
      codecs.foryToString(expected)
      codecs.foryToBytes(expected)
      codecs.foryFromString(fixtureString)
      codecs.foryFromBytes(fixtureBytes)
    }
  }
}

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@Threads(1)
@OptIn(ExperimentalSerializationApi::class)
open class MediaContentBenchmark {
  @Benchmark
  fun foryStringSerialization(state: BenchmarkState): String =
    state.codecs.fory.toJson(state.expected, state.codecs.foryType)

  @Benchmark
  fun foryUtf8BytesSerialization(state: BenchmarkState): ByteArray =
    state.codecs.fory.toJsonBytes(state.expected, state.codecs.foryType)

  @Benchmark
  fun foryStringDeserialization(state: BenchmarkState): MediaContent =
    state.codecs.fory.fromJson(state.fixtureString, state.codecs.foryType)

  @Benchmark
  fun foryUtf8BytesDeserialization(state: BenchmarkState): MediaContent =
    state.codecs.fory.fromJson(state.fixtureBytes, state.codecs.foryType)

  @Benchmark
  fun kotlinxStringSerialization(state: BenchmarkState): String =
    state.codecs.kotlinx.encodeToString(state.codecs.kotlinxSerializer, state.expected)

  @Benchmark
  fun kotlinxUtf8BytesSerialization(state: BenchmarkState): ByteArray {
    val output = ByteArrayOutputStream()
    state.codecs.kotlinx.encodeToStream(state.codecs.kotlinxSerializer, state.expected, output)
    return output.toByteArray()
  }

  @Benchmark
  fun kotlinxStringDeserialization(state: BenchmarkState): MediaContent =
    state.codecs.kotlinx.decodeFromString(state.codecs.kotlinxSerializer, state.fixtureString)

  @Benchmark
  fun kotlinxUtf8BytesDeserialization(state: BenchmarkState): MediaContent =
    state.codecs.kotlinx.decodeFromStream(
      state.codecs.kotlinxSerializer,
      ByteArrayInputStream(state.fixtureBytes),
    )

  @Benchmark
  fun moshiStringSerialization(state: BenchmarkState): String =
    state.codecs.moshiAdapter.toJson(state.expected)

  @Benchmark
  fun moshiUtf8BytesSerialization(state: BenchmarkState): ByteArray {
    val buffer = Buffer()
    state.codecs.moshiAdapter.toJson(buffer, state.expected)
    return buffer.readByteArray()
  }

  @Benchmark
  fun moshiStringDeserialization(state: BenchmarkState): MediaContent? =
    state.codecs.moshiAdapter.fromJson(state.fixtureString)

  @Benchmark
  fun moshiUtf8BytesDeserialization(state: BenchmarkState): MediaContent? =
    state.codecs.moshiAdapter.fromJson(Buffer().write(state.fixtureBytes))

  @Benchmark
  fun jacksonStringSerialization(state: BenchmarkState): String =
    state.codecs.jacksonWriter.writeValueAsString(state.expected)

  @Benchmark
  fun jacksonUtf8BytesSerialization(state: BenchmarkState): ByteArray =
    state.codecs.jacksonWriter.writeValueAsBytes(state.expected)

  @Benchmark
  fun jacksonStringDeserialization(state: BenchmarkState): MediaContent =
    state.codecs.jacksonReader.readValue(state.fixtureString)

  @Benchmark
  fun jacksonUtf8BytesDeserialization(state: BenchmarkState): MediaContent =
    state.codecs.jacksonReader.readValue(state.fixtureBytes)
}
