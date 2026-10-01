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
import org.apache.fory.json.annotation.JsonProperty.NullHandling
import org.apache.fory.json.codec.{
  ArrayCodec,
  CompositeJsonCodec,
  ContainerJsonCodec,
  JsonValueCodec,
  MapCodec,
  MapKeyCodec,
  ScalarCodecs,
  Utf8WriterCodec
}
import org.apache.fory.json.reader.{JsonReader, Latin1JsonReader, Utf16JsonReader, Utf8JsonReader}
import org.apache.fory.json.resolver.{JsonTypeInfo, JsonTypeResolver}
import org.apache.fory.json.writer.{JsonWriter, StringJsonWriter, Utf8JsonWriter}
import org.apache.fory.reflect.TypeRef
import org.apache.fory.serializer.GraphMemoryEstimates

import scala.reflect.ClassTag

// Scala List is sealed to Nil and ::, so exact List codecs traverse nodes without
// runtime-family checks.
private[scala] final class ScalaListCodec(
    nonEmptyOnly: Boolean,
    nilOnly: Boolean,
    runtimeType: Boolean,
    onContentNullRead: NullHandling
)
    extends CompositeJsonCodec[List[Any]] with ContainerJsonCodec[List[Any]] {
  override def withContentNullRead(handling: NullHandling): ContainerJsonCodec[_] = {
    if (handling == onContentNullRead) return this
    val copy = new ScalaListCodec(nonEmptyOnly, nilOnly, runtimeType, handling)
    copy.elementInfo = elementInfo
    copy.booleanElements = booleanElements
    copy
  }

  private def skipNullRead(reader: JsonReader): Boolean = {
    if (onContentNullRead == NullHandling.SET || !reader.tryReadNullToken()) return false
    if (onContentNullRead == NullHandling.FAIL)
      throw new ForyJsonException("Cannot read null container content")
    true
  }

  override def isEmpty(writer: JsonWriter, value: List[Any]): Boolean = value.isEmpty

  private var elementInfo: JsonTypeInfo = _
  private var booleanElements = false

  override def resolveTypes(typeRef: TypeRef[_], resolver: JsonTypeResolver): Unit = {
    // Configured copies keep selected children; recursive copies can still require phase two.
    if (elementInfo != null) return
    if (nilOnly) {
      elementInfo = resolver.getTypeInfo(classOf[Object], classOf[Object])
    } else {
      val arguments = ScalaTypeSupport.runtimeArguments(
        typeRef,
        1,
        "List",
        runtimeType
      )
      elementInfo = resolver.getTypeInfo(arguments(0), ScalaTypeSupport.rawType(arguments(0)))
    }
    booleanElements = ScalaCollectionCodecs.writesBooleanDirectly(elementInfo)
  }

  override def resolveTypes(
      typeRef: TypeRef[_],
      resolver: JsonTypeResolver,
      childCodecs: JsonCodec
  ): Unit = {
    ScalaTypeSupport.requireElementCodec(childCodecs, "List")
    val arguments = ScalaTypeSupport.arguments(typeRef, 1, "List")
    elementInfo = resolver.getTypeInfo(
      arguments(0),
      ScalaTypeSupport.rawType(arguments(0)),
      childCodecs.elementCodec()
    )
    booleanElements = ScalaCollectionCodecs.writesBooleanDirectly(elementInfo)
  }

  override def writeString(writer: StringJsonWriter, value: List[Any]): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    val codec = elementInfo.stringWriter()
    writer.writeArrayStart()
    var current = value
    var index = 0
    while (current ne Nil) {
      val node = current.asInstanceOf[scala.collection.immutable.::[Any]]
      writer.writeComma(index)
      codec.writeString(writer, node.head)
      current = node.tail
      index += 1
    }
    writer.writeArrayEnd()
  }

  override def writeUtf8(writer: Utf8JsonWriter, value: List[Any]): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    val codec = elementInfo.utf8Writer()
    val writeBooleans = booleanElements && !writer.prettyPrint()
    writer.writeArrayStart()
    var current = value
    if (current ne Nil) {
      val first = current.asInstanceOf[scala.collection.immutable.::[Any]]
      codec.writeUtf8(writer, first.head)
      current = first.tail
    }
    while (current ne Nil) {
      val node = current.asInstanceOf[scala.collection.immutable.::[Any]]
      val element = node.head
      val tail = node.tail
      if (writeBooleans && element.isInstanceOf[java.lang.Boolean]) {
        val first = element.asInstanceOf[java.lang.Boolean].booleanValue()
        if ((tail ne Nil) && tail.head.isInstanceOf[java.lang.Boolean]) {
          ScalaCollectionCodecs.writeBooleanPair(
            writer,
            first,
            tail.head.asInstanceOf[java.lang.Boolean].booleanValue()
          )
          current = tail.tail
        } else {
          writer.writeRawValue(
            if (first) 0x65_7572_742cL else 0x6573_6c61_662cL,
            0L,
            if (first) 5 else 6
          )
          current = tail
        }
      } else {
        writer.writeComma(1)
        codec.writeUtf8(writer, element)
        current = tail
      }
    }
    writer.writeArrayEnd()
  }

  override def readLatin1(reader: Latin1JsonReader): List[Any] = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('[')
    val builder = List.newBuilder[Any]
    val codec = elementInfo.latin1Reader()
    var size = 0
    if (!reader.consumeNextToken(']')) {
      var more = true
      while (more) {
        if (!skipNullRead(reader)) {
          reserveBatch(reader, size)
          builder += codec.readLatin1(reader)
          size += 1
        }
        more = reader.consumeNextCommaOrEndArray()
      }
    }
    reserveTail(reader, size)
    reader.exitDepth()
    finish(builder.result(), size)
  }

  override def readUtf16(reader: Utf16JsonReader): List[Any] = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('[')
    val builder = List.newBuilder[Any]
    val codec = elementInfo.utf16Reader()
    var size = 0
    if (!reader.consumeNextToken(']')) {
      var more = true
      while (more) {
        if (!skipNullRead(reader)) {
          reserveBatch(reader, size)
          builder += codec.readUtf16(reader)
          size += 1
        }
        more = reader.consumeNextCommaOrEndArray()
      }
    }
    reserveTail(reader, size)
    reader.exitDepth()
    finish(builder.result(), size)
  }

  override def readUtf8(reader: Utf8JsonReader): List[Any] = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('[')
    val builder = List.newBuilder[Any]
    val codec = elementInfo.utf8Reader()
    var size = 0
    if (!reader.consumeNextToken(']')) {
      var more = true
      while (more) {
        if (!skipNullRead(reader)) {
          reserveBatch(reader, size)
          builder += codec.readUtf8(reader)
          size += 1
        }
        more = reader.consumeNextCommaOrEndArray()
      }
    }
    reserveTail(reader, size)
    reader.exitDepth()
    finish(builder.result(), size)
  }

  private def finish(value: List[Any], size: Int): List[Any] = {
    if (nilOnly && size != 0) throw new ForyJsonException("scala.Nil requires an empty JSON array")
    if (nonEmptyOnly && size == 0)
      throw new ForyJsonException("scala.:: requires a non-empty JSON array")
    value
  }

  private def reserveBatch(reader: JsonReader, size: Int): Unit = {
    if ((size & ScalaCollectionCodecs.BatchMask) == ScalaCollectionCodecs.BatchMask) {
      reader.reserveGraphMemory(ScalaCollectionCodecs.ListBatchBytes)
    }
  }

  private def reserveTail(reader: JsonReader, size: Int): Unit = {
    val tail = size & ScalaCollectionCodecs.BatchMask
    if (tail != 0) reader.reserveGraphMemory(tail * ScalaCollectionCodecs.ListNodeBytes)
  }
}

private[scala] final class ScalaIterableCodec(
    kind: Int,
    ownerBytes: Int,
    runtimeType: Boolean,
    sequence: Boolean,
    onContentNullRead: NullHandling
)
    extends CompositeJsonCodec[scala.collection.Iterable[Any]]
    with ContainerJsonCodec[scala.collection.Iterable[Any]] {
  override def withContentNullRead(handling: NullHandling): ContainerJsonCodec[_] = {
    if (handling == onContentNullRead) return this
    val copy = new ScalaIterableCodec(kind, ownerBytes, runtimeType, sequence, handling)
    copy.elementInfo = elementInfo
    copy.elementClassTag = elementClassTag
    copy.booleanElements = booleanElements
    if (booleanArrayCodec != null)
      copy.booleanArrayCodec = booleanArrayCodec.asInstanceOf[ContainerJsonCodec[Array[Boolean]]]
        .withContentNullRead(handling).asInstanceOf[JsonValueCodec[Array[Boolean]]]
    if (intArrayCodec != null)
      copy.intArrayCodec = intArrayCodec.asInstanceOf[ContainerJsonCodec[Array[Int]]]
        .withContentNullRead(handling).asInstanceOf[JsonValueCodec[Array[Int]]]
    copy
  }

  private def skipNullRead(reader: JsonReader): Boolean = {
    if (onContentNullRead == NullHandling.SET || !reader.tryReadNullToken()) return false
    if (onContentNullRead == NullHandling.FAIL)
      throw new ForyJsonException("Cannot read null container content")
    true
  }

  override def isEmpty(writer: JsonWriter, value: scala.collection.Iterable[Any]): Boolean = value.isEmpty

  private val resultOwnerBytes =
    if (kind == ScalaCollectionCodecs.ListKind) 0 else ownerBytes
  private val retainedElementBytes =
    if (kind == ScalaCollectionCodecs.ListKind) ScalaCollectionCodecs.ListNodeBytes
    else ScalaCollectionCodecs.ReferenceBytes
  private var elementInfo: JsonTypeInfo = _
  private var elementClassTag: ClassTag[Any] = _
  private var booleanArrayCodec: JsonValueCodec[Array[Boolean]] = _
  private var intArrayCodec: JsonValueCodec[Array[Int]] = _
  private var booleanElements = false

  override def resolveTypes(typeRef: TypeRef[_], resolver: JsonTypeResolver): Unit = {
    if (elementInfo != null) return
    val arguments = ScalaTypeSupport.runtimeArguments(
      typeRef,
      1,
      "Scala collection",
      runtimeType
    )
    elementInfo = resolver.getTypeInfo(arguments(0), ScalaTypeSupport.rawType(arguments(0)))
    booleanElements = ScalaCollectionCodecs.writesBooleanDirectly(elementInfo)
    if (
      kind == ScalaCollectionCodecs.ImmutableArraySeqKind ||
      kind == ScalaCollectionCodecs.MutableArraySeqKind
    ) elementClassTag = ScalaTypeSupport.classTag(ScalaTypeSupport.rawType(arguments(0)))
    if (
      kind == ScalaCollectionCodecs.ImmutableHashSetKind &&
      (elementInfo.stringWriter() eq ScalarCodecs.IntCodec.PRIMITIVE)
    ) intArrayCodec = ArrayCodec.create(
      classOf[Array[Int]],
      TypeRef.of(classOf[Array[Int]]),
      resolver
    ).asInstanceOf[ContainerJsonCodec[Array[Int]]]
      .withContentNullRead(onContentNullRead).asInstanceOf[JsonValueCodec[Array[Int]]]
    if (kind == ScalaCollectionCodecs.ImmutableArraySeqKind) {
      if (booleanElements) booleanArrayCodec = ArrayCodec.create(
        classOf[Array[Boolean]],
        TypeRef.of(classOf[Array[Boolean]]),
        resolver
      ).asInstanceOf[ContainerJsonCodec[Array[Boolean]]]
        .withContentNullRead(onContentNullRead).asInstanceOf[JsonValueCodec[Array[Boolean]]]
    }
  }

  override def resolveTypes(
      typeRef: TypeRef[_],
      resolver: JsonTypeResolver,
      childCodecs: JsonCodec
  ): Unit = {
    ScalaTypeSupport.requireElementCodec(childCodecs, "Scala collection")
    val arguments = ScalaTypeSupport.arguments(typeRef, 1, "Scala collection")
    val rawType = ScalaTypeSupport.rawType(arguments(0))
    elementInfo = resolver.getTypeInfo(arguments(0), rawType, childCodecs.elementCodec())
    booleanElements = ScalaCollectionCodecs.writesBooleanDirectly(elementInfo)
    if (
      kind == ScalaCollectionCodecs.ImmutableArraySeqKind ||
      kind == ScalaCollectionCodecs.MutableArraySeqKind
    ) elementClassTag = ScalaTypeSupport.classTag(rawType)
  }

  override def writeString(writer: StringJsonWriter, value: scala.collection.Iterable[Any]): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    ScalaCollectionCodecs.requireSupportedRuntime(value.getClass)
    // Only a primitive backing array and a built-in element codec share the array representation.
    // Annotated elements and reference-backed ArraySeq values retain their resolved element writer.
    if (booleanArrayCodec != null) value match {
      case array: scala.collection.immutable.ArraySeq.ofBoolean =>
        booleanArrayCodec.writeString(writer, array.unsafeArray)
        return
      case _ =>
    }
    val codec = elementInfo.stringWriter()
    val iterator = value.iterator
    writer.writeArrayStart()
    var index = 0
    while (iterator.hasNext) {
      writer.writeComma(index)
      codec.writeString(writer, iterator.next())
      index += 1
    }
    writer.writeArrayEnd()
  }

  override def writeUtf8(writer: Utf8JsonWriter, value: scala.collection.Iterable[Any]): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    ScalaCollectionCodecs.requireSupportedRuntime(value.getClass)
    // Only a primitive backing array and a built-in element codec share the array representation.
    // Annotated elements and reference-backed ArraySeq values retain their resolved element writer.
    if (booleanArrayCodec != null) value match {
      case array: scala.collection.immutable.ArraySeq.ofBoolean =>
        booleanArrayCodec.writeUtf8(writer, array.unsafeArray)
        return
      case _ =>
    }
    val codec = elementInfo.utf8Writer()
    // Sets have at most two Boolean values and keep their ordinary element loop.
    if (
      sequence && booleanElements && !writer.prettyPrint()
    ) {
      writeSequence(writer, value.asInstanceOf[scala.collection.Seq[Object]], codec)
      return
    }
    val iterator = value.iterator
    writer.writeArrayStart()
    var index = 0
    while (iterator.hasNext) {
      writer.writeComma(index)
      codec.writeUtf8(writer, iterator.next())
      index += 1
    }
    writer.writeArrayEnd()
  }

  private def writeSequence(
      writer: Utf8JsonWriter,
      value: scala.collection.Seq[Object],
      codec: Utf8WriterCodec[Object]
  ): Unit = {
    val iterator = value.iterator
    writer.writeArrayStart()
    if (iterator.hasNext) codec.writeUtf8(writer, iterator.next())
    while (iterator.hasNext) {
      val element = iterator.next()
      if (element.isInstanceOf[java.lang.Boolean]) {
        val first = element.asInstanceOf[java.lang.Boolean].booleanValue()
        // The resolved built-in Boolean writer has no callbacks before fetching the next element.
        if (iterator.hasNext) {
          val second = iterator.next()
          if (second.isInstanceOf[java.lang.Boolean]) {
            ScalaCollectionCodecs.writeBooleanPair(
              writer,
              first,
              second.asInstanceOf[java.lang.Boolean].booleanValue()
            )
          } else {
            writer.writeRawValue(
              if (first) 0x65_7572_742cL else 0x6573_6c61_662cL,
              0L,
              if (first) 5 else 6
            )
            writer.writeComma(1)
            codec.writeUtf8(writer, second)
          }
        } else {
          writer.writeRawValue(
            if (first) 0x65_7572_742cL else 0x6573_6c61_662cL,
            0L,
            if (first) 5 else 6
          )
        }
      } else {
        writer.writeComma(1)
        codec.writeUtf8(writer, element)
      }
    }
    writer.writeArrayEnd()
  }

  // Primitive schemas can reuse array decoders; boxed and custom elements retain their codec.
  // ArraySeq adds its wrapper charge; integer sets transfer the array charge in intSet.
  override def readLatin1(reader: Latin1JsonReader): scala.collection.Iterable[Any] = {
    if (intArrayCodec != null) return intSet(reader, intArrayCodec.readLatin1(reader))
    if (booleanArrayCodec != null && elementClassTag.runtimeClass == java.lang.Boolean.TYPE) {
      val values = booleanArrayCodec.readLatin1(reader)
      if (values == null) return null
      reader.reserveGraphMemory(ScalaCollectionCodecs.BooleanArraySeqBytes)
      return new scala.collection.immutable.ArraySeq.ofBoolean(values)
    }
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('[')
    reader.reserveGraphMemory(resultOwnerBytes)
    val builder = newBuilder()
    val codec = elementInfo.latin1Reader()
    var size = 0
    if (!reader.consumeNextToken(']')) {
      var more = true
      while (more) {
        if (!skipNullRead(reader)) {
          ScalaCollectionCodecs.reserveElements(reader, size, retainedElementBytes)
          builder += codec.readLatin1(reader)
          size += 1
        }
        more = reader.consumeNextCommaOrEndArray()
      }
    }
    ScalaCollectionCodecs.reserveElementTail(reader, size, retainedElementBytes)
    val result = builder.result().asInstanceOf[scala.collection.Iterable[Any]]
    reader.exitDepth()
    result
  }

  override def readUtf16(reader: Utf16JsonReader): scala.collection.Iterable[Any] = {
    if (intArrayCodec != null) return intSet(reader, intArrayCodec.readUtf16(reader))
    if (booleanArrayCodec != null && elementClassTag.runtimeClass == java.lang.Boolean.TYPE) {
      val values = booleanArrayCodec.readUtf16(reader)
      if (values == null) return null
      reader.reserveGraphMemory(ScalaCollectionCodecs.BooleanArraySeqBytes)
      return new scala.collection.immutable.ArraySeq.ofBoolean(values)
    }
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('[')
    reader.reserveGraphMemory(resultOwnerBytes)
    val builder = newBuilder()
    val codec = elementInfo.utf16Reader()
    var size = 0
    if (!reader.consumeNextToken(']')) {
      var more = true
      while (more) {
        if (!skipNullRead(reader)) {
          ScalaCollectionCodecs.reserveElements(reader, size, retainedElementBytes)
          builder += codec.readUtf16(reader)
          size += 1
        }
        more = reader.consumeNextCommaOrEndArray()
      }
    }
    ScalaCollectionCodecs.reserveElementTail(reader, size, retainedElementBytes)
    val result = builder.result().asInstanceOf[scala.collection.Iterable[Any]]
    reader.exitDepth()
    result
  }

  override def readUtf8(reader: Utf8JsonReader): scala.collection.Iterable[Any] = {
    if (intArrayCodec != null) return intSet(reader, intArrayCodec.readUtf8(reader))
    if (booleanArrayCodec != null && elementClassTag.runtimeClass == java.lang.Boolean.TYPE) {
      val values = booleanArrayCodec.readUtf8(reader)
      if (values == null) return null
      reader.reserveGraphMemory(ScalaCollectionCodecs.BooleanArraySeqBytes)
      return new scala.collection.immutable.ArraySeq.ofBoolean(values)
    }
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('[')
    reader.reserveGraphMemory(resultOwnerBytes)
    val builder = newBuilder()
    val codec = elementInfo.utf8Reader()
    var size = 0
    if (!reader.consumeNextToken(']')) {
      var more = true
      while (more) {
        if (!skipNullRead(reader)) {
          ScalaCollectionCodecs.reserveElements(reader, size, retainedElementBytes)
          builder += codec.readUtf8(reader)
          size += 1
        }
        more = reader.consumeNextCommaOrEndArray()
      }
    }
    ScalaCollectionCodecs.reserveElementTail(reader, size, retainedElementBytes)
    val result = builder.result().asInstanceOf[scala.collection.Iterable[Any]]
    reader.exitDepth()
    result
  }

  private def intSet(reader: JsonReader, values: Array[Int]): scala.collection.Iterable[Any] = {
    if (values == null) return null
    // The array decoder already charged four bytes per input occurrence, matching each candidate
    // set reference. Transfer that storage and header credit before building the retained owner.
    reader.reserveGraphMemory(math.max(0, resultOwnerBytes - GraphMemoryEstimates.objectArrayBytes()))
    val builder = scala.collection.immutable.HashSet.newBuilder[Int]
    var index = 0
    while (index < values.length) {
      builder += values(index)
      index += 1
    }
    builder.result()
  }

  private def newBuilder(): scala.collection.mutable.Builder[Any, _] = kind match {
    case ScalaCollectionCodecs.VectorKind => Vector.newBuilder[Any]
    case ScalaCollectionCodecs.ImmutableQueueKind => scala.collection.immutable.Queue.newBuilder[Any]
    case ScalaCollectionCodecs.ImmutableArraySeqKind =>
      scala.collection.immutable.ArraySeq.newBuilder[Any](elementClassTag)
    case ScalaCollectionCodecs.MutableArrayBufferKind =>
      scala.collection.mutable.ArrayBuffer.newBuilder[Any]
    case ScalaCollectionCodecs.MutableListBufferKind =>
      scala.collection.mutable.ListBuffer.newBuilder[Any]
    case ScalaCollectionCodecs.MutableArraySeqKind =>
      scala.collection.mutable.ArraySeq.newBuilder[Any](elementClassTag)
    case ScalaCollectionCodecs.MutableArrayDequeKind =>
      scala.collection.mutable.ArrayDeque.newBuilder[Any]
    case ScalaCollectionCodecs.MutableQueueKind => scala.collection.mutable.Queue.newBuilder[Any]
    case ScalaCollectionCodecs.ImmutableHashSetKind =>
      scala.collection.immutable.HashSet.newBuilder[Any]
    case ScalaCollectionCodecs.ImmutableListSetKind =>
      scala.collection.immutable.ListSet.newBuilder[Any]
    case ScalaCollectionCodecs.MutableHashSetKind =>
      // One entry per bucket reduces table growth during incremental decoding.
      scala.collection.mutable.HashSet.newBuilder[Any](16, 1.0)
    case ScalaCollectionCodecs.MutableLinkedHashSetKind =>
      scala.collection.mutable.LinkedHashSet.newBuilder[Any]
    case _ => List.newBuilder[Any]
  }
}

private[scala] final class ScalaMapCodec(kind: Int, ownerBytes: Int, runtimeType: Boolean,
    onContentNullRead: NullHandling)
    extends CompositeJsonCodec[scala.collection.Map[Any, Any]]
    with ContainerJsonCodec[scala.collection.Map[Any, Any]] {
  override def withContentNullRead(handling: NullHandling): ContainerJsonCodec[_] = {
    if (handling == onContentNullRead) return this
    val copy = new ScalaMapCodec(kind, ownerBytes, runtimeType, handling)
    copy.keyCodec = keyCodec
    copy.valueInfo = valueInfo
    copy
  }

  private def skipNullRead(reader: JsonReader): Boolean = {
    if (onContentNullRead == NullHandling.SET || !reader.tryReadNullToken()) return false
    if (onContentNullRead == NullHandling.FAIL)
      throw new ForyJsonException("Cannot read null container content")
    true
  }

  override def isEmpty(writer: JsonWriter, value: scala.collection.Map[Any, Any]): Boolean = value.isEmpty

  private var keyCodec: MapKeyCodec = _
  private var valueInfo: JsonTypeInfo = _

  override def resolveTypes(typeRef: TypeRef[_], resolver: JsonTypeResolver): Unit = {
    if (valueInfo != null) return
    val specializedKey = ScalaCollectionCodecs.specializedMapKey(kind)
    val arguments = ScalaTypeSupport.runtimeArguments(
      typeRef,
      if (specializedKey == null) 2 else 1,
      "Scala Map",
      runtimeType
    )
    val keyType = if (specializedKey == null) arguments(0) else specializedKey
    if (keyCodec == null) keyCodec = defaultKeyCodec(keyType, resolver)
    val valueType = arguments(if (specializedKey == null) 1 else 0)
    valueInfo = resolver.getTypeInfo(valueType, ScalaTypeSupport.rawType(valueType))
  }

  override def resolveTypes(
      typeRef: TypeRef[_],
      resolver: JsonTypeResolver,
      childCodecs: JsonCodec
  ): Unit = {
    ScalaTypeSupport.requireMapCodecs(childCodecs)
    val specializedKey = ScalaCollectionCodecs.specializedMapKey(kind)
    val arguments = ScalaTypeSupport.arguments(
      typeRef,
      if (specializedKey == null) 2 else 1,
      "Scala Map"
    )
    val keyType = if (specializedKey == null) arguments(0) else specializedKey
    val keyRawType = ScalaTypeSupport.rawType(keyType)
    keyCodec =
      if (childCodecs.keyCodec() != classOf[JsonCodec.NoMapKeyCodec])
        resolver.getMapKeyCodec(keyRawType, childCodecs.keyCodec())
      else defaultKeyCodec(keyType, resolver)
    val valueType = arguments(if (specializedKey == null) 1 else 0)
    val valueRawType = ScalaTypeSupport.rawType(valueType)
    valueInfo =
      if (childCodecs.valueCodec() == classOf[JsonCodec.NoJsonValueCodec])
        resolver.getTypeInfo(valueType, valueRawType)
      else resolver.getTypeInfo(valueType, valueRawType, childCodecs.valueCodec())
  }

  private def defaultKeyCodec(keyType: java.lang.reflect.Type, resolver: JsonTypeResolver): MapKeyCodec = {
    val enumerationCodec = ScalaEnumerationTypes.mapKeyCodec(keyType)
    if (enumerationCodec != null) return enumerationCodec
    val rawType = ScalaTypeSupport.rawType(keyType)
    val enumRoot = ScalaEnumCodec.enumRoot(rawType)
    if (enumRoot != null && enumRoot == rawType) {
      resolver.checkMapKeySecure(rawType)
      ScalaEnumCodec.create(enumRoot, TypeRef.of(keyType))
    } else resolver.getMapKeyCodec(rawType)
  }

  override def writeString(writer: StringJsonWriter, value: scala.collection.Map[Any, Any]): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    ScalaCollectionCodecs.requireSupportedRuntime(value.getClass)
    if (kind == ScalaCollectionCodecs.ImmutableIntMapKind && keyCodec.isInstanceOf[MapCodec.IntKeyCodec]) {
      writeIntMap(writer, value.asInstanceOf[scala.collection.immutable.IntMap[Any]])
      return
    }
    if (kind == ScalaCollectionCodecs.MutableLongMapKind && keyCodec.isInstanceOf[MapCodec.LongKeyCodec]) {
      writeLongMap(writer, value.asInstanceOf[scala.collection.mutable.LongMap[Any]])
      return
    }
    val codec = valueInfo.stringWriter()
    val iterator = value.iterator
    writer.writeObjectStart()
    var index = 0
    while (iterator.hasNext) {
      writer.writeComma(index)
      val entry = iterator.next()
      if (entry._1 == null) throw new ForyJsonException("JSON map key cannot be null")
      keyCodec.writeName(writer, entry._1)
      codec.writeString(writer, entry._2)
      index += 1
    }
    writer.writeObjectEnd()
  }

  override def writeUtf8(writer: Utf8JsonWriter, value: scala.collection.Map[Any, Any]): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    ScalaCollectionCodecs.requireSupportedRuntime(value.getClass)
    if (kind == ScalaCollectionCodecs.ImmutableIntMapKind && keyCodec.isInstanceOf[MapCodec.IntKeyCodec]) {
      writeIntMap(writer, value.asInstanceOf[scala.collection.immutable.IntMap[Any]])
      return
    }
    if (kind == ScalaCollectionCodecs.MutableLongMapKind && keyCodec.isInstanceOf[MapCodec.LongKeyCodec]) {
      writeLongMap(writer, value.asInstanceOf[scala.collection.mutable.LongMap[Any]])
      return
    }
    val codec = valueInfo.utf8Writer()
    val iterator = value.iterator
    writer.writeObjectStart()
    var index = 0
    while (iterator.hasNext) {
      writer.writeComma(index)
      val entry = iterator.next()
      if (entry._1 == null) throw new ForyJsonException("JSON map key cannot be null")
      keyCodec.writeName(writer, entry._1)
      codec.writeUtf8(writer, entry._2)
      index += 1
    }
    writer.writeObjectEnd()
  }

  private def writeIntMap(
      writer: StringJsonWriter,
      value: scala.collection.immutable.IntMap[Any]
  ): Unit = {
    val codec = valueInfo.stringWriter()
    writer.writeObjectStart()
    var first = true // Avoid a captured counter update on every entry.
    // foreachEntry preserves IntMap's traversal order without materializing iterator tuples.
    // This route is selected only for natural integer keys; custom key codecs keep the generic loop.
    value.foreachEntry { (key, entryValue) =>
      if (first) first = false else writer.writeComma(1)
      writer.writeIntFieldName(key)
      codec.writeString(writer, entryValue)
    }
    writer.writeObjectEnd()
  }

  private def writeIntMap(
      writer: Utf8JsonWriter,
      value: scala.collection.immutable.IntMap[Any]
  ): Unit = {
    val codec = valueInfo.utf8Writer()
    writer.writeObjectStart()
    val start = writer.getPosition() // Each member name advances the writer cursor.
    // foreachEntry preserves IntMap's traversal order without materializing iterator tuples.
    // This route is selected only for natural integer keys; custom key codecs keep the generic loop.
    value.foreachEntry { (key, entryValue) =>
      if (writer.getPosition() != start) writer.writeComma(1)
      writer.writeIntFieldName(key)
      codec.writeUtf8(writer, entryValue)
    }
    writer.writeObjectEnd()
  }

  private def writeLongMap(
      writer: StringJsonWriter,
      value: scala.collection.mutable.LongMap[Any]
  ): Unit = {
    val codec = valueInfo.stringWriter()
    writer.writeObjectStart()
    var first = true
    // The standard entry traversal handles the zero/minimum keys and deleted slots without
    // allocating iterator tuples. Custom key codecs retain the generic map path above.
    value.foreachEntry { (key, entryValue) =>
      if (first) first = false else writer.writeComma(1)
      writer.writeLongFieldName(key)
      codec.writeString(writer, entryValue)
    }
    writer.writeObjectEnd()
  }

  private def writeLongMap(
      writer: Utf8JsonWriter,
      value: scala.collection.mutable.LongMap[Any]
  ): Unit = {
    val codec = valueInfo.utf8Writer()
    writer.writeObjectStart()
    val start = writer.getPosition()
    // The member name advances the cursor even when the child value is null or custom-coded.
    value.foreachEntry { (key, entryValue) =>
      if (writer.getPosition() != start) writer.writeComma(1)
      writer.writeLongFieldName(key)
      codec.writeUtf8(writer, entryValue)
    }
    writer.writeObjectEnd()
  }

  override def readLatin1(reader: Latin1JsonReader): scala.collection.Map[Any, Any] = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('{')
    reader.reserveGraphMemory(ownerBytes)
    if (kind == ScalaCollectionCodecs.ImmutableIntMapKind && keyCodec.isInstanceOf[MapCodec.IntKeyCodec])
      return readIntMap(reader)
    if (kind == ScalaCollectionCodecs.MutableLongMapKind && keyCodec.isInstanceOf[MapCodec.LongKeyCodec])
      return readLongMap(reader)
    val builder = newBuilder()
    val codec = valueInfo.latin1Reader()
    var size = 0
    if (!reader.consumeNextToken('}')) {
      var more = true
      while (more) {
        val key = keyCodec.readName(reader)
        reader.expectNextToken(':')
        if (!skipNullRead(reader)) {
          ScalaCollectionCodecs.reserveMapEntries(reader, size)
          builder += ((key, codec.readLatin1(reader)))
          size += 1
        }
        more = reader.consumeNextCommaOrEndObject()
      }
    }
    finish(reader, builder, size)
  }

  override def readUtf16(reader: Utf16JsonReader): scala.collection.Map[Any, Any] = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('{')
    reader.reserveGraphMemory(ownerBytes)
    if (kind == ScalaCollectionCodecs.ImmutableIntMapKind && keyCodec.isInstanceOf[MapCodec.IntKeyCodec])
      return readIntMap(reader)
    if (kind == ScalaCollectionCodecs.MutableLongMapKind && keyCodec.isInstanceOf[MapCodec.LongKeyCodec])
      return readLongMap(reader)
    val builder = newBuilder()
    val codec = valueInfo.utf16Reader()
    var size = 0
    if (!reader.consumeNextToken('}')) {
      var more = true
      while (more) {
        val key = keyCodec.readName(reader)
        reader.expectNextToken(':')
        if (!skipNullRead(reader)) {
          ScalaCollectionCodecs.reserveMapEntries(reader, size)
          builder += ((key, codec.readUtf16(reader)))
          size += 1
        }
        more = reader.consumeNextCommaOrEndObject()
      }
    }
    finish(reader, builder, size)
  }

  override def readUtf8(reader: Utf8JsonReader): scala.collection.Map[Any, Any] = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('{')
    reader.reserveGraphMemory(ownerBytes)
    if (kind == ScalaCollectionCodecs.ImmutableIntMapKind && keyCodec.isInstanceOf[MapCodec.IntKeyCodec])
      return readIntMap(reader)
    if (kind == ScalaCollectionCodecs.MutableLongMapKind && keyCodec.isInstanceOf[MapCodec.LongKeyCodec])
      return readLongMap(reader)
    val builder = newBuilder()
    val codec = valueInfo.utf8Reader()
    var size = 0
    if (!reader.consumeNextToken('}')) {
      var more = true
      while (more) {
        val key = keyCodec.readName(reader)
        reader.expectNextToken(':')
        if (!skipNullRead(reader)) {
          ScalaCollectionCodecs.reserveMapEntries(reader, size)
          builder += ((key, codec.readUtf8(reader)))
          size += 1
        }
        more = reader.consumeNextCommaOrEndObject()
      }
    }
    finish(reader, builder, size)
  }

  // Specialized maps own primitive keys. Keep them primitive through insertion; custom key codecs still
  // use the general builder so occurrence-level key conversions retain their semantics.
  private def readIntMap(reader: Latin1JsonReader): scala.collection.Map[Any, Any] = {
    var result = scala.collection.immutable.IntMap.empty[Any]
    val codec = valueInfo.latin1Reader()
    var size = 0
    if (!reader.consumeNextToken('}')) {
      var more = true
      // Small maps stay on the direct insertion path. The bounded prefix is charged at the
      // tail or carried into the suffix owner, so it cannot reach a 1024-entry batch here.
      while (more) {
        val key = reader.readFieldNameInt()
        reader.expectNextToken(':')
        if (!skipNullRead(reader)) {
          result = result.updated(key, codec.readLatin1(reader))
          size += 1
        }
        more = reader.consumeNextCommaOrEndObject()
        if (size == 16 && more) return readIntMapEntries(reader, result, size)
      }
    }
    ScalaCollectionCodecs.reserveMapTail(reader, size)
    reader.exitDepth()
    result.asInstanceOf[scala.collection.Map[Any, Any]]
  }

  private def readIntMap(reader: Utf16JsonReader): scala.collection.Map[Any, Any] = {
    var result = scala.collection.immutable.IntMap.empty[Any]
    val codec = valueInfo.utf16Reader()
    var size = 0
    if (!reader.consumeNextToken('}')) {
      var more = true
      // Small maps stay on the direct insertion path. The bounded prefix is charged at the
      // tail or carried into the suffix owner, so it cannot reach a 1024-entry batch here.
      while (more) {
        val key = reader.readFieldNameInt()
        reader.expectNextToken(':')
        if (!skipNullRead(reader)) {
          result = result.updated(key, codec.readUtf16(reader))
          size += 1
        }
        more = reader.consumeNextCommaOrEndObject()
        if (size == 16 && more) return readIntMapEntries(reader, result, size)
      }
    }
    ScalaCollectionCodecs.reserveMapTail(reader, size)
    reader.exitDepth()
    result.asInstanceOf[scala.collection.Map[Any, Any]]
  }

  private def readIntMap(reader: Utf8JsonReader): scala.collection.Map[Any, Any] = {
    var result = scala.collection.immutable.IntMap.empty[Any]
    val codec = valueInfo.utf8Reader()
    var size = 0
    if (!reader.consumeNextToken('}')) {
      var more = true
      // Small maps stay on the direct insertion path. The bounded prefix is charged at the
      // tail or carried into the suffix owner, so it cannot reach a 1024-entry batch here.
      while (more) {
        val key = reader.readFieldNameInt()
        reader.expectNextToken(':')
        if (!skipNullRead(reader)) {
          result = result.updated(key, codec.readUtf8(reader))
          size += 1
        }
        more = reader.consumeNextCommaOrEndObject()
        if (size == 16 && more) return readIntMapEntries(reader, result, size)
      }
    }
    ScalaCollectionCodecs.reserveMapTail(reader, size)
    reader.exitDepth()
    result.asInstanceOf[scala.collection.Map[Any, Any]]
  }

  private def readIntMapEntries(
      reader: Latin1JsonReader,
      first: scala.collection.immutable.IntMap[Any],
      readCount: Int
  ): scala.collection.Map[Any, Any] = {
    val codec = valueInfo.latin1Reader()
    var firstKey = reader.readFieldNameInt()
    reader.expectNextToken(':')
    while (skipNullRead(reader)) {
      if (!reader.consumeNextCommaOrEndObject()) {
        ScalaCollectionCodecs.reserveMapTail(reader, readCount)
        reader.exitDepth()
        return first.asInstanceOf[scala.collection.Map[Any, Any]]
      }
      firstKey = reader.readFieldNameInt()
      reader.expectNextToken(':')
    }
    ScalaCollectionCodecs.reserveMapEntries(reader, readCount)
    val firstValue = codec.readLatin1(reader)
    if (!reader.consumeNextCommaOrEndObject()) {
      // A single remaining entry needs no temporary arrays or subtree construction.
      val result = first.updated(firstKey, firstValue)
      ScalaCollectionCodecs.reserveMapTail(reader, readCount + 1)
      reader.exitDepth()
      return result.asInstanceOf[scala.collection.Map[Any, Any]]
    }
    var keys = new Array[Long](32)
    var values = new Array[AnyRef](32)
    keys(0) = firstKey.toLong << 32
    values(0) = firstValue.asInstanceOf[AnyRef]
    var size = 1
    var count = readCount + 1
    var more = true
    while (more) {
      val key = reader.readFieldNameInt()
      reader.expectNextToken(':')
      if (!skipNullRead(reader)) {
        ScalaCollectionCodecs.reserveMapEntries(reader, count)
        val value = codec.readLatin1(reader)
        if (size == keys.length) {
          keys = java.util.Arrays.copyOf(keys, size << 1)
          values = java.util.Arrays.copyOf(values, size << 1)
        }
        keys(size) = (key.toLong << 32) | size.toLong
        values(size) = value.asInstanceOf[AnyRef]
        size += 1
        count += 1
      }
      more = reader.consumeNextCommaOrEndObject()
    }
    ScalaCollectionCodecs.reserveMapTail(reader, count)
    sortIntMapKeys(keys, size)
    // Standard right-biased union keeps the last occurrence across the prefix/suffix boundary.
    val result = first ++ intMapRange(keys, values, 0, size)
    reader.exitDepth()
    result.asInstanceOf[scala.collection.Map[Any, Any]]
  }

  private def readIntMapEntries(
      reader: Utf16JsonReader,
      first: scala.collection.immutable.IntMap[Any],
      readCount: Int
  ): scala.collection.Map[Any, Any] = {
    val codec = valueInfo.utf16Reader()
    var firstKey = reader.readFieldNameInt()
    reader.expectNextToken(':')
    while (skipNullRead(reader)) {
      if (!reader.consumeNextCommaOrEndObject()) {
        ScalaCollectionCodecs.reserveMapTail(reader, readCount)
        reader.exitDepth()
        return first.asInstanceOf[scala.collection.Map[Any, Any]]
      }
      firstKey = reader.readFieldNameInt()
      reader.expectNextToken(':')
    }
    ScalaCollectionCodecs.reserveMapEntries(reader, readCount)
    val firstValue = codec.readUtf16(reader)
    if (!reader.consumeNextCommaOrEndObject()) {
      // A single remaining entry needs no temporary arrays or subtree construction.
      val result = first.updated(firstKey, firstValue)
      ScalaCollectionCodecs.reserveMapTail(reader, readCount + 1)
      reader.exitDepth()
      return result.asInstanceOf[scala.collection.Map[Any, Any]]
    }
    var keys = new Array[Long](32)
    var values = new Array[AnyRef](32)
    keys(0) = firstKey.toLong << 32
    values(0) = firstValue.asInstanceOf[AnyRef]
    var size = 1
    var count = readCount + 1
    var more = true
    while (more) {
      val key = reader.readFieldNameInt()
      reader.expectNextToken(':')
      if (!skipNullRead(reader)) {
        ScalaCollectionCodecs.reserveMapEntries(reader, count)
        val value = codec.readUtf16(reader)
        if (size == keys.length) {
          keys = java.util.Arrays.copyOf(keys, size << 1)
          values = java.util.Arrays.copyOf(values, size << 1)
        }
        keys(size) = (key.toLong << 32) | size.toLong
        values(size) = value.asInstanceOf[AnyRef]
        size += 1
        count += 1
      }
      more = reader.consumeNextCommaOrEndObject()
    }
    ScalaCollectionCodecs.reserveMapTail(reader, count)
    sortIntMapKeys(keys, size)
    // Standard right-biased union keeps the last occurrence across the prefix/suffix boundary.
    val result = first ++ intMapRange(keys, values, 0, size)
    reader.exitDepth()
    result.asInstanceOf[scala.collection.Map[Any, Any]]
  }

  private def readIntMapEntries(
      reader: Utf8JsonReader,
      first: scala.collection.immutable.IntMap[Any],
      readCount: Int
  ): scala.collection.Map[Any, Any] = {
    val codec = valueInfo.utf8Reader()
    var firstKey = reader.readFieldNameInt()
    reader.expectNextToken(':')
    while (skipNullRead(reader)) {
      if (!reader.consumeNextCommaOrEndObject()) {
        ScalaCollectionCodecs.reserveMapTail(reader, readCount)
        reader.exitDepth()
        return first.asInstanceOf[scala.collection.Map[Any, Any]]
      }
      firstKey = reader.readFieldNameInt()
      reader.expectNextToken(':')
    }
    ScalaCollectionCodecs.reserveMapEntries(reader, readCount)
    val firstValue = codec.readUtf8(reader)
    if (!reader.consumeNextCommaOrEndObject()) {
      // A single remaining entry needs no temporary arrays or subtree construction.
      val result = first.updated(firstKey, firstValue)
      ScalaCollectionCodecs.reserveMapTail(reader, readCount + 1)
      reader.exitDepth()
      return result.asInstanceOf[scala.collection.Map[Any, Any]]
    }
    var keys = new Array[Long](32)
    var values = new Array[AnyRef](32)
    keys(0) = firstKey.toLong << 32
    values(0) = firstValue.asInstanceOf[AnyRef]
    var size = 1
    var count = readCount + 1
    var more = true
    while (more) {
      val key = reader.readFieldNameInt()
      reader.expectNextToken(':')
      if (!skipNullRead(reader)) {
        ScalaCollectionCodecs.reserveMapEntries(reader, count)
        val value = codec.readUtf8(reader)
        if (size == keys.length) {
          keys = java.util.Arrays.copyOf(keys, size << 1)
          values = java.util.Arrays.copyOf(values, size << 1)
        }
        keys(size) = (key.toLong << 32) | size.toLong
        values(size) = value.asInstanceOf[AnyRef]
        size += 1
        count += 1
      }
      more = reader.consumeNextCommaOrEndObject()
    }
    ScalaCollectionCodecs.reserveMapTail(reader, count)
    sortIntMapKeys(keys, size)
    // Standard right-biased union keeps the last occurrence across the prefix/suffix boundary.
    val result = first ++ intMapRange(keys, values, 0, size)
    reader.exitDepth()
    result.asInstanceOf[scala.collection.Map[Any, Any]]
  }

  private def sortIntMapKeys(keys: Array[Long], size: Int): Unit = {
    // Ordered keys need no scratch storage or counting passes. Include the input index in the
    // comparison so this also proves the order of duplicate keys before choosing their last value.
    var ordered = 1
    while (ordered < size && java.lang.Long.compareUnsigned(keys(ordered - 1), keys(ordered)) <= 0) {
      ordered += 1
    }
    if (ordered == size) return
    if (size <= 256) {
      java.util.Arrays.sort(keys, 0, size)
      return
    }
    // Four stable byte passes bound sorting work independently of input order, including on
    // JDK 8. Equal keys retain their input positions, so the last duplicate still wins.
    val scratch = new Array[Long](size)
    val counts = new Array[Int](256)
    var source = keys
    var target = scratch
    var shift = 32
    while (shift < 64) {
      java.util.Arrays.fill(counts, 0)
      var index = 0
      while (index < size) {
        val digit = ((source(index) >>> shift) & 255).toInt
        counts(digit) += 1
        index += 1
      }
      var total = 0
      var digit = 0
      while (digit < 256) {
        val count = counts(digit)
        counts(digit) = total
        total += count
        digit += 1
      }
      index = 0
      while (index < size) {
        val entry = source(index)
        val digit = ((entry >>> shift) & 255).toInt
        val position = counts(digit)
        target(position) = entry
        counts(digit) = position + 1
        index += 1
      }
      val swap = source
      source = target
      target = swap
      shift += 8
    }
  }

  private def intMapRange(
      keys: Array[Long],
      values: Array[AnyRef],
      from: Int,
      until: Int
  ): scala.collection.immutable.IntMap[Any] = {
    // Keys are ordered by their bits; equal-key ranges retain increasing input positions.
    val first = (keys(from) >> 32).toInt
    val last = (keys(until - 1) >> 32).toInt
    if (first == last) {
      return scala.collection.immutable.IntMap.singleton(first, values(keys(until - 1).toInt))
    }
    // Splitting at the highest differing bit gives disjoint Patricia prefixes. Standard IntMap
    // union joins these subtrees without copying an insertion path for every input entry.
    // Each recursion removes one differing key bit, bounding the stack by the 32-bit key width.
    val bit = java.lang.Integer.highestOneBit(first ^ last)
    var low = from + 1
    var high = until - 1
    while (low < high) {
      val middle = (low + high) >>> 1
      if ((((keys(middle) >> 32).toInt ^ first) & bit) == 0) low = middle + 1
      else high = middle
    }
    val left = intMapRange(keys, values, from, low)
    val right = intMapRange(keys, values, low, until)
    left ++ right
  }

  private def readLongMap(reader: Latin1JsonReader): scala.collection.Map[Any, Any] = {
    if (reader.consumeNextToken('}')) {
      reader.exitDepth()
      return scala.collection.mutable.LongMap.empty[Any].asInstanceOf[scala.collection.Map[Any, Any]]
    }
    val codec = valueInfo.latin1Reader()
    var keys = new Array[Long](8)
    var values = new Array[AnyRef](8)
    var size = 0
    var more = true
    while (more) {
      val key = reader.readFieldNameLong()
      reader.expectNextToken(':')
      if (!skipNullRead(reader)) {
        ScalaCollectionCodecs.reserveMapEntries(reader, size)
        val value = codec.readLatin1(reader)
        if (size == keys.length) {
          keys = java.util.Arrays.copyOf(keys, size << 1)
          values = java.util.Arrays.copyOf(values, size << 1)
        }
        keys(size) = key
        values(size) = value.asInstanceOf[AnyRef]
        size += 1
      }
      more = reader.consumeNextCommaOrEndObject()
    }
    ScalaCollectionCodecs.reserveMapTail(reader, size)
    val result = longMap(keys, values, size)
    reader.exitDepth()
    result.asInstanceOf[scala.collection.Map[Any, Any]]
  }

  private def readLongMap(reader: Utf16JsonReader): scala.collection.Map[Any, Any] = {
    if (reader.consumeNextToken('}')) {
      reader.exitDepth()
      return scala.collection.mutable.LongMap.empty[Any].asInstanceOf[scala.collection.Map[Any, Any]]
    }
    val codec = valueInfo.utf16Reader()
    var keys = new Array[Long](8)
    var values = new Array[AnyRef](8)
    var size = 0
    var more = true
    while (more) {
      val key = reader.readFieldNameLong()
      reader.expectNextToken(':')
      if (!skipNullRead(reader)) {
        ScalaCollectionCodecs.reserveMapEntries(reader, size)
        val value = codec.readUtf16(reader)
        if (size == keys.length) {
          keys = java.util.Arrays.copyOf(keys, size << 1)
          values = java.util.Arrays.copyOf(values, size << 1)
        }
        keys(size) = key
        values(size) = value.asInstanceOf[AnyRef]
        size += 1
      }
      more = reader.consumeNextCommaOrEndObject()
    }
    ScalaCollectionCodecs.reserveMapTail(reader, size)
    val result = longMap(keys, values, size)
    reader.exitDepth()
    result.asInstanceOf[scala.collection.Map[Any, Any]]
  }

  private def readLongMap(reader: Utf8JsonReader): scala.collection.Map[Any, Any] = {
    if (reader.consumeNextToken('}')) {
      reader.exitDepth()
      return scala.collection.mutable.LongMap.empty[Any].asInstanceOf[scala.collection.Map[Any, Any]]
    }
    val codec = valueInfo.utf8Reader()
    var keys = new Array[Long](8)
    var values = new Array[AnyRef](8)
    var size = 0
    var more = true
    while (more) {
      val key = reader.readFieldNameLong()
      reader.expectNextToken(':')
      if (!skipNullRead(reader)) {
        ScalaCollectionCodecs.reserveMapEntries(reader, size)
        val value = codec.readUtf8(reader)
        if (size == keys.length) {
          keys = java.util.Arrays.copyOf(keys, size << 1)
          values = java.util.Arrays.copyOf(values, size << 1)
        }
        keys(size) = key
        values(size) = value.asInstanceOf[AnyRef]
        size += 1
      }
      more = reader.consumeNextCommaOrEndObject()
    }
    ScalaCollectionCodecs.reserveMapTail(reader, size)
    val result = longMap(keys, values, size)
    reader.exitDepth()
    result.asInstanceOf[scala.collection.Map[Any, Any]]
  }

  private def longMap(
      keys: Array[Long],
      values: Array[AnyRef],
      size: Int
  ): scala.collection.mutable.LongMap[Any] = {
    // Size comes from consumed entries, not input-declared lengths. Half-full storage avoids
    // rehashing; insertion order still resolves duplicate keys to their last value.
    val capacity = math.min(size.toLong * 2, 1L << 30).toInt
    val result = new scala.collection.mutable.LongMap[Any](capacity)
    var index = 0
    while (index < size) {
      result.update(keys(index), values(index))
      index += 1
    }
    result
  }

  private def newBuilder(): scala.collection.mutable.Builder[(Any, Any), _] = kind match {
    case ScalaCollectionCodecs.ImmutableVectorMapKind =>
      scala.collection.immutable.VectorMap.newBuilder[Any, Any]
    case ScalaCollectionCodecs.ImmutableListMapKind =>
      scala.collection.immutable.ListMap.newBuilder[Any, Any]
    case ScalaCollectionCodecs.ImmutableIntMapKind =>
      scala.collection.immutable.IntMap.newBuilder[Any].asInstanceOf[scala.collection.mutable.Builder[(Any, Any), _]]
    case ScalaCollectionCodecs.ImmutableLongMapKind =>
      scala.collection.immutable.LongMap.newBuilder[Any].asInstanceOf[scala.collection.mutable.Builder[(Any, Any), _]]
    case ScalaCollectionCodecs.MutableHashMapKind =>
      // Keep the small initial table while reducing bucket allocation during incremental decoding.
      scala.collection.mutable.HashMap.newBuilder[Any, Any](16, 1.0)
    case ScalaCollectionCodecs.MutableLinkedHashMapKind =>
      scala.collection.mutable.LinkedHashMap.newBuilder[Any, Any]
    case ScalaCollectionCodecs.MutableAnyRefMapKind =>
      scala.collection.mutable.AnyRefMap.newBuilder[AnyRef, Any]
        .asInstanceOf[scala.collection.mutable.Builder[(Any, Any), _]]
    case ScalaCollectionCodecs.MutableLongMapKind =>
      scala.collection.mutable.LongMap.newBuilder[Any]
        .asInstanceOf[scala.collection.mutable.Builder[(Any, Any), _]]
    case _ => scala.collection.immutable.HashMap.newBuilder[Any, Any]
  }

  private def finish(
      reader: JsonReader,
      builder: scala.collection.mutable.Builder[(Any, Any), _],
    size: Int
  ): scala.collection.Map[Any, Any] = {
    ScalaCollectionCodecs.reserveMapTail(reader, size)
    val result = builder.result().asInstanceOf[scala.collection.Map[Any, Any]]
    reader.exitDepth()
    result
  }
}

private[scala] object ScalaCollectionCodecs {
  def writesBooleanDirectly(info: JsonTypeInfo): Boolean = {
    val codec = info.valueCodec()
    (codec eq ScalarCodecs.BooleanCodec.PRIMITIVE) || (codec eq ScalarCodecs.BooleanCodec.BOXED) ||
    (codec.getClass == classOf[ScalarCodecs.NaturalCodec] &&
      codec.asInstanceOf[ScalarCodecs.NaturalCodec].writesBooleanDirectly())
  }

  val ListKind = 0
  val VectorKind = 1
  val ImmutableQueueKind = 2
  val ImmutableArraySeqKind = 3
  val MutableArrayBufferKind = 4
  val MutableListBufferKind = 5
  val MutableArraySeqKind = 6
  val MutableArrayDequeKind = 7
  val MutableQueueKind = 8
  val ImmutableHashSetKind = 9
  val ImmutableListSetKind = 10
  val MutableHashSetKind = 11
  val MutableLinkedHashSetKind = 12

  val ImmutableHashMapKind = 20
  val ImmutableVectorMapKind = 21
  val ImmutableListMapKind = 22
  val ImmutableIntMapKind = 23
  val ImmutableLongMapKind = 24
  val MutableHashMapKind = 25
  val MutableLinkedHashMapKind = 26
  val MutableAnyRefMapKind = 27
  val MutableLongMapKind = 28

  def emptyValue(kind: Int, tag: ClassTag[Any]): AnyRef = kind match {
    case ListKind => Nil
    case VectorKind => Vector.empty
    case ImmutableQueueKind => scala.collection.immutable.Queue.empty
    case ImmutableArraySeqKind => scala.collection.immutable.ArraySeq.empty[Any](tag)
    case MutableArrayBufferKind => scala.collection.mutable.ArrayBuffer.empty
    case MutableListBufferKind => scala.collection.mutable.ListBuffer.empty
    case MutableArraySeqKind => scala.collection.mutable.ArraySeq.make[Any](tag.newArray(0))
    case MutableArrayDequeKind => scala.collection.mutable.ArrayDeque.empty
    case MutableQueueKind => scala.collection.mutable.Queue.empty
    case ImmutableHashSetKind => scala.collection.immutable.HashSet.empty
    case ImmutableListSetKind => scala.collection.immutable.ListSet.empty
    case MutableHashSetKind => scala.collection.mutable.HashSet.empty
    case MutableLinkedHashSetKind => scala.collection.mutable.LinkedHashSet.empty
    case ImmutableHashMapKind => scala.collection.immutable.HashMap.empty
    case ImmutableVectorMapKind => scala.collection.immutable.VectorMap.empty
    case ImmutableListMapKind => scala.collection.immutable.ListMap.empty
    case ImmutableIntMapKind => scala.collection.immutable.IntMap.empty
    case ImmutableLongMapKind => scala.collection.immutable.LongMap.empty
    case MutableHashMapKind => scala.collection.mutable.HashMap.empty
    case MutableLinkedHashMapKind => scala.collection.mutable.LinkedHashMap.empty
    case MutableAnyRefMapKind => scala.collection.mutable.AnyRefMap.empty[AnyRef, Any]
    case MutableLongMapKind => scala.collection.mutable.LongMap.empty
  }

  def writeBooleanPair(writer: Utf8JsonWriter, first: Boolean, second: Boolean): Unit = {
    val firstBytes = if (first) 0x65_7572_742cL else 0x6573_6c61_662cL
    val secondBytes = if (second) 0x65_7572_742cL else 0x6573_6c61_662cL
    val firstLength = if (first) 5 else 6
    val secondLength = if (second) 5 else 6
    val shift = firstLength << 3
    // Include both commas and split at the eight-byte boundary to share one capacity check.
    writer.writeRawValue(
      firstBytes | (secondBytes << shift),
      secondBytes >>> (64 - shift),
      firstLength + secondLength
    )
  }

  def specializedMapKey(kind: Int): Class[_] = kind match {
    case ImmutableIntMapKind  => java.lang.Integer.TYPE
    case ImmutableLongMapKind => java.lang.Long.TYPE
    case MutableLongMapKind   => java.lang.Long.TYPE
    case _                    => null
  }

  val BatchSize = 1024
  val BatchMask = BatchSize - 1
  val ReferenceBytes = GraphMemoryEstimates.REFERENCE_BYTES
  val ListNodeBytes = GraphMemoryEstimates.shallowObjectBytes(classOf[scala.collection.immutable.::[_]])
  val ListBatchBytes = BatchSize * ListNodeBytes
  val BooleanArraySeqBytes =
    GraphMemoryEstimates.shallowObjectBytes(classOf[scala.collection.immutable.ArraySeq.ofBoolean])
  private val MapEntryBytes = 2 * ReferenceBytes
  private val MapBatchBytes = BatchSize * MapEntryBytes

  private val RuntimeClasses = {
    val classes = new java.util.IdentityHashMap[Class[_], java.lang.Boolean]()
    def add(value: AnyRef): Unit = classes.put(value.getClass, java.lang.Boolean.TRUE)

    add(Nil)
    add(List(1))
    add(Vector.empty)
    add(Vector(1))
    add(Vector.tabulate(33)(identity))
    add(scala.collection.immutable.Queue.empty)
    add(scala.collection.immutable.Queue(1))
    add(scala.collection.immutable.ArraySeq.empty[Any])
    add(scala.collection.immutable.ArraySeq.empty[Int])
    add(scala.collection.mutable.ArrayBuffer.empty)
    add(scala.collection.mutable.ListBuffer.empty)
    add(scala.collection.mutable.ArraySeq.empty[Any])
    // ArraySeq.empty ignores its ClassTag and always uses ofRef. Register each primitive
    // backing class explicitly without admitting application subclasses of mutable.ArraySeq.
    add(scala.collection.mutable.ArraySeq.make(Array.emptyBooleanArray))
    add(scala.collection.mutable.ArraySeq.make(Array.emptyByteArray))
    add(scala.collection.mutable.ArraySeq.make(Array.emptyShortArray))
    add(scala.collection.mutable.ArraySeq.make(Array.emptyCharArray))
    add(scala.collection.mutable.ArraySeq.make(Array.emptyIntArray))
    add(scala.collection.mutable.ArraySeq.make(Array.emptyLongArray))
    add(scala.collection.mutable.ArraySeq.make(Array.emptyFloatArray))
    add(scala.collection.mutable.ArraySeq.make(Array.emptyDoubleArray))
    add(scala.collection.mutable.ArrayDeque.empty)
    add(scala.collection.mutable.Queue.empty)
    add(scala.collection.immutable.Set.empty)
    add(scala.collection.immutable.Set(1))
    add(scala.collection.immutable.Set(1, 2))
    add(scala.collection.immutable.Set(1, 2, 3))
    add(scala.collection.immutable.Set(1, 2, 3, 4))
    add(scala.collection.immutable.HashSet(1, 2, 3, 4, 5))
    add(scala.collection.immutable.ListSet.empty)
    add(scala.collection.immutable.ListSet(1))
    add(scala.collection.mutable.HashSet.empty)
    add(scala.collection.mutable.LinkedHashSet.empty)
    add(scala.collection.immutable.Map.empty)
    add(scala.collection.immutable.Map(1 -> 1))
    add(scala.collection.immutable.Map(1 -> 1, 2 -> 2))
    add(scala.collection.immutable.Map(1 -> 1, 2 -> 2, 3 -> 3))
    add(scala.collection.immutable.Map(1 -> 1, 2 -> 2, 3 -> 3, 4 -> 4))
    add(scala.collection.immutable.HashMap(1 -> 1, 2 -> 2, 3 -> 3, 4 -> 4, 5 -> 5))
    add(scala.collection.immutable.VectorMap.empty)
    add(scala.collection.immutable.ListMap.empty)
    add(scala.collection.immutable.IntMap.empty)
    add(scala.collection.immutable.LongMap.empty)
    add(scala.collection.mutable.HashMap.empty)
    add(scala.collection.mutable.LinkedHashMap.empty)
    add(scala.collection.mutable.AnyRefMap.empty)
    add(scala.collection.mutable.LongMap.empty)
    classes
  }

  def requireSupportedRuntime(rawType: Class[_]): Unit = {
    if (!supportedRuntime(rawType))
      throw new ForyJsonException(s"Unsupported Scala collection runtime type ${rawType.getName}")
  }

  def supportedRuntime(rawType: Class[_]): Boolean =
    RuntimeClasses.containsKey(rawType) ||
      classOf[List[_]].isAssignableFrom(rawType) ||
      classOf[Vector[_]].isAssignableFrom(rawType) ||
      classOf[scala.collection.immutable.ArraySeq[_]].isAssignableFrom(rawType) ||
      classOf[scala.collection.immutable.HashSet[_]].isAssignableFrom(rawType) ||
      classOf[scala.collection.immutable.ListSet[_]].isAssignableFrom(rawType) ||
      classOf[scala.collection.immutable.HashMap[_, _]].isAssignableFrom(rawType) ||
      classOf[scala.collection.immutable.VectorMap[_, _]].isAssignableFrom(rawType) ||
      classOf[scala.collection.immutable.ListMap[_, _]].isAssignableFrom(rawType) ||
      classOf[scala.collection.immutable.IntMap[_]].isAssignableFrom(rawType) ||
      classOf[scala.collection.immutable.LongMap[_]].isAssignableFrom(rawType)

  def reserveElements(reader: JsonReader, size: Int, bytes: Int): Unit = {
    if ((size & BatchMask) == BatchMask) reader.reserveGraphMemory(BatchSize * bytes)
  }

  def reserveElementTail(reader: JsonReader, size: Int, bytes: Int): Unit = {
    val tail = size & BatchMask
    if (tail != 0) reader.reserveGraphMemory(tail * bytes)
  }

  def reserveMapEntries(reader: JsonReader, size: Int): Unit = {
    if ((size & BatchMask) == BatchMask) reader.reserveGraphMemory(MapBatchBytes)
  }

  def reserveMapTail(reader: JsonReader, size: Int): Unit = {
    val tail = size & BatchMask
    if (tail != 0) reader.reserveGraphMemory(tail * MapEntryBytes)
  }
}
