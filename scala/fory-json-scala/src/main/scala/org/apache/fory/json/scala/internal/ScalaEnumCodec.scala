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

import java.lang.reflect.Modifier
import java.util.{HashMap, IdentityHashMap}

import org.apache.fory.annotation.Internal
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.{JsonValueCodec, StringEnumCodec}
import org.apache.fory.json.meta.JsonAsciiToken
import org.apache.fory.json.reader.{Latin1JsonReader, Utf16JsonReader, Utf8JsonReader}
import org.apache.fory.json.writer.StringJsonWriter
import org.apache.fory.reflect.TypeRef

private[scala] object ScalaEnumCodec {
  def familyRoot(typeClass: Class[_]): Class[_] = {
    var current = typeClass
    while (current != null) {
      if (current.getInterfaces.exists(_.getName == "scala.reflect.Enum")) {
        return current
      }
      current = current.getSuperclass
    }
    null
  }

  def enumRoot(typeClass: Class[_]): Class[_] = {
    var current = familyRoot(typeClass)
    while (current != null) {
      if (current.getInterfaces.exists(_.getName == "scala.reflect.Enum")) {
        try {
          val values = current.getMethod("values")
          if (
            Modifier.isStatic(values.getModifiers) && values.getReturnType.isArray &&
            values.getReturnType.getComponentType == current
          ) return current
        } catch { case _: NoSuchMethodException => () }
      }
      current = current.getSuperclass
    }
    null
  }

  def create(typeClass: Class[_], typeRef: TypeRef[_]): JsonValueCodec[_] = {
    try {
      val valuesMethod = typeClass.getMethod("values")
      if (!Modifier.isPublic(valuesMethod.getModifiers) || !Modifier.isStatic(valuesMethod.getModifiers)) {
        throw ScalaTypeSupport.unsupported(typeRef, "enum values method is not public")
      }
      val values = valuesMethod.invoke(null).asInstanceOf[Array[Object]]
      // productPrefix is the compiler-owned label, independent of application toString overrides.
      new ScalaEnumCodec(typeClass, values, values.map(_.asInstanceOf[Product].productPrefix))
    } catch {
      case error: ReflectiveOperationException =>
        throw new ForyJsonException(s"Cannot resolve Scala enum ${typeClass.getName}", error)
    }
  }
}

/** Base codec extended by compile-time string enum derivation. */
@Internal
class ScalaEnumCodec(
    typeClass: Class[_], values: Array[Object], names: Array[String]
)
    extends StringEnumCodec[Object](names) {
  private val byName = new HashMap[String, Object](values.length * 2)
  private val indexByValue = new IdentityHashMap[Object, Integer](values.length * 2)
  private val tokenPrefixes = new Array[Long](values.length)
  private val tokenMasks = new Array[Long](values.length)
  private val tokenSuffixes = new Array[Int](values.length)
  private val tokenLengths = new Array[Int](values.length)
  values.indices.foreach { index =>
    val value = values(index)
    if (!typeClass.isInstance(value))
      throw new ForyJsonException(s"Scala enum value is not a ${typeClass.getName}")
    val name = names(index)
    if (byName.put(name, value) != null)
      throw new ForyJsonException(s"Duplicate Scala enum name $name on ${typeClass.getName}")
    indexByValue.put(value, Integer.valueOf(index))
    val token = "\"" + name + "\""
    // Only complete, JSON-safe ASCII tokens have identical Latin1 and UTF8 input bytes.
    // Escaped, longer and non-ASCII names keep the ordinary string codec's exact matching.
    if (
      JsonAsciiToken.isPackable(token) &&
      name.forall(ch => ch >= ' ' && ch < 0x7f && ch != '"' && ch != '\\')
    ) {
      tokenPrefixes(index) = JsonAsciiToken.prefix(token)
      tokenMasks(index) = JsonAsciiToken.prefixMask(token.length)
      tokenSuffixes(index) = JsonAsciiToken.suffix(token)
      tokenLengths(index) = token.length
    }
  }

  override protected def valueIndex(value: Object): Int = {
    val index = indexByValue.get(value)
    if (index == null) throw new ForyJsonException(s"Expected Scala enum ${typeClass.getName}")
    index.intValue()
  }

  private def value(name: String): Object = {
    val value = byName.get(name)
    if (value == null) throw new ForyJsonException(s"Unknown Scala enum value $name")
    value
  }

  override def writeString(writer: StringJsonWriter, v: Object): Unit = {
    if (v == null) writer.writeNull() else writer.writeString(names(valueIndex(v)))
  }

  override def readLatin1(reader: Latin1JsonReader): Object = {
    var i = 0
    while (i < tokenLengths.length) {
      val length = tokenLengths(i)
      val matched = length match {
        case 0 => false
        case n if n <= 8 =>
          reader.tryReadNextStringToken0(tokenPrefixes(i), tokenMasks(i), length)
        case 9 =>
          reader.tryReadNextStringToken1(tokenPrefixes(i), tokenMasks(i), tokenSuffixes(i), length)
        case 10 =>
          reader.tryReadNextStringToken2(tokenPrefixes(i), tokenMasks(i), tokenSuffixes(i), length)
        case _ =>
          reader.tryReadNextStringToken3(tokenPrefixes(i), tokenMasks(i), tokenSuffixes(i), length)
      }
      if (matched) return values(i)
      i += 1
    }
    if (reader.tryReadNextNullToken()) null else value(reader.readString())
  }

  override def readUtf16(reader: Utf16JsonReader): Object = {
    if (reader.tryReadNextNullToken()) null else value(reader.readString())
  }

  override def readUtf8(reader: Utf8JsonReader): Object = {
    var i = 0
    while (i < tokenLengths.length) {
      val length = tokenLengths(i)
      val matched = length match {
        case 0 => false
        case n if n <= 8 =>
          reader.tryReadNextStringToken0(tokenPrefixes(i), tokenMasks(i), length)
        case 9 =>
          reader.tryReadNextStringToken1(tokenPrefixes(i), tokenMasks(i), tokenSuffixes(i), length)
        case 10 =>
          reader.tryReadNextStringToken2(tokenPrefixes(i), tokenMasks(i), tokenSuffixes(i), length)
        case _ =>
          reader.tryReadNextStringToken3(tokenPrefixes(i), tokenMasks(i), tokenSuffixes(i), length)
      }
      if (matched) return values(i)
      i += 1
    }
    if (reader.tryReadNextNullToken()) null else value(reader.readString())
  }
}
