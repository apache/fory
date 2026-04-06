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

package org.apache.fory.serializer.kotlin

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.apache.fory.config.Config
import org.apache.fory.context.ReadContext
import org.apache.fory.context.WriteContext
import org.apache.fory.serializer.ImmutableSerializer

@OptIn(ExperimentalUuidApi::class)
public class UuidSerializer(config: Config) : ImmutableSerializer<Uuid>(config, Uuid::class.java) {
  override fun write(writeContext: WriteContext, value: Uuid) {
    val buffer = writeContext.buffer
    value.toLongs { msb, lsb ->
      buffer.writeInt64(msb)
      buffer.writeInt64(lsb)
    }
  }

  override fun read(readContext: ReadContext): Uuid {
    val buffer = readContext.buffer
    return Uuid.fromLongs(buffer.readInt64(), buffer.readInt64())
  }

  override fun shareable(): Boolean = true
}
