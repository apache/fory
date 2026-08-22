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
import org.apache.fory.json.codec.AbstractJsonValueCodec
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.writer.JsonWriter
import org.apache.fory.serializer.GraphMemoryEstimates

private[scala] final class ScalaBitSetCodec(mutableResult: Boolean)
    extends AbstractJsonValueCodec[scala.collection.BitSet] {
  private val OwnerBytes = GraphMemoryEstimates.shallowObjectBytes(
    if (mutableResult) classOf[scala.collection.mutable.BitSet]
    else classOf[scala.collection.immutable.BitSet]
  )
  private val ArrayHeaderBytes = GraphMemoryEstimates.objectArrayBytes()

  override def write(writer: JsonWriter, value: scala.collection.BitSet): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    writer.writeArrayStart()
    val iterator = value.iterator
    var index = 0
    while (iterator.hasNext) {
      writer.writeComma(index)
      writer.writeInt(iterator.next())
      index += 1
    }
    writer.writeArrayEnd()
  }

  override def read(reader: JsonReader): scala.collection.BitSet = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    val inputStart = reader.position()
    reader.expectNextToken('[')
    var words = Array.emptyLongArray
    if (!reader.consumeNextToken(']')) {
      var more = true
      while (more) {
        val bit = reader.readInt()
        if (bit < 0) throw invalidBit(bit)
        val requiredWords = (bit >>> 6) + 1
        if (requiredWords > words.length) {
          words = grow(reader, words, requiredWords, inputStart)
        }
        words(bit >>> 6) |= 1L << bit
        more = reader.consumeNextCommaOrEndArray()
      }
    }
    reader.reserveGraphMemory(OwnerBytes)
    val result: scala.collection.BitSet =
      if (mutableResult)
        scala.collection.mutable.BitSet.fromBitMaskNoCopy(words): scala.collection.BitSet
      else scala.collection.immutable.BitSet.fromBitMaskNoCopy(words): scala.collection.BitSet
    reader.exitDepth()
    result
  }

  private def grow(
      reader: JsonReader,
      source: Array[Long],
      required: Int,
      inputStart: Int
  ): Array[Long] = {
    var capacity = if (source.length == 0) 1 else source.length
    while (capacity < required) {
      val doubled = capacity.toLong << 1
      capacity = if (doubled >= required && doubled <= Int.MaxValue) doubled.toInt else required
    }
    // One readable input byte per retained word is the same proportional-input lower bound used
    // by count-driven containers. A compact high bit index must not allocate a dense backing array.
    reader.checkReadableBytesFrom(inputStart, capacity)
    val addedWords = capacity - source.length
    val bytes = Math.multiplyExact(addedWords, java.lang.Long.BYTES)
    reader.reserveGraphMemory(if (source.length == 0) Math.addExact(ArrayHeaderBytes, bytes) else bytes)
    java.util.Arrays.copyOf(source, capacity)
  }

  private def invalidBit(value: Int): ForyJsonException =
    new ForyJsonException(s"Scala BitSet index must be non-negative: $value")
}
