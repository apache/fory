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

package org.apache.fory.serializer.scala

import org.apache.fory.Fory
import org.apache.fory.annotation.{
  ArrayType,
  ForyCase,
  ForyEnumId,
  ForyField,
  ForyStruct,
  ForyUnion,
  ForyUnknownCase,
  Int32Type,
  Int64Type,
  Ref,
  UInt16Type,
  UInt32Type,
  UInt64Type,
  UInt8Type
}
import org.apache.fory.annotation.ForyStruct.Evolution
import org.apache.fory.collection.{BFloat16List, Float16List, Int32List}
import org.apache.fory.config.{Int32Encoding, Int64Encoding, Language}
import org.apache.fory.context.{ReadContext, WriteContext}
import org.apache.fory.memory.{MemoryBuffer, MemoryUtils}
import org.apache.fory.meta.MetaCompressor
import org.apache.fory.resolver.TypeResolver
import org.apache.fory.scala.ForySerializer
import org.apache.fory.scala.ForyScala
import org.apache.fory.serializer.Serializer
import org.apache.fory.`type`.{BFloat16, Float16}
import org.apache.fory.`type`.union.{Union2, UnknownCase}
import org.apache.fory.util.MurmurHash3

import java.math.BigDecimal as JBigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate}
import java.util
import scala.jdk.CollectionConverters.*

enum Color {
  @ForyEnumId(0)
  case Green
  @ForyEnumId(1)
  case Red
  @ForyEnumId(2)
  case Blue
  @ForyEnumId(3)
  case White
}

enum TestEnum {
  @ForyEnumId(0)
  case VALUE_A
  @ForyEnumId(1)
  case VALUE_B
  @ForyEnumId(2)
  case VALUE_C
}

@ForyStruct
final case class Item(name: String) derives ForySerializer

@ForyStruct
final case class SimpleStruct(
    f1: util.HashMap[Integer, java.lang.Double],
    f2: Int,
    f3: Item,
    f4: String,
    f5: Color,
    f6: util.List[String],
    f7: Int,
    f8: Int,
    last: Int)
    derives ForySerializer

@ForyStruct(evolution = Evolution.ENABLED)
final case class EvolvingOverrideStruct(f1: String) derives ForySerializer

@ForyStruct(evolution = Evolution.DISABLED)
final case class FixedOverrideStruct(f1: String) derives ForySerializer

@ForyStruct
final case class Item1(f1: Int, f2: Int, f3: Int, f4: Int, f5: Int, f6: Int)
    derives ForySerializer

@ForyStruct
final case class StructWithUnion2(union: Union2[String, java.lang.Long]) derives ForySerializer

@ForyStruct
final case class StructWithList(items: util.List[String]) derives ForySerializer

@ForyStruct
final case class StructWithMap(data: util.Map[String, String]) derives ForySerializer

@ForyStruct
final case class NestedAnnotatedContainerSchemaConsistent(
    values: util.Map[
      Long @UInt32Type(encoding = Int32Encoding.FIXED),
      util.List[Long @UInt64Type(encoding = Int64Encoding.TAGGED)]])
    derives ForySerializer

@ForyStruct
final case class NestedAnnotatedContainerCompatible(
    values: util.Map[
      Long @UInt32Type(encoding = Int32Encoding.FIXED),
      util.List[Long @UInt64Type(encoding = Int64Encoding.TAGGED)]])
    derives ForySerializer

@ForyStruct
final case class MyStruct(id: Int) derives ForySerializer

final case class MyExt(id: Int)

final class MyExtSerializer(resolver: TypeResolver, cls: Class[MyExt])
    extends Serializer[MyExt](resolver.getConfig, cls) {
  override def write(writeContext: WriteContext, value: MyExt): Unit =
    writeContext.getBuffer.writeVarInt32(value.id)

  override def read(readContext: ReadContext): MyExt =
    MyExt(readContext.getBuffer.readVarInt32())
}

@ForyStruct
final case class EmptyWrapper() derives ForySerializer

@ForyStruct
final case class VersionCheckStruct(f1: Int, f2: Option[String], f3: Double)
    derives ForySerializer

trait Animal

@ForyStruct
final case class Dog(age: Int, name: Option[String]) extends Animal derives ForySerializer

@ForyStruct
final case class Cat(age: Int, lives: Int) extends Animal derives ForySerializer

@ForyStruct
final case class AnimalListHolder(animals: util.List[Animal]) derives ForySerializer

@ForyStruct
final case class AnimalMapHolder(animal_map: util.Map[String, Animal]) derives ForySerializer

@ForyStruct
final case class EmptyStruct() derives ForySerializer

@ForyStruct
final case class OneStringFieldStruct(f1: Option[String]) derives ForySerializer

@ForyStruct
final case class TwoStringFieldStruct(f1: String, f2: String) derives ForySerializer

@ForyStruct
final case class ReducedPrecisionFloatStruct(
    float16Value: Float16,
    bfloat16Value: BFloat16,
    float16Array: Float16List,
    bfloat16Array: BFloat16List)
    derives ForySerializer

@ForyStruct
final case class OneEnumFieldStruct(f1: TestEnum) derives ForySerializer

@ForyStruct
final case class TwoEnumFieldStruct(f1: TestEnum, f2: TestEnum) derives ForySerializer

@ForyStruct
final case class NullableComprehensiveSchemaConsistent(
    byteField: Byte,
    shortField: Short,
    intField: Int,
    longField: Long,
    floatField: Float,
    doubleField: Double,
    boolField: Boolean,
    stringField: String,
    listField: util.List[String],
    setField: util.Set[String],
    mapField: util.Map[String, String],
    nullableInt: Option[Int],
    nullableLong: Option[Long],
    nullableFloat: Option[Float],
    nullableDouble: Option[Double],
    nullableBool: Option[Boolean],
    nullableString: Option[String],
    nullableList: Option[util.List[String]],
    nullableSet: Option[util.Set[String]],
    nullableMap: Option[util.Map[String, String]])
    derives ForySerializer

@ForyStruct
final case class NullableComprehensiveCompatible(
    byteField: Byte,
    shortField: Short,
    intField: Int,
    longField: Long,
    floatField: Float,
    doubleField: Double,
    boolField: Boolean,
    boxedInt: Int,
    boxedLong: Long,
    boxedFloat: Float,
    boxedDouble: Double,
    boxedBool: Boolean,
    stringField: String,
    listField: util.List[String],
    setField: util.Set[String],
    mapField: util.Map[String, String],
    nullableInt1: Option[Int],
    nullableLong1: Option[Long],
    nullableFloat1: Option[Float],
    nullableDouble1: Option[Double],
    nullableBool1: Option[Boolean],
    nullableString2: Option[String],
    nullableList2: Option[util.List[String]],
    nullableSet2: Option[util.Set[String]],
    nullableMap2: Option[util.Map[String, String]])
    derives ForySerializer

@ForyStruct
final case class RefInnerSchemaConsistent(id: Int, name: String) derives ForySerializer

@ForyStruct
final case class RefOuterSchemaConsistent(
    @Ref inner1: Option[RefInnerSchemaConsistent],
    @Ref inner2: Option[RefInnerSchemaConsistent])
    derives ForySerializer

@ForyStruct
final case class RefInnerCompatible(id: Int, name: String) derives ForySerializer

@ForyStruct
final case class RefOuterCompatible(
    @Ref inner1: Option[RefInnerCompatible],
    @Ref inner2: Option[RefInnerCompatible])
    derives ForySerializer

@ForyStruct
final case class RefOverrideElement(id: Int, name: String) derives ForySerializer

@ForyStruct
final case class RefOverrideContainer(
    listField: util.List[RefOverrideElement @Ref],
    setField: util.Set[RefOverrideElement @Ref],
    mapField: util.Map[String, RefOverrideElement @Ref])
    derives ForySerializer

@ForyStruct
final class CircularRefStruct derives ForySerializer {
  var name: String = ""

  @Ref
  var selfRef: Option[CircularRefStruct] = None
}

@ForyStruct
final case class UnsignedSchemaConsistent(
    u8Field: Int @UInt8Type,
    u16Field: Int @UInt16Type,
    u32VarField: Long @UInt32Type,
    u32FixedField: Long @UInt32Type(encoding = Int32Encoding.FIXED),
    u64VarField: Long @UInt64Type(encoding = Int64Encoding.VARINT),
    u64FixedField: Long @UInt64Type(encoding = Int64Encoding.FIXED),
    u64TaggedField: Long @UInt64Type(encoding = Int64Encoding.TAGGED),
    u8NullableField: Option[Int @UInt8Type],
    u16NullableField: Option[Int @UInt16Type],
    u32VarNullableField: Option[Long @UInt32Type],
    u32FixedNullableField: Option[Long @UInt32Type(encoding = Int32Encoding.FIXED)],
    u64VarNullableField: Option[Long @UInt64Type(encoding = Int64Encoding.VARINT)],
    u64FixedNullableField: Option[Long @UInt64Type(encoding = Int64Encoding.FIXED)],
    u64TaggedNullableField: Option[Long @UInt64Type(encoding = Int64Encoding.TAGGED)])
    derives ForySerializer

@ForyStruct
final case class UnsignedSchemaConsistentSimple(
    u64Tagged: Long @UInt64Type(encoding = Int64Encoding.TAGGED),
    u64TaggedNullable: Option[Long @UInt64Type(encoding = Int64Encoding.TAGGED)])
    derives ForySerializer

@ForyStruct
final case class UnsignedSchemaCompatible(
    u8Field1: Int @UInt8Type,
    u16Field1: Int @UInt16Type,
    u32VarField1: Long @UInt32Type,
    u32FixedField1: Long @UInt32Type(encoding = Int32Encoding.FIXED),
    u64VarField1: Long @UInt64Type(encoding = Int64Encoding.VARINT),
    u64FixedField1: Long @UInt64Type(encoding = Int64Encoding.FIXED),
    u64TaggedField1: Long @UInt64Type(encoding = Int64Encoding.TAGGED),
    u8Field2: Option[Int @UInt8Type],
    u16Field2: Option[Int @UInt16Type],
    u32VarField2: Option[Long @UInt32Type],
    u32FixedField2: Option[Long @UInt32Type(encoding = Int32Encoding.FIXED)],
    u64VarField2: Option[Long @UInt64Type(encoding = Int64Encoding.VARINT)],
    u64FixedField2: Option[Long @UInt64Type(encoding = Int64Encoding.FIXED)],
    u64TaggedField2: Option[Long @UInt64Type(encoding = Int64Encoding.TAGGED)])
    derives ForySerializer

@ForyStruct
final case class XlangCompatibleInt32ListField(
    @ForyField(id = 1)
    values: Int32List @Int32Type(encoding = Int32Encoding.FIXED))
    derives ForySerializer

@ForyStruct
final case class XlangCompatibleNullableInt32ListField(
    @ForyField(id = 1) values: util.List[Integer])
    derives ForySerializer

@ForyStruct
final case class XlangCompatibleInt32ArrayField(@ForyField(id = 1) values: Array[Int])
    derives ForySerializer

@ForyStruct
final case class ManualSchemaKindStruct(
    orderedValues: util.List[Integer],
    denseValues: util.List[Integer] @ArrayType,
    primitiveValues: Array[Int],
    payload: Array[Byte],
    signedBytes: Array[Byte],
    unsignedBytes: Array[Byte] @UInt8Type)
    derives ForySerializer

@ForyStruct
final case class ScalaPeerUser(
    @ForyField(id = 1) id: Int,
    @ForyField(id = 2) name: String,
    @ForyField(id = 3) email: Option[String])
    derives ForySerializer

@ForyUnion
enum ScalaPeerTarget derives ForySerializer {
  @ForyUnknownCase
  case Unknown(value: UnknownCase)

  @ForyCase(id = 0)
  case User(value: ScalaPeerUser)
}

object ScalaXlangPeer {
  private val ScalaPeerNamespace = "scala_peer"

  def main(args: Array[String]): Unit = {
    require(args.length == 2, "Usage: ScalaXlangPeer <caseName> <dataFile>")
    val dataFile = Path.of(args(1))
    args(0) match {
      case "test_buffer" => testBuffer(dataFile)
      case "test_buffer_var" => testBufferVar(dataFile)
      case "test_murmurhash3" => testMurmurHash3(dataFile)
      case "test_string_serializer" => roundTripValues(dataFile, newFory())
      case "test_cross_language_serializer" => roundTripValues(dataFile, crossLanguageFory())
      case "test_simple_struct" => roundTripValues(dataFile, simpleStructFory(false))
      case "test_named_simple_struct" => roundTripValues(dataFile, simpleStructFory(true))
      case "test_struct_evolving_override" => roundTripValues(dataFile, evolvingOverrideFory())
      case "test_list" | "test_map" | "test_item" => roundTripValues(dataFile, itemFory())
      case "test_integer" => roundTripValues(dataFile, integerFory())
      case "test_decimal" => roundTripValues(dataFile, newFory())
      case "test_color" => roundTripValues(dataFile, colorFory())
      case "test_union_xlang" => roundTripValues(dataFile, structWithUnionFory())
      case "test_struct_with_list" => roundTripValues(dataFile, structWithListFory())
      case "test_struct_with_map" => roundTripValues(dataFile, structWithMapFory())
      case "test_nested_annotated_container_schema_consistent" =>
        roundTripValues(dataFile, nestedAnnotatedSchemaFory())
      case "test_nested_annotated_container_compatible" =>
        roundTripValues(dataFile, nestedAnnotatedCompatibleFory())
      case "test_skip_id_custom" => roundTripValues(dataFile, emptyWrapperFory(Left(104)))
      case "test_skip_name_custom" => roundTripValues(dataFile, emptyWrapperFory(Right(("", "my_wrapper"))))
      case "test_consistent_named" => roundTripValues(dataFile, consistentNamedFory())
      case "test_struct_version_check" => roundTripValues(dataFile, versionCheckFory())
      case "test_polymorphic_list" => roundTripValues(dataFile, polymorphicListFory())
      case "test_polymorphic_map" => roundTripValues(dataFile, polymorphicMapFory())
      case "test_one_string_field_schema" => roundTripValues(dataFile, oneStringSchemaFory())
      case "test_one_string_field_compatible" => roundTripValues(dataFile, oneStringCompatibleFory())
      case "test_two_string_field_compatible" => roundTripValues(dataFile, twoStringCompatibleFory(201))
      case "test_schema_evolution_compatible" => schemaEvolutionToEmpty(dataFile)
      case "test_schema_evolution_compatible_reverse" => schemaEvolutionToTwoString(dataFile)
      case "test_reduced_precision_float_struct" =>
        roundTripValues(dataFile, reducedPrecisionFory(false))
      case "test_reduced_precision_float_struct_compatible_skip" =>
        reducedPrecisionToEmpty(dataFile)
      case "test_one_enum_field_schema" => roundTripValues(dataFile, oneEnumSchemaFory())
      case "test_one_enum_field_compatible" => roundTripValues(dataFile, oneEnumCompatibleFory())
      case "test_two_enum_field_compatible" => roundTripValues(dataFile, twoEnumCompatibleFory(212))
      case "test_enum_schema_evolution_compatible" => enumEvolutionToEmpty(dataFile)
      case "test_enum_schema_evolution_compatible_reverse" => enumEvolutionToTwoEnum(dataFile)
      case "test_nullable_field_schema_consistent_not_null" |
          "test_nullable_field_schema_consistent_null" =>
        roundTripValues(dataFile, nullableSchemaFory())
      case "test_nullable_field_compatible_not_null" =>
        roundTripValues(dataFile, nullableCompatibleFory())
      case "test_nullable_field_compatible_null" => nullableCompatibleNull(dataFile)
      case "test_ref_schema_consistent" => roundTripValues(dataFile, refSchemaFory())
      case "test_ref_compatible" => roundTripValues(dataFile, refCompatibleFory())
      case "test_collection_element_ref_override" => collectionElementRefOverride(dataFile)
      case "test_collection_element_ref_remote_tracking" => collectionElementRefRemoteTracking(dataFile)
      case "test_circular_ref_schema_consistent" => roundTripValues(dataFile, circularRefFory(601, false))
      case "test_circular_ref_compatible" => roundTripValues(dataFile, circularRefFory(602, true))
      case "test_unsigned_schema_consistent_simple" =>
        roundTripValues(dataFile, unsignedSimpleFory())
      case "test_unsigned_schema_consistent" => roundTripValues(dataFile, unsignedSchemaFory())
      case "test_unsigned_schema_compatible" => roundTripValues(dataFile, unsignedCompatibleFory())
      case "test_list_array_compatible_list_to_array" => listArrayListToArray(dataFile)
      case "test_list_array_compatible_array_to_list" => listArrayArrayToList(dataFile)
      case "test_list_array_compatible_nullable_list_to_array_error" =>
        roundTripValues(dataFile, nullableInt32ListFory())
      case "derived_struct_round_trip" => roundTripUser(dataFile)
      case "known_union_case_round_trip" => roundTripTarget(dataFile, preserveUnknown = false)
      case "unknown_union_case_round_trip" => roundTripTarget(dataFile, preserveUnknown = true)
      case other => throw new IllegalArgumentException(s"Unknown Scala xlang peer case: $other")
    }
  }

  private object NoOpMetaCompressor extends MetaCompressor {
    override def compress(data: Array[Byte], offset: Int, size: Int): Array[Byte] = {
      val result = new Array[Byte](size + 1)
      System.arraycopy(data, offset, result, 0, size)
      result
    }

    override def decompress(data: Array[Byte], offset: Int, size: Int): Array[Byte] = {
      val result = new Array[Byte](size)
      System.arraycopy(data, offset, result, 0, size)
      result
    }
  }

  private def newFory(
      compatible: Boolean = true,
      refTracking: Boolean = false,
      classVersionCheck: Boolean = false,
      noCompression: Boolean = false): Fory = {
    val builder = ForyScala.builder()
      .withLanguage(Language.XLANG)
      .withCompatible(compatible)
      .withRefTracking(refTracking)
      .requireClassRegistration(false)
    if classVersionCheck then builder.withClassVersionCheck(true)
    if noCompression then builder.withMetaCompressor(NoOpMetaCompressor)
    val fory = builder.build()
    fory
  }

  private def registerStruct[T](fory: Fory, cls: Class[T], id: Long)(using ForySerializer[T]): Unit =
    ForySerializer.register(fory, cls, id)

  private def registerStruct[T](
      fory: Fory,
      cls: Class[T],
      namespace: String,
      typeName: String)(using ForySerializer[T]): Unit =
    ForySerializer.register(fory, cls, namespace, typeName)

  private def roundTripValues(dataFile: Path, fory: Fory): Unit = {
    val bytes = Files.readAllBytes(dataFile)
    val input = MemoryUtils.wrap(bytes)
    val output = MemoryBuffer.newHeapBuffer(Math.max(256, bytes.length * 2 + 64))
    while input.readerIndex() < bytes.length do {
      val value = fory.deserialize(input)
      fory.serialize(output, value)
    }
    writeBuffer(dataFile, output)
  }

  private def readOne[T](dataFile: Path, fory: Fory): T =
    fory.deserialize(Files.readAllBytes(dataFile)).asInstanceOf[T]

  private def writeOne(dataFile: Path, fory: Fory, value: Any): Unit =
    Files.write(dataFile, fory.serialize(value))

  private def writeBuffer(dataFile: Path, buffer: MemoryBuffer): Unit =
    Files.write(dataFile, buffer.getBytes(0, buffer.writerIndex()))

  private def testBuffer(dataFile: Path): Unit = {
    val input = MemoryUtils.wrap(Files.readAllBytes(dataFile))
    val boolValue = input.readBoolean()
    val byteValue = input.readByte()
    val shortValue = input.readInt16()
    val intValue = input.readInt32()
    val longValue = input.readInt64()
    val floatValue = input.readFloat32()
    val doubleValue = input.readFloat64()
    val varUIntValue = input.readVarUInt32()
    val bytes = input.readBytes(input.readInt32())

    val output = MemoryUtils.buffer(32)
    output.writeBoolean(boolValue)
    output.writeByte(byteValue)
    output.writeInt16(shortValue)
    output.writeInt32(intValue)
    output.writeInt64(longValue)
    output.writeFloat32(floatValue)
    output.writeFloat64(doubleValue)
    output.writeVarUInt32(varUIntValue)
    output.writeInt32(bytes.length)
    output.writeBytes(bytes)
    writeBuffer(dataFile, output)
  }

  private def testBufferVar(dataFile: Path): Unit = {
    val input = MemoryUtils.wrap(Files.readAllBytes(dataFile))
    val output = MemoryUtils.buffer(256)
    var i = 0
    while i < 18 do {
      output.writeVarInt32(input.readVarInt32())
      i += 1
    }
    i = 0
    while i < 12 do {
      output.writeVarUInt32(input.readVarUInt32())
      i += 1
    }
    i = 0
    while i < 19 do {
      output.writeVarUInt64(input.readVarUInt64())
      i += 1
    }
    i = 0
    while i < 15 do {
      output.writeVarInt64(input.readVarInt64())
      i += 1
    }
    writeBuffer(dataFile, output)
  }

  private def testMurmurHash3(dataFile: Path): Unit = {
    val bytes = Files.readAllBytes(dataFile)
    if bytes.length == 16 then {
      val expected = MurmurHash3.murmurhash3_x64_128(Array[Byte](1, 2, 8), 0, 3, 47)
      val buffer = MemoryUtils.wrap(bytes)
      require(buffer.readInt64() == expected(0), "Unexpected MurmurHash3 first word")
      require(buffer.readInt64() == expected(1), "Unexpected MurmurHash3 second word")
    }
    Files.write(dataFile, bytes)
  }

  private def crossLanguageFory(): Fory = {
    val fory = newFory()
    ScalaSerializers.registerEnum(fory, classOf[Color], 101L)
    fory
  }

  private def colorFory(): Fory = crossLanguageFory()

  private def simpleStructFory(named: Boolean): Fory = {
    val fory = newFory()
    if named then {
      ScalaSerializers.registerEnum(fory, classOf[Color], "demo", "color")
      registerStruct(fory, classOf[Item], "demo", "item")
      registerStruct(fory, classOf[SimpleStruct], "demo", "simple_struct")
    } else {
      ScalaSerializers.registerEnum(fory, classOf[Color], 101L)
      registerStruct(fory, classOf[Item], 102L)
      registerStruct(fory, classOf[SimpleStruct], 103L)
    }
    fory
  }

  private def evolvingOverrideFory(): Fory = {
    val fory = newFory()
    registerStruct(fory, classOf[EvolvingOverrideStruct], "test", "evolving_yes")
    registerStruct(fory, classOf[FixedOverrideStruct], "test", "evolving_off")
    fory
  }

  private def itemFory(): Fory = {
    val fory = newFory()
    registerStruct(fory, classOf[Item], 102L)
    fory
  }

  private def integerFory(): Fory = {
    val fory = newFory()
    registerStruct(fory, classOf[Item1], 101L)
    fory
  }

  private def structWithUnionFory(): Fory = {
    val fory = newFory()
    registerStruct(fory, classOf[StructWithUnion2], 301L)
    fory
  }

  private def structWithListFory(): Fory = {
    val fory = newFory()
    registerStruct(fory, classOf[StructWithList], 201L)
    fory
  }

  private def structWithMapFory(): Fory = {
    val fory = newFory()
    registerStruct(fory, classOf[StructWithMap], 202L)
    fory
  }

  private def nestedAnnotatedSchemaFory(): Fory = {
    val fory = newFory(compatible = false)
    registerStruct(fory, classOf[NestedAnnotatedContainerSchemaConsistent], 801L)
    fory
  }

  private def nestedAnnotatedCompatibleFory(): Fory = {
    val fory = newFory(compatible = true, noCompression = true)
    registerStruct(fory, classOf[NestedAnnotatedContainerCompatible], 802L)
    fory
  }

  private def emptyWrapperFory(registration: Either[Long, (String, String)]): Fory = {
    val fory = newFory()
    registration match {
      case Left(id) =>
        fory.register(classOf[MyExt], 103)
        fory.registerSerializer(classOf[MyExt], classOf[MyExtSerializer])
        registerStruct(fory, classOf[EmptyWrapper], id)
      case Right((namespace, typeName)) =>
        fory.register(classOf[MyExt], "my_ext")
        fory.registerSerializer(classOf[MyExt], classOf[MyExtSerializer])
        registerStruct(fory, classOf[EmptyWrapper], namespace, typeName)
    }
    fory
  }

  private def consistentNamedFory(): Fory = {
    val fory = newFory(compatible = false, classVersionCheck = true)
    ScalaSerializers.registerEnum(fory, classOf[Color], "", "color")
    registerStruct(fory, classOf[MyStruct], "", "my_struct")
    fory.register(classOf[MyExt], "my_ext")
    fory.registerSerializer(classOf[MyExt], classOf[MyExtSerializer])
    fory
  }

  private def versionCheckFory(): Fory = {
    val fory = newFory(compatible = false, classVersionCheck = true)
    registerStruct(fory, classOf[VersionCheckStruct], 201L)
    fory
  }

  private def polymorphicListFory(): Fory = {
    val fory = newFory()
    registerStruct(fory, classOf[Dog], 302L)
    registerStruct(fory, classOf[Cat], 303L)
    registerStruct(fory, classOf[AnimalListHolder], 304L)
    fory
  }

  private def polymorphicMapFory(): Fory = {
    val fory = newFory()
    registerStruct(fory, classOf[Dog], 302L)
    registerStruct(fory, classOf[Cat], 303L)
    registerStruct(fory, classOf[AnimalMapHolder], 305L)
    fory
  }

  private def oneStringSchemaFory(): Fory = {
    val fory = newFory(compatible = false)
    registerStruct(fory, classOf[OneStringFieldStruct], 200L)
    fory
  }

  private def oneStringCompatibleFory(): Fory = {
    val fory = newFory()
    registerStruct(fory, classOf[OneStringFieldStruct], 200L)
    fory
  }

  private def twoStringCompatibleFory(id: Long): Fory = {
    val fory = newFory()
    registerStruct(fory, classOf[TwoStringFieldStruct], id)
    fory
  }

  private def schemaEvolutionToEmpty(dataFile: Path): Unit = {
    val inFory = twoStringCompatibleFory(200L)
    val outFory = newFory()
    registerStruct(outFory, classOf[EmptyStruct], 200L)
    readOne[TwoStringFieldStruct](dataFile, inFory)
    writeOne(dataFile, outFory, EmptyStruct())
  }

  private def schemaEvolutionToTwoString(dataFile: Path): Unit = {
    val inFory = oneStringCompatibleFory()
    val outFory = twoStringCompatibleFory(200L)
    val value = readOne[OneStringFieldStruct](dataFile, inFory)
    writeOne(dataFile, outFory, TwoStringFieldStruct(value.f1.orNull, ""))
  }

  private def reducedPrecisionFory(compatible: Boolean): Fory = {
    val fory = newFory(compatible = compatible, noCompression = compatible)
    registerStruct(fory, classOf[ReducedPrecisionFloatStruct], 213L)
    fory
  }

  private def reducedPrecisionToEmpty(dataFile: Path): Unit = {
    val inFory = reducedPrecisionFory(compatible = true)
    val outFory = newFory(compatible = true, noCompression = true)
    registerStruct(outFory, classOf[EmptyStruct], 213L)
    readOne[ReducedPrecisionFloatStruct](dataFile, inFory)
    writeOne(dataFile, outFory, EmptyStruct())
  }

  private def oneEnumSchemaFory(): Fory = {
    val fory = newFory(compatible = false)
    ScalaSerializers.registerEnum(fory, classOf[TestEnum], 210L)
    registerStruct(fory, classOf[OneEnumFieldStruct], 211L)
    fory
  }

  private def oneEnumCompatibleFory(): Fory = {
    val fory = newFory()
    ScalaSerializers.registerEnum(fory, classOf[TestEnum], 210L)
    registerStruct(fory, classOf[OneEnumFieldStruct], 211L)
    fory
  }

  private def twoEnumCompatibleFory(id: Long): Fory = {
    val fory = newFory()
    ScalaSerializers.registerEnum(fory, classOf[TestEnum], 210L)
    registerStruct(fory, classOf[TwoEnumFieldStruct], id)
    fory
  }

  private def enumEvolutionToEmpty(dataFile: Path): Unit = {
    val inFory = twoEnumCompatibleFory(211L)
    val outFory = newFory()
    ScalaSerializers.registerEnum(outFory, classOf[TestEnum], 210L)
    registerStruct(outFory, classOf[EmptyStruct], 211L)
    readOne[TwoEnumFieldStruct](dataFile, inFory)
    writeOne(dataFile, outFory, EmptyStruct())
  }

  private def enumEvolutionToTwoEnum(dataFile: Path): Unit = {
    val inFory = oneEnumCompatibleFory()
    val outFory = twoEnumCompatibleFory(211L)
    val value = readOne[OneEnumFieldStruct](dataFile, inFory)
    writeOne(dataFile, outFory, TwoEnumFieldStruct(value.f1, TestEnum.VALUE_A))
  }

  private def nullableSchemaFory(): Fory = {
    val fory = newFory(compatible = false)
    registerStruct(fory, classOf[NullableComprehensiveSchemaConsistent], 401L)
    fory
  }

  private def nullableCompatibleFory(): Fory = {
    val fory = newFory(compatible = true, noCompression = true)
    registerStruct(fory, classOf[NullableComprehensiveCompatible], 402L)
    fory
  }

  private def nullableCompatibleNull(dataFile: Path): Unit = {
    val fory = nullableCompatibleFory()
    val value = readOne[NullableComprehensiveCompatible](dataFile, fory)
    val defaults = value.copy(
      nullableInt1 = Some(0),
      nullableLong1 = Some(0L),
      nullableFloat1 = Some(0.0f),
      nullableDouble1 = Some(0.0d),
      nullableBool1 = Some(false),
      nullableString2 = Some(""),
      nullableList2 = Some(new util.ArrayList[String]()),
      nullableSet2 = Some(new util.HashSet[String]()),
      nullableMap2 = Some(new util.HashMap[String, String]()))
    writeOne(dataFile, fory, defaults)
  }

  private def refSchemaFory(): Fory = {
    val fory = newFory(compatible = false, refTracking = true)
    registerStruct(fory, classOf[RefInnerSchemaConsistent], 501L)
    registerStruct(fory, classOf[RefOuterSchemaConsistent], 502L)
    fory
  }

  private def refCompatibleFory(): Fory = {
    val fory = newFory(compatible = true, refTracking = true, noCompression = true)
    registerStruct(fory, classOf[RefInnerCompatible], 503L)
    registerStruct(fory, classOf[RefOuterCompatible], 504L)
    fory
  }

  private def refOverrideFory(): Fory = {
    val fory = newFory(compatible = false, refTracking = true)
    registerStruct(fory, classOf[RefOverrideElement], 701L)
    registerStruct(fory, classOf[RefOverrideContainer], 702L)
    fory
  }

  private def sharedRefOverrideContainer(): RefOverrideContainer = {
    val element = RefOverrideElement(7, "shared_element")
    val list = new util.ArrayList[RefOverrideElement]()
    list.add(element)
    list.add(element)
    val set = new util.HashSet[RefOverrideElement]()
    set.add(element)
    val map = new util.HashMap[String, RefOverrideElement]()
    map.put("k1", element)
    map.put("k2", element)
    RefOverrideContainer(list, set, map)
  }

  private def collectionElementRefOverride(dataFile: Path): Unit = {
    val fory = refOverrideFory()
    readOne[RefOverrideContainer](dataFile, fory)
    writeOne(dataFile, fory, sharedRefOverrideContainer())
  }

  private def collectionElementRefRemoteTracking(dataFile: Path): Unit =
    writeOne(dataFile, refOverrideFory(), sharedRefOverrideContainer())

  private def circularRefFory(id: Long, compatible: Boolean): Fory = {
    val fory = newFory(compatible = compatible, refTracking = true, noCompression = compatible)
    registerStruct(fory, classOf[CircularRefStruct], id)
    fory
  }

  private def unsignedSimpleFory(): Fory = {
    val fory = newFory(compatible = false)
    registerStruct(fory, classOf[UnsignedSchemaConsistentSimple], 1L)
    fory
  }

  private def unsignedSchemaFory(): Fory = {
    val fory = newFory(compatible = false)
    registerStruct(fory, classOf[UnsignedSchemaConsistent], 501L)
    fory
  }

  private def unsignedCompatibleFory(): Fory = {
    val fory = newFory(compatible = true, noCompression = true)
    registerStruct(fory, classOf[UnsignedSchemaCompatible], 502L)
    fory
  }

  private def int32ListFory(): Fory = {
    val fory = newFory(compatible = true)
    registerStruct(fory, classOf[XlangCompatibleInt32ListField], 901L)
    fory
  }

  private def nullableInt32ListFory(): Fory = {
    val fory = newFory(compatible = true)
    registerStruct(fory, classOf[XlangCompatibleNullableInt32ListField], 901L)
    fory
  }

  private def int32ArrayFory(): Fory = {
    val fory = newFory(compatible = true)
    registerStruct(fory, classOf[XlangCompatibleInt32ArrayField], 901L)
    fory
  }

  private def listArrayListToArray(dataFile: Path): Unit = {
    val value = readOne[XlangCompatibleInt32ListField](dataFile, int32ListFory())
    val values = new Array[Int](value.values.size())
    var i = 0
    while i < value.values.size() do {
      values(i) = value.values.get(i)
      i += 1
    }
    writeOne(dataFile, int32ArrayFory(), XlangCompatibleInt32ArrayField(values))
  }

  private def listArrayArrayToList(dataFile: Path): Unit = {
    val value = readOne[XlangCompatibleInt32ArrayField](dataFile, int32ArrayFory())
    writeOne(dataFile, int32ListFory(), XlangCompatibleInt32ListField(new Int32List(value.values)))
  }

  private def roundTripUser(dataFile: Path): Unit = {
    val fory = scalaPeerFory()
    val request = readOne[ScalaPeerUser](dataFile, fory)
    writeOne(dataFile, fory, request.copy(id = request.id + 1, name = "scala-" + request.name, email = None))
  }

  private def roundTripTarget(dataFile: Path, preserveUnknown: Boolean): Unit = {
    val fory = scalaPeerFory()
    val request = readOne[ScalaPeerTarget](dataFile, fory)
    val response = request match {
      case ScalaPeerTarget.User(user) =>
        ScalaPeerTarget.User(user.copy(id = user.id + 1, name = "scala-" + user.name, email = None))
      case ScalaPeerTarget.Unknown(unknown)
          if preserveUnknown && unknown.value().isInstanceOf[ScalaPeerUser] =>
        val value = unknown.downcast(classOf[ScalaPeerUser])
        ScalaPeerTarget.Unknown(
          new UnknownCase(
            unknown.caseId(),
            value.copy(id = value.id + 1, name = "scala-" + value.name, email = None)))
      case ScalaPeerTarget.Unknown(unknown) =>
        ScalaPeerTarget.Unknown(unknown)
    }
    writeOne(dataFile, fory, response)
  }

  private def scalaPeerFory(): Fory = {
    val fory = ForyScala.builder()
      .withLanguage(Language.XLANG)
      .withCompatible(true)
      .requireClassRegistration(true)
      .build()
    ForySerializer.register(fory, classOf[ScalaPeerUser], ScalaPeerNamespace, "ScalaPeerUser")
    ForySerializer.register(fory, classOf[ScalaPeerTarget], ScalaPeerNamespace, "ScalaPeerTarget")
    fory
  }
}
