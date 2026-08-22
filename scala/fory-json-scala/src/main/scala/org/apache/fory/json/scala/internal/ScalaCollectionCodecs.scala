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
import org.apache.fory.json.codec.{CompositeJsonCodec, MapKeyCodec}
import org.apache.fory.json.reader.{JsonReader, Latin1JsonReader, Utf16JsonReader, Utf8JsonReader}
import org.apache.fory.json.resolver.{JsonTypeInfo, JsonTypeResolver}
import org.apache.fory.json.writer.{StringJsonWriter, Utf8JsonWriter}
import org.apache.fory.reflect.TypeRef
import org.apache.fory.serializer.GraphMemoryEstimates

import scala.reflect.ClassTag

// Scala List is sealed to Nil and ::, so exact List codecs traverse nodes without
// runtime-family checks.
private[scala] final class ScalaListCodec(
    nonEmptyOnly: Boolean,
    nilOnly: Boolean,
    runtimeType: Boolean
)
    extends CompositeJsonCodec[List[Any]] {
  private var elementInfo: JsonTypeInfo = _

  override def resolveTypes(typeRef: TypeRef[_], resolver: JsonTypeResolver): Unit = {
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
    writer.writeArrayStart()
    var current = value
    var index = 0
    while (current ne Nil) {
      val node = current.asInstanceOf[scala.collection.immutable.::[Any]]
      writer.writeComma(index)
      codec.writeUtf8(writer, node.head)
      current = node.tail
      index += 1
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
        reserveBatch(reader, size)
        builder += codec.readLatin1(reader)
        size += 1
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
        reserveBatch(reader, size)
        builder += codec.readUtf16(reader)
        size += 1
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
        reserveBatch(reader, size)
        builder += codec.readUtf8(reader)
        size += 1
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

private[scala] final class ScalaIterableCodec(kind: Int, ownerBytes: Int, runtimeType: Boolean)
    extends CompositeJsonCodec[scala.collection.Iterable[Any]] {
  private val resultOwnerBytes =
    if (kind == ScalaCollectionCodecs.ListKind) 0 else ownerBytes
  private val retainedElementBytes =
    if (kind == ScalaCollectionCodecs.ListKind) ScalaCollectionCodecs.ListNodeBytes
    else ScalaCollectionCodecs.ReferenceBytes
  private var elementInfo: JsonTypeInfo = _
  private var elementClassTag: ClassTag[Any] = _

  override def resolveTypes(typeRef: TypeRef[_], resolver: JsonTypeResolver): Unit = {
    val arguments = ScalaTypeSupport.runtimeArguments(
      typeRef,
      1,
      "Scala collection",
      runtimeType
    )
    elementInfo = resolver.getTypeInfo(arguments(0), ScalaTypeSupport.rawType(arguments(0)))
    if (
      kind == ScalaCollectionCodecs.ImmutableArraySeqKind ||
      kind == ScalaCollectionCodecs.MutableArraySeqKind
    ) elementClassTag = ScalaTypeSupport.classTag(ScalaTypeSupport.rawType(arguments(0)))
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
    val codec = elementInfo.utf8Writer()
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

  override def readLatin1(reader: Latin1JsonReader): scala.collection.Iterable[Any] = {
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
        ScalaCollectionCodecs.reserveElements(reader, size, retainedElementBytes)
        builder += codec.readLatin1(reader)
        size += 1
        more = reader.consumeNextCommaOrEndArray()
      }
    }
    ScalaCollectionCodecs.reserveElementTail(reader, size, retainedElementBytes)
    val result = builder.result().asInstanceOf[scala.collection.Iterable[Any]]
    reader.exitDepth()
    result
  }

  override def readUtf16(reader: Utf16JsonReader): scala.collection.Iterable[Any] = {
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
        ScalaCollectionCodecs.reserveElements(reader, size, retainedElementBytes)
        builder += codec.readUtf16(reader)
        size += 1
        more = reader.consumeNextCommaOrEndArray()
      }
    }
    ScalaCollectionCodecs.reserveElementTail(reader, size, retainedElementBytes)
    val result = builder.result().asInstanceOf[scala.collection.Iterable[Any]]
    reader.exitDepth()
    result
  }

  override def readUtf8(reader: Utf8JsonReader): scala.collection.Iterable[Any] = {
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
        ScalaCollectionCodecs.reserveElements(reader, size, retainedElementBytes)
        builder += codec.readUtf8(reader)
        size += 1
        more = reader.consumeNextCommaOrEndArray()
      }
    }
    ScalaCollectionCodecs.reserveElementTail(reader, size, retainedElementBytes)
    val result = builder.result().asInstanceOf[scala.collection.Iterable[Any]]
    reader.exitDepth()
    result
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
      scala.collection.mutable.HashSet.newBuilder[Any]
    case ScalaCollectionCodecs.MutableLinkedHashSetKind =>
      scala.collection.mutable.LinkedHashSet.newBuilder[Any]
    case _ => List.newBuilder[Any]
  }
}

private[scala] final class ScalaMapCodec(kind: Int, ownerBytes: Int, runtimeType: Boolean)
    extends CompositeJsonCodec[scala.collection.Map[Any, Any]] {
  private var keyCodec: MapKeyCodec = _
  private var valueInfo: JsonTypeInfo = _

  override def resolveTypes(typeRef: TypeRef[_], resolver: JsonTypeResolver): Unit = {
    val specializedKey = ScalaCollectionCodecs.specializedMapKey(kind)
    val arguments = ScalaTypeSupport.runtimeArguments(
      typeRef,
      if (specializedKey == null) 2 else 1,
      "Scala Map",
      runtimeType
    )
    val keyType = if (specializedKey == null) arguments(0) else specializedKey
    val keyRawType = ScalaTypeSupport.rawType(keyType)
    val enumerationKeyCodec = ScalaEnumerationTypes.mapKeyCodec(keyType)
    keyCodec =
      if (enumerationKeyCodec == null) resolver.getMapKeyCodec(keyRawType)
      else enumerationKeyCodec
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
      else {
        val enumerationKeyCodec = ScalaEnumerationTypes.mapKeyCodec(keyType)
        if (enumerationKeyCodec == null) resolver.getMapKeyCodec(keyRawType)
        else enumerationKeyCodec
      }
    val valueType = arguments(if (specializedKey == null) 1 else 0)
    val valueRawType = ScalaTypeSupport.rawType(valueType)
    valueInfo =
      if (childCodecs.valueCodec() == classOf[JsonCodec.NoJsonValueCodec])
        resolver.getTypeInfo(valueType, valueRawType)
      else resolver.getTypeInfo(valueType, valueRawType, childCodecs.valueCodec())
  }

  override def writeString(writer: StringJsonWriter, value: scala.collection.Map[Any, Any]): Unit = {
    if (value == null) {
      writer.writeNull()
      return
    }
    ScalaCollectionCodecs.requireSupportedRuntime(value.getClass)
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

  override def readLatin1(reader: Latin1JsonReader): scala.collection.Map[Any, Any] = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('{')
    reader.reserveGraphMemory(ownerBytes)
    val builder = newBuilder()
    val codec = valueInfo.latin1Reader()
    var size = 0
    if (!reader.consumeNextToken('}')) {
      var more = true
      while (more) {
        ScalaCollectionCodecs.reserveMapEntries(reader, size)
        val key = keyCodec.readName(reader)
        reader.expectNextToken(':')
        builder += ((key, codec.readLatin1(reader)))
        size += 1
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
    val builder = newBuilder()
    val codec = valueInfo.utf16Reader()
    var size = 0
    if (!reader.consumeNextToken('}')) {
      var more = true
      while (more) {
        ScalaCollectionCodecs.reserveMapEntries(reader, size)
        val key = keyCodec.readName(reader)
        reader.expectNextToken(':')
        builder += ((key, codec.readUtf16(reader)))
        size += 1
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
    val builder = newBuilder()
    val codec = valueInfo.utf8Reader()
    var size = 0
    if (!reader.consumeNextToken('}')) {
      var more = true
      while (more) {
        ScalaCollectionCodecs.reserveMapEntries(reader, size)
        val key = keyCodec.readName(reader)
        reader.expectNextToken(':')
        builder += ((key, codec.readUtf8(reader)))
        size += 1
        more = reader.consumeNextCommaOrEndObject()
      }
    }
    finish(reader, builder, size)
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
      scala.collection.mutable.HashMap.newBuilder[Any, Any]
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
    add(scala.collection.immutable.ArraySeq.empty[Any])
    add(scala.collection.immutable.ArraySeq.empty[Int])
    add(scala.collection.mutable.ArrayBuffer.empty)
    add(scala.collection.mutable.ListBuffer.empty)
    add(scala.collection.mutable.ArraySeq.empty[Any])
    add(scala.collection.mutable.ArraySeq.empty[Int])
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
