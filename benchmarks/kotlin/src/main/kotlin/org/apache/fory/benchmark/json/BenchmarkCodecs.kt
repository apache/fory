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

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.databind.ObjectWriter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okio.Buffer
import org.apache.fory.json.ForyJson
import org.apache.fory.json.kotlin.ForyJsonKotlin
import org.apache.fory.json.kotlin.jsonTypeRef
import org.apache.fory.reflect.TypeRef

@OptIn(ExperimentalSerializationApi::class)
class BenchmarkCodecs {
  val foryType: TypeRef<MediaContent> = jsonTypeRef()
  val fory: ForyJson =
    ForyJsonKotlin.builder().withAsyncCompilation(false).writeNullFields(true).build()

  val kotlinx: Json = Json {
    encodeDefaults = true
    explicitNulls = true
  }
  val kotlinxSerializer: KSerializer<MediaContent> = MediaContent.serializer()

  val moshiAdapter: JsonAdapter<MediaContent> =
    Moshi.Builder().build().adapter(MediaContent::class.java).serializeNulls()

  val jackson: ObjectMapper =
    jacksonObjectMapper().setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS)
  val jacksonReader: ObjectReader = jackson.readerFor(MediaContent::class.java)
  val jacksonWriter: ObjectWriter = jackson.writerFor(MediaContent::class.java)

  fun foryToString(value: MediaContent): String = fory.toJson(value, foryType)

  fun foryToBytes(value: MediaContent): ByteArray = fory.toJsonBytes(value, foryType)

  fun foryFromString(value: String): MediaContent = fory.fromJson(value, foryType)

  fun foryFromBytes(value: ByteArray): MediaContent = fory.fromJson(value, foryType)

  fun kotlinxToString(value: MediaContent): String =
    kotlinx.encodeToString(kotlinxSerializer, value)

  fun kotlinxToBytes(value: MediaContent): ByteArray {
    val output = ByteArrayOutputStream()
    kotlinx.encodeToStream(kotlinxSerializer, value, output)
    return output.toByteArray()
  }

  fun kotlinxFromString(value: String): MediaContent =
    kotlinx.decodeFromString(kotlinxSerializer, value)

  fun kotlinxFromBytes(value: ByteArray): MediaContent =
    kotlinx.decodeFromStream(kotlinxSerializer, ByteArrayInputStream(value))

  fun moshiToString(value: MediaContent): String = moshiAdapter.toJson(value)

  fun moshiToBytes(value: MediaContent): ByteArray {
    val buffer = Buffer()
    moshiAdapter.toJson(buffer, value)
    return buffer.readByteArray()
  }

  fun moshiFromString(value: String): MediaContent = checkNotNull(moshiAdapter.fromJson(value))

  fun moshiFromBytes(value: ByteArray): MediaContent =
    checkNotNull(moshiAdapter.fromJson(Buffer().write(value)))

  fun jacksonToString(value: MediaContent): String = jacksonWriter.writeValueAsString(value)

  fun jacksonToBytes(value: MediaContent): ByteArray = jacksonWriter.writeValueAsBytes(value)

  fun jacksonFromString(value: String): MediaContent = jacksonReader.readValue(value)

  fun jacksonFromBytes(value: ByteArray): MediaContent = jacksonReader.readValue(value)

  fun tree(value: String): JsonNode = jackson.readTree(value)

  fun tree(value: ByteArray): JsonNode = jackson.readTree(value)
}
