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
import org.testng.Assert.assertFalse
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
  fun constructorNamesArguments() {
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
    val source =
      KotlinSerializerSourceWriter(
          KotlinSourceStruct(
            packageName = "example",
            typeName = "User",
            qualifiedTypeName = "example.User",
            serializerName = "User_ForySerializer",
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
                  constructorParameterName = "userName",
                )
              ),
            originatingFiles = emptyList(),
          )
        )
        .write()

    assertTrue(source.contains("writeContext.writeString(value.name)"))
    assertTrue(source.contains("this.constructorFieldIds = intArrayOf(0)"))
    assertTrue(source.contains("return User(userName = (fieldValues[0] as String))"))
    assertTrue(!source.contains("return User(name = field0!!)"))
    assertTrue(source.contains("fieldValues[0] = value.name"))
    assertFalse(source.contains("NATURAL_ORDER_COMPARATOR"))
    assertFalse(source.contains("requireXlangNaturalOrdering"))
    assertFalse(source.contains("trackConstructorRefRead(readContext, buffer)"))
  }

  @Test
  fun tracksCtorRefs() {
    val childType =
      KotlinSourceTypeNode(
        rawClassExpression = "Child::class.java",
        kotlinTypeName = "example.Child",
        valueTypeName = "Child",
        typeName = "example.Child",
        typeId = null,
        nullable = false,
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
            fields =
              listOf(
                KotlinSourceField(
                  id = 0,
                  name = "child",
                  type = childType,
                  hasForyField = true,
                  foryFieldId = 1,
                  trackingRef = true,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = false,
                  propertyTypeName = "Child",
                  constructorParameterName = "child",
                )
              ),
            originatingFiles = emptyList(),
          )
        )
        .write()

    assertTrue(source.contains("private fun readSchemaConstructorField"))
    assertTrue(source.contains("private fun readCompatibleConstructorField"))
    assertTrue(source.contains("trackConstructorRefRead(readContext, buffer)"))
  }

  @Test
  fun copyUsesDirectScalarValues() {
    fun scalar(name: String, typeName: String, typeId: String) =
      KotlinSourceTypeNode(
        rawClassExpression = "$typeName::class.java",
        kotlinTypeName = typeName,
        valueTypeName = name,
        typeName = typeName,
        typeId = typeId,
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
      )

    val fields =
      listOf(
        KotlinSourceField(
          id = 0,
          name = "date",
          type = scalar("java.time.LocalDate", "java.time.LocalDate", "Types.DATE"),
          hasForyField = true,
          foryFieldId = 1,
          trackingRef = false,
          dynamic = "AUTO",
          arrayType = false,
          hasDefault = false,
          nullable = false,
          propertyTypeName = "java.time.LocalDate",
        ),
        KotlinSourceField(
          id = 1,
          name = "instant",
          type = scalar("java.time.Instant", "java.time.Instant", "Types.TIMESTAMP"),
          hasForyField = true,
          foryFieldId = 2,
          trackingRef = false,
          dynamic = "AUTO",
          arrayType = false,
          hasDefault = false,
          nullable = false,
          propertyTypeName = "java.time.Instant",
        ),
        KotlinSourceField(
          id = 2,
          name = "duration",
          type = scalar("kotlin.time.Duration", "java.time.Duration", "Types.DURATION"),
          hasForyField = true,
          foryFieldId = 3,
          trackingRef = false,
          dynamic = "AUTO",
          arrayType = false,
          hasDefault = false,
          nullable = false,
          propertyTypeName = "kotlin.time.Duration",
        ),
        KotlinSourceField(
          id = 3,
          name = "decimal",
          type = scalar("java.math.BigDecimal", "java.math.BigDecimal", "Types.DECIMAL"),
          hasForyField = true,
          foryFieldId = 4,
          trackingRef = false,
          dynamic = "AUTO",
          arrayType = false,
          hasDefault = false,
          nullable = false,
          propertyTypeName = "java.math.BigDecimal",
        ),
        KotlinSourceField(
          id = 4,
          name = "float16",
          type =
            scalar("org.apache.fory.type.Float16", "org.apache.fory.type.Float16", "Types.FLOAT16"),
          hasForyField = true,
          foryFieldId = 5,
          trackingRef = false,
          dynamic = "AUTO",
          arrayType = false,
          hasDefault = false,
          nullable = false,
          propertyTypeName = "org.apache.fory.type.Float16",
        ),
        KotlinSourceField(
          id = 5,
          name = "bfloat16",
          type =
            scalar(
              "org.apache.fory.type.BFloat16",
              "org.apache.fory.type.BFloat16",
              "Types.BFLOAT16",
            ),
          hasForyField = true,
          foryFieldId = 6,
          trackingRef = false,
          dynamic = "AUTO",
          arrayType = false,
          hasDefault = false,
          nullable = false,
          propertyTypeName = "org.apache.fory.type.BFloat16",
        ),
        KotlinSourceField(
          id = 6,
          name = "child",
          type =
            KotlinSourceTypeNode(
              rawClassExpression = "example.Child::class.java",
              kotlinTypeName = "example.Child",
              valueTypeName = "Child",
              typeName = "example.Child",
              typeId = null,
              nullable = false,
              trackingRef = false,
              primitive = false,
              unsigned = false,
            ),
          hasForyField = true,
          foryFieldId = 7,
          trackingRef = false,
          dynamic = "AUTO",
          arrayType = false,
          hasDefault = false,
          nullable = false,
          propertyTypeName = "Child",
        ),
      )
    val source =
      KotlinSerializerSourceWriter(
          KotlinSourceStruct(
            packageName = "example",
            typeName = "Scalars",
            qualifiedTypeName = "example.Scalars",
            serializerName = "Scalars_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.PUBLIC,
            fields = fields,
            originatingFiles = emptyList(),
          )
        )
        .write()

    for (field in fields.take(6)) {
      assertTrue(source.contains("fieldValues[${field.id}] = value.${field.name}"))
    }
    assertTrue(source.contains("fieldValues[6] = copyFieldValue(copyContext, value.child"))
  }

  @Test
  fun constructorFieldsAdaptCollections() {
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
    val intType =
      KotlinSourceTypeNode(
        rawClassExpression = "Int::class.javaPrimitiveType!!",
        kotlinTypeName = "kotlin.Int",
        valueTypeName = "Int",
        typeName = "int32",
        typeId = "Types.VARINT32",
        nullable = false,
        trackingRef = false,
        primitive = true,
        unsigned = false,
      )
    val intArrayType =
      KotlinSourceTypeNode(
        rawClassExpression = "IntArray::class.java",
        kotlinTypeName = "kotlin.IntArray",
        valueTypeName = "IntArray",
        typeName = "int[]",
        typeId = "Types.INT32_ARRAY",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
        componentType = intType,
      )
    val setType =
      KotlinSourceTypeNode(
        rawClassExpression = "java.util.Set::class.java",
        kotlinTypeName = "java.util.TreeSet<kotlin.String>",
        valueTypeName = "kotlin.collections.Set<String>",
        typeName = "java.util.Set",
        typeId = "Types.SET",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
        collectionFactory = CollectionFactory.TREE_SET,
        typeArguments = listOf(stringType),
      )
    val listType =
      KotlinSourceTypeNode(
        rawClassExpression = "java.util.List::class.java",
        kotlinTypeName = "java.util.List<java.util.TreeSet<kotlin.String>>",
        valueTypeName = "kotlin.collections.List<java.util.TreeSet<String>>",
        typeName = "java.util.List",
        typeId = "Types.LIST",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
        typeArguments = listOf(setType),
      )
    val arrayListType =
      KotlinSourceTypeNode(
        rawClassExpression = "java.util.List::class.java",
        kotlinTypeName = "java.util.List<kotlin.IntArray>",
        valueTypeName = "kotlin.collections.List<IntArray>",
        typeName = "java.util.List",
        typeId = "Types.LIST",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
        typeArguments = listOf(intArrayType),
      )
    val mapType =
      KotlinSourceTypeNode(
        rawClassExpression = "java.util.Map::class.java",
        kotlinTypeName = "java.util.TreeMap<kotlin.String, kotlin.Int>",
        valueTypeName = "kotlin.collections.Map<String, Int>",
        typeName = "java.util.Map",
        typeId = "Types.MAP",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
        collectionFactory = CollectionFactory.TREE_MAP,
        typeArguments = listOf(stringType, intType),
      )
    val nestedMapType =
      KotlinSourceTypeNode(
        rawClassExpression = "java.util.Map::class.java",
        kotlinTypeName = "java.util.TreeMap<kotlin.String, java.util.TreeSet<kotlin.String>>",
        valueTypeName = "java.util.TreeMap<String, java.util.TreeSet<String>>",
        typeName = "java.util.Map",
        typeId = "Types.MAP",
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = false,
        collectionFactory = CollectionFactory.TREE_MAP,
        typeArguments = listOf(stringType, setType),
      )
    val source =
      KotlinSerializerSourceWriter(
          KotlinSourceStruct(
            packageName = "example",
            typeName = "User",
            qualifiedTypeName = "example.User",
            serializerName = "User_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.PUBLIC,
            fields =
              listOf(
                KotlinSourceField(
                  id = 0,
                  name = "counts",
                  type = mapType,
                  hasForyField = true,
                  foryFieldId = 1,
                  trackingRef = true,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = false,
                  propertyTypeName = "java.util.TreeMap<String, Int>",
                  constructorParameterName = "counts",
                ),
                KotlinSourceField(
                  id = 1,
                  name = "names",
                  type = listType,
                  hasForyField = true,
                  foryFieldId = 2,
                  trackingRef = false,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = false,
                  propertyTypeName = "java.util.List<java.util.TreeSet<String>>",
                  constructorParameterName = "names",
                ),
                KotlinSourceField(
                  id = 2,
                  name = "arrays",
                  type = arrayListType,
                  hasForyField = true,
                  foryFieldId = 3,
                  trackingRef = false,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = false,
                  propertyTypeName = "java.util.List<IntArray>",
                  constructorParameterName = "arrays",
                ),
                KotlinSourceField(
                  id = 3,
                  name = "nestedCounts",
                  type = nestedMapType,
                  hasForyField = true,
                  foryFieldId = 4,
                  trackingRef = false,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = false,
                  nullable = false,
                  propertyTypeName = "java.util.TreeMap<String, java.util.TreeSet<String>>",
                  constructorParameterName = "nestedCounts",
                )
              ),
            originatingFiles = emptyList(),
          )
        )
        .write()

    assertTrue(
      source.contains(
        "return User(counts = (fieldValues[0] as java.util.TreeMap<String, Int>), names = (fieldValues[1] as java.util.List<java.util.TreeSet<String>>), arrays = (fieldValues[2] as java.util.List<IntArray>), nestedCounts = (fieldValues[3] as java.util.TreeMap<String, java.util.TreeSet<String>>))"
      )
    )
    assertTrue(
      source.contains("KotlinCollectionAdapters.toTreeSet((readElement0 as Collection<*>))")
    )
    assertTrue(
      source.contains(
        "KotlinCollectionAdapters.toTreeMap((readFieldValue(readContext, fieldInfo) as kotlin.collections.Map<String, Int>))"
      )
    )
    assertTrue(source.contains("0 -> {\n        trackConstructorRefRead(readContext, buffer)"))
    assertTrue(
      source.contains(
        "1 -> run { val readSource0 = ((readFieldValue(readContext, fieldInfo) as kotlin.collections.List<java.util.TreeSet<String>>) as Collection<*>);"
      )
    )
    assertTrue(
      source.contains(
        "KotlinCollectionAdapters.toTreeMap((readCompatibleFieldValue(readContext, remoteField, localField) as kotlin.collections.Map<String, Int>))"
      )
    )
    assertTrue(
      source.contains(
        "1 -> run { val readSource0 = ((readCompatibleFieldValue(readContext, remoteField, localField) as kotlin.collections.List<java.util.TreeSet<String>>) as Collection<*>);"
      )
    )
    assertTrue(
      source.contains(
        "3 -> run { val readSource0 = ((readCompatibleFieldValue(readContext, remoteField, localField) as java.util.TreeMap<String, java.util.TreeSet<String>>) as Map<*, *>); val readTarget0 = java.util.TreeMap<Any?, Any?>();"
      )
    )
    assertTrue(
      source.contains("KotlinCollectionAdapters.toTreeSet((readEntry0.value as Collection<*>))")
    )
    val comparatorGuardIndex = source.indexOf("requireXlangNaturalOrdering(\"example.User.counts\"")
    assertTrue(comparatorGuardIndex >= 0)
    assertTrue(comparatorGuardIndex < source.indexOf("val buffer = writeContext.buffer"))
    assertTrue(source.contains("requireXlangNaturalOrdering(\"example.User.names element\""))
    assertTrue(source.contains("requireXlangNaturalOrdering(\"example.User.nestedCounts value\""))
    assertTrue(source.contains("private val NATURAL_ORDER_COMPARATOR"))
    assertTrue(source.contains("if (guard1List0 is java.util.RandomAccess)"))
    assertTrue(source.contains("for (guard1Element0 in guard1List0)"))
    assertTrue(
      source.contains(
        "fieldValues[0] = (copyContext.copyObject(value.counts) as kotlin.collections.Map<String, Int>)"
      )
    )
    assertTrue(
      source.contains(
        "fieldValues[1] = run { val copySource0 = value.names; val copyTarget0 = java.util.ArrayList<Any?>(copySource0.size); copyContext.reference<Any>(copySource0, copyTarget0); for (copyElement0 in copySource0) { copyTarget0.add((copyContext.copyObject(copyElement0) as kotlin.collections.Set<String>))"
      )
    )
    assertTrue(
      source.contains(
        "fieldValues[3] = (copyContext.copyObject(value.nestedCounts) as java.util.TreeMap<String, java.util.TreeSet<String>>)"
      )
    )
    assertTrue(source.contains("fieldValues[2] = run { val copySource0 = value.arrays;"))
    assertTrue(source.contains("copyTarget0.add(copyElement0.copyOf())"))
    assertFalse(source.contains("fieldValues[0] = KotlinCollectionAdapters.toTreeMap(run"))
    assertFalse(source.contains("java.util.TreeMap<Any?, Any?>((copySource0.comparator()"))
  }

  @Test
  fun defaultsUseGeneratedCompatibleRead() {
    val stringType =
      KotlinSourceTypeNode(
        rawClassExpression = "String::class.java",
        kotlinTypeName = "kotlin.String",
        valueTypeName = "String",
        typeName = "java.lang.String",
        typeId = "Types.STRING",
        nullable = false,
        trackingRef = true,
        primitive = false,
        unsigned = false,
      )
    val source =
      KotlinSerializerSourceWriter(
          KotlinSourceStruct(
            packageName = "example",
            typeName = "User",
            qualifiedTypeName = "example.User",
            serializerName = "User_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.PUBLIC,
            fields =
              listOf(
                KotlinSourceField(
                  id = 0,
                  name = "name",
                  type = stringType,
                  hasForyField = true,
                  foryFieldId = 1,
                  trackingRef = true,
                  dynamic = "AUTO",
                  arrayType = false,
                  hasDefault = true,
                  nullable = false,
                  propertyTypeName = "String",
                  constructorParameterName = "name",
                )
              ),
            originatingFiles = emptyList(),
          )
        )
        .write()
    val compatibleStart = source.indexOf("override fun readCompatible")
    val copyStart = source.indexOf("override fun copy")
    val compatibleSource = source.substring(compatibleStart, copyStart)

    assertFalse(compatibleSource.contains("return readCompatibleConstructor(readContext)"))
    assertTrue(compatibleSource.contains("return readCompatibleDefaultConstructor(readContext)"))
    assertTrue(compatibleSource.contains("beginConstructorRef(readContext)"))
    assertTrue(compatibleSource.contains("checkNoUnresolvedReadRef(readContext)"))
    assertTrue(
      compatibleSource.contains("trackConstructorRefRead(readContext, readContext.buffer)")
    )
    assertTrue(compatibleSource.contains("referenceConstructorRef(readContext, constructed)"))
    assertTrue(compatibleSource.contains("endConstructorRef(readContext)"))
    assertTrue(compatibleSource.contains("missingDefaultMask"))
    assertTrue(compatibleSource.contains("val constructed = when (missingDefaultMask)"))
  }

  @Test
  fun compatibleScalarReadsUseRuntimePath() {
    val fields =
      listOf(
        field(
          0,
          "flag",
          scalar("Boolean::class.javaPrimitiveType!!", "Boolean", "boolean", "Types.BOOL", true),
        ),
        field(
          1,
          "numberText",
          scalar("Int::class.javaPrimitiveType!!", "Int", "int", "Types.INT32", true),
        ),
        field(
          2,
          "text",
          scalar("String::class.java", "String", "java.lang.String", "Types.STRING", false),
        ),
        field(
          3,
          "decimalText",
          scalar(
            "java.math.BigDecimal::class.java",
            "java.math.BigDecimal",
            "java.math.BigDecimal",
            "Types.DECIMAL",
            false,
          ),
        ),
        field(
          4,
          "decimalValue",
          scalar("String::class.java", "String", "java.lang.String", "Types.STRING", false),
        ),
        field(
          5,
          "narrow",
          scalar("Int::class.javaPrimitiveType!!", "Int", "int", "Types.INT32", true),
        ),
        field(
          6,
          "unsigned",
          scalar(
            "Int::class.javaPrimitiveType!!",
            "UInt",
            "int",
            "Types.UINT32",
            false,
            unsigned = true,
          ),
        ),
      )
    val source =
      KotlinSerializerSourceWriter(
          KotlinSourceStruct(
            packageName = "example",
            typeName = "CompatibleScalar",
            qualifiedTypeName = "example.CompatibleScalar",
            serializerName = "CompatibleScalar_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.PUBLIC,
            construction = KotlinStructConstruction.MUTABLE,
            fields = fields,
            originatingFiles = emptyList(),
          )
        )
        .write()
    val compatibleStart = source.indexOf("override fun readCompatible")
    val copyStart = source.indexOf("override fun copy")
    val compatibleSource = source.substring(compatibleStart, copyStart)

    assertTrue(compatibleSource.contains("if (canReadGeneratedField(remoteField, localField))"))
    assertTrue(
      compatibleSource.contains("readCompatibleFieldValue(readContext, remoteField, localField)")
    )
    assertTrue(
      compatibleSource.contains("value.flag =") && compatibleSource.contains(" as Boolean")
    )
    assertTrue(
      compatibleSource.contains("value.numberText =") && compatibleSource.contains(" as Int")
    )
    assertTrue(compatibleSource.contains("value.text =") && compatibleSource.contains(" as String"))
    assertTrue(
      compatibleSource.contains("value.decimalText =") &&
        compatibleSource.contains(" as java.math.BigDecimal")
    )
    assertTrue(
      compatibleSource.contains("value.decimalValue =") && compatibleSource.contains(" as String")
    )
    assertTrue(compatibleSource.contains("value.narrow =") && compatibleSource.contains(" as Int"))
    assertTrue(
      compatibleSource.contains("value.unsigned = run") &&
        compatibleSource.contains("is org.apache.fory.type.unsigned.UInt32") &&
        compatibleSource.contains("compatibleValue0.toLong().toUInt()")
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
            construction = KotlinStructConstruction.MUTABLE,
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
    assertFalse(source.contains("readCompatibleConstructor("))
    assertFalse(source.contains("newConstructorObject("))
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
  fun compatibleUnsignedJavaCarriers() {
    fun unsignedType(name: String, rawType: String, typeId: String) =
      KotlinSourceTypeNode(
        rawClassExpression = "$rawType::class.javaPrimitiveType!!",
        kotlinTypeName = "kotlin.$name",
        valueTypeName = name,
        typeName = "kotlin.$name",
        typeId = typeId,
        nullable = false,
        trackingRef = false,
        primitive = false,
        unsigned = true,
      )

    val fields =
      listOf(
        KotlinSourceField(
          id = 0,
          name = "u8",
          type = unsignedType("UByte", "Byte", "Types.UINT8"),
          hasForyField = true,
          foryFieldId = 1,
          trackingRef = false,
          dynamic = "AUTO",
          arrayType = false,
          hasDefault = false,
          nullable = false,
          propertyTypeName = "UByte",
        ),
        KotlinSourceField(
          id = 1,
          name = "u16",
          type = unsignedType("UShort", "Short", "Types.UINT16"),
          hasForyField = true,
          foryFieldId = 2,
          trackingRef = false,
          dynamic = "AUTO",
          arrayType = false,
          hasDefault = false,
          nullable = false,
          propertyTypeName = "UShort",
        ),
        KotlinSourceField(
          id = 2,
          name = "u32",
          type = unsignedType("UInt", "Int", "Types.VAR_UINT32"),
          hasForyField = true,
          foryFieldId = 3,
          trackingRef = false,
          dynamic = "AUTO",
          arrayType = false,
          hasDefault = false,
          nullable = false,
          propertyTypeName = "UInt",
        ),
        KotlinSourceField(
          id = 3,
          name = "u64",
          type = unsignedType("ULong", "Long", "Types.VAR_UINT64"),
          hasForyField = true,
          foryFieldId = 4,
          trackingRef = false,
          dynamic = "AUTO",
          arrayType = false,
          hasDefault = false,
          nullable = false,
          propertyTypeName = "ULong",
        )
      )
    val source =
      KotlinSerializerSourceWriter(
          KotlinSourceStruct(
            packageName = "example",
            typeName = "UnsignedScalars",
            qualifiedTypeName = "example.UnsignedScalars",
            serializerName = "UnsignedScalars_ForySerializer",
            serializerVisibility = KotlinSerializerVisibility.PUBLIC,
            fields = fields,
            originatingFiles = emptyList(),
          )
        )
        .write()

    assertTrue(source.contains("is org.apache.fory.type.unsigned.UInt8"))
    assertTrue(source.contains("compatibleValue0.toInt().toUByte()"))
    assertTrue(source.contains("is org.apache.fory.type.unsigned.UInt16"))
    assertTrue(source.contains("compatibleValue0.toInt().toUShort()"))
    assertTrue(source.contains("is org.apache.fory.type.unsigned.UInt32"))
    assertTrue(source.contains("compatibleValue0.toLong().toUInt()"))
    assertTrue(source.contains("is org.apache.fory.type.unsigned.UInt64"))
    assertTrue(source.contains("compatibleValue0.toLong().toULong()"))
    assertFalse(source.contains("as Number).to"))
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

  private fun scalar(
    rawClassExpression: String,
    kotlinTypeName: String,
    typeName: String,
    typeId: String,
    primitive: Boolean,
    unsigned: Boolean = false,
  ): KotlinSourceTypeNode =
    KotlinSourceTypeNode(
      rawClassExpression = rawClassExpression,
      kotlinTypeName = kotlinTypeName,
      valueTypeName = kotlinTypeName,
      typeName = typeName,
      typeId = typeId,
      nullable = false,
      trackingRef = false,
      primitive = primitive,
      unsigned = unsigned,
    )

  private fun field(id: Int, name: String, type: KotlinSourceTypeNode): KotlinSourceField =
    KotlinSourceField(
      id = id,
      name = name,
      type = type,
      hasForyField = true,
      foryFieldId = id + 1,
      trackingRef = false,
      dynamic = "AUTO",
      arrayType = false,
      hasDefault = false,
      nullable = false,
      propertyTypeName = type.valueTypeName,
      constructorParameterName = name,
    )
}
