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
import org.apache.fory.json.annotation.JsonCodec
import org.apache.fory.json.codec.CompositeJsonCodec
import org.apache.fory.json.meta.JsonFieldNameHash
import org.apache.fory.json.reader.{Latin1JsonReader, Utf16JsonReader, Utf8JsonReader}
import org.apache.fory.json.resolver.{JsonTypeInfo, JsonTypeResolver}
import org.apache.fory.json.writer.{StringJsonWriter, Utf8JsonWriter}
import org.apache.fory.reflect.TypeRef
import org.apache.fory.serializer.GraphMemoryEstimates

private[scala] final class ScalaOptionCodec(someOnly: Boolean, runtimeType: Boolean)
    extends CompositeJsonCodec[Option[Any]] {
  private var elementInfo: JsonTypeInfo = _

  override def resolveTypes(typeRef: TypeRef[_], resolver: JsonTypeResolver): Unit = {
    val arguments = ScalaTypeSupport.runtimeArguments(
      typeRef,
      1,
      if (someOnly) "Some" else "Option",
      runtimeType
    )
    val elementType = arguments(0)
    elementInfo = resolver.getTypeInfo(elementType, ScalaTypeSupport.rawType(elementType))
  }

  override def resolveTypes(
      typeRef: TypeRef[_],
      resolver: JsonTypeResolver,
      childCodecs: JsonCodec
  ): Unit = {
    ScalaTypeSupport.requireContentCodec(childCodecs, if (someOnly) "Some" else "Option")
    val arguments = ScalaTypeSupport.arguments(typeRef, 1, if (someOnly) "Some" else "Option")
    val elementType = arguments(0)
    elementInfo = resolver.getTypeInfo(
      elementType,
      ScalaTypeSupport.rawType(elementType),
      childCodecs.contentCodec()
    )
  }

  override def writeString(writer: StringJsonWriter, value: Option[Any]): Unit = {
    if (value == null || value.isEmpty) writer.writeNull()
    else elementInfo.stringWriter().writeString(writer, value.get)
  }

  override def writeUtf8(writer: Utf8JsonWriter, value: Option[Any]): Unit = {
    if (value == null || value.isEmpty) writer.writeNull()
    else elementInfo.utf8Writer().writeUtf8(writer, value.get)
  }

  override def readLatin1(reader: Latin1JsonReader): Option[Any] = {
    if (reader.tryReadNullToken()) readNull()
    else newSome(reader, elementInfo.latin1Reader().readLatin1(reader))
  }

  override def readUtf16(reader: Utf16JsonReader): Option[Any] = {
    if (reader.tryReadNullToken()) readNull()
    else newSome(reader, elementInfo.utf16Reader().readUtf16(reader))
  }

  override def readUtf8(reader: Utf8JsonReader): Option[Any] = {
    if (reader.tryReadNullToken()) readNull()
    else newSome(reader, elementInfo.utf8Reader().readUtf8(reader))
  }

  private def readNull(): Option[Any] = {
    if (someOnly) throw new ForyJsonException("JSON null cannot be decoded as scala.Some")
    None
  }

  private def newSome(reader: org.apache.fory.json.reader.JsonReader, value: Any): Option[Any] = {
    reader.reserveGraphMemory(ScalaOptionCodec.SomeOwnerBytes)
    Some(value)
  }
}

private[scala] object ScalaOptionCodec {
  private val SomeOwnerBytes = GraphMemoryEstimates.shallowObjectBytes(classOf[Some[_]])
}

private[scala] object ScalaNoneCodec extends org.apache.fory.json.codec.AbstractJsonValueCodec[None.type] {
  override def write(writer: org.apache.fory.json.writer.JsonWriter, value: None.type): Unit = {
    if (value != null && (value ne None))
      throw new ForyJsonException("Expected scala.None")
    writer.writeNull()
  }

  override def read(reader: org.apache.fory.json.reader.JsonReader): None.type = {
    if (!reader.tryReadNullToken())
      throw new ForyJsonException("scala.None requires JSON null")
    None
  }
}

private[scala] final class ScalaEitherCodec(branch: Int, runtimeType: Boolean)
    extends CompositeJsonCodec[Either[Any, Any]] {
  private var leftInfo: JsonTypeInfo = _
  private var rightInfo: JsonTypeInfo = _

  override def resolveTypes(typeRef: TypeRef[_], resolver: JsonTypeResolver): Unit = {
    val arguments = ScalaTypeSupport.runtimeArguments(
      typeRef,
      2,
      branch match {
        case 1 => "Left"
        case 2 => "Right"
        case _ => "Either"
      },
      runtimeType
    )
    leftInfo = resolver.getTypeInfo(arguments(0), ScalaTypeSupport.rawType(arguments(0)))
    rightInfo = resolver.getTypeInfo(arguments(1), ScalaTypeSupport.rawType(arguments(1)))
  }

  override def writeString(writer: StringJsonWriter, value: Either[Any, Any]): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    writer.writeObjectStart()
    value match {
      case Left(left) =>
        writer.writeFieldName("l")
        leftInfo.stringWriter().writeString(writer, left)
      case Right(right) =>
        writer.writeFieldName("r")
        rightInfo.stringWriter().writeString(writer, right)
    }
    writer.writeObjectEnd()
  }

  override def writeUtf8(writer: Utf8JsonWriter, value: Either[Any, Any]): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    writer.writeObjectStart()
    value match {
      case Left(left) =>
        writer.writeFieldName("l")
        leftInfo.utf8Writer().writeUtf8(writer, left)
      case Right(right) =>
        writer.writeFieldName("r")
        rightInfo.utf8Writer().writeUtf8(writer, right)
    }
    writer.writeObjectEnd()
  }

  override def readLatin1(reader: Latin1JsonReader): Either[Any, Any] = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('{')
    if (reader.consumeNextToken('}')) throw invalid()
    val nameHash = reader.readFieldNameHash()
    reader.expectNextToken(':')
    val value =
      if (nameHash == ScalaEitherCodec.LeftHash || nameHash == ScalaEitherCodec.LegacyLeftHash)
        newLeft(reader, leftInfo.latin1Reader().readLatin1(reader))
      else if (
        nameHash == ScalaEitherCodec.RightHash || nameHash == ScalaEitherCodec.LegacyRightHash
      )
        newRight(reader, rightInfo.latin1Reader().readLatin1(reader))
      else throw invalid()
    if (reader.consumeNextCommaOrEndObject()) throw invalid()
    reader.exitDepth()
    validateBranch(value)
  }

  override def readUtf16(reader: Utf16JsonReader): Either[Any, Any] = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('{')
    if (reader.consumeNextToken('}')) throw invalid()
    val nameHash = reader.readFieldNameHash()
    reader.expectNextToken(':')
    val value =
      if (nameHash == ScalaEitherCodec.LeftHash || nameHash == ScalaEitherCodec.LegacyLeftHash)
        newLeft(reader, leftInfo.utf16Reader().readUtf16(reader))
      else if (
        nameHash == ScalaEitherCodec.RightHash || nameHash == ScalaEitherCodec.LegacyRightHash
      )
        newRight(reader, rightInfo.utf16Reader().readUtf16(reader))
      else throw invalid()
    if (reader.consumeNextCommaOrEndObject()) throw invalid()
    reader.exitDepth()
    validateBranch(value)
  }

  override def readUtf8(reader: Utf8JsonReader): Either[Any, Any] = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('{')
    if (reader.consumeNextToken('}')) throw invalid()
    val nameHash = reader.readFieldNameHash()
    reader.expectNextToken(':')
    val value =
      if (nameHash == ScalaEitherCodec.LeftHash || nameHash == ScalaEitherCodec.LegacyLeftHash)
        newLeft(reader, leftInfo.utf8Reader().readUtf8(reader))
      else if (
        nameHash == ScalaEitherCodec.RightHash || nameHash == ScalaEitherCodec.LegacyRightHash
      )
        newRight(reader, rightInfo.utf8Reader().readUtf8(reader))
      else throw invalid()
    if (reader.consumeNextCommaOrEndObject()) throw invalid()
    reader.exitDepth()
    validateBranch(value)
  }

  private def validateBranch(value: Either[Any, Any]): Either[Any, Any] = {
    if (branch == 1 && value.isRight || branch == 2 && value.isLeft) throw invalid()
    value
  }

  private def newLeft(reader: org.apache.fory.json.reader.JsonReader, value: Any): Left[Any, Any] = {
    reader.reserveGraphMemory(ScalaEitherCodec.LeftOwnerBytes)
    Left(value)
  }

  private def newRight(reader: org.apache.fory.json.reader.JsonReader, value: Any): Right[Any, Any] = {
    reader.reserveGraphMemory(ScalaEitherCodec.RightOwnerBytes)
    Right(value)
  }

  private def invalid(): ForyJsonException =
    new ForyJsonException("Scala Either JSON must contain exactly one l or r member")
}

private[scala] object ScalaEitherCodec {
  private val LeftHash = JsonFieldNameHash.hash("l")
  private val RightHash = JsonFieldNameHash.hash("r")
  private val LegacyLeftHash = JsonFieldNameHash.hash("left")
  private val LegacyRightHash = JsonFieldNameHash.hash("right")
  private val LeftOwnerBytes = GraphMemoryEstimates.shallowObjectBytes(classOf[Left[_, _]])
  private val RightOwnerBytes = GraphMemoryEstimates.shallowObjectBytes(classOf[Right[_, _]])
}
