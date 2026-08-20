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
import java.util.{ArrayList, Collections, HashSet, List => JList}

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.annotation.JsonSubTypes.Inclusion
import org.apache.fory.json.codec.{ClosedSubtypeCodec, JsonSubTypesInfo, JsonValueCodec}
import org.apache.fory.json.resolver.JsonTypeResolver
import org.apache.fory.json.scala.ScalaJsonCodec
import org.apache.fory.reflect.TypeRef

private[scala] final class DerivedScalaJsonCodec[T](
    rootType: Class[T],
    caseClasses: Array[Class[_]],
    caseNames: Array[String],
    singletonCases: Array[AnyRef]
) extends ScalaJsonCodec[T] {
  if (
    caseClasses.length == 0 || caseClasses.length != caseNames.length ||
    caseClasses.length != singletonCases.length
  )
    throw new IllegalArgumentException("A derived Scala enum must have a non-empty case table")

  private val classes = caseClasses.clone()
  private val names = caseNames.clone()
  private val singletons = singletonCases.clone()
  private val handled: JList[Class[_]] = {
    val result = new ArrayList[Class[_]](classes.length)
    val classSet = new HashSet[Class[_]](classes.length * 2)
    val nameSet = new HashSet[String](names.length * 2)
    var index = 0
    while (index < classes.length) {
      val caseClass = classes(index)
      val name = names(index)
      if (
        caseClass == null || !rootType.isAssignableFrom(caseClass) ||
        Modifier.isAbstract(caseClass.getModifiers)
      ) throw new IllegalArgumentException(s"Invalid derived Scala enum case $caseClass")
      if (name == null || name.isEmpty || !nameSet.add(name))
        throw new IllegalArgumentException(s"Invalid derived Scala enum case name $name")
      val singleton = singletons(index)
      if (singleton != null && singleton.getClass != caseClass)
        throw new IllegalArgumentException(s"Invalid derived Scala enum singleton $name")
      if (classSet.add(caseClass)) result.add(caseClass)
      else {
        var prior = 0
        while (classes(prior) != caseClass) prior += 1
        if (singleton == null || singletons(prior) == null)
          throw new IllegalArgumentException(
            s"Duplicate derived Scala enum case ${caseClass.getName}"
          )
      }
      index += 1
    }
    Collections.unmodifiableList(result)
  }
  private val key = {
    val builder = new StringBuilder(rootType.getName.length + classes.length * 48)
    append(builder, rootType.getName)
    var index = 0
    while (index < classes.length) {
      append(builder, names(index))
      append(builder, classes(index).getName)
      index += 1
    }
    builder.toString
  }

  override def factoryKey(): String = key

  override def handledRuntimeClasses(): JList[Class[_]] = handled

  override def create(
      typeRef: TypeRef[_],
      resolver: JsonTypeResolver,
      runtimeType: Boolean
  ): JsonValueCodec[_] = {
    val rawType = typeRef.getRawType
    if (rawType == rootType) {
      return new ClosedSubtypeCodec(
        rootType,
        new JsonSubTypesInfo(Inclusion.WRAPPER_OBJECT, "", classes.clone(), names.clone()),
        typeRef,
        this,
        singletons.clone()
      )
    }
    val index = classes.indexOf(rawType)
    if (index < 0)
      throw new ForyJsonException(s"Derived Scala enum codec expected ${rootType.getName}")
    val singleton = singletons(index)
    if (singleton == null) ScalaObjectModels.caseClassCodec(typeRef, resolver)
    else ScalaObjectModels.fixedCodec(typeRef, resolver, singleton)
  }

  private def append(builder: StringBuilder, value: String): Unit =
    builder.append(value.length).append(':').append(value)
}
