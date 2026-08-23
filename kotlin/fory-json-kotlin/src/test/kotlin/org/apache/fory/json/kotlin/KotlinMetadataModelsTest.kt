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

import kotlin.ExperimentalContextParameters
import kotlin.jvm.JvmInline
import kotlin.metadata.ExperimentalContextReceivers
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmValueParameter
import kotlin.metadata.jvm.JvmMetadataVersion
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.properties.Delegates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.annotation.JsonCreator
import org.apache.fory.reflect.TypeRef
import org.apache.fory.type.Types

@JvmInline value class MetadataId(val value: Long)

@JvmInline value class MetadataGenericId<T>(val value: T)

@JvmInline value class MetadataNullableId(val value: String?)

class SelfValueNode(val id: MetadataId, val next: SelfValueNode?)

class MutualValueLeft(val id: MetadataId, val right: MutualValueRight?)

class MutualValueRight(val id: MetadataId, val left: MutualValueLeft?)

class ValueOccurrences(
  val generic: MetadataGenericId<String>,
  val nullableUnderlying: MetadataNullableId,
)

class PlatformFactory private constructor(val value: String) {
  companion object {
    @JvmStatic
    @JsonCreator(value = ["value"])
    fun create(value: String): PlatformFactory = PlatformFactory(value)
  }
}

class KotlinMetadataModelsTest {
  private class PrivateModel(val value: String)

  data class GenericFields<T>(val value: T, val optional: T?)

  open class GenericBase<T> {
    var inherited: T? = null
  }

  class GenericChild(val id: Int) : GenericBase<String>()

  class PropertyKinds(val id: Int) {
    var mutable: String = "initial"
    lateinit var required: String
    var privateSink: String = "initial"
      private set

    @JvmField var direct: String = "initial"
    val immutable: String = "constant"
    var delegated: String by Delegates.observable("initial") { _, _, _ -> }
    var computed: String
      get() = mutable
      set(value) {
        mutable = value
      }
  }

  class PrivatePrimary(private val value: String)

  class DirectPrimary(@JvmField val value: String)

  object StatefulCandidate {
    const val CONSTANT: String = "constant"
    var state: String = "state"
  }

  object Stateless

  class UnusedGeneric<T>(val id: Int)

  class UsedGeneric<T>(val value: T)

  open class PartialBase<A, B> {
    var concrete: A? = null
  }

  class PartialChild<T>(val id: Int) : PartialBase<String, T>()

  class MissingChild<T>(val id: Int) : PartialBase<T, String>()

  interface LeftProperty {
    val shared: String
  }

  interface RightProperty {
    val shared: String
  }

  data class DiamondProperty(override val shared: String) : LeftProperty, RightProperty

  class NullableNothing(val value: Nothing?)

  class MetadataBox<T>(val value: T)

  class CovariantField(val value: MetadataBox<out String?>)

  @OptIn(ExperimentalUnsignedTypes::class)
  data class UnsignedArrays(
    val ubytes: UByteArray,
    val nullableUbytes: UByteArray?,
    val ushorts: UShortArray,
    val nullableUshorts: UShortArray?,
    val uints: UIntArray,
    val nullableUints: UIntArray?,
    val ulongs: ULongArray,
    val nullableUlongs: ULongArray?,
  )

  @Test
  fun genericNullability() {
    val model = KotlinMetadataModels.objectModel(jsonTypeRef<GenericFields<String>>())
    val types = model.parameterTypes()
    assertEquals(String::class.java, types[0].rawType)
    assertFalse(types[0].typeExtMeta.nullable())
    assertEquals(String::class.java, types[1].rawType)
    assertTrue(types[1].typeExtMeta.nullable())
  }

  @Test
  fun inheritedGenericProperty() {
    val model = KotlinMetadataModels.objectModel(jsonTypeRef<GenericChild>())
    val index = model.propertyNames().indexOf("inherited")
    assertTrue(index >= 0)
    val type = model.propertyTypes()[index]
    assertEquals(String::class.java, type.rawType)
    assertTrue(type.typeExtMeta.nullable())
    assertEquals(GenericBase::class.java, model.propertyGetters()[index].declaringClass)
    assertEquals(GenericBase::class.java, model.propertySetters()[index].declaringClass)
  }

  @Test
  fun propertyKinds() {
    val model = KotlinMetadataModels.objectModel(jsonTypeRef<PropertyKinds>())
    val names = model.propertyNames()
    val reconstructible = model.propertyReconstructible()
    val required = model.propertyRequired()
    assertTrue(reconstructible[names.indexOf("mutable")])
    assertTrue(reconstructible[names.indexOf("required")])
    assertTrue(required[names.indexOf("required")])
    assertFalse(reconstructible[names.indexOf("privateSink")])
    assertTrue(reconstructible[names.indexOf("direct")])
    assertEquals(null, model.propertyGetters()[names.indexOf("direct")])
    assertEquals(null, model.propertySetters()[names.indexOf("direct")])
    assertFalse(reconstructible[names.indexOf("immutable")])
    assertFalse(reconstructible[names.indexOf("delegated")])
    assertFalse(reconstructible[names.indexOf("computed")])
  }

  @Test
  fun singletonCandidates() {
    val stateful = KotlinMetadataModels.objectModel(jsonTypeRef<StatefulCandidate>())
    assertSame(StatefulCandidate, stateful.fixedInstance())
    assertEquals(listOf("state"), stateful.propertyNames().toList())

    val stateless = KotlinMetadataModels.objectModel(jsonTypeRef<Stateless>())
    assertSame(Stateless, stateless.fixedInstance())
    assertTrue(stateless.propertyNames().isEmpty())
  }

  @Test
  fun rawGenericUse() {
    val unused = KotlinMetadataModels.objectModel(TypeRef.of(UnusedGeneric::class.java))
    assertEquals(listOf("id"), unused.parameterNames().toList())
    assertFailsWith<ForyJsonException> {
      KotlinMetadataModels.objectModel(TypeRef.of(UsedGeneric::class.java))
    }

    val partial = KotlinMetadataModels.objectModel(TypeRef.of(PartialChild::class.java))
    val concrete = partial.propertyNames().indexOf("concrete")
    assertEquals(String::class.java, partial.propertyTypes()[concrete].rawType)
    assertTrue(partial.propertyTypes()[concrete].typeExtMeta.nullable())
    assertFailsWith<ForyJsonException> {
      KotlinMetadataModels.objectModel(TypeRef.of(MissingChild::class.java))
    }
  }

  @Test
  fun classVisibility() {
    assertFailsWith<ForyJsonException> {
      KotlinMetadataModels.objectModel(jsonTypeRef<PrivateModel>())
    }
  }

  @Test
  fun primaryReadability() {
    assertFailsWith<ForyJsonException> {
      KotlinMetadataModels.objectModel(jsonTypeRef<PrivatePrimary>())
    }
    val direct = KotlinMetadataModels.objectModel(jsonTypeRef<DirectPrimary>())
    assertEquals(null, direct.accessors().single())
    assertEquals(listOf("value"), direct.propertyNames().toList())
  }

  @Test
  fun mostSpecificProperty() {
    val model = KotlinMetadataModels.objectModel(jsonTypeRef<DiamondProperty>())
    assertEquals(listOf("shared"), model.propertyNames().toList())
    assertEquals(
      DiamondProperty::class.java,
      model.propertyGetters().single().declaringClass,
    )
  }

  @Test
  fun nothingOccurrence() {
    val nullable = KotlinMetadataModels.objectModel(jsonTypeRef<NullableNothing>())
    assertEquals(Void::class.java, nullable.parameterTypes().single().rawType)
    assertTrue(nullable.parameterTypes().single().typeExtMeta.nullable())
  }

  @Test
  fun covariantOccurrence() {
    val model = KotlinMetadataModels.objectModel(jsonTypeRef<CovariantField>())
    val argument = model.parameterTypes().single().typeArguments.single()
    assertEquals(String::class.java, argument.rawType)
    assertTrue(argument.typeExtMeta.nullable())
    assertTrue(argument.typeExtMeta.covariant())
  }

  @OptIn(ExperimentalContextParameters::class, ExperimentalContextReceivers::class)
  @Suppress("DEPRECATION")
  @Test
  fun legacyContextMetadata() {
    fun stringType(): KmType = KmType().apply { classifier = KmClassifier.Class("kotlin/String") }

    val function =
      KmFunction("contextFunction").apply {
        returnType = stringType()
        contextParameters += KmValueParameter("scope").apply { type = stringType() }
      }
    val property =
      KmProperty("contextProperty").apply {
        returnType = stringType()
        contextParameters += KmValueParameter("scope").apply { type = stringType() }
      }
    val encoded =
      KotlinClassMetadata.Class(
          KmClass().apply {
            name = "org/apache/fory/json/kotlin/LegacyContextModel"
            contextReceiverTypes += stringType()
            functions += function
            properties += property
          },
          JvmMetadataVersion(2, 3, 0),
          0,
        )
        .write()
    val decoded = KotlinClassMetadata.readStrict(encoded) as KotlinClassMetadata.Class
    assertTrue(KotlinMetadataModels.hasImplicitContext(decoded.kmClass))
    assertTrue(KotlinMetadataModels.hasImplicitContext(decoded.kmClass.functions.single()))
    assertTrue(KotlinMetadataModels.hasImplicitContext(decoded.kmClass.properties.single()))
  }

  @Test
  fun recursiveValueOccurrences() {
    val self = KotlinMetadataModels.objectModel(jsonTypeRef<SelfValueNode>())
    assertEquals(MetadataId::class.java, self.parameterTypes()[0].rawType)
    assertEquals(Long::class.javaPrimitiveType, self.creator().parameterTypes[0])
    assertEquals(SelfValueNode::class.java, self.parameterTypes()[1].rawType)
    assertTrue(self.parameterTypes()[1].typeExtMeta.nullable())

    val left = KotlinMetadataModels.objectModel(jsonTypeRef<MutualValueLeft>())
    val right = KotlinMetadataModels.objectModel(jsonTypeRef<MutualValueRight>())
    assertEquals(MetadataId::class.java, left.parameterTypes()[0].rawType)
    assertEquals(MutualValueRight::class.java, left.parameterTypes()[1].rawType)
    assertEquals(MetadataId::class.java, right.parameterTypes()[0].rawType)
    assertEquals(MutualValueLeft::class.java, right.parameterTypes()[1].rawType)
  }

  @Test
  fun valueOccurrenceTypes() {
    val model = KotlinMetadataModels.objectModel(jsonTypeRef<ValueOccurrences>())
    val logicalTypes = model.parameterTypes()
    assertEquals(MetadataGenericId::class.java, logicalTypes[0].rawType)
    assertEquals(String::class.java, logicalTypes[0].typeArguments.single().rawType)
    assertFalse(logicalTypes[0].typeExtMeta.nullable())
    assertEquals(Any::class.java, model.creator().parameterTypes[0])

    assertEquals(MetadataNullableId::class.java, logicalTypes[1].rawType)
    assertFalse(logicalTypes[1].typeExtMeta.nullable())
    assertEquals(String::class.java, model.creator().parameterTypes[1])
  }

  @Test
  fun platformFactoryOwner() {
    val json = newKotlinJson(KotlinJsonTestMode.INTERPRETED)
    val decoded = json.fromJson("{\"value\":\"text\"}", PlatformFactory::class.java)
    assertEquals("text", decoded.value)
  }

  @OptIn(ExperimentalUnsignedTypes::class)
  @Test
  fun unsignedArrayCarrier() {
    val model = KotlinMetadataModels.objectModel(jsonTypeRef<UnsignedArrays>())
    val types = model.parameterTypes()
    val carriers =
      arrayOf(
        ByteArray::class.java,
        ByteArray::class.java,
        ShortArray::class.java,
        ShortArray::class.java,
        IntArray::class.java,
        IntArray::class.java,
        LongArray::class.java,
        LongArray::class.java,
      )
    val typeIds =
      intArrayOf(
        Types.UINT8_ARRAY,
        Types.UINT8_ARRAY,
        Types.UINT16_ARRAY,
        Types.UINT16_ARRAY,
        Types.UINT32_ARRAY,
        Types.UINT32_ARRAY,
        Types.UINT64_ARRAY,
        Types.UINT64_ARRAY,
      )
    for (index in types.indices) {
      assertEquals(carriers[index], types[index].rawType)
      assertEquals(typeIds[index], types[index].typeExtMeta.typeId())
      assertEquals(index % 2 == 1, types[index].typeExtMeta.nullable())
    }
  }
}
