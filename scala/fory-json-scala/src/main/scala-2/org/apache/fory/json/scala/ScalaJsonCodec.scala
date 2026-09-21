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

import org.apache.fory.json.JsonCodecFactory

import scala.language.experimental.macros

/** Compile-time JSON schema for a closed Scala sealed hierarchy. */
trait ScalaJsonCodec[T] extends JsonCodecFactory

object ScalaJsonCodec {
  /** Derives the default wrapper-object representation for a closed sealed hierarchy. */
  def derived[T]: ScalaJsonCodec[T] = macro internal.ScalaJsonCodecMacros.derived[T]

  /** Derives JSON string case names for a sealed hierarchy containing only case objects. */
  def stringEnum[T]: ScalaJsonCodec[T] = macro internal.ScalaJsonCodecMacros.stringEnum[T]
}
