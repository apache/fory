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

package org.apache.fory.json.scala.internal

import java.lang.reflect.{ParameterizedType, Type}

import org.apache.fory.json.resolver.UnsupportedJsonTypeException
import org.apache.fory.json.{ForyJsonException}
import org.apache.fory.json.annotation.JsonCodec
import org.apache.fory.reflect.TypeRef

import scala.reflect.ClassTag

private[scala] object ScalaTypeSupport {
  def arguments(typeRef: TypeRef[_], expected: Int, family: String): Array[Type] = {
    typeRef.getType match {
      case parameterized: ParameterizedType
          if parameterized.getActualTypeArguments.length == expected =>
        parameterized.getActualTypeArguments
      case _ =>
        throw unsupported(typeRef, s"$family requires a complete parameterized TypeRef")
    }
  }

  def runtimeArguments(
      typeRef: TypeRef[_],
      expected: Int,
      family: String,
      runtimeWrite: Boolean
  ): Array[Type] = {
    typeRef.getType match {
      case parameterized: ParameterizedType
          if parameterized.getActualTypeArguments.length == expected =>
        parameterized.getActualTypeArguments
      case _ if runtimeWrite => Array.fill(expected)(classOf[Object])
      case _ => throw unsupported(typeRef, s"$family requires a complete parameterized TypeRef")
    }
  }

  def rawType(value: Type): Class[_] = value match {
    case cls: Class[_]                  => cls
    case parameterized: ParameterizedType => parameterized.getRawType.asInstanceOf[Class[_]]
    case _                              => classOf[Object]
  }

  def classTag(rawType: Class[_]): ClassTag[Any] = {
    val tag =
      if (rawType == java.lang.Boolean.TYPE) ClassTag.Boolean
      else if (rawType == java.lang.Byte.TYPE) ClassTag.Byte
      else if (rawType == java.lang.Short.TYPE) ClassTag.Short
      else if (rawType == java.lang.Integer.TYPE) ClassTag.Int
      else if (rawType == java.lang.Long.TYPE) ClassTag.Long
      else if (rawType == java.lang.Float.TYPE) ClassTag.Float
      else if (rawType == java.lang.Double.TYPE) ClassTag.Double
      else if (rawType == java.lang.Character.TYPE) ClassTag.Char
      else if (rawType == java.lang.Void.TYPE) ClassTag.Unit
      else ClassTag(rawType)
    tag.asInstanceOf[ClassTag[Any]]
  }

  def requireElementCodec(annotation: JsonCodec, family: String): Unit = {
    if (
      annotation.elementCodec() == classOf[JsonCodec.NoJsonValueCodec] ||
      annotation.value() != classOf[JsonCodec.NoJsonValueCodec] ||
      annotation.contentCodec() != classOf[JsonCodec.NoJsonValueCodec] ||
      annotation.keyCodec() != classOf[JsonCodec.NoMapKeyCodec] ||
      annotation.valueCodec() != classOf[JsonCodec.NoJsonValueCodec]
    ) throw new ForyJsonException(s"$family supports only @JsonCodec.elementCodec")
  }

  def requireContentCodec(annotation: JsonCodec, family: String): Unit = {
    if (
      annotation.contentCodec() == classOf[JsonCodec.NoJsonValueCodec] ||
      annotation.value() != classOf[JsonCodec.NoJsonValueCodec] ||
      annotation.elementCodec() != classOf[JsonCodec.NoJsonValueCodec] ||
      annotation.keyCodec() != classOf[JsonCodec.NoMapKeyCodec] ||
      annotation.valueCodec() != classOf[JsonCodec.NoJsonValueCodec]
    ) throw new ForyJsonException(s"$family supports only @JsonCodec.contentCodec")
  }

  def requireMapCodecs(annotation: JsonCodec): Unit = {
    if (
      annotation.value() != classOf[JsonCodec.NoJsonValueCodec] ||
      annotation.elementCodec() != classOf[JsonCodec.NoJsonValueCodec] ||
      annotation.contentCodec() != classOf[JsonCodec.NoJsonValueCodec] ||
      annotation.keyCodec() == classOf[JsonCodec.NoMapKeyCodec] &&
        annotation.valueCodec() == classOf[JsonCodec.NoJsonValueCodec]
    ) throw new ForyJsonException("Scala Map supports only @JsonCodec.keyCodec/valueCodec")
  }

  def unsupported(typeRef: TypeRef[_], reason: String): UnsupportedJsonTypeException =
    new UnsupportedJsonTypeException(s"Unsupported Scala JSON type ${typeRef.getType}: $reason")
}
