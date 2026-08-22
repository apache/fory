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

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.{AbstractJsonValueCodec, JsonValueCodec}
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.writer.JsonWriter
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
      new ScalaEnumCodec(typeClass, values)
    } catch {
      case error: ReflectiveOperationException =>
        throw new ForyJsonException(s"Cannot resolve Scala enum ${typeClass.getName}", error)
    }
  }
}

private final class ScalaEnumCodec(typeClass: Class[_], values: Array[Object])
    extends AbstractJsonValueCodec[Object] {
  private val byName = new HashMap[String, Object](values.length * 2)
  private val nameByValue = new IdentityHashMap[Object, String](values.length * 2)
  values.foreach { value =>
    if (!typeClass.isInstance(value))
      throw new ForyJsonException(s"Scala enum value is not a ${typeClass.getName}")
    // productPrefix is the compiler-owned case label. Cache it by enum identity so an application
    // toString override cannot change the JSON schema or add work to the writer hot path.
    val name = value.asInstanceOf[Product].productPrefix
    if (byName.put(name, value) != null)
      throw new ForyJsonException(s"Duplicate Scala enum name $name on ${typeClass.getName}")
    nameByValue.put(value, name)
  }

  override def write(writer: JsonWriter, value: Object): Unit = {
    if (value == null) writer.writeNull()
    else {
      val name = nameByValue.get(value)
      if (name == null) throw new ForyJsonException(s"Expected Scala enum ${typeClass.getName}")
      writer.writeString(name)
    }
  }

  override def read(reader: JsonReader): Object = {
    if (reader.tryReadNullToken()) return null
    val name = reader.readString()
    val value = byName.get(name)
    if (value == null) throw new ForyJsonException(s"Unknown Scala enum value $name")
    value
  }
}
