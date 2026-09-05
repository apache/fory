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

import kotlin.time.TimedValue
import org.apache.fory.json.JsonCodecFactory
import org.apache.fory.json.codec.ArrayCodec
import org.apache.fory.json.codec.JsonValueCodec
import org.apache.fory.json.codec.ScalarCodecs
import org.apache.fory.json.resolver.ExactTypeRequiredException
import org.apache.fory.json.resolver.JsonTypeResolver
import org.apache.fory.json.resolver.UnsupportedJsonTypeException
import org.apache.fory.reflect.TypeRef
import org.apache.fory.type.Types

internal object KotlinJsonCodecFactory : JsonCodecFactory {
  override fun create(
    type: TypeRef<*>,
    resolver: JsonTypeResolver,
    runtimeType: Boolean,
  ): JsonValueCodec<*>? {
    val rawType = type.rawType
    val semanticId = type.typeExtMeta?.typeId() ?: 0
    if (semanticId in Types.UINT8..Types.UINT64 && semanticId != Types.VAR_UINT32) {
      return KotlinUnsignedCodecs.scalar(
        semanticId,
        !rawType.isPrimitive,
        type.typeExtMeta.nullable(),
        resolver.writeLongAsString(),
      )
    }
    if (semanticId in Types.UINT8_ARRAY..Types.UINT64_ARRAY) {
      val writeLongAsString = resolver.writeLongAsString()
      KotlinUnsignedArrayCodecs.create(rawType, semanticId, writeLongAsString)?.let {
        return it
      }
      return ArrayCodec.createUnsignedPrimitive(rawType, semanticId, writeLongAsString)
    }
    if (rawType == Unit::class.java) {
      return if (type.typeExtMeta?.nullable() == true) {
        KotlinSingletonCodecs.NULLABLE_UNIT
      } else {
        KotlinSingletonCodecs.UNIT
      }
    }
    if (rawType == Void::class.java) {
      if (type.typeExtMeta?.nullable() == true) return ScalarCodecs.VoidCodec.INSTANCE
      throw UnsupportedJsonTypeException("Kotlin Nothing has no JSON value")
    }
    KotlinMapKeyCodecs.create(type, resolver)?.let {
      return it
    }
    if (Map::class.java.isAssignableFrom(rawType)) {
      val arguments = type.typeArguments
      if (arguments.size == 2 && KotlinValueClassMetadata.isValueClass(arguments[0].rawType)) {
        return KotlinValueClassCodecs.createMap(type, resolver)
      }
    }
    KotlinProductCodecs.create(type, resolver)?.let {
      return it
    }
    KotlinRangeCodecs.create(type)?.let {
      return it
    }
    KotlinProgressionCodecs.create(type)?.let {
      return it
    }
    KotlinTemporalCodecs.create(type)?.let {
      return it
    }
    if (rawType == TimedValue::class.java) return KotlinTimedValueCodec()
    KotlinUnsupportedTypes.reject(rawType)
    if (
      Collection::class.java.isAssignableFrom(rawType) || Map::class.java.isAssignableFrom(rawType)
    ) {
      return null
    }
    if (KotlinValueClassMetadata.isValueClass(rawType)) {
      if (!type.hasTypeExtMeta()) {
        throw ExactTypeRequiredException(
          "Kotlin JSON value class ${rawType.name} requires an exact declared occurrence",
        )
      }
      return KotlinValueClassCodecs.create(type)
    }
    if (rawType.getAnnotation(Metadata::class.java) == null) return null
    if (resolver.isInferredSubtype(rawType)) {
      resolver.cachedInferredSubtypeCodec(type, this)?.let {
        return it
      }
      val table = KotlinSealedSubtypes.discover(rawType)
      return resolver.createInferredSubtypeCodec(
        type,
        table.classes,
        table.names,
        this,
        null,
      )
    }
    return resolver.createObjectCodec(
      type,
      KotlinMetadataModels.objectModel(type, resolver.creatorDeclarations(rawType)),
    )
  }
}
