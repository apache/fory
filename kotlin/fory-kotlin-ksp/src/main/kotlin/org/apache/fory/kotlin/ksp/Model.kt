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

internal data class KotlinSourceStruct(
  val packageName: String,
  val typeName: String,
  val qualifiedTypeName: String,
  val serializerName: String,
  val serializerVisibility: KotlinSerializerVisibility,
  val construction: KotlinStructConstruction = KotlinStructConstruction.CONSTRUCTOR,
  val fields: List<KotlinSourceField>,
  val originatingFiles: List<com.google.devtools.ksp.symbol.KSFile>,
) {
  val qualifiedSerializerName: String =
    if (packageName.isEmpty()) serializerName else "$packageName.$serializerName"

  val hasCompatStructFields: Boolean = fields.any { it.type.hasNestedCompatibleStruct() }
}

internal data class KotlinSourceUnion(
  val packageName: String,
  val typeName: String,
  val qualifiedTypeName: String,
  val serializerName: String,
  val serializerVisibility: KotlinSerializerVisibility,
  val unknownCase: KotlinSourceUnionCase,
  val cases: List<KotlinSourceUnionCase>,
  val originatingFiles: List<com.google.devtools.ksp.symbol.KSFile>,
) {
  val qualifiedSerializerName: String =
    if (packageName.isEmpty()) serializerName else "$packageName.$serializerName"
}

internal data class KotlinSourceUnionCase(
  val id: Int?,
  val className: String,
  val qualifiedClassName: String,
  val valueType: KotlinSourceTypeNode,
) {
  val knownId: Int
    get() = id ?: error("unknown union carrier has no schema case id")
}

internal enum class KotlinSerializerVisibility(val keyword: String) {
  PUBLIC("public"),
  INTERNAL("internal"),
}

internal enum class KotlinStructConstruction {
  CONSTRUCTOR,
  MUTABLE,
}

internal data class KotlinSourceField(
  val id: Int,
  val name: String,
  val type: KotlinSourceTypeNode,
  val hasForyField: Boolean,
  val foryFieldId: Int,
  val trackingRef: Boolean,
  val dynamic: String,
  val arrayType: Boolean,
  val hasDefault: Boolean,
  val nullable: Boolean,
  val propertyTypeName: String,
) {
  val localName: String = "field$id"
}

internal data class KotlinSourceTypeNode(
  val rawClassExpression: String,
  val kotlinTypeName: String,
  val valueTypeName: String,
  val typeName: String,
  val typeId: String?,
  val nullable: Boolean,
  val trackingRef: Boolean,
  val primitive: Boolean,
  val unsigned: Boolean,
  val arrayType: Boolean = false,
  val enum: Boolean = false,
  val nestedCompatibleStruct: Boolean = false,
  val collectionFactory: CollectionFactory = CollectionFactory.NONE,
  val typeArguments: List<KotlinSourceTypeNode> = emptyList(),
  val componentType: KotlinSourceTypeNode? = null,
) {
  fun hasNestedCompatibleStruct(): Boolean =
    nestedCompatibleStruct ||
      componentType?.hasNestedCompatibleStruct() == true ||
      typeArguments.any { it.hasNestedCompatibleStruct() }

  fun typeRefExpression(): String {
    if (
      typeId == null &&
        !nullable &&
        !trackingRef &&
        typeArguments.isEmpty() &&
        componentType == null
    ) {
      return "TypeRef.of($rawClassExpression)"
    }
    val metaExpression =
      if (typeId == null && !nullable && !trackingRef) {
        "null"
      } else {
        "TypeExtMeta.of(${typeId ?: "Types.UNKNOWN"}, $nullable, $trackingRef)"
      }
    val argumentsExpression =
      if (typeArguments.isEmpty()) {
        "null"
      } else {
        "listOf<TypeRef<*>>(${typeArguments.joinToString(", ") { it.typeRefExpression() }})"
      }
    val componentExpression = componentType?.typeRefExpression() ?: "null"
    return "TypeRef.of<Any>($rawClassExpression, $metaExpression, $argumentsExpression, $componentExpression)"
  }
}

internal enum class CollectionFactory {
  NONE,
  MUTABLE_LIST,
  ARRAY_LIST,
  LINKED_LIST,
  COPY_ON_WRITE_ARRAY_LIST,
  MUTABLE_SET,
  HASH_SET,
  LINKED_HASH_SET,
  TREE_SET,
  COPY_ON_WRITE_ARRAY_SET,
  CONCURRENT_SKIP_LIST_SET,
  MUTABLE_MAP,
  HASH_MAP,
  LINKED_HASH_MAP,
  TREE_MAP,
  CONCURRENT_HASH_MAP,
  CONCURRENT_SKIP_LIST_MAP,
}

internal data class ForyFieldMeta(
  val hasAnnotation: Boolean,
  val id: Int,
  val dynamic: String,
) {
  companion object {
    val NONE: ForyFieldMeta = ForyFieldMeta(false, -1, "AUTO")
  }
}
