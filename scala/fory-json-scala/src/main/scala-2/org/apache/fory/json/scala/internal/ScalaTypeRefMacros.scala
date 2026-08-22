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
