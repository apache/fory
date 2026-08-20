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

import org.apache.fory.json.scala.ScalaJsonCodec

import scala.quoted.*

private[scala] object ScalaJsonCodecMacros {
  def derive[T: Type](using quotes: Quotes): Expr[ScalaJsonCodec[T]] = {
    import quotes.reflect.*

    val root = TypeRepr.of[T].dealias.typeSymbol
    if (!root.flags.is(Flags.Enum))
      report.errorAndAbort(s"${root.fullName} is not a Scala 3 enum")

    val cases = root.children.filter(_.flags.is(Flags.Case))
    if (cases.isEmpty)
      report.errorAndAbort(s"${root.fullName} has no closed enum cases")

    val rootClass =
      Literal(ClassOfConstant(TypeRepr.of[T].dealias)).asExprOf[Class[?]]
    val caseExpressions = cases.map { child =>
      if (child.primaryConstructor == Symbol.noSymbol) {
        val value = Select.unique(Ref(root.companionModule), child.name).asExpr
        val singleton = '{ $value.asInstanceOf[AnyRef] }
        ('{ $singleton.getClass }, singleton)
      } else {
        (Literal(ClassOfConstant(child.typeRef)).asExprOf[Class[?]], '{ null })
      }
    }
    val classExpressions = caseExpressions.map(_._1)
    val singletonExpressions = caseExpressions.map(_._2)
    val nameExpressions = cases.map(child => Expr(child.name.stripSuffix("$")))

    '{
      new DerivedScalaJsonCodec[T](
        $rootClass.asInstanceOf[Class[T]],
        Array[Class[_]](${ Varargs(classExpressions) }*),
        Array[String](${ Varargs(nameExpressions) }*),
        Array[AnyRef](${ Varargs(singletonExpressions) }*)
      )
    }
  }
}
