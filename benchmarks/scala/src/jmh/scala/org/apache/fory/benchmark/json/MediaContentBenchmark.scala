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

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, readFromString, writeToArray, writeToString}
import org.openjdk.jmh.annotations.*

@State(Scope.Thread)
class BenchmarkState:
  import JsonCodecs.given

  var mediaContent: MediaContent = null
  var jsonString: String = null
  var jsonBytes: Array[Byte] = null

  @Setup
  def setup(): Unit =
    jsonString = readResource()
    jsonBytes = jsonString.getBytes(StandardCharsets.UTF_8)
    mediaContent = readFromString[MediaContent](jsonString)

    verify(JsonCodecs.foryJson.fromJson(jsonString, classOf[MediaContent]))
    verify(JsonCodecs.foryJson.fromJson(jsonBytes, classOf[MediaContent]))
    verify(readFromString[MediaContent](jsonString))
    verify(readFromArray[MediaContent](jsonBytes))
    verify(JsonCodecs.jacksonMapper.readValue(jsonString, classOf[MediaContent]))
    verify(JsonCodecs.jacksonMapper.readValue(jsonBytes, classOf[MediaContent]))

    verify(JsonCodecs.foryJson.fromJson(JsonCodecs.foryJson.toJson(mediaContent), classOf[MediaContent]))
    verify(
      JsonCodecs.foryJson.fromJson(
        JsonCodecs.foryJson.toJsonBytes(mediaContent),
        classOf[MediaContent],
      )
    )
    verify(readFromArray[MediaContent](writeToArray(mediaContent)))
    verify(
      JsonCodecs.jacksonMapper.readValue(
        JsonCodecs.jacksonMapper.writeValueAsBytes(mediaContent),
        classOf[MediaContent],
      )
    )

  private def verify(decoded: MediaContent): Unit =
    require(JsonCodecs.mediaContentEquals(decoded, mediaContent), "codec produced different MediaContent")

  private def readResource(): String =
    val stream = Option(getClass.getClassLoader.getResourceAsStream("data/eishay.json"))
      .getOrElse(throw IllegalStateException("Missing data/eishay.json"))
    try String(stream.readAllBytes(), StandardCharsets.UTF_8)
    finally stream.close()

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@Threads(1)
class MediaContentBenchmark:
  import JsonCodecs.given

  @Benchmark
  def foryToJsonBytes(state: BenchmarkState): Array[Byte] =
    JsonCodecs.foryJson.toJsonBytes(state.mediaContent)

  @Benchmark
  def jsoniterToJsonBytes(state: BenchmarkState): Array[Byte] = writeToArray(state.mediaContent)

  @Benchmark
  def jacksonToJsonBytes(state: BenchmarkState): Array[Byte] =
    JsonCodecs.jacksonMapper.writeValueAsBytes(state.mediaContent)

  @Benchmark
  def foryToJsonString(state: BenchmarkState): String = JsonCodecs.foryJson.toJson(state.mediaContent)

  @Benchmark
  def jsoniterToJsonString(state: BenchmarkState): String = writeToString(state.mediaContent)

  @Benchmark
  def jacksonToJsonString(state: BenchmarkState): String =
    JsonCodecs.jacksonMapper.writeValueAsString(state.mediaContent)

  @Benchmark
  def foryFromJsonBytes(state: BenchmarkState): MediaContent =
    JsonCodecs.foryJson.fromJson(state.jsonBytes, classOf[MediaContent])

  @Benchmark
  def jsoniterFromJsonBytes(state: BenchmarkState): MediaContent =
    readFromArray[MediaContent](state.jsonBytes)

  @Benchmark
  def jacksonFromJsonBytes(state: BenchmarkState): MediaContent =
    JsonCodecs.jacksonMapper.readValue(state.jsonBytes, classOf[MediaContent])

  @Benchmark
  def foryFromJsonString(state: BenchmarkState): MediaContent =
    JsonCodecs.foryJson.fromJson(state.jsonString, classOf[MediaContent])

  @Benchmark
  def jsoniterFromJsonString(state: BenchmarkState): MediaContent =
    readFromString[MediaContent](state.jsonString)

  @Benchmark
  def jacksonFromJsonString(state: BenchmarkState): MediaContent =
    JsonCodecs.jacksonMapper.readValue(state.jsonString, classOf[MediaContent])
