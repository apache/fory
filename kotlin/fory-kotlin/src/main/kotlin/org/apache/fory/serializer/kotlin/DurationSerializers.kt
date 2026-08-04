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

import kotlin.time.Duration
import org.apache.fory.config.Config
import org.apache.fory.context.ReadContext
import org.apache.fory.context.WriteContext
import org.apache.fory.kotlin.DurationEncoding
import org.apache.fory.serializer.ImmutableSerializer
import org.apache.fory.serializer.Serializer
import org.apache.fory.serializer.Shareable

/** Kotlin duration serializer used by generated xlang collection/map field generics. */
public object DurationSerializers {
  @JvmStatic public fun serializer(config: Config): Serializer<*> = DurationXlangSerializer(config)

  private class DurationXlangSerializer(config: Config) :
    ImmutableSerializer<Duration>(config, Duration::class.java, false, true), Shareable {
    override fun readDataAlwaysAdvances(): Boolean = true

    override fun write(writeContext: WriteContext, value: Duration) {
      DurationEncoding.write(writeContext, value)
    }

    override fun read(readContext: ReadContext): Duration = DurationEncoding.read(readContext)
  }
}
