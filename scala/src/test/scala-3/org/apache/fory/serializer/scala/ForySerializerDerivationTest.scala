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
  ForyCase,
  ForyField,
  ForyStruct,
  ForyUnion,
  ForyUnknownCase,
  Ref,
  UInt64Type,
  UInt8Type
}
import org.apache.fory.config.Int64Encoding
import org.apache.fory.memory.MemoryBuffer
import org.apache.fory.meta.TypeDef
import org.apache.fory.scala.ForySerializer
import org.apache.fory.scala.ForyScala
import org.apache.fory.scala.register
import org.apache.fory.serializer.StaticGeneratedStructSerializer
import org.apache.fory.`type`.{Types, TypeUtils}
import org.apache.fory.`type`.union.UnknownCase
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.compiletime.testing.typeCheckErrors
import scala.jdk.CollectionConverters._

object ForySerializerDerivationTest {
  @ForyStruct
  final case class Person(
      @ForyField(id = 1) name: String,
      @ForyField(id = 2) age: Int,
      @ForyField(id = 3) email: Option[String])
      derives ForySerializer

  @ForyStruct
  final case class SearchUser(@ForyField(id = 1) name: String) derives ForySerializer

  @ForyStruct
  final case class CollectionBox(
      @ForyField(id = 1) names: List[String],
      @ForyField(id = 2) tags: Set[String],
      @ForyField(id = 3) scores: Map[String, Int])
      derives ForySerializer

  @ForyStruct
  final case class OptionalCollectionBox(
      @ForyField(id = 1) names: List[Option[String]],
      @ForyField(id = 2) unsigned: List[Option[Int @UInt8Type]],
      @ForyField(id = 3)
      scores: Map[String, Option[Long @UInt64Type(encoding = Int64Encoding.TAGGED)]],
      @ForyField(id = 4) keyed: Map[Option[String], Int])
      derives ForySerializer

  @ForyStruct
  final case class OptionalCollectionBoxWriter(
      @ForyField(id = 1) names: List[Option[String]],
      @ForyField(id = 2) unsigned: List[Option[Int @UInt8Type]],
      @ForyField(id = 3)
      scores: Map[String, Option[Long @UInt64Type(encoding = Int64Encoding.TAGGED)]],
      @ForyField(id = 4) keyed: Map[Option[String], Int],
      @ForyField(id = 5) extra: String)
      derives ForySerializer

  @ForyStruct
  final case class CompatibleScalarWriter(
      @ForyField(id = 1) flag: Option[String],
      @ForyField(id = 2) numberText: String,
      @ForyField(id = 3) text: Int,
      @ForyField(id = 4) decimalText: String,
      @ForyField(id = 5) decimalValue: java.math.BigDecimal,
      @ForyField(id = 6) narrow: Long)
      derives ForySerializer

  @ForyStruct
  final case class CompatibleScalarReader(
      @ForyField(id = 1) flag: Boolean,
      @ForyField(id = 2) numberText: Int,
      @ForyField(id = 3) text: String,
      @ForyField(id = 4) decimalText: java.math.BigDecimal,
      @ForyField(id = 5) decimalValue: String,
      @ForyField(id = 6) narrow: Int)
      derives ForySerializer

  @ForyStruct
  final case class CopyBox(
      @ForyField(id = 1) user: SearchUser,
      @ForyField(id = 2) names: List[String],
      @ForyField(id = 3) values: Array[Int])
      derives ForySerializer

  @ForyStruct
  final case class RefMetadataBox(
      @ForyField(id = 1) peer: SearchUser,
      @ForyField(id = 2) localOnly: SearchUser @Ref(enable = false),
      @ForyField(id = 3) shared: SearchUser @Ref)
      derives ForySerializer

  @ForyStruct
  final class RefNode() derives ForySerializer {
    @ForyField(id = 1)
    var children: List[RefNode @Ref] = List.empty

    @Ref
    @ForyField(id = 2)
    var parent: Option[RefNode] = None
  }

  @ForyStruct
  final class UnionRefNode() derives ForySerializer {
    @ForyField(id = 1)
    var name: String = ""

    @Ref
    @ForyField(id = 2)
    var choice: Option[UnionCycle] = None
  }

  @ForyStruct
  final class MixedRecord(@ForyField(id = 1) val id: Int) derives ForySerializer {
    @ForyField(id = 2)
    var name: String = ""
  }

  @ForyUnion
  enum SearchTarget derives ForySerializer {
    @ForyUnknownCase
    case Unknown(value: UnknownCase)

    @ForyCase(id = 0)
    case User(value: SearchUser)

    @ForyCase(id = 1)
    case FixedId(value: Int)

    @ForyCase(id = 2)
    case OptionalUser(value: Option[SearchUser])

    @ForyCase(id = 3)
    case OptionalTagged(value: Option[Long @UInt64Type(encoding = Int64Encoding.TAGGED)])
  }

  @ForyUnion
  enum UnionCycle derives ForySerializer {
    @ForyUnknownCase
    case Unknown(value: UnknownCase)

    @ForyCase(id = 1)
    case Node(value: UnionRefNode)
  }

  def xlangFory(): Fory = {
    val fory = ForyScala.builder()
      .withXlang(true)
      .withRefTracking(true)
      .withRefCopy(true)
      .requireClassRegistration(true)
      .suppressClassRegistrationWarnings(false)
      .build()
    ForySerializer.register(fory, classOf[Person], "scala_test.Person")
    ForySerializer.register(fory, classOf[SearchUser], "scala_test.SearchUser")
    ForySerializer.register(fory, classOf[CollectionBox], "scala_test.CollectionBox")
    ForySerializer.register(
      fory,
      classOf[OptionalCollectionBox],
      "scala_test.OptionalCollectionBox")
    ForySerializer.register(fory, classOf[CopyBox], "scala_test.CopyBox")
    ForySerializer.register(fory, classOf[RefMetadataBox], "scala_test.RefMetadataBox")
    ForySerializer.register(fory, classOf[RefNode], "scala_test.RefNode")
    ForySerializer.register(fory, classOf[UnionRefNode], "scala_test.UnionRefNode")
    ForySerializer.register(fory, classOf[MixedRecord], "scala_test.MixedRecord")
    ForySerializer.register(fory, classOf[SearchTarget], "scala_test.SearchTarget")
    ForySerializer.register(fory, classOf[UnionCycle], "scala_test.UnionCycle")
    fory
  }

  def compatibleXlangFory(): Fory = {
    val fory = ForyScala.builder()
      .withXlang(true)
      .withCompatible(true)
      .withRefTracking(true)
      .withRefCopy(true)
      .requireClassRegistration(true)
      .suppressClassRegistrationWarnings(false)
      .build()
    fory
  }
}

class ForySerializerDerivationTest extends AnyWordSpec with Matchers {
  import ForySerializerDerivationTest._

  "Scala 3 ForySerializer derivation" should {
    "serialize derived case classes with Option fields" in {
      val fory = xlangFory()
      fory.deserialize(fory.serialize(Person("Ada", 36, Some("ada@example.com")))) shouldEqual
        Person("Ada", 36, Some("ada@example.com"))
      fory.deserialize(fory.serialize(Person("Grace", 85, None))) shouldEqual
        Person("Grace", 85, None)
    }

    "register derived structs with dotted names" in {
      val direct = compatibleXlangFory()
      ForySerializer.register(direct, classOf[Person], "scala_test.Person")
      direct.getTypeResolver.getTypeInfo(classOf[Person]).decodeNamespace() shouldBe "scala_test"
      direct.getTypeResolver.getTypeInfo(classOf[Person]).decodeTypeName() shouldBe "Person"

      val extension = compatibleXlangFory()
      extension.register[SearchUser]("scala_test.SearchUser")
      extension.getTypeResolver.getTypeInfo(classOf[SearchUser]).decodeNamespace() shouldBe
        "scala_test"
      extension.getTypeResolver.getTypeInfo(classOf[SearchUser]).decodeTypeName() shouldBe
        "SearchUser"

      val invalid = compatibleXlangFory()
      intercept[IllegalArgumentException] {
        ForySerializer.register(invalid, classOf[Person], "scala_test", "Bad.Name")
      }
    }

    "serialize derived union enum cases" in {
      val fory = xlangFory()
      val user = SearchTarget.User(SearchUser("Ada"))
      val fixed = SearchTarget.FixedId(7)
      fory.deserialize(fory.serialize(user)) shouldEqual user
      fory.deserialize(fory.serialize(fixed)) shouldEqual fixed
    }

    "serialize derived case classes with Scala collection fields" in {
      val fory = xlangFory()
      val box = CollectionBox(List("a", "b"), Set("x", "y"), Map("a" -> 1, "b" -> 2))
      fory.deserialize(fory.serialize(box)) shouldEqual box

      val serializer =
        summon[ForySerializer[CollectionBox]]
          .createSerializer(fory.getTypeResolver)
          .asInstanceOf[StaticGeneratedStructSerializer[CollectionBox]]
      val tags = serializer.getGeneratedDescriptors.asScala.find(_.getName == "tags").get
      val tagMeta = TypeUtils.getElementType(tags.getTypeRef).getTypeExtMeta
      if tagMeta != null then {
        tagMeta.nullableWrapper() shouldBe false
      }
    }

    "serialize derived Scala collection fields with Option elements" in {
      val fory = xlangFory()
      val box = OptionalCollectionBox(
        List(Some("a"), None),
        List(Some(1), None),
        Map("a" -> Some(9L), "b" -> None),
        Map(Some("a") -> 1, None -> 2))

      fory.deserialize(fory.serialize(box)) shouldEqual box
    }

    "preserve Option collection wrappers on compatible remote reads" in {
      val writerFory = ForySerializerDerivationTest.compatibleXlangFory()
      ForySerializer.register(
        writerFory,
        classOf[OptionalCollectionBoxWriter],
        "scala_test",
        "OptionalCollectionBox")
      val readerFory = ForySerializerDerivationTest.compatibleXlangFory()
      ForySerializer.register(
        readerFory,
        classOf[OptionalCollectionBox],
        "scala_test",
        "OptionalCollectionBox")

      val writerValue = OptionalCollectionBoxWriter(
        List(Some("a"), None),
        List(Some(1), None),
        Map("a" -> Some(9L), "b" -> None),
        Map(Some("a") -> 1, None -> 2),
        "ignored")
      val readerValue =
        readerFory.deserialize(writerFory.serialize(writerValue)).asInstanceOf[OptionalCollectionBox]

      readerValue shouldEqual OptionalCollectionBox(
        List(Some("a"), None),
        List(Some(1), None),
        Map("a" -> Some(9L), "b" -> None),
        Map(Some("a") -> 1, None -> 2))
    }

    "read compatible scalar conversions through derived serializers" in {
      val writerFory = ForySerializerDerivationTest.compatibleXlangFory()
      ForySerializer.register(
        writerFory,
        classOf[CompatibleScalarWriter],
        "scala_test",
        "CompatibleScalar")
      val readerFory = ForySerializerDerivationTest.compatibleXlangFory()
      ForySerializer.register(
        readerFory,
        classOf[CompatibleScalarReader],
        "scala_test",
        "CompatibleScalar")

      val writerValue = CompatibleScalarWriter(
        Some("true"),
        "42",
        7,
        "12.50",
        new java.math.BigDecimal("10.500"),
        123L)
      val readerValue =
        readerFory
          .deserialize(writerFory.serialize(writerValue))
          .asInstanceOf[CompatibleScalarReader]

      readerValue.flag shouldBe true
      readerValue.numberText shouldBe 42
      readerValue.text shouldBe "7"
      readerValue.decimalText.compareTo(new java.math.BigDecimal("12.5")) shouldBe 0
      readerValue.decimalValue shouldBe "10.5"
      readerValue.narrow shouldBe 123
    }

    "emit inner nullable metadata for Option collection elements" in {
      val fory = xlangFory()
      val serializer =
        summon[ForySerializer[OptionalCollectionBox]]
          .createSerializer(fory.getTypeResolver)
          .asInstanceOf[StaticGeneratedStructSerializer[OptionalCollectionBox]]
      val descriptors = serializer.getGeneratedDescriptors.asScala
      val names = descriptors.find(_.getName == "names").get
      val unsigned = descriptors.find(_.getName == "unsigned").get
      val scores = descriptors.find(_.getName == "scores").get
      val keyed = descriptors.find(_.getName == "keyed").get

      val nameElement = TypeUtils.getElementType(names.getTypeRef)
      nameElement.getRawType shouldBe classOf[String]
      nameElement.getTypeExtMeta.nullable() shouldBe true
      nameElement.getTypeExtMeta.nullableWrapper() shouldBe true
      nameElement.getTypeExtMeta.typeId() shouldBe Types.STRING

      val unsignedElement = TypeUtils.getElementType(unsigned.getTypeRef)
      unsignedElement.getRawType shouldBe classOf[java.lang.Integer]
      unsignedElement.getTypeExtMeta.nullable() shouldBe true
      unsignedElement.getTypeExtMeta.nullableWrapper() shouldBe true
      unsignedElement.getTypeExtMeta.typeId() shouldBe Types.UINT8

      val mapValue = TypeUtils.getMapKeyValueType(scores.getTypeRef).f1
      mapValue.getRawType shouldBe classOf[java.lang.Long]
      mapValue.getTypeExtMeta.nullable() shouldBe true
      mapValue.getTypeExtMeta.nullableWrapper() shouldBe true
      mapValue.getTypeExtMeta.typeId() shouldBe Types.TAGGED_UINT64

      val mapKey = TypeUtils.getMapKeyValueType(keyed.getTypeRef).f0
      mapKey.getRawType shouldBe classOf[String]
      mapKey.getTypeExtMeta.nullable() shouldBe true
      mapKey.getTypeExtMeta.nullableWrapper() shouldBe true
      mapKey.getTypeExtMeta.typeId() shouldBe Types.STRING
    }

    "serialize mixed constructor and mutable field classes" in {
      val fory = xlangFory()
      val record = new MixedRecord(7)
      record.name = "Ada"
      val restored = fory.deserialize(fory.serialize(record)).asInstanceOf[MixedRecord]
      restored.id shouldBe 7
      restored.name shouldBe "Ada"
    }

    "preserve nested reference metadata in generated descriptors" in {
      val fory = xlangFory()
      val serializer =
        summon[ForySerializer[RefNode]]
          .createSerializer(fory.getTypeResolver)
          .asInstanceOf[StaticGeneratedStructSerializer[RefNode]]
      val descriptors = serializer.getGeneratedDescriptors.asScala
      val children = descriptors.find(_.getName == "children").get
      val parent = descriptors.find(_.getName == "parent").get

      children.isTrackingRef shouldBe false
      TypeUtils.getElementType(children.getTypeRef).getTypeExtMeta.trackingRef() shouldBe true
      parent.isNullable shouldBe true
      parent.isTrackingRef shouldBe true
    }

    "preserve explicit top-level ref metadata presence in generated descriptors" in {
      val fory = xlangFory()
      val serializer =
        summon[ForySerializer[RefMetadataBox]]
          .createSerializer(fory.getTypeResolver)
          .asInstanceOf[StaticGeneratedStructSerializer[RefMetadataBox]]
      val descriptors = serializer.getGeneratedDescriptors.asScala
      val peer = descriptors.find(_.getName == "peer").get
      val localOnly = descriptors.find(_.getName == "localOnly").get
      val shared = descriptors.find(_.getName == "shared").get

      peer.hasTrackingRefMetadata shouldBe false
      peer.isTrackingRef shouldBe false
      localOnly.hasTrackingRefMetadata shouldBe true
      localOnly.isTrackingRef shouldBe false
      shared.hasTrackingRefMetadata shouldBe true
      shared.isTrackingRef shouldBe true
    }

    "disable same-schema compatible fast path for nested compatible structs" in {
      def sameSchemaCompatible(serializer: AnyRef): Boolean = {
        val field = serializer.getClass.getDeclaredField("sameSchemaCompatible")
        field.setAccessible(true)
        field.getBoolean(serializer)
      }

      val fory = xlangFory()
      val resolver = fory.getTypeResolver
      val personFactory = summon[ForySerializer[Person]]
      val copyBoxFactory = summon[ForySerializer[CopyBox]]

      val personSerializer =
        personFactory.createSerializer(resolver, TypeDef.buildTypeDef(resolver, classOf[Person]))
      val copyBoxSerializer =
        copyBoxFactory.createSerializer(resolver, TypeDef.buildTypeDef(resolver, classOf[CopyBox]))

      sameSchemaCompatible(personSerializer.asInstanceOf[AnyRef]) shouldBe true
      sameSchemaCompatible(copyBoxSerializer.asInstanceOf[AnyRef]) shouldBe false
    }

    "reject union enum cases without ForyCase metadata" in {
      val errors = typeCheckErrors("""
        import org.apache.fory.annotation.{ForyCase, ForyStruct, ForyUnion, ForyUnknownCase}
        import org.apache.fory.scala.ForySerializer
import org.apache.fory.scala.ForyScala

        @ForyStruct
        final case class MissingCaseUser(name: String) derives ForySerializer

          @ForyUnion
          enum MissingCaseUnion derives ForySerializer {
            @ForyUnknownCase
          case Unknown(value: org.apache.fory.`type`.union.UnknownCase)

          case User(value: MissingCaseUser)
        }
      """)

      errors.exists(_.message.contains("must be annotated with @ForyCase")) shouldBe true
    }

    "reject union enums with only unknown case" in {
      val errors = typeCheckErrors("""
        import org.apache.fory.annotation.{ForyUnion, ForyUnknownCase}
        import org.apache.fory.scala.ForySerializer
        import org.apache.fory.`type`.union.UnknownCase

        @ForyUnion
        enum OnlyUnknown derives ForySerializer {
          @ForyUnknownCase
          case Unknown(value: UnknownCase)
        }
      """)

      errors.exists(_.message.contains("at least one non-Unknown case")) shouldBe true
    }

    "serialize derived union unknown cases with original ids" in {
      val fory = xlangFory()
      val unknown = SearchTarget.Unknown(new UnknownCase(99, SearchUser("Future")))
      val decoded = fory.deserialize(fory.serialize(unknown)).asInstanceOf[SearchTarget.Unknown]
      decoded.value.caseId() shouldBe 99
      decoded.value.value().asInstanceOf[SearchUser] shouldEqual SearchUser("Future")
      decoded should not equal unknown
    }

    "serialize derived union case id zero" in {
      val fory = xlangFory()
      val serializer = summon[ForySerializer[SearchTarget]].createSerializer(fory.getTypeResolver)
      val value = SearchTarget.User(SearchUser("Ada"))
      val buffer = MemoryBuffer.newHeapBuffer(64)
      val writeContext = fory.getWriteContext
      writeContext.prepare(buffer, null)
      try serializer.write(writeContext, value)
      finally writeContext.reset()
      buffer.readerIndex(0)
      buffer.readVarUInt32() shouldBe 0
      buffer.readerIndex(0)
      val readContext = fory.getReadContext
      readContext.prepare(buffer, null, false)
      try serializer.read(readContext) shouldBe value
      finally readContext.reset()
    }

    "serialize and copy derived union Option payloads" in {
      val fory = xlangFory()
      val some: SearchTarget.OptionalUser =
        SearchTarget.OptionalUser(Some(SearchUser("Ada")))
      val none: SearchTarget.OptionalUser = SearchTarget.OptionalUser(None)
      val taggedSome = SearchTarget.OptionalTagged(Some(99L))
      val taggedNone = SearchTarget.OptionalTagged(None)

      fory.deserialize(fory.serialize(some)) shouldEqual some
      fory.deserialize(fory.serialize(none)) shouldEqual none
      fory.deserialize(fory.serialize(taggedSome)) shouldEqual taggedSome
      fory.deserialize(fory.serialize(taggedNone)) shouldEqual taggedNone

      val copiedSome = fory.copy(some).asInstanceOf[SearchTarget.OptionalUser]
      val copiedNone = fory.copy(none).asInstanceOf[SearchTarget.OptionalUser]
      val copiedTagged = fory.copy(taggedSome).asInstanceOf[SearchTarget.OptionalTagged]

      copiedSome shouldEqual some
      copiedSome should not be theSameInstanceAs(some)
      copiedSome.value.get should not be theSameInstanceAs(some.value.get)
      copiedNone shouldEqual none
      copiedNone should not be theSameInstanceAs(none)
      copiedTagged shouldEqual taggedSome
      copiedTagged should not be theSameInstanceAs(taggedSome)
    }

    "copy derived case classes through field serializers" in {
      val fory = xlangFory()
      val box = CopyBox(SearchUser("Ada"), List("compiler", "runtime"), Array(1, 2, 3))

      val copied = fory.copy(box)

      copied should not be theSameInstanceAs(box)
      copied.user shouldEqual box.user
      copied.user should not be theSameInstanceAs(box.user)
      copied.names shouldEqual box.names
      copied.names should not be theSameInstanceAs(box.names)
      copied.values.sameElements(box.values) shouldBe true
      copied.values should not be theSameInstanceAs(box.values)
    }

    "copy derived normal classes with ref cycles" in {
      val fory = xlangFory()
      val root = new RefNode()
      val child = new RefNode()
      child.parent = Some(root)
      root.children = List(child)

      val copied = fory.copy(root)

      copied should not be theSameInstanceAs(root)
      copied.children.head should not be theSameInstanceAs(child)
      copied.children.head.parent.get shouldBe theSameInstanceAs(copied)
    }

    "copy cyclic graphs rooted at mutable classes with union edges" in {
      val fory = xlangFory()
      val root = new UnionRefNode()
      root.name = "root"
      val choice = UnionCycle.Node(root)
      root.choice = Some(choice)

      val copied = fory.copy(root)

      copied should not be theSameInstanceAs(root)
      copied.name shouldBe "root"
      copied.choice.get should not be theSameInstanceAs(choice)
      copied.choice.get match {
        case UnionCycle.Node(value) => value shouldBe theSameInstanceAs(copied)
        case other => fail(s"Unexpected copied union case $other")
      }
    }

    "reject cyclic copies rooted at immutable union values" in {
      val fory = xlangFory()
      val root = new UnionRefNode()
      val choice = UnionCycle.Node(root)
      root.choice = Some(choice)

      val error = intercept[org.apache.fory.exception.CopyException] {
        fory.copy(choice)
      }

      error.getMessage should include("constructor-owned immutable value")
      error.getMessage should include(classOf[UnionCycle.Node].getName)
    }

    "copy derived union cases through payload serializers" in {
      val fory = xlangFory()
      val target = SearchTarget.User(SearchUser("Ada"))

      val copied = fory.copy(target)

      copied shouldEqual target
      copied should not be theSameInstanceAs(target)
      copied.asInstanceOf[SearchTarget.User].value should not be theSameInstanceAs(
        target.asInstanceOf[SearchTarget.User].value)
    }

    "copy derived union unknown cases" in {
      val fory = xlangFory()
      val unknown: SearchTarget.Unknown =
        SearchTarget.Unknown(new UnknownCase(99, SearchUser("Future")))

      val copied = fory.copy(unknown).asInstanceOf[SearchTarget.Unknown]

      copied.value should not be theSameInstanceAs(unknown.value)
      copied.value.caseId() shouldBe unknown.value.caseId()
      copied.value.typeId() shouldBe unknown.value.typeId()
      copied.value.value() shouldEqual unknown.value.value()
      copied.value.value() should not be theSameInstanceAs(unknown.value.value())
    }
  }
}
