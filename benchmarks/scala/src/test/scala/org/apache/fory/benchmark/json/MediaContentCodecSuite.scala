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

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, readFromString, writeToArray, writeToString}

class MediaContentCodecSuite extends munit.FunSuite:
  import JsonCodecs.given

  private val jsonString =
    val stream = getClass.getClassLoader.getResourceAsStream("data/eishay.json")
    try String(stream.readAllBytes(), StandardCharsets.UTF_8)
    finally stream.close()
  private val expected = readFromString[MediaContent](jsonString)

  private def assertMediaContentEquals(actual: MediaContent): Unit =
    assert(JsonCodecs.mediaContentEquals(actual, expected))

  test("all codecs round trip MediaContent"):
    val foryString = JsonCodecs.foryJson.toJson(expected)
    val foryBytes = JsonCodecs.foryJson.toJsonBytes(expected)
    assertMediaContentEquals(JsonCodecs.foryJson.fromJson(foryString, classOf[MediaContent]))
    assertMediaContentEquals(JsonCodecs.foryJson.fromJson(foryBytes, classOf[MediaContent]))

    assertMediaContentEquals(readFromString[MediaContent](writeToString(expected)))
    assertMediaContentEquals(readFromArray[MediaContent](writeToArray(expected)))

    val jacksonString = JsonCodecs.jacksonMapper.writeValueAsString(expected)
    val jacksonBytes = JsonCodecs.jacksonMapper.writeValueAsBytes(expected)
    assertMediaContentEquals(JsonCodecs.jacksonMapper.readValue(jacksonString, classOf[MediaContent]))
    assertMediaContentEquals(JsonCodecs.jacksonMapper.readValue(jacksonBytes, classOf[MediaContent]))
