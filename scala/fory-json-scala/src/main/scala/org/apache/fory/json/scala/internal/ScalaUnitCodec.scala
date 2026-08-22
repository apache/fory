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

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.AbstractJsonValueCodec
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.writer.JsonWriter

private[scala] object ScalaUnitCodec
    extends AbstractJsonValueCodec[scala.runtime.BoxedUnit] {
  override def write(writer: JsonWriter, value: scala.runtime.BoxedUnit): Unit = writer.writeNull()

  override def read(reader: JsonReader): scala.runtime.BoxedUnit = {
    if (!reader.tryReadNullToken()) {
      throw new ForyJsonException("Scala Unit must be encoded as JSON null")
    }
    scala.runtime.BoxedUnit.UNIT
  }
}
