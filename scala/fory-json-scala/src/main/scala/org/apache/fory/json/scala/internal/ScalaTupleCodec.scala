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

package org.apache.fory.json.scala.internal

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.CompositeJsonCodec
import org.apache.fory.json.reader.{JsonReader, Latin1JsonReader, Utf16JsonReader, Utf8JsonReader}
import org.apache.fory.json.resolver.{JsonTypeInfo, JsonTypeResolver}
import org.apache.fory.json.writer.{StringJsonWriter, Utf8JsonWriter}
import org.apache.fory.reflect.TypeRef
import org.apache.fory.serializer.GraphMemoryEstimates

private[scala] final class ScalaTupleCodec(arity: Int, tupleType: Class[_], runtimeType: Boolean)
    extends CompositeJsonCodec[Product] {
  private var elements: Array[JsonTypeInfo] = _
  private val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(tupleType)

  override def resolveTypes(typeRef: TypeRef[_], resolver: JsonTypeResolver): Unit = {
    val arguments = ScalaTypeSupport.runtimeArguments(
      typeRef,
      arity,
      s"Tuple$arity",
      runtimeType
    )
    val infos = new Array[JsonTypeInfo](arity)
    var index = 0
    while (index < arity) {
      infos(index) =
        resolver.getTypeInfo(arguments(index), ScalaTypeSupport.rawType(arguments(index)))
      index += 1
    }
    elements = infos
  }

  override def writeString(writer: StringJsonWriter, value: Product): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    requireArity(value)
    writer.writeArrayStart()
    var index = 0
    while (index < arity) {
      writer.writeComma(index)
      elements(index).stringWriter().writeString(writer, value.productElement(index))
      index += 1
    }
    writer.writeArrayEnd()
  }

  override def writeUtf8(writer: Utf8JsonWriter, value: Product): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    requireArity(value)
    writer.writeArrayStart()
    var index = 0
    while (index < arity) {
      writer.writeComma(index)
      elements(index).utf8Writer().writeUtf8(writer, value.productElement(index))
      index += 1
    }
    writer.writeArrayEnd()
  }

  override def readLatin1(reader: Latin1JsonReader): Product = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('[')
    var v0: Object = null
    var v1: Object = null
    var v2: Object = null
    var v3: Object = null
    var v4: Object = null
    var v5: Object = null
    var v6: Object = null
    var v7: Object = null
    var v8: Object = null
    var v9: Object = null
    var v10: Object = null
    var v11: Object = null
    var v12: Object = null
    var v13: Object = null
    var v14: Object = null
    var v15: Object = null
    var v16: Object = null
    var v17: Object = null
    var v18: Object = null
    var v19: Object = null
    var v20: Object = null
    var v21: Object = null
    if (arity > 0) {
      if (reader.consumeNextToken(']')) throw invalidArity()
      v0 = elements(0).latin1Reader().readLatin1(reader)
    }
    if (arity > 1) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v1 = elements(1).latin1Reader().readLatin1(reader)
    }
    if (arity > 2) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v2 = elements(2).latin1Reader().readLatin1(reader)
    }
    if (arity > 3) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v3 = elements(3).latin1Reader().readLatin1(reader)
    }
    if (arity > 4) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v4 = elements(4).latin1Reader().readLatin1(reader)
    }
    if (arity > 5) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v5 = elements(5).latin1Reader().readLatin1(reader)
    }
    if (arity > 6) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v6 = elements(6).latin1Reader().readLatin1(reader)
    }
    if (arity > 7) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v7 = elements(7).latin1Reader().readLatin1(reader)
    }
    if (arity > 8) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v8 = elements(8).latin1Reader().readLatin1(reader)
    }
    if (arity > 9) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v9 = elements(9).latin1Reader().readLatin1(reader)
    }
    if (arity > 10) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v10 = elements(10).latin1Reader().readLatin1(reader)
    }
    if (arity > 11) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v11 = elements(11).latin1Reader().readLatin1(reader)
    }
    if (arity > 12) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v12 = elements(12).latin1Reader().readLatin1(reader)
    }
    if (arity > 13) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v13 = elements(13).latin1Reader().readLatin1(reader)
    }
    if (arity > 14) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v14 = elements(14).latin1Reader().readLatin1(reader)
    }
    if (arity > 15) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v15 = elements(15).latin1Reader().readLatin1(reader)
    }
    if (arity > 16) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v16 = elements(16).latin1Reader().readLatin1(reader)
    }
    if (arity > 17) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v17 = elements(17).latin1Reader().readLatin1(reader)
    }
    if (arity > 18) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v18 = elements(18).latin1Reader().readLatin1(reader)
    }
    if (arity > 19) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v19 = elements(19).latin1Reader().readLatin1(reader)
    }
    if (arity > 20) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v20 = elements(20).latin1Reader().readLatin1(reader)
    }
    if (arity > 21) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v21 = elements(21).latin1Reader().readLatin1(reader)
    }
    if (reader.consumeNextCommaOrEndArray()) throw invalidArity()
    reader.reserveGraphMemory(ownerBytes)
    val result = ScalaTupleCodec.create(arity, v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16, v17, v18, v19, v20, v21)
    reader.exitDepth()
    result
  }

  override def readUtf16(reader: Utf16JsonReader): Product = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('[')
    var v0: Object = null
    var v1: Object = null
    var v2: Object = null
    var v3: Object = null
    var v4: Object = null
    var v5: Object = null
    var v6: Object = null
    var v7: Object = null
    var v8: Object = null
    var v9: Object = null
    var v10: Object = null
    var v11: Object = null
    var v12: Object = null
    var v13: Object = null
    var v14: Object = null
    var v15: Object = null
    var v16: Object = null
    var v17: Object = null
    var v18: Object = null
    var v19: Object = null
    var v20: Object = null
    var v21: Object = null
    if (arity > 0) {
      if (reader.consumeNextToken(']')) throw invalidArity()
      v0 = elements(0).utf16Reader().readUtf16(reader)
    }
    if (arity > 1) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v1 = elements(1).utf16Reader().readUtf16(reader)
    }
    if (arity > 2) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v2 = elements(2).utf16Reader().readUtf16(reader)
    }
    if (arity > 3) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v3 = elements(3).utf16Reader().readUtf16(reader)
    }
    if (arity > 4) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v4 = elements(4).utf16Reader().readUtf16(reader)
    }
    if (arity > 5) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v5 = elements(5).utf16Reader().readUtf16(reader)
    }
    if (arity > 6) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v6 = elements(6).utf16Reader().readUtf16(reader)
    }
    if (arity > 7) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v7 = elements(7).utf16Reader().readUtf16(reader)
    }
    if (arity > 8) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v8 = elements(8).utf16Reader().readUtf16(reader)
    }
    if (arity > 9) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v9 = elements(9).utf16Reader().readUtf16(reader)
    }
    if (arity > 10) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v10 = elements(10).utf16Reader().readUtf16(reader)
    }
    if (arity > 11) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v11 = elements(11).utf16Reader().readUtf16(reader)
    }
    if (arity > 12) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v12 = elements(12).utf16Reader().readUtf16(reader)
    }
    if (arity > 13) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v13 = elements(13).utf16Reader().readUtf16(reader)
    }
    if (arity > 14) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v14 = elements(14).utf16Reader().readUtf16(reader)
    }
    if (arity > 15) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v15 = elements(15).utf16Reader().readUtf16(reader)
    }
    if (arity > 16) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v16 = elements(16).utf16Reader().readUtf16(reader)
    }
    if (arity > 17) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v17 = elements(17).utf16Reader().readUtf16(reader)
    }
    if (arity > 18) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v18 = elements(18).utf16Reader().readUtf16(reader)
    }
    if (arity > 19) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v19 = elements(19).utf16Reader().readUtf16(reader)
    }
    if (arity > 20) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v20 = elements(20).utf16Reader().readUtf16(reader)
    }
    if (arity > 21) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v21 = elements(21).utf16Reader().readUtf16(reader)
    }
    if (reader.consumeNextCommaOrEndArray()) throw invalidArity()
    reader.reserveGraphMemory(ownerBytes)
    val result = ScalaTupleCodec.create(arity, v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16, v17, v18, v19, v20, v21)
    reader.exitDepth()
    result
  }

  override def readUtf8(reader: Utf8JsonReader): Product = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('[')
    var v0: Object = null
    var v1: Object = null
    var v2: Object = null
    var v3: Object = null
    var v4: Object = null
    var v5: Object = null
    var v6: Object = null
    var v7: Object = null
    var v8: Object = null
    var v9: Object = null
    var v10: Object = null
    var v11: Object = null
    var v12: Object = null
    var v13: Object = null
    var v14: Object = null
    var v15: Object = null
    var v16: Object = null
    var v17: Object = null
    var v18: Object = null
    var v19: Object = null
    var v20: Object = null
    var v21: Object = null
    if (arity > 0) {
      if (reader.consumeNextToken(']')) throw invalidArity()
      v0 = elements(0).utf8Reader().readUtf8(reader)
    }
    if (arity > 1) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v1 = elements(1).utf8Reader().readUtf8(reader)
    }
    if (arity > 2) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v2 = elements(2).utf8Reader().readUtf8(reader)
    }
    if (arity > 3) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v3 = elements(3).utf8Reader().readUtf8(reader)
    }
    if (arity > 4) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v4 = elements(4).utf8Reader().readUtf8(reader)
    }
    if (arity > 5) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v5 = elements(5).utf8Reader().readUtf8(reader)
    }
    if (arity > 6) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v6 = elements(6).utf8Reader().readUtf8(reader)
    }
    if (arity > 7) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v7 = elements(7).utf8Reader().readUtf8(reader)
    }
    if (arity > 8) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v8 = elements(8).utf8Reader().readUtf8(reader)
    }
    if (arity > 9) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v9 = elements(9).utf8Reader().readUtf8(reader)
    }
    if (arity > 10) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v10 = elements(10).utf8Reader().readUtf8(reader)
    }
    if (arity > 11) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v11 = elements(11).utf8Reader().readUtf8(reader)
    }
    if (arity > 12) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v12 = elements(12).utf8Reader().readUtf8(reader)
    }
    if (arity > 13) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v13 = elements(13).utf8Reader().readUtf8(reader)
    }
    if (arity > 14) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v14 = elements(14).utf8Reader().readUtf8(reader)
    }
    if (arity > 15) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v15 = elements(15).utf8Reader().readUtf8(reader)
    }
    if (arity > 16) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v16 = elements(16).utf8Reader().readUtf8(reader)
    }
    if (arity > 17) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v17 = elements(17).utf8Reader().readUtf8(reader)
    }
    if (arity > 18) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v18 = elements(18).utf8Reader().readUtf8(reader)
    }
    if (arity > 19) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v19 = elements(19).utf8Reader().readUtf8(reader)
    }
    if (arity > 20) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v20 = elements(20).utf8Reader().readUtf8(reader)
    }
    if (arity > 21) {
      if (!reader.consumeNextCommaOrEndArray()) throw invalidArity()
      v21 = elements(21).utf8Reader().readUtf8(reader)
    }
    if (reader.consumeNextCommaOrEndArray()) throw invalidArity()
    reader.reserveGraphMemory(ownerBytes)
    val result = ScalaTupleCodec.create(arity, v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16, v17, v18, v19, v20, v21)
    reader.exitDepth()
    result
  }

  private def requireArity(value: Product): Unit = {
    if (value.getClass != tupleType || value.productArity != arity) throw invalidArity()
  }

  private def invalidArity(): ForyJsonException =
    new ForyJsonException(s"Scala Tuple$arity requires exactly $arity JSON elements")
}

private[scala] object ScalaTupleCodec {
  def arity(rawType: Class[_]): Int = {
    val name = rawType.getName
    if (!name.startsWith("scala.Tuple")) return -1
    val suffix = name.substring("scala.Tuple".length)
    if (suffix.isEmpty || !suffix.forall(_.isDigit)) return -1
    val value = suffix.toInt
    if (value >= 1 && value <= 22) value else -1
  }

  def create(
      arity: Int,
      v0: Object,
      v1: Object,
      v2: Object,
      v3: Object,
      v4: Object,
      v5: Object,
      v6: Object,
      v7: Object,
      v8: Object,
      v9: Object,
      v10: Object,
      v11: Object,
      v12: Object,
      v13: Object,
      v14: Object,
      v15: Object,
      v16: Object,
      v17: Object,
      v18: Object,
      v19: Object,
      v20: Object,
      v21: Object
  ): Product = arity match {
    case 1 => Tuple1(v0)
    case 2 => (v0, v1)
    case 3 => (v0, v1, v2)
    case 4 => (v0, v1, v2, v3)
    case 5 => (v0, v1, v2, v3, v4)
    case 6 => (v0, v1, v2, v3, v4, v5)
    case 7 => (v0, v1, v2, v3, v4, v5, v6)
    case 8 => (v0, v1, v2, v3, v4, v5, v6, v7)
    case 9 => (v0, v1, v2, v3, v4, v5, v6, v7, v8)
    case 10 => (v0, v1, v2, v3, v4, v5, v6, v7, v8, v9)
    case 11 => (v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10)
    case 12 => (v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11)
    case 13 => (v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12)
    case 14 => (v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13)
    case 15 => (v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14)
    case 16 => (v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15)
    case 17 => (v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16)
    case 18 => (v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16, v17)
    case 19 => (v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16, v17, v18)
    case 20 => (v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16, v17, v18, v19)
    case 21 => (v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16, v17, v18, v19, v20)
    case 22 => (v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16, v17, v18, v19, v20, v21)
    case _ => throw new AssertionError(arity)
  }
}
