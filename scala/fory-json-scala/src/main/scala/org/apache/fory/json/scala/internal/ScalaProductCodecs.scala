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

import java.lang.invoke.{MethodHandle, MethodHandles}
import java.lang.reflect.{Constructor, Method, Modifier, ParameterizedType}

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.{AbstractJsonValueCodec, CompositeJsonCodec, JsonValueCodec}
import org.apache.fory.json.reader.{JsonReader, Latin1JsonReader, Utf16JsonReader, Utf8JsonReader}
import org.apache.fory.json.resolver.{JsonTypeInfo, JsonTypeResolver}
import org.apache.fory.json.writer.{JsonWriter, StringJsonWriter, Utf8JsonWriter}
import org.apache.fory.reflect.TypeRef
import org.apache.fory.serializer.GraphMemoryEstimates

private[scala] final class ScalaEmptyTupleCodec(tupleType: Class[_])
    extends AbstractJsonValueCodec[AnyRef] {
  private val value = tupleType.getField("MODULE$").get(null).asInstanceOf[AnyRef]

  override def write(writer: JsonWriter, input: AnyRef): Unit = {
    if (input == null) writer.writeNull()
    else if (input ne value) throw new ForyJsonException("Expected scala.EmptyTuple")
    else {
      writer.writeArrayStart()
      writer.writeArrayEnd()
    }
  }

  override def read(reader: JsonReader): AnyRef = {
    if (reader.tryReadNullToken()) return null
    reader.enterDepth()
    reader.expectNextToken('[')
    if (!reader.consumeNextToken(']'))
      throw new ForyJsonException("scala.EmptyTuple requires an empty JSON array")
    reader.exitDepth()
    value
  }
}

private[scala] final class ScalaValueClassCodec(
    valueType: Class[_],
    constructor: Constructor[_],
    accessor: Method,
    runtimeType: Boolean
) extends CompositeJsonCodec[AnyRef] {
  private val constructorHandle: MethodHandle = MethodHandles.publicLookup().unreflectConstructor(constructor)
  private val accessorHandle: MethodHandle = MethodHandles.publicLookup().unreflect(accessor)
  private val ownerBytes = GraphMemoryEstimates.shallowObjectBytes(valueType)
  private var valueInfo: JsonTypeInfo = _

  override def resolveTypes(typeRef: TypeRef[_], resolver: JsonTypeResolver): Unit = {
    if (
      !runtimeType && valueType.getTypeParameters.nonEmpty &&
      !typeRef.getType.isInstanceOf[ParameterizedType]
    ) throw ScalaTypeSupport.unsupported(typeRef, "value class requires a complete parameterized TypeRef")
    val underlying = typeRef.resolveType(constructor.getGenericParameterTypes.apply(0))
    valueInfo = resolver.getTypeInfo(underlying.getType, underlying.getRawType)
  }

  override def writeString(writer: StringJsonWriter, value: AnyRef): Unit = {
    if (value == null) writer.writeNull()
    else valueInfo.stringWriter().writeString(writer, accessorHandle.invoke(value))
  }

  override def writeUtf8(writer: Utf8JsonWriter, value: AnyRef): Unit = {
    if (value == null) writer.writeNull()
    else valueInfo.utf8Writer().writeUtf8(writer, accessorHandle.invoke(value))
  }

  override def readLatin1(reader: Latin1JsonReader): AnyRef = {
    if (reader.tryReadNullToken()) return null
    construct(reader, valueInfo.latin1Reader().readLatin1(reader))
  }

  override def readUtf16(reader: Utf16JsonReader): AnyRef = {
    if (reader.tryReadNullToken()) return null
    construct(reader, valueInfo.utf16Reader().readUtf16(reader))
  }

  override def readUtf8(reader: Utf8JsonReader): AnyRef = {
    if (reader.tryReadNullToken()) return null
    construct(reader, valueInfo.utf8Reader().readUtf8(reader))
  }

  private def construct(reader: JsonReader, underlying: Any): AnyRef = {
    reader.reserveGraphMemory(ownerBytes)
    constructorHandle.invoke(underlying).asInstanceOf[AnyRef]
  }
}

private[scala] object ScalaValueClassCodec {
  def create(typeClass: Class[_], runtimeType: Boolean): JsonValueCodec[_] = {
    if (!Modifier.isFinal(typeClass.getModifiers) || typeClass.isInterface) return null
    val fields = typeClass.getDeclaredFields.filter { field =>
      !Modifier.isStatic(field.getModifiers) && !field.isSynthetic && !field.getName.startsWith("$")
    }
    if (fields.length != 1) return null
    val constructors = typeClass.getConstructors.filter { constructor =>
      !constructor.isSynthetic && !constructor.isVarArgs && constructor.getParameterCount == 1
    }
    if (constructors.length != 1) return null
    val constructor = constructors(0)
    val carrier = constructor.getParameterTypes.apply(0)
    if (fields(0).getType != carrier) return null
    val accessor =
      try typeClass.getMethod(fields(0).getName)
      catch { case _: NoSuchMethodException => return null }
    if (
      accessor.getParameterCount != 0 || Modifier.isStatic(accessor.getModifiers) ||
      accessor.getReturnType != carrier
    ) return null

    val hasEqualsExtension = typeClass.getMethods.exists { method =>
      method.getName == "equals$extension" && Modifier.isStatic(method.getModifiers) &&
      method.getParameterCount == 2 && method.getParameterTypes.apply(0) == carrier &&
      method.getParameterTypes.apply(1) == classOf[Object] && method.getReturnType == java.lang.Boolean.TYPE
    }
    val hasHashExtension = typeClass.getMethods.exists { method =>
      method.getName == "hashCode$extension" && Modifier.isStatic(method.getModifiers) &&
      method.getParameterCount == 1 && method.getParameterTypes.apply(0) == carrier &&
      method.getReturnType == java.lang.Integer.TYPE
    }
    if (!hasEqualsExtension || !hasHashExtension) null
    else new ScalaValueClassCodec(typeClass, constructor, accessor, runtimeType)
  }
}
