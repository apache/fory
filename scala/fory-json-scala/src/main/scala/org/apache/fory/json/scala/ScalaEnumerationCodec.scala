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

package org.apache.fory.json.scala

import java.util.HashMap

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.{AbstractJsonValueCodec, MapKeyCodec}
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.writer.JsonWriter

/** JSON codec bound to one Scala 2 `Enumeration` owner. */
abstract class ScalaEnumerationCodec(val enumeration: Enumeration)
    extends AbstractJsonValueCodec[Enumeration#Value]
    with MapKeyCodec {
  if (enumeration == null) throw new NullPointerException("enumeration")

  private val byName = {
    val values = new HashMap[String, Enumeration#Value](enumeration.values.size * 2)
    val iterator = enumeration.values.iterator
    while (iterator.hasNext) {
      val value = iterator.next()
      val previous = values.put(value.toString, value)
      if (previous != null)
        throw new IllegalArgumentException(s"Duplicate Scala Enumeration name ${value.toString}")
    }
    values
  }

  override final def write(writer: JsonWriter, value: Enumeration#Value): Unit = {
    if (value == null) writer.writeNull()
    else writer.writeString(toName(value))
  }

  override final def read(reader: JsonReader): Enumeration#Value = {
    if (reader.tryReadNullToken()) return null
    fromName(reader.readString()).asInstanceOf[Enumeration#Value]
  }

  override final def toName(key: Object): String = key match {
    case value: Enumeration#Value if byName.get(value.toString) eq value => value.toString
    case _ => throw new ForyJsonException("Scala Enumeration value belongs to a different owner")
  }

  override final def fromName(name: String): Object = {
    val value = byName.get(name)
    if (value == null) throw new ForyJsonException(s"Unknown Scala Enumeration value $name")
    value
  }
}
