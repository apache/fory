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

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.JsonObjectModel
import org.apache.fory.json.codec.JsonValueCodec
import org.apache.fory.json.resolver.JsonTypeResolver
import org.apache.fory.reflect.TypeRef

/** Exact Kotlin standard-library product models owned by the standard object codec. */
internal object KotlinProductCodecs {
  private val pairConstructor = Pair::class.java.getConstructor(Any::class.java, Any::class.java)
  private val pairFirst = Pair::class.java.getMethod("getFirst")
  private val pairSecond = Pair::class.java.getMethod("getSecond")

  private val tripleConstructor =
    Triple::class.java.getConstructor(Any::class.java, Any::class.java, Any::class.java)
  private val tripleFirst = Triple::class.java.getMethod("getFirst")
  private val tripleSecond = Triple::class.java.getMethod("getSecond")
  private val tripleThird = Triple::class.java.getMethod("getThird")

  fun create(type: TypeRef<*>, resolver: JsonTypeResolver): JsonValueCodec<*>? =
    when (type.rawType) {
      Pair::class.java -> pair(type, resolver)
      Triple::class.java -> triple(type, resolver)
      else -> null
    }

  private fun pair(type: TypeRef<*>, resolver: JsonTypeResolver): JsonValueCodec<*> {
    val arguments = requireArguments(type, 2)
    return resolver.createObjectCodec(
      type,
      model(
        pairConstructor,
        arrayOf("first", "second"),
        arrayOf(pairFirst, pairSecond),
        arguments,
      ),
    )
  }

  private fun triple(type: TypeRef<*>, resolver: JsonTypeResolver): JsonValueCodec<*> {
    val arguments = requireArguments(type, 3)
    return resolver.createObjectCodec(
      type,
      model(
        tripleConstructor,
        arrayOf("first", "second", "third"),
        arrayOf(tripleFirst, tripleSecond, tripleThird),
        arguments,
      ),
    )
  }

  private fun model(
    constructor: Constructor<*>,
    names: Array<String>,
    accessors: Array<Method>,
    types: Array<TypeRef<*>>,
  ): JsonObjectModel =
    JsonObjectModel(
      constructor,
      null,
      names,
      accessors,
      arrayOfNulls(names.size),
      IntArray(names.size) { -1 },
      BooleanArray(names.size) { nullable(types[it]) },
      types,
      names,
      accessors,
      arrayOfNulls(names.size),
      types,
    )

  private fun requireArguments(type: TypeRef<*>, count: Int): Array<TypeRef<*>> {
    val arguments = type.typeArguments
    if (arguments.size != count) {
      throw ForyJsonException(
        "Kotlin JSON product ${type.type} requires $count exact type arguments"
      )
    }
    return arguments.toTypedArray()
  }

  private fun nullable(type: TypeRef<*>): Boolean = type.typeExtMeta?.nullable() == true
}
