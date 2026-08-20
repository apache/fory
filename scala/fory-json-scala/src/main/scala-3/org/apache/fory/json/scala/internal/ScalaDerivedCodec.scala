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

import org.apache.fory.json.{ForyJsonException, JsonCodecFactory}
import org.apache.fory.json.scala.ScalaJsonCodec

private[scala] object ScalaDerivedCodec {
  private val MethodName = "derived$ScalaJsonCodec"

  def find(rootType: Class[_]): JsonCodecFactory = {
    val method =
      try rootType.getDeclaredMethod(MethodName)
      catch { case _: NoSuchMethodException => return null }
    val modifiers = method.getModifiers
    if (
      !Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers) ||
      method.getParameterCount != 0 ||
      !classOf[ScalaJsonCodec[_]].isAssignableFrom(method.getReturnType)
    ) return null
    try {
      val codec = method.invoke(null)
      if (codec == null)
        throw new ForyJsonException(s"Derived Scala JSON codec is null for ${rootType.getName}")
      codec.asInstanceOf[JsonCodecFactory]
    } catch {
      case error: ReflectiveOperationException =>
        throw new ForyJsonException(
          s"Cannot load derived Scala JSON codec for ${rootType.getName}",
          error
        )
    }
  }
}
