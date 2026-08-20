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

import scala.quoted.*

private[scala] object ScalaTypeRefMacros {
  def create[T: Type](using Quotes): Expr[org.apache.fory.reflect.TypeRef[T]] = {
    import quotes.reflect.*

    def typeRefExpr(tpe: TypeRepr): Expr[org.apache.fory.reflect.TypeRef[?]] = {
      val normalized = tpe.dealias
      val raw = normalized.classSymbol.getOrElse {
        report.errorAndAbort(s"${normalized.show} has no runtime class")
      }
      val rawClass = Literal(ClassOfConstant(normalized)).asExprOf[Class[?]]
      normalized match {
        case AppliedType(_, arguments) if arguments.nonEmpty =>
          val childRefs = arguments.map(typeRefExpr)
          '{
            org.apache.fory.reflect.TypeRef.ofDeclaredTypeArguments(
              $rawClass.asInstanceOf[Class[Any]],
              null,
              java.util.Arrays.asList[org.apache.fory.reflect.TypeRef[_]](${ Varargs(childRefs) }*),
              null
            )
          }
        case _ => '{ org.apache.fory.reflect.TypeRef.of($rawClass) }
      }
    }

    val result = typeRefExpr(TypeRepr.of[T])
    '{ $result.asInstanceOf[org.apache.fory.reflect.TypeRef[T]] }
  }
}
