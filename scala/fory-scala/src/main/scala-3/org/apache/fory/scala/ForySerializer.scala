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

import org.apache.fory.{BaseFory, Fory, ForyModule, ThreadSafeFory}
import org.apache.fory.annotation.Internal
import org.apache.fory.meta.TypeDef
import org.apache.fory.resolver.TypeResolver
import org.apache.fory.serializer.Serializer
import org.apache.fory.serializer.scala.ScalaSerializers

trait ForySerializer[T] {
  def createSerializer(typeResolver: TypeResolver): Serializer[T] =
    createSerializer(typeResolver, null)

  def createSerializer(typeResolver: TypeResolver, typeDef: TypeDef): Serializer[T]

  def isUnion: Boolean = false

  private[scala] def handledRuntimeClasses(cls: Class[T]): Array[Class[_]] = Array.empty
}

object ForySerializer {
  inline def derived[T]: ForySerializer[T] =
    ${ org.apache.fory.scala.internal.ForySerializerMacros.derive[T] }

  private[scala] def splitName(name: String): (String, String) = {
    if name == null then {
      throw new NullPointerException("name")
    }
    val idx = name.lastIndexOf('.')
    val namespace = if idx >= 0 then name.substring(0, idx) else ""
    val typeName = if idx >= 0 then name.substring(idx + 1) else name
    if typeName.isEmpty then {
      throw new IllegalArgumentException("Name must include a non-empty type name")
    }
    (namespace, typeName)
  }

  private def checkTypeName(typeName: String): Unit = {
    if typeName == null || typeName.isEmpty || typeName.contains(".") then {
      throw new IllegalArgumentException(
        "typeName must be non-empty and must not contain `.` when namespace is provided")
    }
  }

  def register[T](fory: Fory, cls: Class[T])(using serializer: ForySerializer[T]): Unit = {
    register(fory, cls, null, null)
  }

  def register[T](
      fory: Fory,
      cls: Class[T],
      typeId: Long)(using serializer: ForySerializer[T]): Unit = {
    register(fory, cls, java.lang.Long.valueOf(typeId), null, null)
  }

  def register[T](fory: Fory, cls: Class[T], name: String)(using
      serializer: ForySerializer[T]): Unit = {
    val (namespace, typeName) = splitName(name)
    register(fory, cls, null, namespace, typeName)
  }

  def register[T](
      fory: Fory,
      cls: Class[T],
      namespace: String,
      typeName: String)(using serializer: ForySerializer[T]): Unit = {
    checkTypeName(typeName)
    register(fory, cls, null, namespace, typeName)
  }

  @Internal
  def registerType[T](fory: Fory, cls: Class[T], typeId: Long): Unit = {
    registerType(fory, cls, java.lang.Long.valueOf(typeId), null, null)
  }

  @Internal
  def registerType[T](fory: Fory, cls: Class[T], name: String): Unit = {
    val (namespace, typeName) = splitName(name)
    registerType(fory, cls, null, namespace, typeName)
  }

  @Internal
  def registerType[T](fory: Fory, cls: Class[T], namespace: String, typeName: String): Unit = {
    checkTypeName(typeName)
    registerType(fory, cls, null, namespace, typeName)
  }

  @Internal
  def registerSerializer[T](fory: Fory, cls: Class[T])(using serializer: ForySerializer[T]): Unit = {
    if serializer.isUnion then {
      throw new IllegalArgumentException("Use ForySerializer.register for Scala union serializers")
    }
    val resolver = fory.getTypeResolver
    resolver.setSerializer(cls, serializer.createSerializer(resolver))
  }

  private def register[T](
      fory: Fory,
      cls: Class[T],
      typeId: java.lang.Long,
      namespace: String,
      typeName: String)(using serializer: ForySerializer[T]): Unit = {
    if typeName != null then {
      checkTypeName(typeName)
    }
    val resolver = fory.getTypeResolver
    serializer match {
      case _ if serializer.isUnion =>
        val unionSerializer = serializer.createSerializer(resolver)
        if typeId != null then {
          resolver.registerUnion(cls, typeId.longValue(), unionSerializer)
        } else {
          val unionNamespace =
            if namespace != null then namespace else Option(cls.getPackage).map(_.getName).orNull
          val unionTypeName = if typeName != null then typeName else cls.getSimpleName
          fory.registerUnion(
            cls,
            if unionNamespace == null then "" else unionNamespace,
            unionTypeName,
            unionSerializer)
        }
        serializer.handledRuntimeClasses(cls).foreach { runtimeClass =>
          ScalaSerializers.registerRuntimeTypeAlias(fory, runtimeClass, cls)
        }
      case _ =>
        registerType(fory, cls, typeId, namespace, typeName)
        resolver.setSerializer(cls, serializer.createSerializer(resolver))
    }
  }

  def register[T](
      fory: ThreadSafeFory,
      cls: Class[T])(using serializer: ForySerializer[T]): Unit = {
    registerModule(fory, cls, null, null, null)
  }

  def register[T](
      fory: ThreadSafeFory,
      cls: Class[T],
      typeId: Long)(using serializer: ForySerializer[T]): Unit = {
    registerModule(fory, cls, java.lang.Long.valueOf(typeId), null, null)
  }

  def register[T](
      fory: ThreadSafeFory,
      cls: Class[T],
      name: String)(using serializer: ForySerializer[T]): Unit = {
    val (namespace, typeName) = splitName(name)
    registerModule(fory, cls, null, namespace, typeName)
  }

  def register[T](
      fory: ThreadSafeFory,
      cls: Class[T],
      namespace: String,
      typeName: String)(using serializer: ForySerializer[T]): Unit = {
    checkTypeName(typeName)
    registerModule(fory, cls, null, namespace, typeName)
  }

  private[scala] def registerModule[T](
      fory: BaseFory,
      cls: Class[T],
      typeId: java.lang.Long,
      namespace: String,
      typeName: String)(using serializer: ForySerializer[T]): Unit = {
    if typeName != null then {
      checkTypeName(typeName)
    }
    fory.register(new ForyModule {
      override def install(runtime: Fory): Unit = {
        runtime.register(ForyScala)
        register(runtime, cls, typeId, namespace, typeName)(using serializer)
      }
    })
  }

  private def registerType[T](
      fory: Fory,
      cls: Class[T],
      typeId: java.lang.Long,
      namespace: String,
      typeName: String): Unit = {
    if typeId != null then {
      fory.getTypeResolver.register(cls, typeId.longValue())
    } else if namespace == null || typeName == null then {
      fory.register(cls)
    } else {
      fory.register(cls, namespace, typeName)
    }
  }
}
