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

@file:OptIn(ExperimentalUnsignedTypes::class)

package org.apache.fory.kotlin.xlang

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.apache.fory.BaseFory
import org.apache.fory.Fory
import org.apache.fory.annotation.ArrayType
import org.apache.fory.annotation.ForyCase
import org.apache.fory.annotation.ForyField
import org.apache.fory.annotation.ForyStruct
import org.apache.fory.annotation.ForyUnion
import org.apache.fory.annotation.ForyUnknownCase
import org.apache.fory.annotation.Ref
import org.apache.fory.exception.ForyException
import org.apache.fory.exception.SerializationException
import org.apache.fory.kotlin.Fixed
import org.apache.fory.kotlin.ForyKotlin
import org.apache.fory.kotlin.VarInt
import org.apache.fory.kotlin.register
import org.apache.fory.memory.MemoryUtils
import org.apache.fory.serializer.StaticGeneratedStructSerializer
import org.apache.fory.serializer.kotlin.KotlinSerializers
import org.apache.fory.type.BFloat16
import org.apache.fory.type.BFloat16Array
import org.apache.fory.type.Float16
import org.apache.fory.type.Float16Array
import org.apache.fory.type.Types
import org.apache.fory.type.union.UnknownCase

@ForyStruct
public data class KotlinUser
constructor(
  @ForyField(id = 1) val id: @Fixed UInt,
  @ForyField(id = 2) val name: String = "anonymous",
  @ForyField(id = 3) val score: @VarInt Long,
)

@ForyStruct
internal data class KotlinInternalUser
constructor(
  @ForyField(id = 1) val id: UInt,
  @ForyField(id = 2) val name: String = "internal",
)

@ForyStruct
public data class KotlinConcreteCollections
constructor(
  @ForyField(id = 1) val names: ArrayList<String>,
  @ForyField(id = 2) val values: java.util.LinkedList<Int>,
  @ForyField(id = 3) val tags: CopyOnWriteArrayList<Long>,
  @ForyField(id = 4) val counts: TreeMap<String, Int>,
  @ForyField(id = 5) val mutableNames: MutableList<String>,
  @ForyField(id = 6) val mutableTags: MutableSet<Int>,
  @ForyField(id = 7) val mutableCounts: MutableMap<String, Long>,
  @ForyField(id = 8) val sortedNames: TreeSet<String>,
  @ForyField(id = 9) val concurrentCounts: ConcurrentHashMap<String, Int>,
)

@ForyStruct
public data class KotlinUnsignedCollections
constructor(
  @ForyField(id = 1) val ids: List<UInt>,
  @ForyField(id = 2) val optionalIds: List<UInt?>,
  @ForyField(id = 3) val totals: Set<ULong>,
  @ForyField(id = 4) val byName: Map<String, UInt>,
  @ForyField(id = 5) val namesById: Map<UInt, String>,
)

@ForyStruct
public data class KotlinSchemaSurface
constructor(
  @ForyField(id = 1) val nullableNames: List<String?>?,
  @ForyField(id = 2) val dynamicList: List<*>,
  @ForyField(id = 3) val dynamicValues: Map<String, *>,
  @ForyField(id = 4) val bytesAsArray: @ArrayType ByteArray,
  @ForyField(id = 5) val bits: BooleanArray,
  @ForyField(id = 6) val unsignedLongs: ULongArray,
  @field:ForyField(id = 7) val fieldSiteId: Int,
  @ForyField(id = 8) val denseIds: @ArrayType List<Int>,
  @ForyField(id = 9) val noRefUser: KotlinUser?,
  @ForyField(id = 10) val noRefUsers: List<KotlinUser>,
  @ForyField(id = 11) val chunks: List<@ArrayType ByteArray>,
  @ForyField(id = 12) val chunksByName: Map<String, @ArrayType ByteArray>,
  @ForyField(id = 13) val nestedSortedNames: List<TreeSet<String>>,
)

@ForyStruct
public data class KotlinDenseArrays
constructor(
  @ForyField(id = 1) val ubytes: UByteArray,
  @ForyField(id = 2) val ushorts: UShortArray,
  @ForyField(id = 3) val uints: UIntArray,
  @ForyField(id = 4) val ulongs: ULongArray,
  @ForyField(id = 5) val ints: IntArray,
  @ForyField(id = 6) val longs: LongArray,
  @ForyField(id = 7) val bytes: ByteArray,
  @ForyField(id = 8) val shorts: ShortArray,
  @ForyField(id = 9) val floats: FloatArray,
  @ForyField(id = 10) val doubles: DoubleArray,
  @ForyField(id = 11) val booleans: BooleanArray,
  @ForyField(id = 12) val nullableUInts: UIntArray?,
)

@ForyStruct
public data class KotlinNullableCompatibleWriter
constructor(@ForyField(id = 1) val anchor: String)

@ForyStruct
public data class KotlinNullableCompatibleReader
constructor(
  @ForyField(id = 1) val anchor: String,
  @ForyField(id = 2) val maybeBoolean: Boolean?,
  @ForyField(id = 3) val maybeInt: Int?,
  @ForyField(id = 4) val maybeLong: Long?,
  @ForyField(id = 5) var maybeUInt: UInt?,
  @ForyField(id = 6) var maybeULong: ULong?,
)

@ForyStruct
public data class KotlinDefaultCompatibleWriter
constructor(@ForyField(id = 1) val id: Int)

@ForyStruct
public data class KotlinDefaultCompatibleReader
constructor(
  @ForyField(id = 1) val id: Int,
  @ForyField(id = 2) val name: String = "generated-default",
)

@ForyStruct
public data class KotlinDefaultRefWriter
constructor(
  @ForyField(id = 1) val id: Int,
  @Ref @ForyField(id = 2) var next: KotlinDefaultRefWriter?,
)

@ForyStruct
public data class KotlinDefaultRefReader
constructor(
  @ForyField(id = 1) val id: Int,
  @ForyField(id = 3) val name: String = "generated-default",
  @Ref @ForyField(id = 2) var next: KotlinDefaultRefReader?,
)

@ForyStruct
public data class KotlinDurationAndHalfArrays
constructor(
  @ForyField(id = 1) val duration: kotlin.time.Duration,
  @ForyField(id = 2) val date: LocalDate,
  @ForyField(id = 3) val instant: Instant,
  @ForyField(id = 4) val decimal: BigDecimal,
  @ForyField(id = 5) val float16: Float16,
  @ForyField(id = 6) val bfloat16: BFloat16,
  @ForyField(id = 7) val float16s: Float16Array,
  @ForyField(id = 8) val bfloat16s: BFloat16Array,
)

@ForyStruct
public class KotlinMutableNode() {
  @ForyField(id = 1) public var id: String = ""

  @Ref @ForyField(id = 2) public var parent: KotlinMutableNode? = null
}

@ForyStruct
public class KotlinCtorBackrefRoot
constructor(@ForyField(id = 1) val child: KotlinCtorBackrefChild)

@ForyStruct
public class KotlinCtorBackrefChild {
  @ForyField(id = 1) public var root: KotlinCtorBackrefRoot? = null
}

@ForyUnion
public sealed class KotlinPet {
  @ForyUnknownCase public data class Unknown(val value: UnknownCase) : KotlinPet()

  @ForyCase(id = 0) public data class User(val value: KotlinUser) : KotlinPet()

  @ForyCase(id = 1) public data class Name(val value: String) : KotlinPet()
}

public fun main(args: Array<String>) {
  if (args.size < 2) {
    throw IllegalArgumentException("Usage: <case> <data-file>")
  }
  when (args[0]) {
    "static_serializer_round_trip" -> staticSerializerRoundTrip(args[1])
    "compatible_default_round_trip" -> compatibleDefaultRoundTrip()
    "constructor_backref_copy" -> constructorBackrefCopy()
    "dense_array_round_trip" -> denseArrayRoundTrip(args[1])
    "unsigned_collection_round_trip" -> unsignedCollectionRoundTrip(args[1])
    else -> throw IllegalArgumentException("Unsupported Kotlin xlang case ${args[0]}")
  }
}

private fun staticSerializerRoundTrip(dataFile: String) {
  checkNoArgRegisterReceivers()

  val fory = newFory()
  fory.register<KotlinUser>("kotlin", "KotlinUser")
  fory.register<KotlinInternalUser>("kotlin", "KotlinInternalUser")
  fory.register<KotlinConcreteCollections>("kotlin", "KotlinConcreteCollections")
  fory.register<KotlinUnsignedCollections>("kotlin", "KotlinUnsignedCollections")
  fory.register<KotlinSchemaSurface>("kotlin", "KotlinSchemaSurface")
  fory.register<KotlinDenseArrays>("kotlin", "KotlinDenseArrays")
  fory.register<KotlinDurationAndHalfArrays>("kotlin", "KotlinDurationAndHalfArrays")
  KotlinSerializers.registerUnion(fory, KotlinPet::class.java, "kotlin", "KotlinPet")

  val javaRequest =
    fory.deserialize(MemoryUtils.wrap(java.io.File(dataFile).readBytes()), KotlinUser::class.java)
  check(
    javaRequest == KotlinUser(id = UInt.MAX_VALUE, name = "java-to-kotlin", score = -123456789L)
  )
  val response = KotlinUser(id = UInt.MAX_VALUE - 1u, name = "kotlin-to-java", score = 987654321L)
  java.io.File(dataFile).writeBytes(fory.serialize(response))

  val user = KotlinUser(id = UInt.MAX_VALUE, name = "fory", score = -123456789L)
  check(fory.deserialize(fory.serialize(user), KotlinUser::class.java) == user)
  val userSerializer = fory.getSerializer(KotlinUser::class.java)
  check(userSerializer is StaticGeneratedStructSerializer<*>) {
    "KotlinUser did not load a static generated serializer: ${userSerializer::class.java.name}"
  }
  val descriptors = userSerializer.generatedDescriptors
  check(descriptors.size == 3)
  check(descriptors[0].foryFieldId == 1)
  check(descriptors[0].typeRef.typeExtMeta.typeId() == Types.UINT32)
  check(descriptors[2].typeRef.typeExtMeta.typeId() == Types.VARINT64)

  val internalUser = KotlinInternalUser(id = UInt.MAX_VALUE, name = "internal-static")
  check(
    fory.deserialize(fory.serialize(internalUser), KotlinInternalUser::class.java) == internalUser
  )
  check(fory.getSerializer(KotlinInternalUser::class.java) is StaticGeneratedStructSerializer<*>) {
    "KotlinInternalUser did not load a static generated serializer"
  }

  val schemaSurface =
    KotlinSchemaSurface(
      nullableNames = listOf("first", null),
      dynamicList = emptyList<Any?>(),
      dynamicValues = emptyMap<String, Any?>(),
      bytesAsArray = byteArrayOf(1, 2),
      bits = booleanArrayOf(true, false),
      unsignedLongs = ulongArrayOf(1uL, ULong.MAX_VALUE),
      fieldSiteId = 7,
      denseIds = listOf(1, 2, 3),
      noRefUser = null,
      noRefUsers = emptyList(),
      chunks = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4)),
      chunksByName = mapOf("left" to byteArrayOf(5, 6)),
      nestedSortedNames = listOf(TreeSet(listOf("blue", "green"))),
    )
  val decodedSchemaSurface =
    fory.deserialize(fory.serialize(schemaSurface), KotlinSchemaSurface::class.java)
  check(decodedSchemaSurface.nullableNames == schemaSurface.nullableNames)
  check(decodedSchemaSurface.dynamicList == schemaSurface.dynamicList)
  check(decodedSchemaSurface.dynamicValues == schemaSurface.dynamicValues)
  check(decodedSchemaSurface.bytesAsArray contentEquals schemaSurface.bytesAsArray)
  check(decodedSchemaSurface.bits contentEquals schemaSurface.bits)
  check(decodedSchemaSurface.unsignedLongs contentEquals schemaSurface.unsignedLongs)
  check(decodedSchemaSurface.fieldSiteId == schemaSurface.fieldSiteId)
  check(decodedSchemaSurface.denseIds == schemaSurface.denseIds) {
    "denseIds round trip mismatch: expected=${schemaSurface.denseIds}, actual=${decodedSchemaSurface.denseIds}"
  }
  check(decodedSchemaSurface.chunks.size == schemaSurface.chunks.size)
  for (index in schemaSurface.chunks.indices) {
    check(decodedSchemaSurface.chunks[index] contentEquals schemaSurface.chunks[index])
  }
  check(
    checkNotNull(decodedSchemaSurface.chunksByName["left"]) contentEquals
      schemaSurface.chunksByName["left"]!!
  )
  check(decodedSchemaSurface.nestedSortedNames == schemaSurface.nestedSortedNames)
  check(decodedSchemaSurface.nestedSortedNames[0].javaClass == TreeSet::class.java)
  val copiedSchemaSurface = fory.copy(schemaSurface)
  check(copiedSchemaSurface.bytesAsArray !== schemaSurface.bytesAsArray)
  check(copiedSchemaSurface.chunks !== schemaSurface.chunks)
  check(copiedSchemaSurface.chunks[0] !== schemaSurface.chunks[0])
  check(copiedSchemaSurface.chunksByName !== schemaSurface.chunksByName)
  check(
    checkNotNull(copiedSchemaSurface.chunksByName["left"]) !== schemaSurface.chunksByName["left"]
  )
  check(copiedSchemaSurface.nestedSortedNames !== schemaSurface.nestedSortedNames)
  check(copiedSchemaSurface.nestedSortedNames[0] !== schemaSurface.nestedSortedNames[0])
  check(copiedSchemaSurface.nestedSortedNames[0].javaClass == TreeSet::class.java)
  schemaSurface.bytesAsArray[0] = 99.toByte()
  schemaSurface.chunks[0][0] = 98.toByte()
  checkNotNull(schemaSurface.chunksByName["left"])[0] = 97.toByte()
  check(copiedSchemaSurface.bytesAsArray[0] == 1.toByte())
  check(copiedSchemaSurface.chunks[0][0] == 1.toByte())
  check(checkNotNull(copiedSchemaSurface.chunksByName["left"])[0] == 5.toByte())
  val schemaDescriptors =
    checkNotNull(
        fory.getSerializer(KotlinSchemaSurface::class.java) as? StaticGeneratedStructSerializer<*>
      ) {
        "KotlinSchemaSurface did not load a static generated serializer"
      }
      .generatedDescriptors
  check(schemaDescriptors[3].isArrayType)
  check(schemaDescriptors[6].hasForyField())
  check(schemaDescriptors[6].foryFieldId == 7)
  check(schemaDescriptors[7].isArrayType)
  check(schemaDescriptors[7].typeRef.typeExtMeta.typeId() == Types.INT32_ARRAY)
  check(!schemaDescriptors[8].isTrackingRef)
  check(schemaDescriptors[9].typeRef.componentType.getTypeExtMeta()?.trackingRef() != true)
  check(schemaDescriptors[10].typeRef.typeArguments[0].typeExtMeta.typeId() == Types.INT8_ARRAY)
  check(schemaDescriptors[11].typeRef.typeArguments[1].typeExtMeta.typeId() == Types.INT8_ARRAY)

  val collections =
    KotlinConcreteCollections(
      names = arrayListOf("a", "b"),
      values = java.util.LinkedList(listOf(1, 2, 3)),
      tags = CopyOnWriteArrayList(listOf(4L, 5L)),
      counts = TreeMap(mapOf("x" to 7, "y" to 8)),
      mutableNames = mutableListOf("c", "d"),
      mutableTags = mutableSetOf(9, 10),
      mutableCounts = mutableMapOf("z" to 11L),
      sortedNames = TreeSet(listOf("e", "f")),
      concurrentCounts = ConcurrentHashMap(mapOf("q" to 12)),
    )
  val decoded = fory.deserialize(fory.serialize(collections), KotlinConcreteCollections::class.java)
  check(decoded == collections)
  check(decoded.names.javaClass == java.util.ArrayList::class.java)
  check(decoded.values.javaClass == java.util.LinkedList::class.java)
  check(decoded.tags.javaClass == CopyOnWriteArrayList::class.java)
  check(decoded.counts.javaClass == TreeMap::class.java)
  decoded.mutableNames.add("round-trip-mutable-list")
  decoded.mutableTags.add(13)
  decoded.mutableCounts["round-trip-mutable-map"] = 14L
  check(decoded.sortedNames.javaClass == TreeSet::class.java)
  check(decoded.concurrentCounts.javaClass == ConcurrentHashMap::class.java)
  val reverseCounts = TreeMap<String, Int>(java.util.Collections.reverseOrder<String>())
  reverseCounts.putAll(mapOf("x" to 7, "y" to 8))
  val reverseSortedNames = TreeSet<String>(java.util.Collections.reverseOrder<String>())
  reverseSortedNames.addAll(listOf("e", "f"))
  val copyCollections = collections.copy(counts = reverseCounts, sortedNames = reverseSortedNames)
  try {
    fory.serialize(copyCollections)
    error("Kotlin concrete sorted collections with custom comparators were serialized")
  } catch (e: SerializationException) {
    check(e.cause is UnsupportedOperationException)
  }
  val copiedCollections = fory.copy(copyCollections)
  check(copiedCollections == copyCollections)
  check(copiedCollections.counts !== copyCollections.counts)
  check(checkNotNull(copiedCollections.counts.comparator()).compare("a", "b") > 0)
  check(copiedCollections.sortedNames !== copyCollections.sortedNames)
  check(checkNotNull(copiedCollections.sortedNames.comparator()).compare("a", "b") > 0)

  val unsignedCollections =
    KotlinUnsignedCollections(
      ids = listOf(0u, UInt.MAX_VALUE),
      optionalIds = listOf(1u, null, UInt.MAX_VALUE),
      totals = linkedSetOf(0uL, ULong.MAX_VALUE),
      byName = linkedMapOf("min" to 0u, "max" to UInt.MAX_VALUE),
      namesById = linkedMapOf(0u to "min", UInt.MAX_VALUE to "max"),
    )
  val decodedUnsignedCollections =
    fory.deserialize(fory.serialize(unsignedCollections), KotlinUnsignedCollections::class.java)
  check(decodedUnsignedCollections == unsignedCollections)
  check(fory.copy(unsignedCollections) == unsignedCollections)

  val arrays =
    KotlinDenseArrays(
      ubytes = ubyteArrayOf(0u, 255u),
      ushorts = ushortArrayOf(0u, 65535u),
      uints = uintArrayOf(0u, UInt.MAX_VALUE),
      ulongs = ulongArrayOf(0u, ULong.MAX_VALUE),
      ints = intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE),
      longs = longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE),
      bytes = byteArrayOf(Byte.MIN_VALUE, Byte.MAX_VALUE),
      shorts = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE),
      floats = floatArrayOf(1.25f, -3.5f),
      doubles = doubleArrayOf(1.25, -3.5),
      booleans = booleanArrayOf(true, false),
      nullableUInts = uintArrayOf(42u, UInt.MAX_VALUE),
    )
  val decodedArrays = fory.deserialize(fory.serialize(arrays), KotlinDenseArrays::class.java)
  check(decodedArrays.ubytes contentEquals arrays.ubytes)
  check(decodedArrays.ushorts contentEquals arrays.ushorts)
  check(decodedArrays.uints contentEquals arrays.uints)
  check(decodedArrays.ulongs contentEquals arrays.ulongs)
  check(decodedArrays.ints contentEquals arrays.ints)
  check(decodedArrays.longs contentEquals arrays.longs)
  check(decodedArrays.bytes contentEquals arrays.bytes)
  check(decodedArrays.shorts contentEquals arrays.shorts)
  check(decodedArrays.floats contentEquals arrays.floats)
  check(decodedArrays.doubles contentEquals arrays.doubles)
  check(decodedArrays.booleans contentEquals arrays.booleans)
  check(decodedArrays.nullableUInts!!.contentEquals(arrays.nullableUInts!!))
  val arrayDescriptors =
    checkNotNull(
        fory.getSerializer(KotlinDenseArrays::class.java) as? StaticGeneratedStructSerializer<*>
      ) {
        "KotlinDenseArrays did not load a static generated serializer"
      }
      .generatedDescriptors
  check(arrayDescriptors[2].typeRef.componentType.typeExtMeta.typeId() == Types.UINT32)
  check(arrayDescriptors[3].typeRef.componentType.typeExtMeta.typeId() == Types.UINT64)
  check(arrayDescriptors[4].typeRef.componentType.typeExtMeta.typeId() == Types.INT32)
  check(arrayDescriptors[5].typeRef.componentType.typeExtMeta.typeId() == Types.INT64)

  val nullArrays = arrays.copy(nullableUInts = null)
  val decodedNullArrays =
    fory.deserialize(fory.serialize(nullArrays), KotlinDenseArrays::class.java)
  check(decodedNullArrays.nullableUInts == null)

  val writer = newCompatibleFory()
  writer.register<KotlinNullableCompatibleWriter>("kotlin", "NullableCompatible")
  val reader = newCompatibleFory()
  reader.register<KotlinNullableCompatibleReader>("kotlin", "NullableCompatible")
  val compatibleDecoded =
    reader.deserialize(
      writer.serialize(KotlinNullableCompatibleWriter("anchor")),
      KotlinNullableCompatibleReader::class.java,
    )
  check(compatibleDecoded.anchor == "anchor")
  check(compatibleDecoded.maybeBoolean == null)
  check(compatibleDecoded.maybeInt == null)
  check(compatibleDecoded.maybeLong == null)
  check(compatibleDecoded.maybeUInt == null)
  check(compatibleDecoded.maybeULong == null)

  compatibleDefaultRoundTrip()

  val durationAndHalfArrays =
    KotlinDurationAndHalfArrays(
      duration = (-500).milliseconds,
      date = LocalDate.of(2026, 6, 2),
      instant = Instant.parse("2026-06-02T04:00:00Z"),
      decimal = BigDecimal("12345.6789"),
      float16 = Float16.valueOf(1.5f),
      bfloat16 = BFloat16.valueOf(-2.5f),
      float16s = Float16Array.of(1.0f, -2.0f),
      bfloat16s = BFloat16Array.of(3.0f, -4.0f),
    )
  val decodedDurationAndHalfArrays =
    fory.deserialize(
      fory.serialize(durationAndHalfArrays),
      KotlinDurationAndHalfArrays::class.java,
    )
  check(decodedDurationAndHalfArrays.duration == durationAndHalfArrays.duration)
  check(decodedDurationAndHalfArrays.date == durationAndHalfArrays.date)
  check(decodedDurationAndHalfArrays.instant == durationAndHalfArrays.instant)
  check(decodedDurationAndHalfArrays.decimal == durationAndHalfArrays.decimal)
  check(decodedDurationAndHalfArrays.float16 == durationAndHalfArrays.float16)
  check(decodedDurationAndHalfArrays.bfloat16 == durationAndHalfArrays.bfloat16)
  check(decodedDurationAndHalfArrays.float16s == durationAndHalfArrays.float16s)
  check(decodedDurationAndHalfArrays.bfloat16s == durationAndHalfArrays.bfloat16s)
  val copiedDurationAndHalfArrays = fory.copy(durationAndHalfArrays)
  check(copiedDurationAndHalfArrays.date === durationAndHalfArrays.date)
  check(copiedDurationAndHalfArrays.instant === durationAndHalfArrays.instant)
  check(copiedDurationAndHalfArrays.decimal === durationAndHalfArrays.decimal)
  check(copiedDurationAndHalfArrays.float16 === durationAndHalfArrays.float16)
  check(copiedDurationAndHalfArrays.bfloat16 === durationAndHalfArrays.bfloat16)
  try {
    fory.serialize(durationAndHalfArrays.copy(duration = Duration.INFINITE))
    error("Infinite Kotlin xlang duration was serialized")
  } catch (_: ForyException) {}

  val refFory = newRefFory()
  refFory.register<KotlinMutableNode>("kotlin", "KotlinMutableNode")
  refFory.register<KotlinUser>("kotlin", "KotlinUser")
  KotlinSerializers.registerUnion(refFory, KotlinPet::class.java, "kotlin", "KotlinPet")
  val node = KotlinMutableNode()
  node.id = "root"
  node.parent = node
  val decodedNode = refFory.deserialize(refFory.serialize(node), KotlinMutableNode::class.java)
  check(decodedNode.parent === decodedNode)
  val copiedNode = refFory.copy(node)
  check(copiedNode.parent === copiedNode)
  check(
    refFory.getSerializer(KotlinMutableNode::class.java) is StaticGeneratedStructSerializer<*>
  ) {
    "KotlinMutableNode did not load a static generated serializer"
  }
  val unknownPayload = KotlinUser(id = 7u, name = "future", score = 11L)
  val unknownPet = KotlinPet.Unknown(UnknownCase(99, unknownPayload))
  val copiedUnknownPet = refFory.copy(unknownPet) as KotlinPet.Unknown
  check(copiedUnknownPet.value !== unknownPet.value)
  check(copiedUnknownPet.value.caseId() == 99)
  val copiedUnknownPayload = copiedUnknownPet.value.downcast(KotlinUser::class.java)
  check(copiedUnknownPayload == unknownPayload)
  check(copiedUnknownPayload !== unknownPayload)

  val pet: KotlinPet = KotlinPet.User(response)
  val decodedPet = fory.deserialize(fory.serialize(pet), KotlinPet::class.java)
  check(decodedPet == pet)
  check(fory.getSerializer(KotlinPet::class.java) is StaticGeneratedStructSerializer<*>) {
    "KotlinPet did not load a static generated union serializer"
  }
}

private fun constructorBackrefCopy() {
  val refFory =
    ForyKotlin.builder()
      .withXlang(false)
      .requireClassRegistration(true)
      .withRefTracking(true)
      .withRefCopy(true)
      .build()
  refFory.register<KotlinCtorBackrefChild>()
  refFory.register<KotlinCtorBackrefRoot>()
  checkConstructorBackrefCopy(refFory)
}

private fun checkConstructorBackrefCopy(fory: Fory) {
  val child = KotlinCtorBackrefChild()
  val root = KotlinCtorBackrefRoot(child)
  child.root = root
  try {
    fory.copy(root)
    error("Constructor back-reference was copied")
  } catch (_: ForyException) {}
}

private fun compatibleDefaultRoundTrip() {
  val writer = newCompatibleFory()
  writer.register<KotlinDefaultCompatibleWriter>("kotlin", "DefaultCompatible")
  val reader = newCompatibleFory()
  reader.register<KotlinDefaultCompatibleReader>("kotlin", "DefaultCompatible")
  val decoded =
    reader.deserialize(
      writer.serialize(KotlinDefaultCompatibleWriter(7)),
      KotlinDefaultCompatibleReader::class.java,
    )
  check(decoded == KotlinDefaultCompatibleReader(id = 7))

  val refWriter = newRefCompatibleFory()
  refWriter.register<KotlinDefaultRefWriter>("kotlin", "DefaultRef")
  val refReader = newRefCompatibleFory()
  refReader.register<KotlinDefaultRefReader>("kotlin", "DefaultRef")
  val node = KotlinDefaultRefWriter(id = 9, next = null)
  node.next = node
  try {
    refReader.deserialize(refWriter.serialize(node), KotlinDefaultRefReader::class.java)
    error("Constructor self-reference with a defaulted compatible field was deserialized")
  } catch (_: ForyException) {}
}

private fun checkNoArgRegisterReceivers() {
  checkNoArgRegister(newFory())
  checkNoArgRegister(
    ForyKotlin.builder()
      .withXlang(true)
      .requireClassRegistration(true)
      .withRefTracking(false)
      .buildThreadLocalFory()
  )
  checkNoArgRegister(
    ForyKotlin.builder()
      .withXlang(true)
      .requireClassRegistration(true)
      .withRefTracking(false)
      .buildThreadSafeForyPool(1)
  )
}

private fun checkNoArgRegister(fory: BaseFory) {
  fory.register<KotlinInternalUser>()
  val value = KotlinInternalUser(id = 7u, name = "receiver")
  check(fory.deserialize(fory.serialize(value), KotlinInternalUser::class.java) == value)
}

private fun denseArrayRoundTrip(dataFile: String) {
  val fory = newFory()
  fory.register<KotlinDenseArrays>("kotlin", "KotlinDenseArrays")
  val request =
    fory.deserialize(
      MemoryUtils.wrap(java.io.File(dataFile).readBytes()),
      KotlinDenseArrays::class.java
    )
  check(request.ubytes contentEquals ubyteArrayOf(1u, 255u))
  check(request.ushorts contentEquals ushortArrayOf(2u, 65535u))
  check(request.uints contentEquals uintArrayOf(3u, UInt.MAX_VALUE))
  check(request.ulongs contentEquals ulongArrayOf(4u, ULong.MAX_VALUE))
  check(request.ints contentEquals intArrayOf(-1, 1))
  check(request.longs contentEquals longArrayOf(-2L, 2L))
  check(request.bytes contentEquals byteArrayOf(5, 6))
  check(request.shorts contentEquals shortArrayOf(-7, 7))
  check(request.floats contentEquals floatArrayOf(1.5f, -1.5f))
  check(request.doubles contentEquals doubleArrayOf(2.5, -2.5))
  check(request.booleans contentEquals booleanArrayOf(true, false))
  check(request.nullableUInts!!.contentEquals(uintArrayOf(8u, UInt.MAX_VALUE)))
  val response =
    KotlinDenseArrays(
      ubytes = ubyteArrayOf(9u, 255u),
      ushorts = ushortArrayOf(10u, 65535u),
      uints = uintArrayOf(11u, UInt.MAX_VALUE),
      ulongs = ulongArrayOf(12u, ULong.MAX_VALUE),
      ints = intArrayOf(-13, 13),
      longs = longArrayOf(-14L, 14L),
      bytes = byteArrayOf(15, 16),
      shorts = shortArrayOf(-17, 17),
      floats = floatArrayOf(18.5f, -18.5f),
      doubles = doubleArrayOf(19.5, -19.5),
      booleans = booleanArrayOf(false, true),
      nullableUInts = null,
    )
  java.io.File(dataFile).writeBytes(fory.serialize(response))
}

private fun unsignedCollectionRoundTrip(dataFile: String) {
  val fory = newFory()
  fory.register<KotlinUnsignedCollections>("kotlin", "KotlinUnsignedCollections")
  val request =
    fory.deserialize(
      MemoryUtils.wrap(java.io.File(dataFile).readBytes()),
      KotlinUnsignedCollections::class.java,
    )
  check(request.ids == listOf(1u, UInt.MAX_VALUE))
  check(request.optionalIds == listOf(2u, null, UInt.MAX_VALUE))
  check(request.totals == linkedSetOf(3uL, ULong.MAX_VALUE))
  check(request.byName == linkedMapOf("a" to 4u, "max" to UInt.MAX_VALUE))
  check(request.namesById == linkedMapOf(5u to "five", UInt.MAX_VALUE to "max"))
  val response =
    KotlinUnsignedCollections(
      ids = listOf(6u, UInt.MAX_VALUE),
      optionalIds = listOf(7u, null, UInt.MAX_VALUE),
      totals = linkedSetOf(8uL, ULong.MAX_VALUE),
      byName = linkedMapOf("b" to 9u, "max" to UInt.MAX_VALUE),
      namesById = linkedMapOf(10u to "ten", UInt.MAX_VALUE to "max"),
    )
  java.io.File(dataFile).writeBytes(fory.serialize(response))
}

private fun newFory(): Fory =
  ForyKotlin.builder().withXlang(true).requireClassRegistration(true).withRefTracking(false).build()

private fun newCompatibleFory(): Fory =
  ForyKotlin.builder()
    .withXlang(true)
    .withCompatible(true)
    .requireClassRegistration(true)
    .withRefTracking(false)
    .build()

private fun newRefCompatibleFory(): Fory =
  ForyKotlin.builder()
    .withXlang(true)
    .withCompatible(true)
    .requireClassRegistration(true)
    .withRefTracking(true)
    .build()

private fun newRefFory(): Fory =
  ForyKotlin.builder()
    .withXlang(true)
    .requireClassRegistration(true)
    .withRefTracking(true)
    .withRefCopy(true)
    .build()
