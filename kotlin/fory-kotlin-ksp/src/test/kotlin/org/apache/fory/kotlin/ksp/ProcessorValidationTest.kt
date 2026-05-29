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

package org.apache.fory.kotlin.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.Modifier
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class ProcessorValidationTest {
  @Test
  fun rejectsBadTargets() {
    assertNull(structKindError(ClassKind.CLASS, emptySet()))

    val objectDiagnostic = structKindError(ClassKind.OBJECT, emptySet()).orEmpty()
    assertTrue(objectDiagnostic.contains("concrete class"))
    assertTrue(objectDiagnostic.contains("object declarations do not expose primary-constructor"))

    val interfaceDiagnostic = structKindError(ClassKind.INTERFACE, emptySet()).orEmpty()
    assertTrue(interfaceDiagnostic.contains("interfaces are not concrete serializer targets"))

    val abstractDiagnostic = structKindError(ClassKind.CLASS, setOf(Modifier.ABSTRACT)).orEmpty()
    assertTrue(abstractDiagnostic.contains("abstract declarations are polymorphic bases"))

    val sealedDiagnostic = structKindError(ClassKind.CLASS, setOf(Modifier.SEALED)).orEmpty()
    assertTrue(sealedDiagnostic.contains("sealed declarations are polymorphic bases"))
  }

  @Test
  fun rejectsPrivateTargets() {
    assertNull(structVisibilityError(emptySet()))
    assertNull(structVisibilityError(setOf(Modifier.INTERNAL)))
    assertTrue(structVisibilityError(setOf(Modifier.PRIVATE))!!.contains("private"))
  }

  @Test
  fun rejectsGenericTargets() {
    assertNull(structTypeParamError(0))
    assertTrue(structTypeParamError(1)!!.contains("generic @ForyStruct"))
  }

  @Test
  fun rejectsHiddenCtors() {
    assertNull(ctorVisibilityError(emptySet()))
    assertNull(ctorVisibilityError(setOf(Modifier.INTERNAL)))
    assertTrue(ctorVisibilityError(setOf(Modifier.PRIVATE))!!.contains("primary constructor"))
    assertTrue(ctorVisibilityError(setOf(Modifier.PROTECTED))!!.contains("primary constructor"))
  }

  @Test
  fun rejectsManyDefaults() {
    assertNull(fieldLimitError(12))
    assertEquals(
      fieldLimitError(13),
      "Kotlin KSP xlang serializers currently support at most 12 defaulted constructor fields because Kotlin source generation must call constructors with omitted default arguments",
    )
  }

  @Test
  fun tracksWidePresence() {
    val intType =
      KotlinSourceTypeNode(
        rawClassExpression = "Int::class.javaPrimitiveType!!",
        kotlinTypeName = "kotlin.Int",
        valueTypeName = "Int",
        typeName = "int32",
        typeId = "Types.VAR_INT32",
        nullable = false,
        trackingRef = false,
        primitive = true,
        unsigned = false,
      )
    val source =
      KotlinSerializerSourceWriter(
          KotlinSourceStruct(
            packageName = "example",
            typeName = "WideStruct",
            qualifiedTypeName = "example.WideStruct",
            serializerName = "WideStruct_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.PUBLIC,
            fields =
              (0 until 70).map { id ->
                KotlinSourceField(
                  id = id,
                  name = "field$id",
                  type = intType,
                  hasForyField = true,
                  foryFieldId = id + 1,
                  trackingRef = false,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = false,
                  propertyTypeName = "Int",
                )
              },
            originatingFiles = emptyList(),
          )
        )
        .write()

    assertTrue(!source.contains("BooleanArray"))
    assertTrue(source.contains("var presentMask0 = 0L"))
    assertTrue(source.contains("var presentMask1 = 0L"))
    assertTrue(source.contains("presentMask1 = presentMask1 or (1L shl 5)"))
    assertTrue(source.contains("if ((presentMask1 and (1L shl 5)) == 0L)"))
  }

  @Test
  fun escapesSerializerNames() {
    assertEquals(escapeBinarySimpleName("User") + "_ForySerializer", "User_ForySerializer")
    assertEquals(
      escapeBinarySimpleName("Outer\$Inner") + "_ForySerializer",
      "Outer_Inner_ForySerializer",
    )
    assertEquals(
      escapeBinarySimpleName("Outer_Inner") + "_ForySerializer",
      "Outer_u_Inner_ForySerializer",
    )
    assertEquals(
      escapeBinarySimpleName("Outer__Inner") + "_ForySerializer",
      "Outer_u__u_Inner_ForySerializer",
    )
    assertEquals(
      escapeBinarySimpleName("Outer-Inner") + "_ForySerializer",
      "Outer_x2d_Inner_ForySerializer",
    )
  }

  @Test
  fun mapKeysFollowXlangSpec() {
    fun scalar(typeId: String) =
      KotlinSourceTypeNode(
        rawClassExpression = "Any::class.java",
        kotlinTypeName = "Any",
        valueTypeName = "Any",
        typeName = "java.lang.Object",
        typeId = typeId,
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
      )

    assertTrue(isValidMapKeyType(scalar("Types.STRING")))
    assertTrue(isValidMapKeyType(scalar("Types.VAR_UINT32")))
    assertTrue(isValidMapKeyType(scalar("Types.DATE")))
    assertTrue(!isValidMapKeyType(scalar("Types.FLOAT16")))
    assertTrue(!isValidMapKeyType(scalar("Types.BFLOAT16")))
    assertTrue(!isValidMapKeyType(scalar("Types.FLOAT32")))
    assertTrue(!isValidMapKeyType(scalar("Types.FLOAT64")))
    assertTrue(!isValidMapKeyType(scalar("Types.DECIMAL")))
    assertTrue(!isValidMapKeyType(scalar("Types.BINARY")))
  }

  @Test
  fun writesNullableUIntArray() {
    val uintType =
      KotlinSourceTypeNode(
        rawClassExpression = "Int::class.javaPrimitiveType!!",
        kotlinTypeName = "kotlin.UInt",
        valueTypeName = "UInt",
        typeName = "kotlin.UInt",
        typeId = "Types.UINT32",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = true,
      )
    val nullableUIntArray =
      KotlinSourceTypeNode(
        rawClassExpression = "UIntArray::class.java",
        kotlinTypeName = "kotlin.UIntArray",
        valueTypeName = "UIntArray?",
        typeName = "kotlin.UIntArray",
        typeId = "Types.UINT32_ARRAY",
        nullable = true,
        trackingRef = false,
        primitive = false,
        unsigned = true,
        componentType = uintType,
      )
    val source =
      KotlinSerializerSourceWriter(
          KotlinSourceStruct(
            packageName = "example",
            typeName = "UIntArrayBox",
            qualifiedTypeName = "example.UIntArrayBox",
            serializerName = "UIntArrayBox_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.PUBLIC,
            fields =
              listOf(
                KotlinSourceField(
                  id = 0,
                  name = "values",
                  type = nullableUIntArray,
                  hasForyField = true,
                  foryFieldId = 1,
                  trackingRef = false,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = true,
                  propertyTypeName = "UIntArray?",
                )
              ),
            originatingFiles = emptyList(),
          )
        )
        .write()

    assertTrue(
      source.contains("KotlinXlangArrayEncoding.writeUIntArray(writeContext, nullableArray0)")
    )
    assertTrue(source.contains("KotlinXlangArrayEncoding.readUIntArray(readContext"))
    assertTrue(!source.contains("KotlinXlangArrayEncoding.toIntArray"))
  }

  @Test
  fun writesNullableUInt() {
    val nullableUInt =
      KotlinSourceTypeNode(
        rawClassExpression = "Int::class.javaPrimitiveType!!",
        kotlinTypeName = "kotlin.UInt",
        valueTypeName = "UInt?",
        typeName = "kotlin.UInt",
        typeId = "Types.UINT32",
        nullable = true,
        trackingRef = false,
        primitive = false,
        unsigned = true,
      )
    val source =
      KotlinSerializerSourceWriter(
          KotlinSourceStruct(
            packageName = "example",
            typeName = "NullableUIntHolder",
            qualifiedTypeName = "example.NullableUIntHolder",
            serializerName = "NullableUIntHolder_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.PUBLIC,
            fields =
              listOf(
                KotlinSourceField(
                  id = 0,
                  name = "value",
                  type = nullableUInt,
                  hasForyField = true,
                  foryFieldId = 1,
                  trackingRef = false,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = true,
                  propertyTypeName = "UInt?",
                )
              ),
            originatingFiles = emptyList(),
          )
        )
        .write()

    assertTrue(source.contains("val nullableValue0 = value.value"))
    assertTrue(source.contains("buffer.writeInt32(nullableValue0.toInt())"))
    assertTrue(!source.contains("value.value.toInt()"))
  }

  @Test
  fun mutableStructPublishesBeforeFields() {
    val stringType =
      KotlinSourceTypeNode(
        rawClassExpression = "String::class.java",
        kotlinTypeName = "kotlin.String",
        valueTypeName = "String",
        typeName = "java.lang.String",
        typeId = "Types.STRING",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
      )
    val node =
      KotlinSourceTypeNode(
        rawClassExpression = "example.Node::class.java",
        kotlinTypeName = "example.Node",
        valueTypeName = "Node?",
        typeName = "example.Node",
        typeId = null,
        nullable = true,
        trackingRef = true,
        primitive = false,
        unsigned = false,
      )
    val source =
      KotlinSerializerSourceWriter(
          KotlinSourceStruct(
            packageName = "example",
            typeName = "Node",
            qualifiedTypeName = "example.Node",
            serializerName = "Node_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.PUBLIC,
            construction = KotlinStructConstruction.MUTABLE,
            fields =
              listOf(
                KotlinSourceField(
                  id = 0,
                  name = "id",
                  type = stringType,
                  hasForyField = true,
                  foryFieldId = 1,
                  trackingRef = false,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = false,
                  propertyTypeName = "String",
                ),
                KotlinSourceField(
                  id = 1,
                  name = "parent",
                  type = node,
                  hasForyField = true,
                  foryFieldId = 2,
                  trackingRef = true,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = true,
                  propertyTypeName = "Node?",
                )
              ),
            originatingFiles = emptyList(),
          )
        )
        .write()

    assertTrue(source.contains("val value = Node()"))
    assertTrue(source.contains("readContext.reference(value)"))
    assertTrue(source.contains("value.parent = (readFieldValue(readContext, fieldInfo) as Node?)"))
    assertTrue(source.contains("presentMask0 = presentMask0 or (1L shl 0)"))
    assertTrue(source.contains("Required Kotlin field example.Node.id is missing"))
    assertTrue(source.contains("copyContext.reference(value, copy)"))
    assertTrue(!source.contains("return Node(parent ="))
  }

  @Test
  fun internalStructSerializer() {
    val source =
      KotlinSerializerSourceWriter(
          KotlinSourceStruct(
            packageName = "example",
            typeName = "InternalHolder",
            qualifiedTypeName = "example.InternalHolder",
            serializerName = "InternalHolder_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.INTERNAL,
            fields = emptyList(),
            originatingFiles = emptyList(),
          )
        )
        .write()

    assertTrue(source.contains("internal class InternalHolder_ForySerializer"))
  }

  @Test
  fun defaultPackageWritersOmitPackage() {
    val stringType =
      KotlinSourceTypeNode(
        rawClassExpression = "String::class.java",
        kotlinTypeName = "kotlin.String",
        valueTypeName = "String",
        typeName = "java.lang.String",
        typeId = "Types.STRING",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
      )
    val any =
      KotlinSourceTypeNode(
        rawClassExpression = "Any::class.java",
        kotlinTypeName = "Any?",
        valueTypeName = "Any?",
        typeName = "java.lang.Object",
        typeId = "Types.UNKNOWN",
        nullable = true,
        trackingRef = false,
        primitive = false,
        unsigned = false,
      )
    val dogType =
      KotlinSourceTypeNode(
        rawClassExpression = "Dog::class.java",
        kotlinTypeName = "Dog",
        valueTypeName = "Dog",
        typeName = "Dog",
        typeId = null,
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
      )
    val structSource =
      KotlinSerializerSourceWriter(
          KotlinSourceStruct(
            packageName = "",
            typeName = "Dog",
            qualifiedTypeName = "Dog",
            serializerName = "Dog_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.PUBLIC,
            fields =
              listOf(
                KotlinSourceField(
                  id = 0,
                  name = "name",
                  type = stringType,
                  hasForyField = true,
                  foryFieldId = 1,
                  trackingRef = false,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = false,
                  propertyTypeName = "String",
                )
              ),
            originatingFiles = emptyList(),
          )
        )
        .write()
    val unionSource =
      UnionSerializerSourceWriter(
          KotlinSourceUnion(
            packageName = "",
            typeName = "Animal",
            qualifiedTypeName = "Animal",
            serializerName = "Animal_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.PUBLIC,
            unknownCase =
              KotlinSourceUnionCase(
                id = null,
                className = "Unknown",
                qualifiedClassName = "Animal.Unknown",
                valueType = any,
              ),
            cases =
              listOf(
                KotlinSourceUnionCase(
                  id = 1,
                  className = "DogCase",
                  qualifiedClassName = "Animal.DogCase",
                  valueType = dogType,
                )
              ),
            originatingFiles = emptyList(),
          )
        )
        .write()

    assertTrue(!structSource.contains("\npackage "))
    assertTrue(!unionSource.contains("\npackage "))
    assertTrue(unionSource.contains("is Animal.DogCase ->"))
    assertTrue(unionSource.contains("as Dog"))
  }

  @Test
  fun durationUsesCodec() {
    val duration =
      KotlinSourceTypeNode(
        rawClassExpression = "java.time.Duration::class.java",
        kotlinTypeName = "kotlin.time.Duration",
        valueTypeName = "kotlin.time.Duration",
        typeName = "kotlin.time.Duration",
        typeId = "Types.DURATION",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
      )
    val source =
      KotlinSerializerSourceWriter(
          KotlinSourceStruct(
            packageName = "example",
            typeName = "DurationHolder",
            qualifiedTypeName = "example.DurationHolder",
            serializerName = "DurationHolder_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.PUBLIC,
            fields =
              listOf(
                KotlinSourceField(
                  id = 0,
                  name = "value",
                  type = duration,
                  hasForyField = true,
                  foryFieldId = 1,
                  trackingRef = false,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = false,
                  propertyTypeName = "kotlin.time.Duration",
                )
              ),
            originatingFiles = emptyList(),
          )
        )
        .write()

    assertTrue(source.contains("DurationEncoding.write(writeContext, value.value)"))
    assertTrue(source.contains("DurationEncoding.read(readContext)"))
  }

  @Test
  fun unsignedContainersUseLoops() {
    val uint =
      KotlinSourceTypeNode(
        rawClassExpression = "Int::class.javaPrimitiveType!!",
        kotlinTypeName = "kotlin.UInt",
        valueTypeName = "UInt",
        typeName = "kotlin.UInt",
        typeId = "Types.VAR_UINT32",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = true,
      )
    val list =
      KotlinSourceTypeNode(
        rawClassExpression = "java.util.List::class.java",
        kotlinTypeName = "java.util.List",
        valueTypeName = "List<UInt>",
        typeName = "java.util.List",
        typeId = "Types.LIST",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
        typeArguments = listOf(uint),
      )
    val map =
      KotlinSourceTypeNode(
        rawClassExpression = "java.util.Map::class.java",
        kotlinTypeName = "java.util.Map",
        valueTypeName = "Map<UInt, UInt>",
        typeName = "java.util.Map",
        typeId = "Types.MAP",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
        typeArguments = listOf(uint, uint),
      )
    val duration =
      KotlinSourceTypeNode(
        rawClassExpression = "java.time.Duration::class.java",
        kotlinTypeName = "kotlin.time.Duration",
        valueTypeName = "kotlin.time.Duration",
        typeName = "kotlin.time.Duration",
        typeId = "Types.DURATION",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
      )
    val durationList =
      KotlinSourceTypeNode(
        rawClassExpression = "java.util.List::class.java",
        kotlinTypeName = "java.util.List",
        valueTypeName = "List<kotlin.time.Duration>",
        typeName = "java.util.List",
        typeId = "Types.LIST",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
        typeArguments = listOf(duration),
      )
    val source =
      KotlinSerializerSourceWriter(
          KotlinSourceStruct(
            packageName = "example",
            typeName = "UnsignedContainers",
            qualifiedTypeName = "example.UnsignedContainers",
            serializerName = "UnsignedContainers_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.PUBLIC,
            fields =
              listOf(
                KotlinSourceField(
                  id = 0,
                  name = "values",
                  type = list,
                  hasForyField = true,
                  foryFieldId = 1,
                  trackingRef = false,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = false,
                  propertyTypeName = "List<UInt>",
                ),
                KotlinSourceField(
                  id = 1,
                  name = "by_id",
                  type = map,
                  hasForyField = true,
                  foryFieldId = 2,
                  trackingRef = false,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = false,
                  propertyTypeName = "Map<UInt, UInt>",
                ),
                KotlinSourceField(
                  id = 2,
                  name = "durations",
                  type = durationList,
                  hasForyField = true,
                  foryFieldId = 3,
                  trackingRef = false,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = false,
                  propertyTypeName = "List<kotlin.time.Duration>",
                )
              ),
            originatingFiles = emptyList(),
          )
        )
        .write()

    assertTrue(source.contains("java.util.ArrayList<Any?>(source0.size())"))
    assertTrue(source.contains("java.util.LinkedHashMap<Any?, Any?>(source0.size)"))
    assertTrue(source.contains("DurationEncoding.fromJava"))
    assertTrue(!source.contains(".map {"))
    assertTrue(!source.contains(".mapTo("))
    assertTrue(!source.contains(".associate {"))
  }

  @Test
  fun unionUsesCoreHelpers() {
    val owner =
      KotlinSourceTypeNode(
        rawClassExpression = "example.Owner::class.java",
        kotlinTypeName = "Owner",
        valueTypeName = "Owner",
        typeName = "example.Owner",
        typeId = null,
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
      )
    val any =
      KotlinSourceTypeNode(
        rawClassExpression = "Any::class.java",
        kotlinTypeName = "Any?",
        valueTypeName = "Any?",
        typeName = "java.lang.Object",
        typeId = "Types.UNKNOWN",
        nullable = true,
        trackingRef = false,
        primitive = false,
        unsigned = false,
      )
    val uint =
      KotlinSourceTypeNode(
        rawClassExpression = "Int::class.javaPrimitiveType!!",
        kotlinTypeName = "kotlin.UInt",
        valueTypeName = "UInt",
        typeName = "kotlin.UInt",
        typeId = "Types.VAR_UINT32",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = true,
      )
    val duration =
      KotlinSourceTypeNode(
        rawClassExpression = "java.time.Duration::class.java",
        kotlinTypeName = "kotlin.time.Duration",
        valueTypeName = "kotlin.time.Duration",
        typeName = "kotlin.time.Duration",
        typeId = "Types.DURATION",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
      )
    val uintList =
      KotlinSourceTypeNode(
        rawClassExpression = "java.util.List::class.java",
        kotlinTypeName = "java.util.List",
        valueTypeName = "List<UInt>",
        typeName = "java.util.List",
        typeId = "Types.LIST",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
        typeArguments = listOf(uint),
      )
    val durationList =
      KotlinSourceTypeNode(
        rawClassExpression = "java.util.List::class.java",
        kotlinTypeName = "java.util.List",
        valueTypeName = "List<kotlin.time.Duration>",
        typeName = "java.util.List",
        typeId = "Types.LIST",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
        typeArguments = listOf(duration),
      )
    val uintArray =
      KotlinSourceTypeNode(
        rawClassExpression = "UIntArray::class.java",
        kotlinTypeName = "kotlin.UIntArray",
        valueTypeName = "UIntArray",
        typeName = "kotlin.UIntArray",
        typeId = "Types.UINT32_ARRAY",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = true,
        componentType = uint,
      )
    val source =
      UnionSerializerSourceWriter(
          KotlinSourceUnion(
            packageName = "example",
            typeName = "Pet",
            qualifiedTypeName = "example.Pet",
            serializerName = "Pet_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.PUBLIC,
            unknownCase =
              KotlinSourceUnionCase(
                id = null,
                className = "Unknown",
                qualifiedClassName = "example.Pet.Unknown",
                valueType = any,
              ),
            cases =
              listOf(
                KotlinSourceUnionCase(
                  id = 0,
                  className = "Owner",
                  qualifiedClassName = "example.Pet.Owner",
                  valueType = owner,
                ),
                KotlinSourceUnionCase(
                  id = 1,
                  className = "Count",
                  qualifiedClassName = "example.Pet.Count",
                  valueType = uint,
                ),
                KotlinSourceUnionCase(
                  id = 2,
                  className = "Duration",
                  qualifiedClassName = "example.Pet.Duration",
                  valueType = duration,
                ),
                KotlinSourceUnionCase(
                  id = 3,
                  className = "CountList",
                  qualifiedClassName = "example.Pet.CountList",
                  valueType = uintList,
                ),
                KotlinSourceUnionCase(
                  id = 4,
                  className = "DurationList",
                  qualifiedClassName = "example.Pet.DurationList",
                  valueType = durationList,
                ),
                KotlinSourceUnionCase(
                  id = 5,
                  className = "CountArray",
                  qualifiedClassName = "example.Pet.CountArray",
                  valueType = uintArray,
                ),
                KotlinSourceUnionCase(
                  id = 6,
                  className = "UseCase",
                  qualifiedClassName = "example.Pet.UseCase",
                  valueType = owner,
                )
              ),
            originatingFiles = emptyList(),
          )
        )
        .write()

    assertTrue(source.contains("class Pet_ForySerializer"))
    assertTrue(source.contains("UnionSerializer.writeCaseValue(typeResolver, writeContext"))
    assertTrue(source.contains("UnionSerializer.readCaseValue(typeResolver, readContext"))
    assertTrue(source.contains("UnionSerializer.copyCaseValue(copyContext"))
    assertTrue(source.contains("UnionSerializer.copyUnknownValue(copyContext, value.value)"))
    assertTrue(source.contains("UnionSerializer.writeUnknownValue(writeContext"))
    assertTrue(source.contains("TypeRef.of<Any>(Int::class.javaPrimitiveType!!"))
    assertTrue(!source.contains("UInt::class.java"))
    assertTrue(source.contains("buffer.writeUInt8(Types.VAR_UINT32)"))
    assertTrue(source.contains("buffer.writeVarUInt32(value.value.toInt())"))
    assertTrue(source.contains("example.Pet.Count(value.value)"))
    assertTrue(source.contains("DurationEncoding.write(writeContext, value.value)"))
    assertTrue(source.contains("DurationEncoding.read(readContext)"))
    assertTrue(source.contains("example.Pet.Duration(value.value)"))
    assertTrue(source.contains("0 -> example.Pet.Owner("))
    assertTrue(source.contains("TypeRef.of<Any>(java.time.Duration::class.java"))
    assertTrue(source.contains("KotlinXlangUnsignedSerializers.serializer(typeResolver.config"))
    assertTrue(source.contains("DurationSerializers.serializer(typeResolver.config"))
    assertTrue(source.contains("listValue.isNotEmpty()"))
    assertTrue(source.contains("buffer.writeByte(CollectionFlags.DECL_SAME_TYPE_NOT_HAS_NULL)"))
    assertTrue(source.contains("if (size > 0)"))
    assertTrue(source.contains("java.util.ArrayList<Any?>(size)"))
    assertTrue(
      source.contains("KotlinXlangArrayEncoding.writeUIntArray(writeContext, value.value)")
    )
    assertTrue(source.contains("KotlinXlangArrayEncoding.readUIntArray(readContext"))
    assertTrue(source.contains("example.Pet.CountArray(value.value.copyOf())"))
    assertTrue(source.contains("\"UseCase\","))
    assertTrue(!source.contains("Unknown union case id"))
    assertTrue(source.contains("is example.Pet.UseCase ->"))
    assertTrue(!source.contains("org.apache.fory.type.union.Union"))
  }
}
