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

package org.apache.fory.scala

import org.apache.fory.BaseFory

import scala.reflect.ClassTag

extension (fory: BaseFory)
  def register[T](using serializer: ForySerializer[T], tag: ClassTag[T]): Unit =
    ForySerializer.registerModule(fory, tag.runtimeClass.asInstanceOf[Class[T]], null, null, null)

  def register[T](typeId: Long)(using serializer: ForySerializer[T], tag: ClassTag[T]): Unit =
    ForySerializer.registerModule(
      fory,
      tag.runtimeClass.asInstanceOf[Class[T]],
      java.lang.Long.valueOf(typeId),
      null,
      null)

  def register[T](name: String)(using serializer: ForySerializer[T], tag: ClassTag[T]): Unit =
    val (namespace, typeName) = ForySerializer.splitName(name)
    ForySerializer.registerModule(
      fory,
      tag.runtimeClass.asInstanceOf[Class[T]],
      null,
      namespace,
      typeName)

  def register[T](namespace: String, typeName: String)(using
      serializer: ForySerializer[T],
      tag: ClassTag[T]): Unit =
    ForySerializer.registerModule(
      fory,
      tag.runtimeClass.asInstanceOf[Class[T]],
      null,
      namespace,
      typeName)
