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

package org.apache.fory.integration.kotlin.json.corpus

import kotlin.jvm.JvmInline
import org.apache.fory.json.annotation.JsonCodec
import org.apache.fory.json.annotation.JsonSubTypes
import org.apache.fory.json.annotation.JsonType
import org.apache.fory.json.codec.AbstractJsonValueCodec
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.writer.JsonWriter

@JsonType
public data class PlatformAccount(
  public val id: Int,
  public val name: String,
  public val label: String? = "corpus-default",
)

@JsonType public data class PlatformBox<T>(public val value: T)

@JsonType @JvmInline public value class PlatformId(public val value: Long)

@JsonType
@JsonSubTypes(
  value =
    [
      JsonSubTypes.Type(value = PlatformCircle::class, name = "circle"),
      JsonSubTypes.Type(value = PlatformMarker::class, name = "marker"),
    ],
  property = "kind",
)
public sealed interface PlatformShape

@JsonType public data class PlatformCircle(public val radius: Int) : PlatformShape

@JsonType public data object PlatformMarker : PlatformShape

public data class PlatformToken(public val value: String)

public class PlatformTokenCodec : AbstractJsonValueCodec<PlatformToken>() {
  override fun write(writer: JsonWriter, value: PlatformToken?) {
    if (value == null) writer.writeNull() else writer.writeString(value.value)
  }

  override fun read(reader: JsonReader): PlatformToken? {
    val value = reader.readString() ?: return null
    return PlatformToken(value)
  }
}

@JsonType
public data class PlatformRoot(
  public val account: PlatformAccount,
  public val id: PlatformId,
  public val unsigned: UInt,
  public val shape: PlatformShape,
  public val profile: PlatformJavaProfile,
  @field:JsonCodec(PlatformTokenCodec::class) public val token: PlatformToken,
  public val box: PlatformBox<String>,
)

internal fun platformRootValue(): PlatformRoot =
  PlatformRoot(
    account = PlatformAccount(1, "default"),
    id = PlatformId(9),
    unsigned = UInt.MAX_VALUE,
    shape = PlatformCircle(3),
    profile = PlatformJavaProfile("mixin"),
    token = PlatformToken("custom"),
    box = PlatformBox("generic"),
  )
