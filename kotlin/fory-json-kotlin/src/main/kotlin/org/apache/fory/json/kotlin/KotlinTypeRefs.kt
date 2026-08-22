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

import java.lang.reflect.Array as ReflectArray
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVariance
import kotlin.reflect.typeOf
import org.apache.fory.json.ForyJsonException
import org.apache.fory.meta.TypeExtMeta
import org.apache.fory.reflect.TypeRef
import org.apache.fory.type.TypeUtils
import org.apache.fory.type.Types

/** Returns a structural Fory JSON type token which preserves Kotlin nullability and value types. */
@OptIn(ExperimentalStdlibApi::class)
@Suppress("UNCHECKED_CAST")
public inline fun <reified T> jsonTypeRef(): TypeRef<T> =
  KotlinTypeRefs.from(typeOf<T>()) as TypeRef<T>

/** Kotlin/JVM type-token conversion used by public reified roots and metadata model discovery. */
@OptIn(ExperimentalUnsignedTypes::class)
@PublishedApi
internal object KotlinTypeRefs {
  /** Converts one closed Kotlin type into its canonical structural JSON token. */
  @PublishedApi internal fun from(type: KType): TypeRef<*> = from(type, false, false)

  private fun from(type: KType, typeArgument: Boolean, covariant: Boolean): TypeRef<*> {
    val classifier = type.classifier
    if (classifier is KTypeParameter) {
      throw ForyJsonException(
        "Unresolved Kotlin type parameter ${classifier.name}; use a complete declared type",
      )
    }
    if (classifier !is KClass<*>) {
      throw ForyJsonException("Unsupported Kotlin type classifier $classifier")
    }
    val raw = carrier(classifier.java, type.isMarkedNullable || typeArgument)
    val arguments = type.arguments.map { projection(it) }
    val component =
      if (classifier.java.isArray && arguments.isNotEmpty()) {
        // KType specializes Array<T>'s classifier to the concrete JVM array class, so comparing
        // the classifier with Array<Any?> loses the component token and its nullability.
        if (arguments.size != 1) {
          throw ForyJsonException("Kotlin Array requires one exact component type")
        }
        arguments[0]
      } else {
        null
      }
    val actualRaw =
      if (component != null) {
        ReflectArray.newInstance(TypeUtils.boxedType(component.rawType), 0).javaClass
      } else {
        raw
      }
    val metadata = typeMetadata(classifier, type.isMarkedNullable, covariant)
    return when {
      component != null -> TypeRef.of<Any>(actualRaw, metadata, null, component)
      arguments.isEmpty() -> plainTypeRef(actualRaw, metadata)
      else -> TypeRef.ofDeclaredTypeArguments(actualRaw, metadata, arguments, null)
    }
  }

  private fun projection(projection: KTypeProjection): TypeRef<*> {
    val type =
      projection.type ?: throw ForyJsonException("Star-projected Kotlin JSON types are unsupported")
    if (projection.variance == KVariance.IN) {
      throw ForyJsonException("Contravariant Kotlin JSON types are unsupported: $projection")
    }
    return from(type, true, projection.variance == KVariance.OUT)
  }

  @Suppress("UNCHECKED_CAST")
  private fun plainTypeRef(type: Class<*>, metadata: TypeExtMeta): TypeRef<*> =
    TypeRef.of(type as Class<Any>, metadata)

  private fun carrier(type: Class<*>, boxed: Boolean): Class<*> =
    if (!boxed || !type.isPrimitive) type else TypeUtils.boxedType(type)

  private fun typeMetadata(type: KClass<*>, nullable: Boolean, covariant: Boolean): TypeExtMeta =
    TypeExtMeta.of(semanticTypeId(type), nullable, false, false, covariant)

  private fun semanticTypeId(type: KClass<*>): Int =
    when (type) {
      UByte::class -> Types.UINT8
      UShort::class -> Types.UINT16
      UInt::class -> Types.UINT32
      ULong::class -> Types.UINT64
      UByteArray::class -> Types.UINT8_ARRAY
      UShortArray::class -> Types.UINT16_ARRAY
      UIntArray::class -> Types.UINT32_ARRAY
      ULongArray::class -> Types.UINT64_ARRAY
      else -> 0
    }
}
