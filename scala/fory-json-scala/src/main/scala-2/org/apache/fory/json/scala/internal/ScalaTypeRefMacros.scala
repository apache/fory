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

import scala.reflect.macros.blackbox

private[scala] object ScalaTypeRefMacros {
  def create[T: c.WeakTypeTag](
      c: blackbox.Context
  ): c.Expr[org.apache.fory.reflect.TypeRef[T]] = {
    import c.universe._

    def rawClass(tpe: Type): Tree = {
      val normalized = tpe.dealias
      val symbol = normalized.typeSymbol
      if (!symbol.isClass) c.abort(c.enclosingPosition, s"${normalized.toString} has no runtime class")
      val rawType = symbol.asClass.toType
      q"classOf[$rawType]"
    }

    def createTypeRef(tpe: Type): Tree = {
      val normalized = tpe.dealias
      // Value's JVM class is shared by every Enumeration. The stable prefix, when available,
      // belongs to this occurrence and must survive inside arrays and generic arguments.
      normalized match {
        case TypeRef(prefix, _, _) if normalized <:< typeOf[Enumeration#Value] &&
            (prefix.termSymbol.isModule || prefix.typeSymbol.isModuleClass) =>
          val module = if (prefix.termSymbol.isModule) prefix.termSymbol else prefix.typeSymbol.asClass.module
          val owner = c.internal.gen.mkAttributedRef(module)
          return q"_root_.org.apache.fory.reflect.TypeRef.ofDeclaredTypeArguments(classOf[_root_.scala.Enumeration#Value], null, _root_.java.util.Collections.emptyList[_root_.org.apache.fory.reflect.TypeRef[_]](), null, _root_.org.apache.fory.reflect.TypeRef.of($owner.getClass))"
        case _ =>
      }
      if (normalized.typeSymbol == definitions.ArrayClass) {
        val component = createTypeRef(normalized.typeArgs.head)
        return q"{ val component = $component; _root_.org.apache.fory.reflect.TypeRef.of(_root_.org.apache.fory.reflect.TypeRef.newArrayType(component.getType), null, null, component) }"
      }
      val clazz = rawClass(normalized)
      if (normalized.typeArgs.isEmpty) {
        q"_root_.org.apache.fory.reflect.TypeRef.of($clazz)"
      } else {
        val children = normalized.typeArgs.map(createTypeRef)
        q"_root_.org.apache.fory.reflect.TypeRef.ofDeclaredTypeArguments($clazz, null, _root_.java.util.Arrays.asList(..$children), null)"
      }
    }

    val result = createTypeRef(weakTypeOf[T])
    c.Expr[org.apache.fory.reflect.TypeRef[T]](
      q"$result.asInstanceOf[_root_.org.apache.fory.reflect.TypeRef[${weakTypeOf[T]}]]"
    )
  }
}
