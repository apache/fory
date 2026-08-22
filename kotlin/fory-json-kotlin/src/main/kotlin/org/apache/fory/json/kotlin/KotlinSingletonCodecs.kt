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

package org.apache.fory.json.kotlin

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.JsonValueCodec
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.reader.Latin1JsonReader
import org.apache.fory.json.reader.Utf16JsonReader
import org.apache.fory.json.reader.Utf8JsonReader
import org.apache.fory.json.writer.StringJsonWriter
import org.apache.fory.json.writer.Utf8JsonWriter

internal object KotlinSingletonCodecs {
  val UNIT: JsonValueCodec<Unit> = UnitCodec
  val NULLABLE_UNIT: JsonValueCodec<Unit> = NullableUnitCodec

  private object UnitCodec : JsonValueCodec<Unit> {
    override fun writeString(writer: StringJsonWriter, value: Unit?) {
      requireUnit(value)
      writer.writeObjectStart()
      writer.writeObjectEnd()
    }

    override fun writeUtf8(writer: Utf8JsonWriter, value: Unit?) {
      requireUnit(value)
      writer.writeObjectStart()
      writer.writeObjectEnd()
    }

    override fun readLatin1(reader: Latin1JsonReader): Unit {
      readEmptyObject(reader)
      return Unit
    }

    override fun readUtf16(reader: Utf16JsonReader): Unit {
      readEmptyObject(reader)
      return Unit
    }

    override fun readUtf8(reader: Utf8JsonReader): Unit {
      readEmptyObject(reader)
      return Unit
    }

    private fun requireUnit(value: Unit?) {
      if (value == null) throw ForyJsonException("Kotlin Unit is not nullable")
    }
  }

  private object NullableUnitCodec : JsonValueCodec<Unit> {
    override fun writeString(writer: StringJsonWriter, value: Unit?) {
      if (value == null) writer.writeNull() else writeUnit(writer)
    }

    override fun writeUtf8(writer: Utf8JsonWriter, value: Unit?) {
      if (value == null) writer.writeNull() else writeUnit(writer)
    }

    override fun readLatin1(reader: Latin1JsonReader): Unit? = read(reader)

    override fun readUtf16(reader: Utf16JsonReader): Unit? = read(reader)

    override fun readUtf8(reader: Utf8JsonReader): Unit? = read(reader)

    private fun read(reader: JsonReader): Unit? {
      if (reader.tryReadNullToken()) return null
      readEmptyObject(reader)
      return Unit
    }
  }

  private fun writeUnit(writer: org.apache.fory.json.writer.JsonWriter) {
    writer.writeObjectStart()
    writer.writeObjectEnd()
  }

  private fun readEmptyObject(reader: JsonReader) {
    reader.enterDepth()
    reader.expectNextToken('{')
    if (!reader.consumeNextToken('}')) {
      throw ForyJsonException("Kotlin Unit must be represented by an empty JSON object")
    }
    reader.exitDepth()
  }
}
